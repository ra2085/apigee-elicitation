var mcpTargetSessions = context.getVariable("mcp_target_sessions").split(",");
var targetUrls = "";
var total = mcpTargetSessions.length - 1;
for(var i = 0; i < total; i++){
    var mcpTargetSession = mcpTargetSessions[i].split("|");
    targetUrls += mcpTargetSession[0] + ",";
    var currentIndex = i + 1;
    context.setVariable("mcp_target_session_id_"+currentIndex, mcpTargetSession[1]);
}
context.setVariable("mcp_target_urls", targetUrls);
