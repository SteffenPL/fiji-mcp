"""MCP server bridging LLM agents with a running Fiji instance via WebSocket."""

from __future__ import annotations

import asyncio
import os
import subprocess
import sys
from typing import TYPE_CHECKING

from fastmcp import FastMCP

from fiji_mcp.action_log import ActionLog
from fiji_mcp.fiji_client import FijiClient
from fiji_mcp.fiji_home import FijiNotFoundError, resolve_fiji_home
from fiji_mcp.install import JAR_NAME

if TYPE_CHECKING:
    from fiji_mcp.trace_logger import TraceLogger

mcp = FastMCP(
    "fiji-mcp",
    instructions="""\
Scripting interface to a running Fiji (ImageJ2) instance — a bioimage
analysis platform used for microscopy and scientific imaging. The bridge
auto-launches Fiji on the first tool call; call status to check connectivity.

Compose work with run_ij_macro, run_script (Python/Groovy), or run_command.
Images live in Fiji; save_image only when a file on disk is needed.
list_commands discovers available ImageJ commands by keyword.

## Tips
- Use get_thumbnail and get_image_info early to see what you are working
  with (modality, channels, bit depth) before designing a pipeline.
- For multi-step processing, briefly outline the planned pipeline and let
  the user confirm before running it.
- Web search is fair game for state-of-the-art methods or paper references
  when unsure which approach fits.
- If a plugin would help, get_fiji_info returns the plugins_dir; drop the
  .jar there and restart Fiji.
- The user is likely interacting with Fiji at the same time. When state
  looks unexpected (wrong active image, stray ROI, surprising results),
  get_recent_actions(source="user") shows what they did.

## ImageJ statefulness
ImageJ carries global state that silently affects operations:
- **Selections**: a makeRectangle/makeOval/etc. selection persists on the
  active image and is inherited by Duplicate, Clear, Fill, Measure, and
  many other commands. Call run("Select None") before operations that
  should apply to the full image.
- **Results table**: Analyze Particles and Measure append rows to the
  shared Results table. Call run("Clear Results") before a new
  measurement pass to start fresh.
- **Active image**: commands operate on whichever image is frontmost.
  Because the user may also be clicking around, start scripts with
  selectWindow("title") to lock focus to the intended image.
- **LUT polarity**: get_image_info and get_thumbnail return preview_inverted.
  When true, the display LUT is inverted: low raw pixel values appear bright
  and high raw values appear dark. This is purely a display property — it
  does NOT change the underlying pixel data.
- **BlackBackground option**: setOption("BlackBackground", true/false) is
  global state that affects Convert to Mask, Make Binary, and all binary
  morphology commands (Erode, Dilate, Open, Close-, Watershed, Skeletonize).

## LUT, thresholds, and binary operations
How the inverted LUT interacts with each operation category:

**setThreshold(lo, hi)** — operates on raw pixel values; the LUT has no
effect. setThreshold(128, 255) always selects pixels with raw values
128–255, regardless of how they are displayed.

**setAutoThreshold("method")** — computes a threshold on raw values, BUT
the "dark" keyword and which side of the threshold is selected operate
in DISPLAY space. ImageJ internally checks isInvertedLut() and flips the
selection direction when the LUT is inverted. Decision rule:
  - Objects look BRIGHT, background looks DARK → "method dark"
  - Objects look DARK, background looks BRIGHT  → "method" (no dark)
This works regardless of LUT state — just reason about what you SEE in
the thumbnail.

**Filters** (Gaussian Blur, Median, etc.) — operate on raw pixel values
only; the LUT has no effect.

**Convert to Mask / Make Binary** — always assign raw 255 to the thresholded
region and raw 0 to the rest. The BlackBackground option controls only the
LUT on the output mask:
  - BlackBackground=true  → normal LUT (foreground=white, background=black)
  - BlackBackground=false → inverted LUT (foreground=black, background=white)
The raw pixel values are identical either way.

**Binary morphology** (Erode, Dilate, Open, Close-, Watershed, Skeletonize) —
determines which pixels are "foreground" by combining BlackBackground with
the image's LUT. Effective rule: foreground is raw 255 when
(BlackBackground XOR InvertedLUT) is true. This means an inverted LUT
flips the interpretation of BlackBackground, so pipelines that use
Convert to Mask (which sets the LUT to match BlackBackground) stay
self-consistent. But changing BlackBackground mid-pipeline, or stripping
the LUT (e.g. by saving as plain TIFF and reopening), silently inverts
which pixels are treated as objects.

**Analyze Particles** — on binary images, auto-detects polarity from
edge/corner pixels and is robust to both LUT and BlackBackground settings.
On non-binary (thresholded) images, it uses the threshold range directly.

**run("Invert")** — flips raw pixel values (v → 255−v). Does not touch the
LUT. **run("Invert LUT")** — flips only the display lookup table. Does
not touch raw pixel values. These are independent operations.

## LUT-safe thresholding recipe
1. get_thumbnail → look at the image, note preview_inverted
2. Decide: do your objects of interest look BRIGHT or DARK in the display?
3. setOption("BlackBackground", true)
4. Threshold:
   - Objects look BRIGHT → setAutoThreshold("method dark")
   - Objects look DARK  → setAutoThreshold("method")   (no dark)
   - Or use explicit setThreshold(lo, hi) on raw pixel values
   Common mistake: forgetting "dark" for bright objects (e.g. fluorescence).
   Without "dark", the LOW histogram side is selected — that is the
   dark background, not the bright objects.
5. run("Convert to Mask") — with BB=true, objects will be white (255)
6. get_thumbnail of the mask → verify white regions match your objects.
   If inverted, flip the "dark" keyword and redo from step 4.

## Common pitfalls
- **Segmenting background instead of foreground**: follow the recipe above.
  Most common cause: omitting "dark" for bright objects (e.g. fluorescence).
- **Watershed over-splitting**: binary watershed splits touching objects but
  also creates tiny fragment particles at saddle points. Use a minimum particle
  size filter in Analyze Particles (size=200-Infinity or similar) to discard
  fragments and recover the correct object count.
- **Stale particle counts**: Analyze Particles appends to the shared Results
  table. Without run("Clear Results") beforehand, counts from previous runs
  accumulate and produce inflated totals.

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
- results_snapshot: auto-embedded when the Results table row count changes
  during execution — {total_rows, new_rows, columns, data}. Only rows
  added by the current execution are shown in data[]; null when the table
  did not change.
""",
)

