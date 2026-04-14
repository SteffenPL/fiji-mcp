# Example Claude Code Configuration

## `test-env/` — Using fiji-mcp in your own project

Copy `.mcp.json` and `CLAUDE.md` into your project root, then update the path in `.mcp.json` to point to your fiji-mcp installation.

### Setup

1. Copy the files:
   ```bash
   cp tests/examples/test-env/.mcp.json /path/to/your/project/
   cp tests/examples/test-env/CLAUDE.md /path/to/your/project/
   ```
2. Edit `.mcp.json` and replace `/absolute/path/to/fiji-mcp` with the actual path
3. Start Fiji and enable the bridge (`Plugins > fiji-mcp > Start Bridge`)
4. Start Claude Code in your project — the MCP server connects on first tool call

## Dev environment

This repo's own `.mcp.json` at the project root configures fiji-mcp for development. It uses a relative path (`"."`) so it works without editing.
