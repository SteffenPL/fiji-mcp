"""Entry point router for ``fiji-mcp`` CLI.

    fiji-mcp                                → start MCP server (default)
    fiji-mcp --fiji-home /path/...          → start MCP server, override saved Fiji path
    fiji-mcp --save-trace /path/to/traces   → enable session tracing
    fiji-mcp install ...                    → install bridge plugin into Fiji
"""

from __future__ import annotations

import os
import sys


def _parse_opt(args: list[str], name: str) -> str | None:
    """Extract ``--name VALUE`` or ``--name=VALUE`` from *args*."""
    for i, arg in enumerate(args):
        if arg == name and i + 1 < len(args):
            return args[i + 1]
        if arg.startswith(f"{name}="):
            return arg.split("=", 1)[1]
    return None


def main() -> None:
    if len(sys.argv) > 1 and sys.argv[1] == "install":
        from fiji_mcp.install import run
        run(sys.argv[2:])
    else:
        args = sys.argv[1:]

        fiji_home = _parse_opt(args, "--fiji-home")
        if fiji_home:
            from fiji_mcp.fiji_home import save_fiji_path
            from pathlib import Path
            save_fiji_path(Path(fiji_home).expanduser().resolve())

        save_trace = _parse_opt(args, "--save-trace")
        if save_trace:
            os.environ["FIJI_MCP_SAVE_TRACE"] = save_trace

        from fiji_mcp.server import main as server_main
        server_main()
