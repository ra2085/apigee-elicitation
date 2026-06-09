// Copyright © 2025 Google LLC.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.apigee.callouts;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;

/**
 * An Apigee Java callout that sends multiple HTTP requests ("fan-out") in parallel.
 *
 * <p>This callout uses {@link java.net.http.HttpClient} to send requests concurrently and
 * asynchronously. It is configurable via properties in the JavaCallout policy.
 */
public class ServiceFanout implements Execution {

  private static final String VAR_PREFIX = "servicefanout_";

  // A dedicated, fixed-size thread pool for the HttpClient.
  // Using a ThreadFactory to create daemon threads allows the JVM to exit
  // even if this pool has active threads. This is important in application server
  // environments like Apigee to prevent thread leaks on application undeploy/redeploy.
  private static final ExecutorService httpClientExecutor =
      Executors.newFixedThreadPool(
          20, // A reasonable default size, can be made configurable.
          r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setDaemon(true);
            return t;
          });

  // A cache of HttpClients mapped by their connect timeout.
  // This allows reusing HttpClient instances (and their connection pools)
  // while supporting different connect timeouts.
  private static final Map<Duration, HttpClient> httpClientCache = new ConcurrentHashMap<>();

  private static HttpClient getHttpClient(Duration connectTimeout) {
    Duration key = (connectTimeout != null) ? connectTimeout : Duration.ZERO;
    return httpClientCache.computeIfAbsent(
        key,
        timeout -> {
          HttpClient.Builder builder =
              HttpClient.newBuilder()
                  .executor(httpClientExecutor)
                  .followRedirects(HttpClient.Redirect.NEVER);
          if (!timeout.isZero()) {
            builder.connectTimeout(timeout);
          }
          return builder.build();
        });
  }

  private final Map<String, String> properties;

  public ServiceFanout(Map<String, String> properties) {
    this.properties = properties;
  }

  // This pattern finds the innermost curly braces, which is key to handling nested templates.
  private static final Pattern INNERMOST_TEMPLATE_PATTERN = Pattern.compile("\\{([^{}]+)\\}");

  /**
   * Resolves a string containing placeholders like {variable_name}.
   * It supports nested placeholders by iteratively resolving the innermost ones first.
   *
   * @param template The string template to resolve.
   * @param messageContext The Apigee message context for variable lookup.
   * @return The resolved string.
   */
  private static String resolveTemplate(String template, MessageContext messageContext) {
    if (template == null) {
      return null;
    }

    String current = template;
    // A limit prevents infinite loops in case of circular template definitions.
    for (int i = 0; i < 10; i++) {
      Matcher matcher = INNERMOST_TEMPLATE_PATTERN.matcher(current);
      if (!matcher.find()) {
        // No more templates to resolve, we are done.
        return current;
      }

      // We need to reset the matcher to find all occurrences in the string.
      matcher.reset();
      StringBuffer sb = new StringBuffer();
      boolean changedInPass = false;

      while (matcher.find()) {
        String varName = matcher.group(1);
        Object varValue = messageContext.getVariable(varName);
        if (varValue != null) {
          matcher.appendReplacement(sb, Matcher.quoteReplacement(varValue.toString()));
          changedInPass = true;
        }
      }
      matcher.appendTail(sb);

      if (!changedInPass) {
        return current;
      }
      current = sb.toString();
    }
    return current;
  }

  private static String getStackTrace(Throwable t) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    return sw.toString();
  }

  @Override
  public ExecutionResult execute(MessageContext messageContext, ExecutionContext executionContext) {
    try {
      // 1. Clear stale variables from previous runs to ensure fresh template resolution
      messageContext.removeVariable(VAR_PREFIX + "current_index");
      messageContext.removeVariable(VAR_PREFIX + "current_url");

      // 2. Get property specifications
      String urlsSpec = getRequiredProperty("urls");
      String methodSpec = getOptionalProperty("method", "GET");
      String bodySpec = getOptionalProperty("body", null);
      String headersSpec = getOptionalProperty("headers", null);
      String timeoutSecondsSpec = getOptionalProperty("timeout-seconds", "30");
      String connectTimeoutSecondsSpec = getOptionalProperty("connect-timeout-seconds", null);
      String outputPrefixSpec = getOptionalProperty("output-variable-prefix", "fanout.response");
      String continueOnErrorSpec = getOptionalProperty("continue-on-error", "false");

      // 2. Resolve properties against message context
      String urlsValue = resolveTemplate(urlsSpec, messageContext);
      if (urlsValue == null || urlsValue.trim().isEmpty()) {
        // If the variable for URLs is not found or empty, there's nothing to do.
        return ExecutionResult.SUCCESS;
      }
      List<String> urls = Arrays.asList(urlsValue.split("\\s*,\\s*"));

      String method = resolveTemplate(methodSpec, messageContext);
      String body = resolveTemplate(bodySpec, messageContext);
      String headersValue = resolveTemplate(headersSpec, messageContext);
      String timeoutSecondsValue = resolveTemplate(timeoutSecondsSpec, messageContext);
      String connectTimeoutSecondsValue = resolveTemplate(connectTimeoutSecondsSpec, messageContext);
      String outputPrefix = resolveTemplate(outputPrefixSpec, messageContext);
      boolean continueOnError =
          Boolean.parseBoolean(resolveTemplate(continueOnErrorSpec, messageContext));

      int timeoutSeconds;
      try {
        timeoutSeconds = Integer.parseInt(timeoutSecondsValue);
      } catch (NumberFormatException e) {
        throw new IllegalStateException(
            "'timeout-seconds' must be a valid integer. Value was: " + timeoutSecondsValue);
      }

      Duration connectTimeout = null;
      if (connectTimeoutSecondsValue != null && !connectTimeoutSecondsValue.trim().isEmpty()) {
        try {
          connectTimeout = Duration.ofSeconds(Integer.parseInt(connectTimeoutSecondsValue));
        } catch (NumberFormatException e) {
          throw new IllegalStateException(
              "'connect-timeout-seconds' must be a valid integer. Value was: " + connectTimeoutSecondsValue);
        }
      }

      // Parse the RAW spec before resolving templates. 
      List<String[]> headerTemplates = new ArrayList<>();
      if (headersValue != null && !headersValue.trim().isEmpty()) {
        // Split on newlines OR pipes to allow semicolons in header values
        for (String header : headersValue.split("\\s*(?:\\||\\r?\\n)\\s*")) {
          if (header.trim().isEmpty()) continue;
          String[] parts = header.split(":", 2);
          if (parts.length == 2) {
            headerTemplates.add(new String[]{parts[0].trim(), parts[1].trim()});
          }
        }
      }

      // 3. Create tasks for parallel execution
      HttpClient client = getHttpClient(connectTimeout);
      List<String> resolvedUrls = new ArrayList<>();
      List<CompletableFuture<HttpResponse<java.io.InputStream>>> futures = new ArrayList<>();
      for (int i = 0; i < urls.size(); i++) {
        String url = urls.get(i);
        String resolvedUrl = resolveTemplate(url, messageContext);
        if (resolvedUrl != null && !resolvedUrl.trim().isEmpty()) {
          resolvedUrl = resolvedUrl.trim();
          messageContext.setVariable(VAR_PREFIX + "current_index", i + 1);
          messageContext.setVariable(VAR_PREFIX + "current_url", resolvedUrl);
          resolvedUrls.add(resolvedUrl);
          try {
            String lowercaseUrl = resolvedUrl.toLowerCase();
            if (!lowercaseUrl.startsWith("http://") && !lowercaseUrl.startsWith("https://")) {
              throw new IllegalArgumentException("URI scheme must be http or https");
            }
            HttpRequest.Builder requestBuilder =
                HttpRequest.newBuilder()
                    .uri(new URI(resolvedUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds));

            Map<String, List<String>> requestHeadersMap = new HashMap<>();

            for (String[] headerTemplate : headerTemplates) {
              // Resolve templates per-request so context variables evaluate correctly
              String resolvedName = resolveTemplate(headerTemplate[0], messageContext);
              String resolvedValue = resolveTemplate(headerTemplate[1], messageContext);
              
              if (resolvedName != null && !resolvedName.isEmpty() && resolvedValue != null) {
                // requestBuilder.header safely appends multi-value headers
                requestBuilder.header(resolvedName, resolvedValue);
                requestHeadersMap.computeIfAbsent(resolvedName, k -> new ArrayList<>()).add(resolvedValue);
              }
            }
            
            String requestHeadersJson =
                requestHeadersMap.entrySet().stream()
                    .map(
                        entry ->
                            "\""
                                + entry.getKey().replace("\"", "\\\"")
                                + "\":\""
                                + String.join(",", entry.getValue()).replace("\"", "\\\"")
                                + "\"")
                    .collect(Collectors.joining(", ", "{", "}"));
            messageContext.setVariable(VAR_PREFIX + (i + 1) + "_request_headers", requestHeadersJson);

            HttpRequest.BodyPublisher bodyPublisher =
                (body != null && !body.isEmpty())
                    ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                    : HttpRequest.BodyPublishers.noBody();

            requestBuilder.method(method.toUpperCase(), bodyPublisher);

            futures.add(
                client.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream()));
          } catch (Exception e) {
            // Create a failed future for invalid URLs or other setup issues
            futures.add(CompletableFuture.failedFuture(e));
          }
        }
      }

      if (futures.isEmpty()) {
        return ExecutionResult.SUCCESS;
      }

      // 4. Execute tasks and gather results
      try {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      } catch (Exception e) {
        // Ignore exception here. allOf completes when ALL futures have completed (successfully or exceptionally).
      }

      boolean hasErrors = false;
      int successCount = 0;
      int errorCount = 0;

      // Local helper class to store response state for each target
      class TargetResponse {
        final int index;
        final String currentUrl;
        final String responsePrefix;
        final HttpResponse<java.io.InputStream> response;
        final Exception connectionException;
        
        final java.io.InputStream is;
        final StringBuilder bodyBuilder = new StringBuilder();
        CompletableFuture<Void> readFuture;
        boolean readSuccess = false;
        String readError = null;
        String readStackTrace = null;

        TargetResponse(int index, String currentUrl, String responsePrefix, HttpResponse<java.io.InputStream> response, Exception connectionException) {
          this.index = index;
          this.currentUrl = currentUrl;
          this.responsePrefix = responsePrefix;
          this.response = response;
          this.connectionException = connectionException;
          this.is = (response != null) ? response.body() : null;
        }
      }

      List<TargetResponse> targets = new ArrayList<>();
      List<CompletableFuture<Void>> bodyReadFutures = new ArrayList<>();

      for (int i = 0; i < futures.size(); i++) {
        CompletableFuture<HttpResponse<java.io.InputStream>> future = futures.get(i);
        String currentUrl = resolvedUrls.get(i);
        String responsePrefix = outputPrefix + "." + (i + 1);

        try {
          HttpResponse<java.io.InputStream> response = future.get(); // Won't block
          TargetResponse target = new TargetResponse(i + 1, currentUrl, responsePrefix, response, null);
          targets.add(target);
        } catch (Exception e) {
          TargetResponse target = new TargetResponse(i + 1, currentUrl, responsePrefix, null, e);
          targets.add(target);
        }
      }

      // Start all stream body reads in parallel
      for (TargetResponse target : targets) {
        if (target.connectionException != null || target.is == null) {
          continue;
        }

        java.io.InputStream is = target.is;
        StringBuilder bodyBuilder = target.bodyBuilder;

        target.readFuture = CompletableFuture.runAsync(() -> {
          try {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
              synchronized (bodyBuilder) {
                bodyBuilder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
              }
            }
            target.readSuccess = true;
          } catch (Exception e) {
            target.readError = e.getMessage();
            target.readStackTrace = getStackTrace(e);
          } finally {
            try {
              is.close();
            } catch (Exception ignored) {}
          }
        }, httpClientExecutor);

        bodyReadFutures.add(target.readFuture);
      }

      // Wait for ALL streaming bodies to finish or time out concurrently
      if (!bodyReadFutures.isEmpty()) {
        try {
          CompletableFuture.allOf(bodyReadFutures.toArray(new CompletableFuture[0]))
              .get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
          // Close all input streams immediately to unblock any waiting reads in background threads
          for (TargetResponse target : targets) {
            if (target.is != null) {
              try {
                target.is.close();
              } catch (Exception ignored) {}
            }
          }
          // Wait for background reading futures to exit cleanly
          try {
            CompletableFuture.allOf(bodyReadFutures.toArray(new CompletableFuture[0])).join();
          } catch (Exception ignored) {}
        } catch (Exception e) {
          // Wait for remaining reads if one failed
          try {
            CompletableFuture.allOf(bodyReadFutures.toArray(new CompletableFuture[0])).join();
          } catch (Exception ignored) {}
        }
      }

      // 5. Populate Apigee message context variables sequentially and count success/errors
      for (TargetResponse target : targets) {
        String responsePrefix = target.responsePrefix;

        if (target.connectionException != null) {
          hasErrors = true;
          errorCount++;
          String errorMsg = "Error processing URL " + target.currentUrl + ": " + target.connectionException.getMessage();
          messageContext.setVariable(responsePrefix + ".success", false);
          messageContext.setVariable(responsePrefix + ".error", errorMsg);
          messageContext.setVariable(responsePrefix + ".stacktrace", getStackTrace(target.connectionException));
          if (!continueOnError) {
            throw new Exception(errorMsg, target.connectionException);
          }
          continue;
        }

        HttpResponse<java.io.InputStream> response = target.response;
        messageContext.setVariable(responsePrefix + ".status_code", response.statusCode());

        String headersJson =
            response.headers().map().entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .map(
                    entry ->
                        "\""
                            + entry.getKey().replace("\"", "\\\"")
                            + "\":\""
                            + String.join(",", entry.getValue()).replace("\"", "\\\"")
                            + "\"")
                .collect(Collectors.joining(", ", "{", "}"));
        messageContext.setVariable(responsePrefix + ".headers", headersJson);
        messageContext.setVariable(responsePrefix + ".body", target.bodyBuilder.toString());
        messageContext.setVariable(responsePrefix + ".success", true);
        successCount++;
      }

      messageContext.setVariable(VAR_PREFIX + "request_count", futures.size());
      messageContext.setVariable(VAR_PREFIX + "success_count", successCount);
      messageContext.setVariable(VAR_PREFIX + "error_count", errorCount);

      if (hasErrors && !continueOnError) {
        // The exception would have already been thrown, but as a safeguard:
        return ExecutionResult.ABORT;
      }

      return ExecutionResult.SUCCESS;

    } catch (Exception e) {
      messageContext.setVariable(VAR_PREFIX + "error", e.getMessage());
      messageContext.setVariable(VAR_PREFIX + "stacktrace", getStackTrace(e));
      return ExecutionResult.ABORT;
    }
  }

  private String getRequiredProperty(String name) {
    String value = properties.get(name);
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalStateException(String.format("'%s' property is required.", name));
    }
    return value.trim();
  }

  private String getOptionalProperty(String name, String defaultValue) {
    String value = properties.get(name);
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    return value.trim();
  }
}
