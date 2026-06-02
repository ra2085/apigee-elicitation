var count = parseInt(context.getVariable("servicefanout_request_count"), 10);
var toReturn = "";

for(var x = 0; x<count; x++){
  var index = x + 1;
  var current = JSON.parse(context.getVariable("fanout_initialize_request." + index + ".headers"));
  context.setVariable("mcp_target_session_id_"+ index, current["mcp-session-id"]);
}