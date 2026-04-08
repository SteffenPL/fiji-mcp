# Existing Fiji/ImageJ & Bioimage MCP Servers — Comparison

## Direct Fiji/ImageJ MCP

### fiji_mcp (NicoKiaru)
- **URL**: https://github.com/NicoKiaru/fiji_mcp
- **Approach**: Dual architecture — Java package (`fiji-tools`) inside Fiji + Python MCP wrapper (`fiji_mcp`)
- **Features**: Wraps Fiji functions as MCP tools, integrates with Claude Desktop via `uv`
- **Status**: Proof of concept (~4 stars, last updated March 2026)
- **Limitations**:
  - JAVA_HOME must be hardcoded in `main.py`
  - `fiji-tools` JAR only available as SNAPSHOT (manual build required)
  - No published package; setup is fully manual
  - Limited documentation on available tools
  - Windows-centric examples

---

## Bioimage MCP Servers (non-Fiji)

### napari-mcp (royerlab)
- **URL**: https://github.com/royerlab/napari-mcp
- **Approach**: Python MCP server controlling napari viewer
- **Features**: 16 tools (session mgmt, layers, viewer controls, code execution), one-command setup for Claude Desktop/Code/Cursor, PyPI published
- **Status**: Most mature in the space (~31 stars, active, April 2026)
- **Limitations**: Napari-only, arbitrary code execution risk, localhost-only

### bioimage-mcp (cqian89)
- **URL**: https://github.com/cqian89/bioimage-mcp
- **Approach**: Local-first MCP with artifact-based I/O and isolated conda envs per tool
- **Features**: Artifact-based I/O, session export/replay, filesystem allowlist/denylist, isolated environments (e.g. Cellpose)
- **Status**: Early (~1 star, April 2026)
- **Limitations**: Requires Python 3.13+, no published package, not Fiji-specific

### bioimage-mcp-server (Ichoran)
- **URL**: https://github.com/Ichoran/bioimage-mcp-server
- **Approach**: Java MCP server wrapping Bio-Formats (headless, read-only)
- **Features**: 5 tools (`inspect_image`, `get_thumbnail`, `get_plane`, `get_intensity_stats`, `export_to_tiff`), 150+ microscopy formats, JBang install
- **Status**: Proof of concept (~0 stars, March 2026)
- **Limitations**: Read-only, no image processing, no GUI

### BioImage-Agent (LLNL)
- **URL**: https://github.com/llnl/bioimage-agent
- **Approach**: Napari plugin exposing viewer over MCP via socket server
- **Features**: Works with Claude Desktop + OpenAI, Promptfoo eval framework
- **Status**: Early (~2 stars, March 2026)
- **Limitations**: Napari-only, socket-based (more complex than stdio), install from source

### cellpose_mcp (surajinacademia)
- **URL**: https://github.com/surajinacademia/cellpose_mcp
- **Approach**: MCP server for Cellpose segmentation
- **Features**: 13+ tools (2D/3D segmentation, batch processing, denoising, custom model training), PyPI published
- **Status**: Active (~0 stars, Feb 2026)
- **Limitations**: Cellpose-specific only

---

## Summary & Opportunity

| Project | Target | Language | Maturity | Published |
|---------|--------|----------|----------|-----------|
| fiji_mcp | Fiji | Java+Python | PoC | No |
| napari-mcp | napari | Python | Production-ready | PyPI |
| bioimage-mcp | General | Python | Early | No |
| bioimage-mcp-server | Bio-Formats | Java | PoC | JBang |
| BioImage-Agent | napari | Python | Early | No |
| cellpose_mcp | Cellpose | Python | Active | PyPI |

**Key takeaway**: There is only one Fiji MCP project (NicoKiaru's PoC), and it has significant setup friction. The napari ecosystem is well ahead. Fiji — the most widely used bioimage tool — is underserved in the MCP space. This is the gap we aim to fill with `fiji-mcp`.

**Design lessons from existing projects**:
- napari-mcp: clean auto-configuration and PyPI publishing drives adoption
- bioimage-mcp: artifact-based I/O and isolated environments are good patterns for reproducibility
- bioimage-mcp-server: JBang provides easy Java tool distribution
- fiji_mcp: dual Java+Python architecture is one viable approach, but adds friction
