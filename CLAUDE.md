# fiji-mcp

MCP server bridging LLM agents with a running Fiji instance via WebSocket. Gives LLMs a scripting interface into a biologist's live Fiji session.

## Architecture

```
LLM Agent → (stdio MCP) → Python server → (ws://localhost:8765) → Fiji + Java plugin
```

- **Python** (`src/fiji_mcp/`): fastmcp server, WebSocket client, event buffer
- **Java** (`fiji-plugin/`): Fiji plugin, WebSocket server, script executor, event emitter

Design spec: `docs/superpowers/specs/2026-04-09-fiji-mcp-design.md`

## Design principles

- Scripting-first: LLM composes via `run_ij_macro` / `run_script` / `run_command`, no curated wrappers
- File-based image I/O: Fiji saves to temp, MCP returns paths — no base64 in the protocol
- Requests matched to responses by `id`; events are fire-and-forget from Fiji's side

## Java builds

Fiji is installed locally in `Fiji/` (gitignored). The build needs the bundled Zulu JDK 21:

```
cd fiji-plugin && JAVA_HOME="../Fiji/java/macos-arm64/zulu21.42.19-ca-jdk21.0.7-macosx_aarch64/zulu-21.jdk/Contents/Home" mvn package -q
cp target/fiji-mcp-bridge-0.1.0.jar ../Fiji/plugins/
```

Note: the Jaunch launcher's `--run` flag has known issues (fiji/fiji#416); use `./launch-fiji.sh` to start Fiji.

## Configuration

- Port: `8765` (override via `FIJI_MCP_PORT`, read by both Python and Java)
- Default execution hard ceiling: 600s (configurable per call via `hard_timeout_seconds`); opt-in long-poll via `soft_timeout_seconds` plus `wait_for_execution` and `kill_execution` — see `docs/superpowers/specs/2026-04-10-execution-result-envelope-design.md`
- WebSocket connect timeout: 5s
