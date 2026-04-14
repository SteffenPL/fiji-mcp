# fiji-mcp Test Environment

This project uses fiji-mcp to interact with a running Fiji instance.

## Setup

1. Start Fiji and enable the bridge: `Plugins > fiji-mcp > Start Bridge`
2. The MCP server connects automatically on first tool call

## Available tools

Use `run_ij_macro` to execute ImageJ macro code, `run_command` for menu commands,
and `list_commands` to search for available commands.

Use `get_recent_actions` to see what the user has been doing in Fiji,
and `export_actions_as_macro` to convert those actions into a reusable macro.

## Conventions

- Image data is file-based: use `save_image` to get a file path, then inspect the file
- Always check `list_images` before operating on images to confirm what's open
- Use `status` to verify the Fiji connection is alive
