"""MCP server bridging LLM agents with a running Fiji instance via WebSocket."""

from fastmcp import FastMCP

from fiji_mcp.action_log import ActionLog
from fiji_mcp.fiji_client import FijiClient

mcp = FastMCP("fiji-mcp")

_client: FijiClient | None = None
_action_log = ActionLog()


async def _get_client() -> FijiClient:
    """Lazy-connect to Fiji; register the ActionLog as the event callback."""
    global _client
    if _client is None or not _client.connected:
        try:
            _client = FijiClient()
            _client.on_event(_action_log.append)
            await _client.connect()
        except ConnectionError:
            raise
    return _client


@mcp.tool
async def run_ij_macro(code: str, args: str | None = None) -> dict:
    """Run an ImageJ1 macro in Fiji."""
    client = await _get_client()
    params: dict = {"code": code}
    if args is not None:
        params["args"] = args
    return await client.send_request("run_ij_macro", params)


@mcp.tool
async def run_script(language: str, code: str, args: str | None = None) -> dict:
    """Run a script in the given scripting language in Fiji."""
    client = await _get_client()
    params: dict = {"language": language, "code": code}
    if args is not None:
        params["args"] = args
    return await client.send_request("run_script", params)


@mcp.tool
async def run_command(command: str, args: str | None = None) -> dict:
    """Run a named Fiji/ImageJ command, optionally with arguments."""
    client = await _get_client()
    params: dict = {"command": command}
    if args is not None:
        params["args"] = args
    return await client.send_request("run_command", params)


@mcp.tool
async def list_images() -> dict:
    """List all currently open images in Fiji."""
    client = await _get_client()
    return await client.send_request("list_images")


@mcp.tool
async def get_image_info(title: str | None = None, image_id: int | None = None) -> dict:
    """Get detailed information about an image, identified by title or id."""
    client = await _get_client()
    params: dict = {}
    if title is not None:
        params["title"] = title
    if image_id is not None:
        params["id"] = image_id
    return await client.send_request("get_image_info", params)


@mcp.tool
async def save_image(title: str, format: str = "tiff", path: str | None = None) -> dict:
    """Save an open image to disk."""
    client = await _get_client()
    params: dict = {"title": title, "format": format}
    if path is not None:
        params["path"] = path
    return await client.send_request("save_image", params)


@mcp.tool
async def list_commands(pattern: str) -> dict:
    """List available Fiji commands matching a pattern."""
    client = await _get_client()
    return await client.send_request("list_commands", {"pattern": pattern})


@mcp.tool
async def get_results_table(path: str | None = None) -> dict:
    """Get the current Results table, optionally saving it to a CSV path."""
    client = await _get_client()
    if path is not None:
        return await client.send_request("get_results_table", {"path": path})
    return await client.send_request("get_results_table")


@mcp.tool
async def get_log(count: int = 50) -> dict:
    """Get recent entries from the Fiji log."""
    client = await _get_client()
    return await client.send_request("get_log", {"count": count})


@mcp.tool
async def status() -> dict:
    """Get Fiji status, including version, uptime, and action count."""
    client = await _get_client()
    result = await client.send_request("status")
    return {**result, "action_count": _action_log.count}


@mcp.tool
async def set_event_categories(categories: list[str]) -> dict:
    """Set which event categories Fiji should emit."""
    client = await _get_client()
    return await client.send_request("set_event_categories", {"categories": categories})


# ── Local tools (handled by Python server) ──────────────────────────


@mcp.tool
async def get_recent_actions(
    count: int = 20,
    offset: int = 0,
    source: str | None = None,
    categories: list[str] | None = None,
) -> dict:
    """Read recent actions from the event log.

    Actions include user interactions and MCP-triggered commands.
    Use source='user' to see only what the biologist did.
    """
    events, total = _action_log.get_recent(
        count=count, offset=offset, source=source, categories=categories
    )
    return {"actions": events, "total": total, "returned": len(events)}


@mcp.tool
async def export_actions_as_macro(
    start: int,
    end: int,
    source: str | None = None,
    categories: list[str] | None = None,
) -> dict:
    """Export a slice of the action log as a runnable ImageJ macro.

    Indices are global (0-based). Negative indices count from the end.
    Only command_finished events produce macro lines.
    """
    events, _ = _action_log.get_range(
        start=start, end=end, source=source, categories=categories
    )
    macro = _events_to_macro(events)
    return {"macro": macro, "event_count": len(events)}


def _events_to_macro(events: list[dict]) -> str:
    """Convert command events to IJ macro code."""
    lines = ["// Auto-generated from fiji-mcp action log"]
    for event in events:
        if event.get("event") != "command_finished":
            continue
        data = event.get("data", {})
        cmd = data.get("command", "")
        args = data.get("args", "")
        if args:
            lines.append(f'run("{cmd}", "{args}");')
        else:
            lines.append(f'run("{cmd}");')
    if len(lines) == 1:
        lines.append("// No command events in the selected range")
    return "\n".join(lines)


def main():
    mcp.run()


if __name__ == "__main__":
    main()
