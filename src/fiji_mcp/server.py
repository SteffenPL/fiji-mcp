"""MCP server bridging LLM agents with a running Fiji instance via WebSocket."""

from __future__ import annotations

import asyncio
import subprocess
import sys

from fastmcp import FastMCP

from fiji_mcp.action_log import ActionLog
from fiji_mcp.fiji_client import FijiClient
from fiji_mcp.fiji_home import FijiNotFoundError, resolve_fiji_home
from fiji_mcp.install import JAR_NAME

mcp = FastMCP(
    "fiji-mcp",
    instructions="""\
Scripting interface to a running Fiji (ImageJ2) instance — a bioimage
analysis platform used for microscopy and scientific imaging. The bridge
auto-launches Fiji on the first tool call; call status to check connectivity.

## Core workflow
Compose scripts with run_ij_macro (ImageJ macro) or run_script (Python/Groovy).
Images stay in Fiji; use save_image only when the caller needs a file on disk.
Use get_thumbnail regularly to see what you are working with.
Use list_commands to discover available ImageJ commands by keyword.

## Execution envelope
run_ij_macro, run_script, and run_command all return:
  {status, stdout, stderr, value, error, duration_ms, execution_id,
   active_image, dismissed_dialogs, results_snapshot}
- status: "completed" or "running" (long-poll via soft_timeout_seconds)
- value: script return value as string, or null (always null for run_command)
- error: {message, type, line?} or null — when set, check stderr and
  dismissed_dialogs to diagnose
- execution_id: set when status="running", use with wait_for_execution
- dismissed_dialogs: [{title, text, when_ms}] — modal dialogs auto-closed
  during execution; may explain missing side-effects
- results_snapshot: auto-embedded when the Results table changes —
  {total_rows, columns, data}. Saves a separate get_results_table call.

## Verify your work
Every execution returns feedback — use it. Read stdout, stderr, and
dismissed_dialogs after each step; don't assume success from a lack of
error. Use get_thumbnail to visually verify processing results — a
threshold that runs without error can still be wrong. Check
results_snapshot or get_results_table after measurements to confirm the
numbers make sense (e.g. plausible cell counts, reasonable area ranges).
When results look off, investigate before continuing.

## Working with images
When encountering a new image, first identify what it is: use get_thumbnail
and get_image_info to understand the content, modality, and dimensions.
If the image type or context is unclear, ask the user before processing.
At intermediate steps, share thumbnails and results with the user and ask
for feedback — e.g. whether a threshold looks right or parameters need
tuning. Do not run a full pipeline silently.

## Example workflows
- **Measure**: open → inspect image → threshold → verify with thumbnail →
  Analyze Particles → check results_snapshot → get_thumbnail for overlay
- **Batch**: open → process → save_image → close, repeat
- **Segment**: open → inspect → preprocess → Analyze Particles with "add" →
  get_thumbnail (ROIs baked in)
""",
)

_client: FijiClient | None = None
_action_log = ActionLog()


_STARTUP_POLL_TIMEOUT = 30  # seconds to wait for Fiji to start


