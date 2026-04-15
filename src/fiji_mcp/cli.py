"""Entry point router for ``fiji-mcp`` CLI.

    fiji-mcp                         → start MCP server (default)
    fiji-mcp --fiji-home /path/...   → start MCP server, override saved Fiji path
    fiji-mcp install ...             → install bridge plugin into Fiji
"""

from __future__ import annotations

import sys


def main() -> None:
    if len(sys.argv) > 1 and sys.argv[1] == "install":
        from fiji_mcp.install import run
        run(sys.argv[2:])
    else:
        # Parse optional --fiji-home before starting the server.
        fiji_home = None
        args = sys.argv[1:]
        for i, arg in enumerate(args):
            if arg == "--fiji-home" and i + 1 < len(args):
                fiji_home = args[i + 1]
                break
            if arg.startswith("--fiji-home="):
                fiji_home = arg.split("=", 1)[1]
                break

        if fiji_home:
            from fiji_mcp.fiji_home import save_fiji_path
            from pathlib import Path
            save_fiji_path(Path(fiji_home).expanduser().resolve())

        from fiji_mcp.server import main as server_main
        server_main()
