# fiji-mcp

## Goal
Build an MCP (Model Context Protocol) server that exposes Fiji/ImageJ functionality to LLM-based agents (Claude Code, Claude Desktop, etc.).

## Local Fiji
- Fiji is installed locally in `Fiji/` (gitignored).
- The Jaunch launcher's `--run` flag has known issues (fiji/fiji#416). Use direct Java invocation instead:
  ```
  JAVA_HOME="Fiji/java/macos-arm64/zulu21.42.19-ca-jdk21.0.7-macosx_aarch64/zulu-21.jdk/Contents/Home"
  "$JAVA_HOME/bin/java" -cp "Fiji/jars/*" ij.ImageJ --headless -eval '<macro code>' -batch
  ```

## Architecture (planned)
- Python MCP server wrapping Fiji's headless mode
- Expose image processing operations as MCP tools
- Support common bioimage analysis workflows (segmentation, filtering, measurements, format conversion)

## Development
- Python-based MCP server (use `mcp` SDK)
- Fiji invoked via CLI or pyimagej bridge
- Keep tools focused and composable