_client: FijiClient | None = None
_action_log = ActionLog()
_trace_logger: TraceLogger | None = None  # set in main() when FIJI_MCP_SAVE_TRACE is configured


_STARTUP_POLL_TIMEOUT = 30  # seconds to wait for Fiji to start


async def _get_client() -> FijiClient:
    """Lazy-connect to Fiji; auto-launch if FIJI_HOME is available."""
    global _client
    if _client is not None and _client.connected:
        return _client

    _client = FijiClient()

    def _on_event(event):
        _action_log.append(event)
        if _trace_logger:
            _trace_logger.log_event(event)

    _client.on_event(_on_event)

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
    for _ in range(_STARTUP_POLL_TIMEOUT):
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


async def _trace_step(
    action: str, params: dict, result: dict, client: FijiClient,
) -> None:
    """Log a completed execution step to the trace (if enabled)."""
    if not _trace_logger:
        return
    if result.get("status") == "running":
        return  # snapshot only after execution finishes
    try:
        await _trace_logger.log_step(action, params, result, client)
    except Exception as exc:
        print(f"[fiji-mcp] Trace error: {exc}", file=sys.stderr)


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
    result = await client.send_request(
        "run_ij_macro", params, timeout=_python_timeout(hard_timeout_seconds)
    )
    await _trace_step("run_ij_macro", params, result, client)
    return result


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
    result = await client.send_request(
        "run_script", params, timeout=_python_timeout(hard_timeout_seconds)
    )
    await _trace_step("run_script", params, result, client)
    return result


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
    result = await client.send_request(
        "run_command", params, timeout=_python_timeout(hard_timeout_seconds)
    )
    await _trace_step("run_command", params, result, client)
    return result


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
    result = await client.send_request(
        "wait_for_execution", params, timeout=_python_timeout(py_wait)
    )
    await _trace_step("wait_for_execution", params, result, client)
    return result


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

    Returns: {images: [{id, title, width, height}], active?: title}
    The ``active`` field names the frontmost image (same value as
    ``active_image`` in execution envelopes); omitted when no image is open.
    """
    client = await _get_client()
    return await client.send_request("list_images")


@mcp.tool
async def get_image_info(title: str | None = None, image_id: int | None = None) -> dict:
    """Get detailed information about an image, identified by title or id.

    Returns: {title, width, height, depth, channels, frames, type, path?,
              preview_inverted}
    preview_inverted: true when the display LUT is inverted — low raw pixel
    values appear bright and high raw values appear dark. This is a display
    property only — but it DOES affect setAutoThreshold's "dark" keyword
    direction. See the "LUT-safe thresholding recipe" in server instructions.
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

    Returns: {path, width, height, preview_inverted}
    preview_inverted: true when the display LUT is inverted — low raw values
    appear bright. This affects setAutoThreshold's "dark" keyword direction.
    See the "LUT-safe thresholding recipe" in server instructions.
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
    """Get recent entries from the Fiji log, with consecutive duplicate lines collapsed.

    Returns: {lines: [str], total}
    """
    client = await _get_client()
    raw = await client.send_request("get_log", {"count": count})
    lines: list[str] = raw.get("lines", [])
    deduped: list[str] = []
    for line in lines:
        if not deduped or line != deduped[-1]:
            deduped.append(line)
    return {"lines": deduped, "total": len(deduped)}


