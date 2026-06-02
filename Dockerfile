# Use an official, secure, lightweight Python base image
FROM python:3.11-slim AS builder

WORKDIR /app

# Install dependencies to build environment
COPY requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

# Second stage: runtime
FROM python:3.11-slim

WORKDIR /app

# Create a secure, non-privileged system user to run the server
RUN groupadd -g 10001 mcpuser && \
    useradd -u 10001 -g mcpuser -s /sbin/nologin -c "MCP Server User" mcpuser

# Copy installed dependencies from builder
COPY --from=builder --chown=mcpuser:mcpuser /root/.local /home/mcpuser/.local
COPY --chown=mcpuser:mcpuser . .

# Ensure paths and python path are set up correctly for local installs
ENV PATH=/home/mcpuser/.local/bin:$PATH
ENV PYTHONUNBUFFERED=1
ENV MCP_TRANSPORT=sse
ENV FASTMCP_HOST=0.0.0.0

# Switch to the non-privileged user
USER mcpuser

# Expose standard HTTP port for Cloud Run
EXPOSE 8080

# Dynamically bind FASTMCP_PORT to $PORT supplied by Cloud Run, fallback to 8080
CMD ["sh", "-c", "FASTMCP_PORT=${PORT:-8080} python3 server.py"]
