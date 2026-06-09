var mcpTargetSessionsStr = context.getVariable("mcp_target_sessions") || "";
var mcpTargetSessions = mcpTargetSessionsStr ? mcpTargetSessionsStr.split(",") : [];
var targetUrls = "";
var mcpTargetsStr = context.getVariable("propertyset.mcp-targets.mcp_targets");
var mcpTargets = mcpTargetsStr ? JSON.parse(mcpTargetsStr) : [];

var currentIndex = 1;
for (var i = 0; i < mcpTargetSessions.length; i++) {
    var sessionStr = mcpTargetSessions[i].trim();
    if (!sessionStr) continue;
    
    var parts = sessionStr.split("|");
    if (parts.length === 2) {
        var key = parts[0].trim();
        var sessionId = parts[1].trim();
        var resolvedUrl = key;
        
        // If key is an index, resolve it to the URL
        if (!isNaN(key)) {
            var idx = parseInt(key, 10);
            if (mcpTargets && idx >= 0 && idx < mcpTargets.length) {
                resolvedUrl = mcpTargets[idx].mcp_target_url;
            }
        }
        
        targetUrls += resolvedUrl + ",";
        context.setVariable("mcp_target_session_id_" + currentIndex, sessionId);
        currentIndex++;
    }
}
context.setVariable("mcp_target_urls", targetUrls);