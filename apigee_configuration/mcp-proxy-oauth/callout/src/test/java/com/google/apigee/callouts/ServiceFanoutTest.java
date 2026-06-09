package com.google.apigee.callouts;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.message.MessageContext;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

public class ServiceFanoutTest {

  private MessageContext msgCtxt;
  private ExecutionContext exeCtxt;

  @BeforeMethod
  public void setUp() {
    msgCtxt = mock(MessageContext.class);
    exeCtxt = mock(ExecutionContext.class);
  }

  @Test
  public void testDynamicHeadersProperty() {
    Map<String, String> props = new HashMap<>();
    props.put("urls", "http://example.com");
    props.put("headers", "{my_headers_var}");
    
    // Simulate resolution of the variable
    when(msgCtxt.getVariable("my_headers_var")).thenReturn("X-Dynamic: val1\nX-Dynamic: val2");
    
    ServiceFanout callout = new ServiceFanout(props);
    
    // We expect this to FAIL to add headers because the code parses the raw property "{my_headers_var}"
    // which has no colon, so it treats it as invalid header format.
    // If it were working correctly, it would resolve the var and see the headers.
    
    // Note: Since we can't easily spy on the private HttpClient in the class without PowerMock or similar,
    // we can check if the code throws or if we can infer behavior. 
    // Actually, the class makes real HTTP requests. This is a bit problematic for a unit test without mocking HttpClient.
    // However, the class uses a static HttpClient.
    
    // For reproduction, we might rely on the fact that if headers are not set, the request is sent without them.
    // Ideally we'd modify the class to allow injecting a mock HttpClient or HttpClient.Builder, but it's static final.
    // OR we can look at the debug variables set by the callout?
    // The callout sets `fanout.response.1.headers`.
    // But that is the RESPONSE headers. We want to check REQUEST headers.
    
    // The current class design is hard to test for outgoing request headers without a real server or mocking jdk.internal.
    // However, we can assert on the BUG logic we saw:
    // "ServiceFanout.java" lines 171-180 parses `headersSpec`.
    // If we can prove that logic is what's used, we confirm the bug.
    
    // Let's rely on the analysis for now, but to run code we need a test that compiles.
    // I will write a test that exercises the code path.
    // Since I cannot verify outgoing headers easily with the current class structure,
    // I will modify the test to just run locally and print output if I can, or use reflection?
    // No, I should fix the code.
  }

  @Test
  public void testInvalidConnectTimeout() {
    Map<String, String> props = new HashMap<>();
    props.put("urls", "http://localhost:1");
    props.put("connect-timeout-seconds", "invalid_value");

    ServiceFanout callout = new ServiceFanout(props);
    ExecutionResult result = callout.execute(msgCtxt, exeCtxt);
    assertEquals(result, ExecutionResult.ABORT);
    verify(msgCtxt).setVariable(eq("servicefanout_error"), contains("connect-timeout-seconds"));
  }

  @Test
  public void testValidConnectTimeout() {
    Map<String, String> props = new HashMap<>();
    props.put("urls", "http://localhost:1");
    props.put("connect-timeout-seconds", "5");
    props.put("continue-on-error", "true"); // Continue so it doesn't abort on connect error

    ServiceFanout callout = new ServiceFanout(props);
    ExecutionResult result = callout.execute(msgCtxt, exeCtxt);
    assertEquals(result, ExecutionResult.SUCCESS);
  }

  @Test
  public void testUnresolvedUrlsVariable() {
    Map<String, String> props = new HashMap<>();
    props.put("urls", "{mcp_target_urls}");
    props.put("continue-on-error", "false");

    ServiceFanout callout = new ServiceFanout(props);
    ExecutionResult result = callout.execute(msgCtxt, exeCtxt);
    assertEquals(result, ExecutionResult.ABORT);
    verify(msgCtxt).setVariable(eq("servicefanout_error"), contains("URI scheme must be http or https"));
  }

  @Test
  public void testUrlNoScheme() {
    Map<String, String> props = new HashMap<>();
    props.put("urls", "localhost:8080");
    props.put("continue-on-error", "false");

    ServiceFanout callout = new ServiceFanout(props);
    ExecutionResult result = callout.execute(msgCtxt, exeCtxt);
    assertEquals(result, ExecutionResult.ABORT);
    verify(msgCtxt).setVariable(eq("servicefanout_error"), contains("URI scheme must be http or https"));
  }
}
