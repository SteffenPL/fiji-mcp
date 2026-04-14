# fiji-mcp

> **Prior art & related projects** — several other projects bridge bioimage tools with LLMs via MCP.
> This project was developed independently but shares the same problem space:
>
> - [fiji_mcp](https://github.com/NicoKiaru/fiji_mcp) (NicoKiaru) — Fiji MCP via PyImageJ (in-process, Groovy)
> - [fiji-mcp-bridge](https://github.com/kusumotok/fiji-mcp-bridge) (kusumotok) — Fiji MCP via TCP sockets, curated tool wrappers
> - [napari-mcp](https://github.com/royerlab/napari-mcp) (royerlab) — napari MCP server, PyPI published
> - [bioimage-mcp](https://github.com/cqian89/bioimage-mcp) (cqian89) — artifact-based I/O, isolated conda envs
> - [bioimage-mcp-server](https://github.com/Ichoran/bioimage-mcp-server) (Ichoran) — Java MCP for Bio-Formats
> - [BioImage-Agent](https://github.com/llnl/bioimage-agent) (LLNL) — napari plugin over MCP via sockets
> - [cellpose_mcp](https://github.com/surajinacademia/cellpose_mcp) — Cellpose segmentation MCP

An MCP server that gives LLM agents (Claude Code, Claude Desktop, Cursor, etc.) a live scripting interface into a running [Fiji](https://fiji.sc/) instance via WebSocket.

```
LLM Agent  --(MCP/stdio)-->  Python MCP Server  --(WebSocket)-->  Fiji + Java Plugin
```

## What it does

- **Run macros and scripts** in Fiji from your LLM conversation (`run_ij_macro`, `run_script`, `run_command`)
- **Inspect images** — list open images, get metadata, save to disk
- **Visual feedback via thumbnails** — `get_thumbnail` returns a display-ready PNG with the current LUT and overlays baked in, so the LLM can *see* what it's working with
- **Observe user actions** — the plugin captures what the biologist does in Fiji's GUI (commands, image opens/closes, ROI edits) and streams it to the LLM via an event log
- **Export workflows** — convert a recorded action sequence to a runnable ImageJ macro
- **Search commands** — find Fiji menu commands by name

### Thumbnails and visual models

This project is designed for use with **vision-capable LLMs** (Claude, GPT-4o, Gemini, etc.). The `get_thumbnail` tool is the primary way the agent sees image data — it returns a scaled PNG with the current LUT, brightness/contrast, and overlays already applied, exactly as the image appears in Fiji.

Agents should call `get_thumbnail` liberally: after opening an image, after each processing step, and before reporting results. This gives the model visual ground truth to catch mistakes (wrong channel, bad threshold, off-target ROI) that text-only metadata would miss. The thumbnail is file-path-based — no base64 in the protocol — so it works efficiently even for large images.

### Available tools

| Tool | Description |
|---|---|
| `run_ij_macro` | Execute ImageJ macro code |
| `run_script` | Execute a script in any supported language (Python, Groovy, JS, ...) |
| `run_command` | Run a Fiji menu command by name |
| `list_images` | List all open images |
| `get_image_info` | Get image metadata (dimensions, type, path) |
| `get_thumbnail` | Get a display-ready PNG snapshot (LUT + overlays baked in) |
| `save_image` | Save an image to disk (TIFF, PNG, JPEG) |
| `list_commands` | Search available menu commands |
| `get_results_table` | Export the Results Table to CSV |
| `get_log` | Get recent Fiji log messages |
| `status` | Connection health check |
| `set_event_categories` | Configure which events are captured |
| `get_recent_actions` | Read the action event log |
| `export_actions_as_macro` | Convert recorded actions to an IJ macro |

## Requirements

- [Fiji](https://fiji.sc/) (any version with Java 8+)
- Python 3.11+
- [uv](https://docs.astral.sh/uv/) (Python package manager)

## Installation

### 1. Install fiji-mcp

```bash
git clone https://github.com/SteffenPL/fiji-mcp.git
cd fiji-mcp
uv sync
```

### 2. Install the bridge plugin into Fiji

```bash
# Auto-discovers Fiji in standard locations:
uv run fiji-mcp install

# Or specify your Fiji path explicitly:
uv run fiji-mcp install --fiji-home /path/to/Fiji.app
```

This copies the bridge plugin JAR into your Fiji's `plugins/` directory and checks the bundled Java version. No Maven or JDK needed.

### 3. Configure your MCP client

Add fiji-mcp to your MCP client. Set `FIJI_HOME` so the server can auto-launch Fiji when needed (optional if Fiji is in a standard location).

#### Claude Code

```bash
claude mcp add fiji-mcp -- uv run --directory /absolute/path/to/fiji-mcp fiji-mcp
```

Or add a `.mcp.json` file to your project root:

```json
{
  "mcpServers": {
    "fiji-mcp": {
      "command": "uv",
      "args": ["run", "--directory", "/absolute/path/to/fiji-mcp", "fiji-mcp"],
      "env": {
        "FIJI_HOME": "/path/to/Fiji.app"
      }
    }
  }
}
```

#### Claude Desktop

Add to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) or `%APPDATA%\Claude\claude_desktop_config.json` (Windows):

```json
{
  "mcpServers": {
    "fiji-mcp": {
      "command": "uv",
      "args": ["run", "--directory", "/absolute/path/to/fiji-mcp", "fiji-mcp"],
      "env": {
        "FIJI_HOME": "/path/to/Fiji.app"
      }
    }
  }
}
```

#### Cursor / Codex

Use the same `command`, `args`, and `env` as above in your client's MCP config.

## Usage

The first tool call auto-launches Fiji if it's not already running (requires `FIJI_HOME`). You can also start Fiji manually and go to `Plugins > fiji-mcp > Start Bridge`.

Example conversation:

> **You:** Open the sample image "blobs.gif" and apply a Gaussian blur with sigma 2
>
> **Claude:** *(uses `run_ij_macro` with code `open("http://imagej.net/images/blobs.gif"); run("Gaussian Blur...", "sigma=2");`)*

### Configuration

| Variable | Default | Description |
|---|---|---|
| `FIJI_HOME` | *(auto-discover)* | Path to `Fiji.app` — enables auto-launch and better error messages |
| `FIJI_MCP_PORT` | `8765` | WebSocket port (set on both Fiji and MCP server side) |

## Architecture

Two processes connected by WebSocket:

- **fiji-mcp** (Python) — MCP server using [fastmcp](https://github.com/jlowin/fastmcp) 3.x with stdio transport. Connects to Fiji as a WebSocket client. Buffers events in a 10,000-entry ring buffer.
- **fiji-mcp-bridge** (Java) — Fiji plugin. Runs a WebSocket server inside Fiji. Executes macros/scripts, captures user actions via ImageJ listeners, and pushes events to the MCP server.

Image data is file-based only (Fiji saves to temp directory, MCP server returns file paths). No base64 encoding in the protocol.

### Event capture

The plugin captures user and MCP-triggered actions across 7 categories:

| Category | Events | Default |
|---|---|---|
| `command` | command_finished, command_error | enabled |
| `image` | image_opened, image_closed, image_updated, image_saved | enabled |
| `roi` | roi_created, roi_moved, roi_modified, roi_completed, roi_deleted | enabled |
| `log` | log_message | enabled |
| `display` | display_created, display_closed, display_activated | disabled |
| `tool` | tool_changed, color_changed | disabled |
| `overlay` | overlay_updated | disabled |

Every event is tagged with `"source": "user"` or `"source": "mcp"` so the LLM can distinguish what the biologist did from what it triggered itself.

## Development

```bash
# Install dev dependencies
uv sync --dev

# Run Python tests (no Fiji required)
uv run pytest -v

# Run smoke test (requires Fiji with bridge started)
uv run python tests/smoke_test.py

# Build the Java plugin from source (requires Maven + JDK 8+)
cd fiji-plugin && mvn package -q
# Copy to package data:
cp target/fiji-mcp-bridge-0.1.0.jar ../src/fiji_mcp/data/
```

## License

TBD
