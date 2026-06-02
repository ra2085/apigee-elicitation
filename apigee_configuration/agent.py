from google.adk.agents.llm_agent import LlmAgent
root_agent = LlmAgent(
    name="apigee_config_ref_agent",
    model="gemini-2.5-flash",
    instruction="Apigee Configuration Reference Agent"
)
