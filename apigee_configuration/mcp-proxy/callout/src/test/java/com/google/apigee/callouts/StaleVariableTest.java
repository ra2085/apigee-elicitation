package com.google.apigee.callouts;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.message.MessageContext;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;

public class StaleVariableTest {

  private MessageContext msgCtxt;
  private ExecutionContext exeCtxt;
  private Map<String, Object> variables;
  private static final String VAR_PREFIX = "servicefanout_";

  @BeforeMethod
  public void setUp() {
    msgCtxt = mock(MessageContext.class);
    exeCtxt = mock(ExecutionContext.class);
    variables = new HashMap<>();
    
    // Simulate getVariable/setVariable/removeVariable using the map
    when(msgCtxt.getVariable(anyString())).thenAnswer(invocation -> variables.get(invocation.getArgument(0)));
    
    doAnswer(invocation -> {
      variables.put(invocation.getArgument(0), invocation.getArgument(1));
      return null;
    }).when(msgCtxt).setVariable(anyString(), any());
    
    doAnswer(invocation -> {
      variables.remove(invocation.getArgument(0));
      return true;
    }).when(msgCtxt).removeVariable(anyString());
  }

  @Test
  public void testStaleIndexVariable() {
    Map<String, String> props = new HashMap<>();
    props.put("urls", "http://server1,http://server2");
    // This header uses the current index variable
    props.put("headers", "My-Header: {val_{servicefanout_current_index}}"); 
    
    // Simulate that servicefanout_current_index ALREADY exists (stale from prev run)
    variables.put("servicefanout_current_index", 2);
    
    // When resolving {val_{servicefanout_current_index}}, if it uses the stale value (2), 
    // it becomes {val_2}.
    // We simulate val_2 exists
    variables.put("val_2", "stale-value");
    // We simulate val_1 exists (desired value for index 1)
    variables.put("val_1", "correct-value-1");
    
    ServiceFanout callout = new ServiceFanout(props);
    callout.execute(msgCtxt, exeCtxt);
    
    // Verify that request headers are logged to the variable
    // We expect "correct-value-1" for index 1 because we cleared the stale variables!
    verify(msgCtxt).setVariable(eq("servicefanout_1_request_headers"), contains("correct-value-1"));
    
    // Also verify that we cleared the variables
    verify(msgCtxt).removeVariable(VAR_PREFIX + "current_index");
    verify(msgCtxt).removeVariable(VAR_PREFIX + "current_url");
  }
}
