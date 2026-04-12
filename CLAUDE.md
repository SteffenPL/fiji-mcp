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

Fiji is installed locally in `Fiji/` (gitignored) and ships with a bundled Zulu JDK 21 at `Fiji/java/macos-arm64/zulu21.42.19-ca-jdk21.0.7-macosx_aarch64/zulu-21.jdk/Contents/Home`. `JAVA_HOME` points there automatically in Claude Code sessions via `.claude/settings.local.json`.

```
cd fiji-plugin && mvn package -q
cp target/fiji-mcp-bridge-0.1.0.jar ../Fiji/plugins/
```

Note: the Jaunch launcher's `--run` flag has known issues (fiji/fiji#416); use `./launch-fiji.sh` to start Fiji.

## Configuration

- Port: `8765` (override via `FIJI_MCP_PORT`, read by both Python and Java)
- Default execution hard ceiling: 600s (configurable per call via `hard_timeout_seconds`); opt-in long-poll via `soft_timeout_seconds` plus `wait_for_execution` and `kill_execution` — see `docs/superpowers/specs/2026-04-10-execution-result-envelope-design.md`
- WebSocket connect timeout: 5s

## Bug tracking

This project uses [`tk`](https://github.com/...) for issue tracking. Tickets live in `.tickets/` as markdown files with short prefix-matching IDs (e.g. `fm-9cbk`).

- `tk list` — all tickets
- `tk ready` — unblocked open/in-progress tickets, sorted by priority
- `tk blocked` — open tickets waiting on dependencies
- `tk closed` — recently closed
- `tk show <id>` — full ticket (partial IDs work)
- `tk create "title" -t bug -p 1 --external-ref feedback-4 --tags bridge,observability -d "..."` — new ticket
- `tk start <id>` / `tk close <id>` / `tk reopen <id>` — status transitions
- `tk dep <id> <blocker>` — dependency (directional); `tk link <id> <id>` — symmetric link
- `tk --help` for everything else

Conventions for this repo:
- **Feedback sessions** (`docs/superpowers/feedback_N.md`) are the canonical source for bridge UX bugs and proposals. When creating tickets from feedback, set `--external-ref feedback-N` and tag `feedback`.
- **Priority 0** is reserved for bugs that silently break core workflows (e.g. an observability gap that hides other bugs, or a regression that makes a canonical ImageJ command return wrong answers).
- **Link vs dep**: use `tk dep` only when one ticket literally cannot start until another is done (e.g. eval harness depends on headless mode). Use `tk link` for "these two fixes are related / would have caught each other" — the common case for observability bugs.
- When running `tk create` from tool calls, always capture the returned ID so follow-up `tk close` / `tk dep` / `tk link` calls can use it.

## Structural code search with ast-grep

Use `ast-grep` for queries that are about code *shape* rather than text: bug-family sweeps after finding one instance, refactoring checks, finding all members of a structural family (listener callbacks, decorated handlers, catch blocks).

```
ast-grep --lang java --pattern 'try { $$$ } catch (Throwable $V) { $$$BODY }' fiji-plugin/src/main/java
ast-grep --lang python --pattern '@mcp.tool
async def $FN($$$ARGS) -> $RET: $$$BODY' src/fiji_mcp/
```

`$VAR` matches one node, `$$$VARS` matches a sequence.
