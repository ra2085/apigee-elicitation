import os
import re
from datetime import date, datetime
from typing import Dict, Type
from pydantic import BaseModel, Field, field_validator
from mcp.server.fastmcp import FastMCP, Context

# Initialize the FastMCP server with host and port loaded dynamically from environment variables
mcp = FastMCP(
    "Form Elicitation Server",
    host=os.getenv("FASTMCP_HOST", "127.0.0.1"),
    port=int(os.getenv("FASTMCP_PORT", os.getenv("PORT", "8000")))
)

# Define Form Models using Pydantic V2 with custom field validators for normalization and validation
class SoftwareRequestForm(BaseModel):
    """Form used to request new software development projects or tool installations."""
    
    project_name: str = Field(
        ...,
        min_length=3,
        max_length=50,
        description="The name of the software project (3-50 characters)."
    )
    description: str = Field(
        ...,
        min_length=10,
        description="A detailed description of the application and its core purpose (at least 10 characters)."
    )
    framework: str = Field(
        ...,
        description="Preferred programming framework/library. Allowed: React, Vue, Angular, Django, FastAPI, Flask."
    )
    database_required: bool = Field(
        ...,
        description="Specify if the project requires a database or persistent storage (True/False)."
    )
    expected_launch_date: str = Field(
        ...,
        description="The target launch date of the software in YYYY-MM-DD format."
    )
    contact_email: str = Field(
        ...,
        description="Primary contact email address for project updates."
    )

    @field_validator("framework", mode="before")
    @classmethod
    def normalize_framework(cls, v):
        if isinstance(v, str):
            frameworks = ["React", "Vue", "Angular", "Django", "FastAPI", "Flask"]
            for f in frameworks:
                if f.lower() == v.strip().lower():
                    return f
        return v

    @field_validator("framework")
    @classmethod
    def validate_framework(cls, v):
        frameworks = ["React", "Vue", "Angular", "Django", "FastAPI", "Flask"]
        if v not in frameworks:
            raise ValueError(f"Invalid framework '{v}'. Must be one of: {', '.join(frameworks)}")
        return v

    @field_validator("database_required", mode="before")
    @classmethod
    def normalize_db_required(cls, v):
        if isinstance(v, str):
            val = v.strip().lower()
            if val in ("yes", "y", "true", "1"):
                return True
            if val in ("no", "n", "false", "0"):
                return False
        return v

    @field_validator("expected_launch_date")
    @classmethod
    def validate_launch_date(cls, v):
        if isinstance(v, date):
            return v.isoformat()
        if isinstance(v, str):
            try:
                parsed_date = datetime.strptime(v.strip(), "%Y-%m-%d").date()
                return parsed_date.isoformat()
            except ValueError:
                raise ValueError("Target launch date must be in YYYY-MM-DD format.")
        raise ValueError("Invalid date format.")

    @field_validator("contact_email")
    @classmethod
    def validate_contact_email(cls, v):
        if isinstance(v, str):
            email = v.strip()
            # Simple robust email regex matching standard EmailStr constraints
            if not re.match(r"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$", email):
                raise ValueError("Invalid contact email format.")
            return email
        raise ValueError("Email must be a string.")

class FeedbackForm(BaseModel):
    """Form used to collect customer satisfaction reviews and feedback comments."""
    
    customer_name: str = Field(
        ...,
        min_length=2,
        description="Your full name (at least 2 characters)."
    )
    rating: int = Field(
        ...,
        ge=1,
        le=5,
        description="Your satisfaction rating from 1 (poor) to 5 (excellent)."
    )
    comments: str = Field(
        ...,
        min_length=5,
        description="Detailed comments or suggestions about your experience (at least 5 characters)."
    )
    would_recommend: bool = Field(
        ...,
        description="Whether you would recommend our product to others (True/False)."
    )

    @field_validator("would_recommend", mode="before")
    @classmethod
    def normalize_would_recommend(cls, v):
        if isinstance(v, str):
            val = v.strip().lower()
            if val in ("yes", "y", "true", "1"):
                return True
            if val in ("no", "n", "false", "0"):
                return False
        return v

