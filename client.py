#!/usr/bin/env python3
"""
Interactive and Automated Protocol-Compliant Test Client for Form Elicitation MCP Server.
Uses standard Python mcp stdio or remote Streamable HTTP connection to test official MCP elicitation
using ClientSession and an elicitation_callback.
"""

import os
import sys
import anyio
import argparse
import subprocess
from mcp import StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.client.streamable_http import streamablehttp_client
from mcp.client.session import ClientSession
import mcp.types as types

# Cloud Run Endpoint
REMOTE_URL = ""

def print_header(text: str):
    print("\n" + "=" * 60)
    print(f" {text} ".center(60, "="))
    print("=" * 60)

# --- Interactive Console Elicitation Callback ---
async def interactive_elicitation_callback(context, params: types.ElicitRequestParams) -> types.ElicitResult:
    print_header("ELICITATION REQ RECEIVED FROM SERVER")
    print(f"💬 Message: {params.message}")
    
    if params.mode != "form":
        print("⚠️ Only 'form' elicitation mode is supported by this client.")
        return types.ElicitResult(action="cancel")
        
    properties = params.requestedSchema.get("properties", {})
    answers = {}
    
    print("\nPlease fill out the following questionnaire:")
    for name, prop in properties.items():
        desc = prop.get("description", "No description provided.")
        prop_type = prop.get("type", "string")
        allowed_enum = prop.get("enum", None)
        
        print(f"\n📋 [Field: {name}]")
        print(f"   Type: {prop_type}")
        print(f"   Description: {desc}")
        if allowed_enum:
            print(f"   Allowed values: {', '.join(str(x) for x in allowed_enum)}")
            
        while True:
            try:
                val_input = input(f"👉 Enter value for '{name}' (or 'q' to cancel): ").strip()
                if val_input.lower() in ("q", "quit", "exit", "cancel"):
                    print("\n❌ User requested cancellation.")
                    return types.ElicitResult(action="cancel")
                    
                if not val_input and prop.get("default") is not None:
                    answers[name] = prop["default"]
                    break
                    
                if not val_input:
                    print("   ⚠️ Value cannot be empty. Please provide a valid input.")
                    continue
                    
                # Coerce basic primitive types for JSON schema
                if prop_type == "boolean":
                    if val_input.lower() in ("true", "yes", "1", "y"):
                        answers[name] = True
                        break
                    elif val_input.lower() in ("false", "no", "0", "n"):
                        answers[name] = False
                        break
                    else:
                        print("   ⚠️ Invalid boolean. Enter 'yes'/'no' or 'true'/'false'.")
                        continue
                elif prop_type == "integer":
                    try:
                        answers[name] = int(val_input)
                        break
                    except ValueError:
                        print("   ⚠️ Invalid integer. Please enter a valid number.")
                        continue
                elif prop_type == "number":
                    try:
                        answers[name] = float(val_input)
                        break
                    except ValueError:
                        print("   ⚠️ Invalid decimal number.")
                        continue
                else:
                    answers[name] = val_input
                    break
            except (KeyboardInterrupt, EOFError):
                print("\n❌ Interrupted.")
                return types.ElicitResult(action="cancel")
                
    print("\n--- Compiling and Submitting Form Responses ---")
    return types.ElicitResult(
        action="accept",
        content=answers
    )

# --- Automated Simulated Elicitation Callback ---
async def automated_elicitation_callback(context, params: types.ElicitRequestParams) -> types.ElicitResult:
    print("\n🤖 [Simulated Client] Handling elicitation request...")
    if params.mode != "form":
        return types.ElicitResult(action="cancel")
        
    properties = params.requestedSchema.get("properties", {})
    answers = {}
    
    # Simulate filling out 'software_request' or 'feedback' form
    for name in properties.keys():
        if name == "project_name":
            answers[name] = "SecureVault Cloud"
        elif name == "description":
            answers[name] = "A highly encrypted cloud storage platform for sharing corporate credentials securely."
        elif name == "framework":
            answers[name] = "FastAPI"
        elif name == "database_required":
            answers[name] = True
        elif name == "expected_launch_date":
            answers[name] = "2026-12-31"
        elif name == "contact_email":
            answers[name] = "security-team@securevault.io"
        elif name == "customer_name":
            answers[name] = "Ruben Gonzalez"
        elif name == "rating":
            answers[name] = 5
        elif name == "comments":
            answers[name] = "Excellent developer onboarding experience and clean APIs!"
        elif name == "would_recommend":
            answers[name] = True
            
    print(f"🤖 [Simulated Client] Submitting Answers: {answers}")
    return types.ElicitResult(
        action="accept",
        content=answers
    )

async def run_client(interactive: bool, remote: bool, url: str):
    elicitation_cb = interactive_elicitation_callback if interactive else automated_elicitation_callback
    
    if remote:
        print(f"Connecting to remote server via Streamable HTTP: {url}")
        
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream"
        }

        
        async with streamablehttp_client(url, headers=headers) as (read, write, _):
            async with ClientSession(read, write, elicitation_callback=elicitation_cb) as session:
                await session.initialize()
                await run_flow(session, interactive)
    else:
        base_dir = os.path.abspath(os.path.dirname(__file__))
        python_executable = sys.executable
        server_script = os.path.join(base_dir, "server.py")
        
        print(f"Starting local server subprocess: {server_script}")
        server_params = StdioServerParameters(
            command=python_executable,
            args=[server_script]
        )
        
        async with stdio_client(server_params) as (read, write):
            async with ClientSession(read, write, elicitation_callback=elicitation_cb) as session:
                await session.initialize()
                await run_flow(session, interactive)

async def run_flow(session: ClientSession, interactive: bool):
    # Populate Apigee's session tools cache first
    await session.list_tools()
    
    # Discover form schemas using standard tool call list_form_types
    print("\nDiscovering supported forms from server...")
    forms_summary = await session.call_tool("list_form_types")
    print(forms_summary.content[0].text)
    
    # Ask user to choose if interactive, else choose software_request automatically
    form_type = "software_request"
    if interactive:
        print("\nSelect a form type to elicit:")
        print("  1. software_request (Request software development projects)")
        print("  2. feedback (Submit customer reviews)")
        choice = input("Enter choice [1 or 2, default 1]: ").strip()
        if choice == "2":
            form_type = "feedback"
            
    print_header(f"INITIATING ELICITATION FOR '{form_type}'")
    
    try:
        # Call standard fill_form tool which triggers elicitation callback nested inside it
        result = await session.call_tool("fill_form", arguments={"form_type": form_type})
        
        print_header("FINAL RESULT FROM SERVER")
        print(result.content[0].text)
        
    except Exception as e:
        print(f"\n❌ Error during form elicitation: {e}")

def main():
    parser = argparse.ArgumentParser(description="Protocol-Compliant MCP Elicitation Client")
    parser.add_argument("--interactive", action="store_true", help="Run in interactive terminal wizard mode")
    parser.add_argument("--remote", action="store_true", help="Run against the Cloud Run deployed server")
    parser.add_argument("--url", type=str, default=os.getenv("MCP_SERVER_URL", REMOTE_URL), help="Custom URL to connect to in remote mode")
    args = parser.parse_args()
    
    try:
        anyio.run(run_client, args.interactive, args.remote, args.url)
    except KeyboardInterrupt:
        print("\nGoodbye!")

if __name__ == "__main__":
    main()
