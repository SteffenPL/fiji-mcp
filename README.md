# fiji-mcp

An MCP server that gives LLM agents (Claude Code, Claude Desktop, Cursor, etc.) a live scripting interface into a running [Fiji](https://fiji.sc/) instance via WebSocket.

```
LLM Agent  --(MCP/stdio)-->  Python MCP Server  --(WebSocket)-->  Fiji + Java Plugin
```

## What it does

- **Run macros and scripts** in Fiji from your LLM conversation (`run_ij_macro`, `run_script`, `run_command`)
- **Inspect images** — list open images, get metadata, save to disk
- **Observe user actions** — the plugin captures what the biologist does in Fiji's GUI (commands, image opens/closes, ROI edits) and streams it to the LLM via an event log
- **Export workflows** — convert a recorded action sequence to a runnable ImageJ macro
- **Search commands** — find Fiji menu commands by name

### Available tools

| Tool | Description |
|---|---|
| `run_ij_macro` | Execute ImageJ macro code |
| `run_script` | Execute a script in any supported language (Python, Groovy, JS, ...) |
| `run_command` | Run a Fiji menu command by name |
| `list_images` | List all open images |
| `get_image_info` | Get image metadata (dimensions, type, path) |
| `save_image` | Save an image to disk (TIFF, PNG, JPEG) |
| `list_commands` | Search available menu commands |
| `get_results_table` | Export the Results Table to CSV |
| `get_log` | Get recent Fiji log messages |
| `status` | Connection health check |
| `set_event_categories` | Configure which events are captured |
| `get_recent_actions` | Read the action event log |
| `export_actions_as_macro` | Convert recorded actions to an IJ macro |

## Requirements

- [Fiji](https://fiji.sc/) (tested with 2.16.0)
- Python 3.11+
- [uv](https://docs.astral.sh/uv/) (Python package manager)
- Java 21 (bundled with Fiji)
- Maven (only if building the Java plugin from source)

## Installation

### 1. Install the Fiji plugin

**Option A: Pre-built JAR** (if available in releases)

Download `fiji-mcp-bridge-0.1.0.jar` and copy it to your `Fiji/plugins/` directory.

**Option B: Build from source**

```bash
cd fiji-plugin
JAVA_HOME="/path/to/your/fiji/java/zulu-21.jdk/Contents/Home" mvn package -q
cp target/fiji-mcp-bridge-0.1.0.jar /path/to/your/Fiji/plugins/
```

Restart Fiji after installing.

### 2. Install the Python MCP server

```bash
git clone https://github.com/your-username/fiji-mcp.git
cd fiji-mcp
uv sync
```

### 3. Configure your MCP client

#### Claude Code

Add a `.mcp.json` file to your project root (or copy from `examples/test-env/`):

```json
{
  "mcpServers": {
    "fiji-mcp": {
      "command": "uv",
      "args": ["run", "--directory", "/absolute/path/to/fiji-mcp", "fiji-mcp"],
      "env": { "FIJI_MCP_PORT": "8765" }
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
      "env": { "FIJI_MCP_PORT": "8765" }
    }
  }
}
```

#### Cursor

Add to Cursor's MCP configuration with the same `command`, `args`, and `env` as above.

## Usage

1. **Start Fiji** and go to `Plugins > fiji-mcp > Start Bridge`
2. **Start your MCP client** (Claude Code, Claude Desktop, etc.)
3. The MCP server connects to Fiji automatically on the first tool call

Example conversation:

> **You:** Open the sample image "blobs.gif" and apply a Gaussian blur with sigma 2
>
> **Claude:** *(uses `run_ij_macro` with code `open("http://imagej.net/images/blobs.gif"); run("Gaussian Blur...", "sigma=2");`)*

### Configuration

| Variable | Default | Description |
|---|---|---|
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

# Run tests (54 unit tests, no Fiji required)
uv run pytest -v

# Run smoke test (requires Fiji with bridge started)
uv run python tests/smoke_test.py

# Build the Java plugin
cd fiji-plugin
JAVA_HOME="../Fiji/java/macos-arm64/zulu21.42.19-ca-jdk21.0.7-macosx_aarch64/zulu-21.jdk/Contents/Home" mvn package -q
```

## Related projects

This project was inspired by and builds on ideas from existing bioimage MCP servers:

| Project | Target | Description |
|---|---|---|
| [fiji_mcp](https://github.com/NicoKiaru/fiji_mcp) (NicoKiaru) | Fiji | Proof-of-concept Fiji MCP with dual Java+Python architecture |
| [napari-mcp](https://github.com/royerlab/napari-mcp) (royerlab) | napari | Most mature bioimage MCP server, PyPI published, 16 tools |
| [bioimage-mcp](https://github.com/cqian89/bioimage-mcp) (cqian89) | General | Artifact-based I/O with isolated conda environments |
| [bioimage-mcp-server](https://github.com/Ichoran/bioimage-mcp-server) (Ichoran) | Bio-Formats | Java MCP server for reading 150+ microscopy formats |
| [BioImage-Agent](https://github.com/llnl/bioimage-agent) (LLNL) | napari | Napari plugin exposing viewer over MCP via sockets |
| [cellpose_mcp](https://github.com/surajinacademia/cellpose_mcp) | Cellpose | MCP server for Cellpose segmentation (PyPI published) |

## License

TBD
