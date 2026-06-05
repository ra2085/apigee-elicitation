var requestCount = parseInt(context.getVariable("servicefanout_request_count") || "0");
var aggregatedPayload = "";

for (var i = 1; i <= requestCount; i++) {
    var body = context.getVariable("fanout_event_poll." + i + ".body");
    if (body && body.trim().length > 0) {
        // Filter out empty keep-alive pings from backends to avoid spamming the client
        if (body.indexOf("event: message") !== -1 || body.indexOf("data:") !== -1) {
            aggregatedPayload += body.trim() + "\n\n";
        }
    }
}

// If no real events were fetched, return a standard keep-alive ping with retry directive
if (aggregatedPayload.trim().length === 0) {
    aggregatedPayload = "event: ping\nretry: 60000\ndata: keep-alive\n\n";
}

context.setVariable("response.content", aggregatedPayload);
context.setVariable("response.status.code", 200);
context.setVariable("response.header.Content-Type", "text/event-stream");
context.setVariable("response.header.mcp-session-id", context.getVariable("mcp_session_id"));
