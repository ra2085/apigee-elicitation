var originalId = context.getVariable("jsonrpc_opid");
var mcp_targets = context.getVariable("propertyset.mcp-targets.mcp_targets");

if (mcp_targets) {
    try {
        mcp_targets = JSON.parse(mcp_targets);
    } catch(e) {
        mcp_targets = [];
        print("Error parsing targets propertyset: " + e.message);
    }
} else {
    mcp_targets = [];
}

function combineMcpResponses(responses, originalId) {
    // Return null or a default object if the input is empty or invalid
    if (!responses || Object.prototype.toString.call(responses) !== '[object Array]' || responses.length === 0) {
        return null;
    }

    // Initialize the base structure using the first object's metadata
    var combinedResponse = {
        "jsonrpc": responses[0].response.jsonrpc || "2.0",
        "id": originalId || 1,
        "result": {
            "tools": []
        }
    };

    // Iterate over the array of responses
    for (var i = 0; i < responses.length; i++) {
        var currentResponse = responses[i].response;
        var target = responses[i].target;

        // Check if the current response has the result.tools structure
        if (currentResponse && currentResponse.result && currentResponse.result.tools) {
            var tools = currentResponse.result.tools;

            // Push each tool into the combined array
            for (var j = 0; j < tools.length; j++) {
                combinedResponse.result.tools.push(tools[j]);
                tools[j]._meta = {
                  "mcp_target_host": target.mcp_target_host,
                  "mcp_target_path": target.mcp_target_path,
                  "mcp_target_protocol": target.mcp_target_protocol,
                  "mcp_target_url": target.mcp_target_url,
                  "_fastmcp": {
                    "tags": []
                  }
                };
            }
        }
    }

    return combinedResponse;
}

var count = parseInt(context.getVariable("servicefanout_request_count"), 10) || 0;
var toReturn = [];
var sessionPairs = [];

// Extract target URL array for mapping target indices to their session IDs
var targetUrlsStr = context.getVariable("mcp_target_urls");
var targetUrls = targetUrlsStr ? targetUrlsStr.split(",") : [];

for (var x = 0; x < count && x < mcp_targets.length; x++) {
    var index = x + 1;
    var rawBody = context.getVariable("fanout_tool_list_request." + index + ".body");
    var currentHeadersStr = context.getVariable("fanout_initialize_request." + index + ".headers");
    
    // 1. Recover Session ID from initialized request headers
    if (currentHeadersStr) {
        try {
            var currentHeaders = JSON.parse(currentHeadersStr);
            if (currentHeaders) {
                var sessionId = currentHeaders["mcp-session-id"] || currentHeaders["MCP-Session-Id"];
                if (sessionId) {
                    // Use index 'x' as key instead of full URL
                    sessionPairs.push(x + "|" + sessionId.trim());
                }
            }
        } catch (e) {
            print("Failed to parse headers for index " + index + ": " + e.message);
        }
    }
    
    // 2. Parse tools list from body
    if (rawBody) {
        var jsonStartIndex = rawBody.search(/[\{\[]/);
        if (jsonStartIndex !== -1) {
            var cleanString = rawBody.substring(jsonStartIndex);
            try {
                var currentBody = JSON.parse(cleanString);
                toReturn.push({
                    target: mcp_targets[x],
                    response: currentBody
                });
            } catch (e) {
                print("Failed to parse tool list JSON for index " + index + ": " + e.message);
            }
        }
    }
}

// Save active session mappings if any succeeded
if (sessionPairs.length > 0) {
    var mcpTargetSessions = sessionPairs.join(",");
    context.setVariable("mcp_target_sessions", mcpTargetSessions);
    context.setVariable("mcp_fanout_failed", "false");
} else {
    // Flag total failure if no target session could be established
    context.setVariable("mcp_fanout_failed", "true");
}

var resp = combineMcpResponses(toReturn, originalId);
if (resp) {
    var mcpTools = JSON.stringify(resp);
    context.setVariable("mcp_session_tools", mcpTools);
    context.setVariable("response.content", "data: " + mcpTools + "\n\n");
}