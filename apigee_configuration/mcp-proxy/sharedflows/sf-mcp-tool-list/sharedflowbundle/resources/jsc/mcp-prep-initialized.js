var count = parseInt(context.getVariable("servicefanout_request_count"), 10);
var toReturn = "";

for(var x = 0; x<count; x++){
  var index = x + 1;
  var headersStr = context.getVariable("fanout_initialize_request." + index + ".headers");
  if (headersStr) {
    try {
      var current = JSON.parse(headersStr);
      if (current) {
        var sessionId = current["mcp-session-id"] || current["MCP-Session-Id"];
        if (sessionId) {
          context.setVariable("mcp_target_session_id_" + index, sessionId);
        }
      }
    } catch (e) {
      print("Failed to parse headers for index " + index + ": " + e.message);
    }
  }
}