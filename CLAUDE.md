# fiji-mcp

## Goal
MCP server bridging LLM agents (Claude Code, Claude Desktop) with a running Fiji instance via WebSocket. Gives LLMs a scripting interface into a biologist's live Fiji session.

## Architecture

Two components, one WebSocket connection:

```
LLM Agent → (stdio MCP) → Python MCP Server → (ws://localhost:8765) → Fiji + Java Plugin
```

- **fiji-mcp** (Python): MCP server (stdio transport), WebSocket client, event buffer. Lives in `src/fiji_mcp/`.
- **fiji-mcp-bridge** (Java): Fiji plugin, WebSocket server, script executor, event emitter. Lives in `fiji-plugin/` (Maven project).

Image data is file-based only (Fiji saves to temp dir, MCP returns paths). No base64 in the protocol.

Design spec: `docs/superpowers/specs/2026-04-09-fiji-mcp-design.md`

## Technology Stack

| Component | Choice |
|---|---|
| Python packaging | `uv` with `pyproject.toml` |
| MCP SDK | `fastmcp` 3.x (jlowin's PyPI package, stdio transport) |
| Python WebSocket | `websockets` |
| Java WebSocket | `org.java-websocket:Java-WebSocket` |
| Java JSON | `gson` (already in Fiji at `Fiji/jars/gson-2.11.0.jar`) |
| Java build | Maven, targeting JDK 21 |

## Configuration

- WebSocket port: `8765` (override via `FIJI_MCP_PORT` env var, read by both sides)
- Script execution timeout: 60s default
- WebSocket connect timeout: 5s

## Local Fiji

- Fiji is installed locally in `Fiji/` (gitignored).
- Bundled JDK: `Fiji/java/macos-arm64/zulu21.42.19-ca-jdk21.0.7-macosx_aarch64/zulu-21.jdk/Contents/Home`
- The Jaunch launcher's `--run` flag has known issues (fiji/fiji#416). For headless macro testing, use direct Java invocation:
  ```
  JAVA_HOME="Fiji/java/macos-arm64/zulu21.42.19-ca-jdk21.0.7-macosx_aarch64/zulu-21.jdk/Contents/Home"
  "$JAVA_HOME/bin/java" -cp "Fiji/jars/*" ij.ImageJ --headless -eval '<macro code>' -batch
  ```

## Development

- Keep tools focused and composable — LLM composes via scripting, no curated wrappers like `gaussian_blur`
- WebSocket protocol uses JSON with `type` field; request/response matched by `id`
- Events (command_executed, image_opened, image_closed) are fire-and-forget from Fiji side
- favour `rg` (ripgrep) over `grep`