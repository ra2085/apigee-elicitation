function generateUuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}
var message_id = "registry_collected_events_" + "_" + generateUuid();
var response_event = context.getVariable("response.event.current.content");
var current_session = context.getVariable("mcp_session_id");
context.setVariable("response.header.mcp-session-id", current_session);
context.setVariable(message_id, response_event);