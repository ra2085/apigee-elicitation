var targetSessionsStr = context.getVariable("mcp_target_sessions") || "";
var targetUrl = context.getVariable("mcp_target_url");
var newSessionId = context.getVariable("mcp_initialize_response.header.mcp-session-id");
var mcpTargetsStr = context.getVariable("propertyset.mcp-targets.mcp_targets");

if (targetUrl && newSessionId && mcpTargetsStr) {
    try {
        var mcpTargets = JSON.parse(mcpTargetsStr);
        var targetIndex = -1;
        
        for (var k = 0; k < mcpTargets.length; k++) {
            if (mcpTargets[k] && mcpTargets[k].mcp_target_url === targetUrl) {
                targetIndex = k;
                break;
            }
        }
        
        if (targetIndex !== -1) {
            var newPair = targetIndex + "|" + newSessionId.trim();
            if (targetSessionsStr) {
                // Prevent duplicate indexes in the mapping string
                var pairs = targetSessionsStr.split(",");
                var filteredPairs = [];
                for (var i = 0; i < pairs.length; i++) {
                    var parts = pairs[i].split("|");
                    if (parts.length === 2 && parts[0].trim() !== String(targetIndex)) {
                        filteredPairs.push(pairs[i]);
                    }
                }
                filteredPairs.push(newPair);
                targetSessionsStr = filteredPairs.join(",");
            } else {
                targetSessionsStr = newPair;
            }
            context.setVariable("mcp_target_sessions", targetSessionsStr);
        }
    } catch(e) {
        print("Error updating target session index: " + e.message);
    }
}
