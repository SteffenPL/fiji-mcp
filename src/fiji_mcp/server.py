"""MCP server bridging LLM agents with a running Fiji instance via WebSocket."""

from fastmcp import FastMCP

from fiji_mcp.action_log import ActionLog
from fiji_mcp.fiji_client import FijiClient

mcp = FastMCP(
    "fiji-mcp",
    instructions="""\
Scripting interface to a running Fiji (ImageJ2) instance via WebSocket.

## Starting Fiji
If the bridge is not running, start it from the project root:
  1. Run `./launch-fiji-bridge.sh` (or `.bat` on Windows) in the background.
  2. Run `./fiji-health.sh` to block until the bridge is ready (no sleep needed).
Once fiji-health exits 0, MCP tools are usable.

## Core workflow
Compose scripts with run_ij_macro (ImageJ macro) or run_script (Python/Groovy).
Images stay in Fiji; use save_image only when the caller needs a file on disk.
All I/O is file-path-based — no base64 in the protocol.

## Visual feedback
Use get_thumbnail frequently to see what you are working with.
Call it after opening images, after processing steps, and before reporting
results. It returns a display-ready PNG with the current LUT and overlays
baked in — one call replaces duplicate/resize/enhance/save/close.

## Timeouts
Default hard ceiling is 600 s. For known-fast calls, lower it.
For long operations, pass soft_timeout_seconds to get an execution_id,
then poll with wait_for_execution or cancel with kill_execution.
""",
)

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


_DEFAULT_HARD_TIMEOUT_SECONDS = 600
_TIMEOUT_BUFFER_SECONDS = 10


def _python_timeout(hard_timeout_seconds: int) -> float:
    """Python-side wait must outlast the bridge-side hard timeout."""
    return float(hard_timeout_seconds + _TIMEOUT_BUFFER_SECONDS)


@mcp.tool
async def run_ij_macro(
    code: str,
    args: str | None = None,
    soft_timeout_seconds: int | None = None,
    hard_timeout_seconds: int = _DEFAULT_HARD_TIMEOUT_SECONDS,
) -> dict:
    """Run an ImageJ1 macro in Fiji.

    Returns the unified execution envelope. By default the call blocks until
    the macro completes or the 600-second hard ceiling auto-kills it. Pass
    soft_timeout_seconds to opt into the long-poll path: the call returns a
    "running" envelope after that many seconds with an execution_id you can
    pass to wait_for_execution or kill_execution. Pass a larger
    hard_timeout_seconds for known-long operations.
    """
    client = await _get_client()
    params: dict = {"code": code, "hard_timeout_seconds": hard_timeout_seconds}
    if args is not None:
        params["args"] = args
    if soft_timeout_seconds is not None:
        params["soft_timeout_seconds"] = soft_timeout_seconds
    return await client.send_request(
        "run_ij_macro", params, timeout=_python_timeout(hard_timeout_seconds)
    )


@mcp.tool
async def run_script(
    language: str,
    code: str,
    args: str | None = None,
    soft_timeout_seconds: int | None = None,
    hard_timeout_seconds: int = _DEFAULT_HARD_TIMEOUT_SECONDS,
) -> dict:
    """Run a script in the given scripting language in Fiji.

    Returns the unified execution envelope. See run_ij_macro for the lifecycle.
    """
    client = await _get_client()
    params: dict = {
        "language": language,
        "code": code,
        "hard_timeout_seconds": hard_timeout_seconds,
    }
    if args is not None:
        params["args"] = args
    if soft_timeout_seconds is not None:
        params["soft_timeout_seconds"] = soft_timeout_seconds
    return await client.send_request(
        "run_script", params, timeout=_python_timeout(hard_timeout_seconds)
    )


@mcp.tool
async def run_command(
    command: str,
    args: str | None = None,
    soft_timeout_seconds: int | None = None,
    hard_timeout_seconds: int = _DEFAULT_HARD_TIMEOUT_SECONDS,
) -> dict:
    """Run a named Fiji/ImageJ command, optionally with arguments.

    Returns the unified execution envelope. value is always null because
    IJ.run has no return path.
    """
    client = await _get_client()
    params: dict = {
        "command": command,
        "hard_timeout_seconds": hard_timeout_seconds,
    }
    if args is not None:
        params["args"] = args
    if soft_timeout_seconds is not None:
        params["soft_timeout_seconds"] = soft_timeout_seconds
    return await client.send_request(
        "run_command", params, timeout=_python_timeout(hard_timeout_seconds)
    )


@mcp.tool
async def wait_for_execution(
    execution_id: str,
    soft_timeout_seconds: int | None = None,
) -> dict:
    """Wait for a previously-started execution to complete.

    Use this when run_ij_macro / run_script / run_command returned a "running"
    envelope and you want to keep waiting. Returns either a completed envelope
    or another running envelope (call again to keep waiting). With
    soft_timeout_seconds == None (default), waits until completion or until the
    original execution's hard timeout fires. The hard timeout was set on the
    original call and continues to apply.
    """
    client = await _get_client()
    params: dict = {"execution_id": execution_id}
    if soft_timeout_seconds is not None:
        params["soft_timeout_seconds"] = soft_timeout_seconds
    # If the caller set a soft timeout, the bridge responds within that window
    # (either completed or running). Otherwise we don't know the original hard
    # limit, so default to 3600s (covers the common 600s hard default with
    # margin). For longer waits, the LLM should chunk via soft_timeout_seconds.
    py_wait = soft_timeout_seconds if soft_timeout_seconds is not None else 3600
    return await client.send_request(
        "wait_for_execution", params, timeout=_python_timeout(py_wait)
    )


@mcp.tool
async def kill_execution(execution_id: str | None = None) -> dict:
    """Kill a running execution.

    If execution_id is given, kills that specific execution. If omitted, kills
    whatever is currently active in the single execution slot — useful for
    unsticking a hung lock when no id is known.
    """
    client = await _get_client()
    params: dict = {}
    if execution_id is not None:
        params["execution_id"] = execution_id
    return await client.send_request("kill_execution", params)


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
async def get_thumbnail(
    title: str | None = None,
    image_id: int | None = None,
    max_size: int = 800,
    apply_lut: bool = True,
) -> dict:
    """Get a PNG thumbnail of an image with current display settings applied.

    Returns a file path to a scaled-down PNG snapshot. By default the current
    LUT, brightness/contrast, overlays, and ROI outlines are baked in
    (apply_lut=True), so the thumbnail matches what the user sees in Fiji."""
    client = await _get_client()
    params: dict = {"max_size": max_size, "apply_lut": apply_lut}
    if title is not None:
        params["title"] = title
    if image_id is not None:
        params["id"] = image_id
    return await client.send_request("get_thumbnail", params)


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