# Registry of supported form types
FORM_SCHEMAS: Dict[str, Type[BaseModel]] = {
    "software_request": SoftwareRequestForm,
    "feedback": FeedbackForm
}

# --- MCP Tools ---

@mcp.tool()
def list_form_types() -> str:
    """
    Returns a list of supported form types and a summary of their requirements.
    Use this tool to discover which forms can be elicited.
    """
    md = ["# Supported Elicitation Forms"]
    for key, form_class in FORM_SCHEMAS.items():
        md.append(f"\n### Form: `{key}`")
        md.append(f"**Description:** {form_class.__doc__ or 'Standard data collection form.'}")
        md.append("| Field Name | Required Type | Description |")
        md.append("| :--- | :--- | :--- |")
        for name, field_info in form_class.model_fields.items():
            type_name = getattr(field_info.annotation, "__name__", str(field_info.annotation))
            if name == "contact_email":
                type_name = "Email"
            elif name == "expected_launch_date":
                type_name = "Date (YYYY-MM-DD)"
            elif "bool" in type_name:
                type_name = "Boolean"
            elif "int" in type_name:
                type_name = "Integer"
            desc = field_info.description or ""
            md.append(f"| `{name}` | `{type_name}` | {desc} |")
    return "\n".join(md)

@mcp.tool()
async def fill_form(form_type: str, ctx: Context) -> str:
    """
    Initiates standard elicitation to fill out a form of the specified type.
    Presents a structured input form to the client/user, validates the response,
    and returns the finalized form data as a formatted Markdown table.
    
    Parameters:
        form_type: The type of form to elicit. Must be 'software_request' or 'feedback'.
    """
    if form_type not in FORM_SCHEMAS:
        allowed = ", ".join(f"'{k}'" for k in FORM_SCHEMAS.keys())
        return f"Error: Invalid form_type '{form_type}'. Allowed values are: {allowed}"
        
    schema = FORM_SCHEMAS[form_type]
    form_title = form_type.replace("_", " ").title()
    
    # Call the official standard elicitation protocol using FastMCP Context
    result = await ctx.elicit(
        message=f"Please provide the required fields to fill out the {form_title} Form:",
        schema=schema
    )
    
    if result.action == "decline":
        return f"❌ The {form_title} form elicitation was explicitly declined by the user."
    elif result.action == "cancel":
        return f"⚠️ The {form_title} form elicitation was cancelled or dismissed by the user."
        
    # User accepted and validation succeeded, so result.data is populated!
    data = result.data
    
    md = []
    md.append(f"# Form Completed Successfully!")
    md.append(f"**Form Type:** `{form_type}`")
    md.append(f"**Status:** Completed")
    md.append("")
    md.append("## Submitted Data Summary")
    md.append("| Field Name | Submitted Value | Description |")
    md.append("| :--- | :--- | :--- |")
    
    for name, field_info in schema.model_fields.items():
        val = getattr(data, name)
        # Pretty print values
        val_str = str(val)
        if isinstance(val, bool):
            val_str = "Yes" if val else "No"
        md.append(f"| `{name}` | **{val_str}** | {field_info.description or ''} |")
        
    return "\n".join(md)
if __name__ == "__main__":
    # Run the FastMCP server with transport configured via environment variable
    transport = os.getenv("MCP_TRANSPORT", "stdio")
    if transport == "sse":
        import uvicorn

        host = os.getenv("FASTMCP_HOST", "0.0.0.0")
        port = int(os.getenv("FASTMCP_PORT", os.getenv("PORT", "8080")))

        # Use FastMCP's built-in streamable_http_app which is already prefixed with /mcp
        app = mcp.streamable_http_app()

        print(f"Starting Form Elicitation Server on Streamable HTTP transport at http://{host}:{port}/mcp")
        uvicorn.run(app, host=host, port=port)
    else:
        mcp.run(transport=transport)
