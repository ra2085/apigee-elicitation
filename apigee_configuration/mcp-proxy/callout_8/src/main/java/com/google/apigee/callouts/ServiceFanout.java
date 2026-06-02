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

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.MessageContext;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * An Apigee Java callout that sends multiple HTTP requests ("fan-out") in parallel.
 * <p>This callout uses {@link java.net.HttpURLConnection} and {@link
 * java.util.concurrent.CompletableFuture} to send requests concurrently and asynchronously. It is
 * configurable via properties in the JavaCallout policy.
 */
public class ServiceFanout implements Execution {

  private static final String VAR_PREFIX = "servicefanout_";

  // A dedicated, fixed-size thread pool for HTTP requests.
  // Using a ThreadFactory to create daemon threads allows the JVM to exit
  // even if this pool has active threads. This is important in application server
  // environments like Apigee to prevent thread leaks on application undeploy/redeploy.
  private static final ExecutorService httpClientExecutor =
      Executors.newFixedThreadPool(
          20, // A reasonable default size, can be made configurable.
          new ThreadFactory() {
            public Thread newThread(Runnable r) {
              Thread t = Executors.defaultThreadFactory().newThread(r);
              t.setDaemon(true);
              return t;
            }
          });

  private final Map<String, String> properties;

  public ServiceFanout(Map<String, String> properties) {
    this.properties = properties;
  }

  // This pattern finds the innermost curly braces, which is key to handling nested templates.
  private static final Pattern INNERMOST_TEMPLATE_PATTERN = Pattern.compile("\\{([^{}]+)\\}");

  /** A simple container for an HTTP response, compatible with Java 8. */
  private static class SimpleHttpResponse {
    final int statusCode;
    final String body;
    final Map<String, List<String>> headers;

    SimpleHttpResponse(int statusCode, String body, Map<String, List<String>> headers) {
      this.statusCode = statusCode;
      this.body = body;
      this.headers = headers;
    }
  }

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
      // 1. Get property specifications
      String urlsSpec = getRequiredProperty("urls");
      String methodSpec = getOptionalProperty("method", "GET");
      String bodySpec = getOptionalProperty("body", null);
      String headersSpec = getOptionalProperty("headers", null);
      String timeoutSecondsSpec = getOptionalProperty("timeout-seconds", "30");
      String outputPrefixSpec = getOptionalProperty("output-variable-prefix", "fanout.response");
      String continueOnErrorSpec = getOptionalProperty("continue-on-error", "false");

      // 2. Resolve properties against message context
      String urlsValue = resolveTemplate(urlsSpec, messageContext);
      if (urlsValue == null || urlsValue.trim().isEmpty()) {
        // If the variable for URLs is not found or empty, there's nothing to do.
        return ExecutionResult.SUCCESS;
      }
      List<String> urls = Arrays.asList(urlsValue.split("\\s*,\\s*"));

      final String method = resolveTemplate(methodSpec, messageContext);
      final String body = resolveTemplate(bodySpec, messageContext);
      String headersValue = resolveTemplate(headersSpec, messageContext);
      String timeoutSecondsValue = resolveTemplate(timeoutSecondsSpec, messageContext);
      final String outputPrefix = resolveTemplate(outputPrefixSpec, messageContext);
      final boolean continueOnError =
          Boolean.parseBoolean(resolveTemplate(continueOnErrorSpec, messageContext));

      final int timeoutMillis;
      try {
        timeoutMillis = Integer.parseInt(timeoutSecondsValue) * 1000;
      } catch (NumberFormatException e) {
        throw new IllegalStateException(
            "'timeout-seconds' must be a valid integer. Value was: " + timeoutSecondsValue);
      }

      // Use a map of templates for headers, to be resolved for each request.
      final Map<String, String> headerTemplates = new LinkedHashMap<>();
      if (headersValue != null && !headersValue.isEmpty()) {
        for (String header : headersValue.split("\\s*,\\s*")) {
          String[] parts = header.split(":", 2);
          if (parts.length == 2) {
            headerTemplates.put(parts[0].trim(), parts[1].trim());
          }
        }
      }