@mcp.tool
async def status() -> dict:
    """Get Fiji bridge status.

    Returns: {connected, fiji_version, uptime_s, action_count,
              enabled_categories, trace?}
    When session tracing is enabled, ``trace`` carries ``{uuid, dir}``
    so the trace UUID is discoverable from the transcript itself
    (handy for pairing traces with Claude Code session logs).
    """
    client = await _get_client()
    result = await client.send_request("status")
    payload = {**result, "action_count": _action_log.count}
    if _trace_logger is not None:
        payload["trace"] = {
            "uuid": _trace_logger.trace_uuid,
            "dir": str(_trace_logger.trace_dir),
        }
    return payload


@mcp.tool
async def link_session(
    session_path: str | None = None,
    session_id: str | None = None,
    label: str | None = None,
) -> dict:
    """Link this trace to a Claude Code session log.

    Writes a back-reference into the trace directory's ``session.json``
    under ``links`` so downstream tooling (e.g. the ai-trace-videos merge
    script) can fuse the trace with the session transcript. Call at the
    end of a session. Provide whichever of ``session_path`` / ``session_id``
    / ``label`` you have — at least one is expected.

    Returns: {status, trace_uuid, trace_dir, linked} or
             {status: "no-trace"} when tracing is disabled.
    """
    if _trace_logger is None:
        return {
            "status": "no-trace",
            "message": "Session tracing is not enabled "
            "(set FIJI_MCP_SAVE_TRACE or pass --save-trace).",
        }
    link = _trace_logger.link_session(
        session_path=session_path,
        session_id=session_id,
        label=label,
    )
    return {
        "status": "ok",
        "trace_uuid": _trace_logger.trace_uuid,
        "trace_dir": str(_trace_logger.trace_dir),
        "linked": link,
    }


@mcp.tool
async def set_event_categories(categories: list[str]) -> dict:
    """Set which event categories Fiji should emit.

    Returns: {categories: [str]}
    """
    client = await _get_client()
    return await client.send_request("set_event_categories", {"categories": categories})


