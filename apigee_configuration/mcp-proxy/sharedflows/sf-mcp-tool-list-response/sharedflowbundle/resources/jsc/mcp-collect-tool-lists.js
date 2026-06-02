var originalId = context.getVariable("jsonrpc_opid");
var mcp_targets = context.getVariable("propertyset.mcp-targets.mcp_targets");
mcp_targets = JSON.parse(mcp_targets);
function combineMcpResponses(responses, originalId) {
    // Return null or a default object if the input is empty or invalid
    if (!responses || Object.prototype.toString.call(responses) !== '[object Array]' || responses.length === 0) {
        return null;
    }

    // Initialize the base structure using the first object's metadata
    var combinedResponse = {
        "jsonrpc": responses[0].jsonrpc || "2.0",
        "id": originalId || 1,
        "result": {
            "tools": []
        }
    };

    // Iterate over the array of responses
    for (var i = 0; i < responses.length; i++) {
        var currentResponse = responses[i];

        // Check if the current response has the result.tools structure
        if (currentResponse && currentResponse.result && currentResponse.result.tools) {
            var tools = currentResponse.result.tools;

            // Push each tool into the combined array
            for (var j = 0; j < tools.length; j++) {
                combinedResponse.result.tools.push(tools[j]);
                tools[j]._meta = {
                  "mcp_target_host": mcp_targets[i].mcp_target_host,
                  "mcp_target_path": mcp_targets[i].mcp_target_path,
                  "mcp_target_protocol": mcp_targets[i].mcp_target_protocol,
                  "mcp_target_url": mcp_targets[i].mcp_target_url,
                  "_fastmcp": {
                    "tags": []
                  }
                }
            }
        }
    }

    return combinedResponse;
}

var count = parseInt(context.getVariable("servicefanout_request_count"), 10);
var toReturn = [];

for (var x = 0; x < count; x++) {
  var index = x + 1;
  var rawBody = context.getVariable("fanout_tool_list_request." + index + ".body");
  
  if (rawBody) {
    // Search for the first occurrence of an open curly brace or bracket
    var jsonStartIndex = rawBody.search(/[\{\[]/);
    
    if (jsonStartIndex !== -1) {
      // Extract everything from that bracket to the end of the string
      var cleanString = rawBody.substring(jsonStartIndex);
      
      try {
        var current = JSON.parse(cleanString);
        // Do what you need with 'current' here
        toReturn.push(current);
      } catch (e) {
        // It's highly recommended to wrap this in a try/catch in Rhino
        // so a malformed payload doesn't crash your entire Apigee proxy!
        print("Failed to parse JSON for index " + index + ": " + e.message);
      }
    }
  }
}

// Extract established target session IDs and URL mappings for subsequent poll/GET streams
var targetUrlsStr = context.getVariable("mcp_target_urls");
var targetUrls = targetUrlsStr ? targetUrlsStr.split(",") : [];
var sessionPairs = [];

for (var x = 0; x < count; x++) {
  var index = x + 1;
  var currentHeadersStr = context.getVariable("fanout_initialize_request." + index + ".headers");
  if (currentHeadersStr) {
    var current = JSON.parse(currentHeadersStr);
    var sessionId = current["mcp-session-id"] || current["MCP-Session-Id"];
    if (sessionId) {
      var url = targetUrls[x];
      if (url) {
        sessionPairs.push(url.trim() + "|" + sessionId.trim());
      }
    }
  }
}

if (sessionPairs.length > 0) {
  var mcpTargetSessions = sessionPairs.join(",");
  context.setVariable("mcp_target_sessions", mcpTargetSessions);
}

var resp = combineMcpResponses(toReturn, originalId);
var mcpTools = JSON.stringify(resp);
context.setVariable("mcp_session_tools", mcpTools);
resp = "data: " + mcpTools + "\n\n";
context.setVariable("response.content", resp);