async def _get_client() -> FijiClient:
    """Lazy-connect to Fiji; auto-launch if FIJI_HOME is available."""
    global _client
    if _client is not None and _client.connected:
        return _client

    _client = FijiClient()
    _client.on_event(_action_log.append)

    # First try: maybe the bridge is already running.
    try:
        await _client.connect()
        return _client
    except ConnectionError:
        pass

    # Bridge not running — try to diagnose and auto-launch.
    try:
        info = resolve_fiji_home()
    except FijiNotFoundError:
        raise ConnectionError(
            "Fiji bridge is not running and no Fiji installation was found.\n"
            "Run: uv run fiji-mcp install --fiji-home /path/to/Fiji.app"
        )

    # Check plugin is installed
    if not (info.plugins_dir / JAR_NAME).exists():
        raise ConnectionError(
            f"Fiji found at {info.path} but the bridge plugin is not installed. "
            "Run: uv run fiji-mcp install"
        )

    # Auto-launch Fiji with bridge
    print(f"[fiji-mcp] Launching Fiji from {info.launcher} ...", file=sys.stderr)
    try:
        subprocess.Popen(
            [str(info.launcher), "-eval", 'run("Start Bridge");'],
            start_new_session=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
    except OSError as exc:
        raise ConnectionError(
            f"Failed to launch Fiji at {info.launcher}: {exc}"
        ) from exc

    # Poll for bridge readiness
    for i in range(_STARTUP_POLL_TIMEOUT):
        await asyncio.sleep(1)
        try:
            await _client.connect()
            print("[fiji-mcp] Bridge is ready.", file=sys.stderr)
            return _client
        except ConnectionError:
            pass

    raise ConnectionError(
        f"Fiji was launched but the bridge did not become ready within "
        f"{_STARTUP_POLL_TIMEOUT}s. Check that the plugin loaded correctly "
        f"in Fiji's Plugins menu."
    )


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

    Returns the execution envelope (see server instructions for shape).
    Blocks until done or the hard ceiling (default 600 s) fires.
    Pass soft_timeout_seconds to get a "running" envelope early with an
    execution_id for wait_for_execution / kill_execution.
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

    Returns the execution envelope. See run_ij_macro for timeout lifecycle.
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

    Returns the execution envelope. value is always null (IJ.run has no
    return path).
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

    Returns a completed or still-running execution envelope. Call again
    if still running. The original hard timeout continues to apply.
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

    If execution_id is omitted, kills whatever is currently active — useful
    for unsticking a hung lock when no id is known.

    Returns the execution envelope of the killed execution.
    """
    client = await _get_client()
    params: dict = {}
    if execution_id is not None:
        params["execution_id"] = execution_id
    return await client.send_request("kill_execution", params)


@mcp.tool
async def list_images() -> dict:
    """List all currently open images in Fiji.

    Returns: {images: [{id, title, width, height}]}
    """
    client = await _get_client()
    return await client.send_request("list_images")


@mcp.tool
async def get_image_info(title: str | None = None, image_id: int | None = None) -> dict:
    """Get detailed information about an image, identified by title or id.

    Returns: {title, width, height, depth, channels, frames, type, path?}
    """
    client = await _get_client()
    params: dict = {}
    if title is not None:
        params["title"] = title
    if image_id is not None:
        params["id"] = image_id
    return await client.send_request("get_image_info", params)


@mcp.tool
async def save_image(title: str, format: str = "tiff", path: str | None = None) -> dict:
    """Save an open image to disk.

    Returns: {path, format}
    """
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

    LUT, brightness/contrast, overlays, and ROI outlines are baked in by
    default (apply_lut=True), so the thumbnail matches what the user sees.

    Returns: {path, width, height}
    """
    client = await _get_client()
    params: dict = {"max_size": max_size, "apply_lut": apply_lut}
    if title is not None:
        params["title"] = title
    if image_id is not None:
        params["id"] = image_id
    return await client.send_request("get_thumbnail", params)


@mcp.tool
async def list_commands(pattern: str) -> dict:
    """List available Fiji commands matching a pattern (max 100 results).

    Returns: {commands: [{name, class}], count}
    """
    client = await _get_client()
    return await client.send_request("list_commands", {"pattern": pattern})


@mcp.tool
async def get_results_table(path: str | None = None) -> dict:
    """Get the current Results table as a CSV file.

    Returns: {path, rows} or {rows: 0, message} if empty.
    """
    client = await _get_client()
    if path is not None:
        return await client.send_request("get_results_table", {"path": path})
    return await client.send_request("get_results_table")


@mcp.tool
async def get_roi_manager() -> dict:
    """Get the current ROI Manager contents.

    Returns: {count, rois: [{index, name, type, bounds: {x, y, width, height}}]}
    """
    client = await _get_client()
    return await client.send_request("get_roi_manager")


@mcp.tool
async def get_log(count: int = 50) -> dict:
    """Get recent entries from the Fiji log.

    Returns: {lines: [str], total}
    """
    client = await _get_client()
    return await client.send_request("get_log", {"count": count})


@mcp.tool
async def status() -> dict:
    """Get Fiji bridge status.

    Returns: {connected, fiji_version, uptime_s, action_count, enabled_categories}
    """
    client = await _get_client()
    result = await client.send_request("status")
    return {**result, "action_count": _action_log.count}


@mcp.tool
async def set_event_categories(categories: list[str]) -> dict:
    """Set which event categories Fiji should emit.

    Returns: {categories: [str]}
    """
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

    Use source='user' to see only what the biologist did manually.

    Returns: {actions: [event], total, returned}
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

    Indices are 0-based; negative counts from end. Only command_finished
    events produce macro lines.

    Returns: {macro: str, event_count}
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
