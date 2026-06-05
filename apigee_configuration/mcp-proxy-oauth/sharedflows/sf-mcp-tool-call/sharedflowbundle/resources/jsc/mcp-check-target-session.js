var targetSessionsStr = context.getVariable("mcp_target_sessions") || "";
var targetUrl = context.getVariable("mcp_target_url");
var mcpTargetsStr = context.getVariable("propertyset.mcp-targets.mcp_targets");
var found = false;

if (targetSessionsStr && targetUrl && mcpTargetsStr) {
    try {
        var mcpTargets = JSON.parse(mcpTargetsStr);
        var targetIndex = -1;
        
        // Find target index from config array
        for (var k = 0; k < mcpTargets.length; k++) {
            if (mcpTargets[k] && mcpTargets[k].mcp_target_url === targetUrl) {
                targetIndex = k;
                break;
            }
        }
        
        if (targetIndex !== -1) {
            var pairs = targetSessionsStr.split(",");
            for (var i = 0; i < pairs.length; i++) {
                var parts = pairs[i].split("|");
                if (parts.length === 2 && parts[0].trim() === String(targetIndex)) {
                    var sessionId = parts[1].trim();
                    context.setVariable("mcp_target_session_id", sessionId);
                    context.setVariable("mcp_initialize_response.header.mcp-session-id", sessionId);
                    context.setVariable("lookupcache.MCP-LookupTargetSession.cachehit", "true");
                    found = true;
                    break;
                }
            }
        }
    } catch(e) {
        print("Error checking target session index: " + e.message);
    }
}

if (!found) {
    context.setVariable("lookupcache.MCP-LookupTargetSession.cachehit", "false");
}