# ── Local tools (handled by Python server) ──────────────────────────


@mcp.tool
async def get_fiji_info() -> dict:
    """Get Fiji installation paths and detected plugin packages.

    To install a plugin, download or copy its .jar file into the plugins_dir
    and restart Fiji.

    When the bridge is already connected, plugin_packages lists Java
    package prefixes grouped from Menus.getCommands() — e.g. inra.ijpb
    (MorphoLibJ), sc.fiji.trackmate, net.haesleinhuepf.clij2. Scan these
    before designing a pipeline: third-party plugins often offer better
    algorithms than the IJ1 stdlib for the same task.

    Returns: {fiji_home, plugins_dir, java_version,
              plugin_packages?: [{prefix, command_count, example_commands}],
              plugin_packages_error?: str}
    plugin_packages is omitted when the bridge is not yet connected;
    any later tool call will start it, then this tool reports the list.
    """
    info = resolve_fiji_home()
    result: dict = {
        "fiji_home": str(info.path),
        "plugins_dir": str(info.plugins_dir),
        "java_version": info.java_version,
    }
    if _client is not None and _client.connected:
        try:
            pkgs = await _client.send_request("list_plugin_packages", timeout=10.0)
            result["plugin_packages"] = pkgs.get("packages", [])
        except Exception as exc:
            result["plugin_packages_error"] = str(exc)
    return result


@mcp.tool
async def get_recent_actions(
    count: int = 20,
    offset: int = 0,
    source: str | None = None,
    categories: list[str] | None = None,
) -> dict:
    """Read recent actions from the event log.

    Each event: {category, event, data, source, timestamp}
    - source: "user" (biologist interacting with Fiji directly) or
      "mcp" (actions triggered by this agent). Use source='user' to see
      only what the biologist did.
    - category: "command", "image", "roi", "tool", "display", "overlay", "log"
    - event: e.g. "command_finished", "image_opened", "roi_added"

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


def _register_trace_only_tools() -> None:
    """Register MCP tools that only make sense when tracing is enabled.

    Called from ``main()`` after ``_trace_logger`` is constructed so these
    tools are advertised to the client only in trace mode, keeping the
    non-trace tool list uncluttered.
    """

    @mcp.tool
    async def add_trace_note(message: str, label: str | None = None) -> dict:
        """Append a free-form note to the active trace timeline.

        Only available when session tracing is enabled (``FIJI_MCP_SAVE_TRACE``
        is set). Use to mark phases ("starting segmentation"), record
        observations ("clumped nuclei — watershed helps"), or flag
        checkpoints the reviewer should notice. The note is written to the
        trace's ``events.jsonl`` with ``type="annotation"`` and a UTC ISO
        timestamp that aligns with Claude Code session timestamps.

        Returns: {status, record}
        """
        assert _trace_logger is not None  # registration is gated on trace mode
        record = _trace_logger.add_annotation(message, label=label)
        return {"status": "ok", "record": record}

    @mcp.tool
    async def set_trace_metadata(key: str, value: str) -> dict:
        """Store a structured key-value fact in the active trace.

        Use for machine-readable data that downstream tools should parse
        reliably — session IDs, parameter choices, metric values, etc.
        Each call writes one ``{"type": "metadata", "data": {key: value}}``
        record to ``events.jsonl``.

        Examples:
            set_trace_metadata("session_uuid", "6dfefadf...")
            set_trace_metadata("threshold_method", "Otsu")
            set_trace_metadata("cell_count", "42")

        Returns: {status, record}
        """
        assert _trace_logger is not None
        record = _trace_logger.add_metadata(key, value)
        return {"status": "ok", "record": record}


def main():
    global _trace_logger
    trace_dir = os.environ.get("FIJI_MCP_SAVE_TRACE")
    if trace_dir:
        from fiji_mcp.trace_logger import TraceLogger
        _trace_logger = TraceLogger(trace_dir)
        _register_trace_only_tools()
    mcp.run()


if __name__ == "__main__":
    main()
