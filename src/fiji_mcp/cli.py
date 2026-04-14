"""Entry point router for ``fiji-mcp`` CLI.

    fiji-mcp              → start MCP server (default)
    fiji-mcp install ...  → install bridge plugin into Fiji
"""

from __future__ import annotations

import sys


def main() -> None:
    if len(sys.argv) > 1 and sys.argv[1] == "install":
        from fiji_mcp.install import run
        run(sys.argv[2:])
    else:
        from fiji_mcp.server import main as server_main
        server_main()