      // 3. Create tasks for parallel execution
      List<String> resolvedUrls = new ArrayList<>();
      List<CompletableFuture<SimpleHttpResponse>> futures = new ArrayList<>();
      for (int i = 0; i < urls.size(); i++) {
        String url = urls.get(i);
        if (url == null || url.trim().isEmpty()) {
          continue;
        }

        messageContext.setVariable(VAR_PREFIX + "current_index", i + 1);
        final String resolvedUrl = resolveTemplate(url, messageContext);
        if (resolvedUrl == null || resolvedUrl.trim().isEmpty()) {
          continue;
        }
        resolvedUrls.add(resolvedUrl);

        final Map<String, String> resolvedHeaders = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headerTemplates.entrySet()) {
          resolvedHeaders.put(
              entry.getKey(), resolveTemplate(entry.getValue(), messageContext));
        }

        final String resolvedBody = resolveTemplate(body, messageContext);

        Supplier<SimpleHttpResponse> task =
            new Supplier<SimpleHttpResponse>() {
              @Override
              public SimpleHttpResponse get() {
                HttpURLConnection connection = null;
                try {
                  URL requestUrl = new URL(resolvedUrl);
                  connection = (HttpURLConnection) requestUrl.openConnection();
                  connection.setRequestMethod(method.toUpperCase());
                  connection.setConnectTimeout(timeoutMillis);
                  connection.setReadTimeout(timeoutMillis);
                  connection.setInstanceFollowRedirects(false);

                  for (Map.Entry<String, String> header : resolvedHeaders.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                  }

                  if (resolvedBody != null && !resolvedBody.isEmpty()) {
                    connection.setDoOutput(true);
                    byte[] input = resolvedBody.getBytes(StandardCharsets.UTF_8);
                    try (OutputStream os = connection.getOutputStream()) {
                      os.write(input, 0, input.length);
                    }
                  }

                  int statusCode = connection.getResponseCode();
                  InputStream is =
                      (statusCode >= 200 && statusCode < 300)
                          ? connection.getInputStream()
                          : connection.getErrorStream();

                  String responseBody = "";
                  if (is != null) {
                    try (BufferedReader br =
                        new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                      responseBody = br.lines().collect(Collectors.joining("\n"));
                    }
                  }
                  return new SimpleHttpResponse(
                      statusCode, responseBody, connection.getHeaderFields());
                } catch (Exception e) {
                  throw new RuntimeException(e);
                } finally {
                  if (connection != null) {
                    connection.disconnect();
                  }
                }
              }
            };
        futures.add(CompletableFuture.supplyAsync(task, httpClientExecutor));
      }

      if (futures.isEmpty()) {
        return ExecutionResult.SUCCESS;
      }

      // 4. Execute tasks and gather results
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

      boolean hasErrors = false;
      int successCount = 0;
      int errorCount = 0;

      for (int i = 0; i < futures.size(); i++) {
        CompletableFuture<SimpleHttpResponse> future = futures.get(i);
        String currentUrl = resolvedUrls.get(i);
        String responsePrefix = outputPrefix + "." + (i + 1);

        try {
          SimpleHttpResponse response = future.get(); // This won't block

          messageContext.setVariable(responsePrefix + ".status_code", response.statusCode);
          messageContext.setVariable(responsePrefix + ".body", response.body);

          String headersJson =
              response.headers.entrySet().stream()
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
          messageContext.setVariable(responsePrefix + ".success", true);
          successCount++;

        } catch (Exception e) {
          hasErrors = true;
          errorCount++;
          Throwable cause = e;
          if (e instanceof ExecutionException) {
            cause = e.getCause();
            if (cause instanceof RuntimeException) {
              cause = cause.getCause();
            }
          }
          String errorMsg = "Error processing URL " + currentUrl + ": " + cause.getMessage();
          messageContext.setVariable(responsePrefix + ".success", false);
          messageContext.setVariable(responsePrefix + ".error", errorMsg);
          messageContext.setVariable(responsePrefix + ".stacktrace", getStackTrace(cause));
          if (!continueOnError) {
            throw new Exception(errorMsg, cause);
          }
        }
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
