"""Session trace logger — captures window snapshots and events after each tool call.

When ``FIJI_MCP_SAVE_TRACE`` points to a directory, a timestamped session
sub-directory is created on startup. After every execution tool call
(run_ij_macro / run_script / run_command) a step record is written, and
a PNG thumbnail + JSON metadata sidecar is written for every image window
whose rendered content has changed since the last step. Unchanged images
contribute a pointer entry referencing their previous snapshot. All bridge
events are streamed to ``events.jsonl`` in real time.

The trace carries a ``trace_uuid`` (surfaced via the ``status`` MCP tool),
and a ``link_session`` MCP tool lets the agent record a back-reference to
the Claude Code session JSONL — giving downstream tooling two independent
ways to pair a trace directory with its session transcript.

Timestamps use ISO 8601 UTC with millisecond precision and a ``Z`` suffix
(e.g. ``2026-04-16T05:08:57.614Z``) so they align byte-for-byte with entries
in ``~/.claude/projects/<encoded-path>/<session-id>.jsonl``.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from fiji_mcp.fiji_client import FijiClient


TRACE_SCHEMA_VERSION = 3


def _iso_utc_ms() -> str:
    """Return now() as ISO 8601 UTC with ms precision and ``Z`` suffix."""
    return (
        datetime.now(timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )


def _epoch_to_iso_ms(ts: float) -> str:
    """Convert seconds-since-epoch to the same ISO format used by _iso_utc_ms."""
    return (
        datetime.fromtimestamp(ts, tz=timezone.utc)
        .isoformat(timespec="milliseconds")
        .replace("+00:00", "Z")
    )


class TraceLogger:
    """Writes a per-session trace: events.jsonl + per-step window snapshots."""

    def __init__(self, base_dir: str | Path):
        base = Path(base_dir).resolve()
        ts = datetime.now().strftime("%Y-%m-%dT%H-%M-%S")
        session_dir = base / ts
        # Ensure uniqueness if a session already started in the same second.
        if session_dir.exists():
            counter = 1
            while (base / f"{ts}_{counter}").exists():
                counter += 1
            session_dir = base / f"{ts}_{counter}"
        session_dir.mkdir(parents=True)

        self._dir = session_dir
        self._events_path = self._dir / "events.jsonl"
        self._session_path = self._dir / "session.json"
        self._step = 0
        self._uuid = uuid.uuid4().hex

        # Per-title state carried across steps.
        self._image_hashes: dict[str, str] = {}
        self._image_snapshots: dict[str, str] = {}
        self._prev_titles: set[str] = set()

        self._session_data: dict = {
            "version": TRACE_SCHEMA_VERSION,
            "trace_uuid": self._uuid,
            "start_time": _iso_utc_ms(),
            "trace_dir": str(self._dir),
        }
        self._write_session()

        print(
            f"[fiji-mcp] Trace logging to {self._dir} (uuid={self._uuid})",
            file=sys.stderr,
        )

    @property
    def trace_dir(self) -> Path:
        return self._dir

    @property
    def trace_uuid(self) -> str:
        return self._uuid

    # ── external linkage ─────────────────────────────────────────────

    def link_session(
        self,
        session_path: str | None = None,
        session_id: str | None = None,
        label: str | None = None,
    ) -> dict:
        """Record a back-reference to a Claude Code session log.

        Appended to ``session.json`` under ``links`` so multiple calls
        (e.g. speculative + confirmed) are preserved in order.
        """
        link: dict = {"linked_at": _iso_utc_ms()}
        if session_path is not None:
            link["session_path"] = session_path
        if session_id is not None:
            link["session_id"] = session_id
        if label is not None:
            link["label"] = label
        self._session_data.setdefault("links", []).append(link)
        self._write_session()
        return link

    # ── agent annotations ────────────────────────────────────────────

    def add_annotation(
        self,
        message: str,
        label: str | None = None,
    ) -> dict:
        """Append an agent-authored note to ``events.jsonl``.

        Appears inline in the trace timeline with ``type="annotation"`` so
        downstream tools can distinguish agent commentary from Fiji events
        (``type="event"``). Useful for marking phase transitions, surfacing
        hypotheses, or flagging results the agent wants reviewers to notice.
        """
        record: dict = {
            "type": "annotation",
            "source": "agent",
            "message": message,
            "timestamp": _iso_utc_ms(),
        }
        if label is not None:
            record["label"] = label
        with open(self._events_path, "a") as f:
            f.write(json.dumps(record) + "\n")
        return record

    # ── event stream (synchronous — called from the WS receive loop) ──

    def log_event(self, event: dict) -> None:
        """Append one event to ``events.jsonl``.

        The Java side emits ``timestamp`` as epoch seconds (int); we translate
        it to an ISO-UTC string so event timestamps share the same format as
        step records and Claude Code session entries. The original numeric
        value is preserved as ``timestamp_epoch`` for downstream consumers
        that want a sortable numeric key without reparsing the ISO string.
        """
        out = dict(event)
        ts = out.get("timestamp")
        if isinstance(ts, (int, float)):
            out["timestamp_epoch"] = ts
            out["timestamp"] = _epoch_to_iso_ms(ts)
        with open(self._events_path, "a") as f:
            f.write(json.dumps(out) + "\n")

    # ── per-step snapshots (async — called after execution tools) ─────

    async def log_step(
        self,
        action: str,
        params: dict,
        result: dict,
        client: FijiClient,
    ) -> None:
        """Record one execution step with call metadata and window snapshots."""
        self._step += 1
        prefix = f"step_{self._step:03d}"

        snapshots = await _snapshot_windows(
            client,
            self._dir,
            prefix,
            self._image_hashes,
            self._image_snapshots,
        )

        current_titles = {s["title"] for s in snapshots}
        opened = sorted(current_titles - self._prev_titles)
        closed = sorted(self._prev_titles - current_titles)
        self._prev_titles = current_titles

        call_record = {
            "step": self._step,
            "action": action,
            "params": params,
            "result": result,
            "timestamp": _iso_utc_ms(),
            "images": snapshots,
            "images_opened": opened,
            "images_closed": closed,
        }
        (self._dir / f"{prefix}_call.json").write_text(
            json.dumps(call_record, indent=2),
        )

    # ── internals ────────────────────────────────────────────────────

    def _write_session(self) -> None:
        self._session_path.write_text(json.dumps(self._session_data, indent=2))


# ── helpers ───────────────────────────────────────────────────────────


async def _snapshot_windows(
    client: FijiClient,
    trace_dir: Path,
    prefix: str,
    hashes: dict[str, str],
    snapshots_ref: dict[str, str],
    max_size: int = 1200,
) -> list[dict]:
    """Capture a thumbnail + metadata sidecar for every changed image window.

    Unchanged images reuse their previous snapshot and contribute a pointer
    entry only. Returns a list of ``{title, snapshot, changed}`` dicts in
    the same order as Fiji reports open images.
    """
    try:
        listing = await client.send_request("list_images")
    except Exception:
        return []

    results: list[dict] = []
    for img in listing.get("images", []):
        title = img["title"]
        safe = _safe_title(title)
        try:
            thumb = await client.send_request(
                "get_thumbnail",
                {"title": title, "max_size": max_size, "apply_lut": True},
            )
            src = Path(thumb["path"])
            if not src.exists():
                continue

            new_hash = _file_hash(src)
            changed = new_hash != hashes.get(title)

            if changed:
                dst_name = f"{prefix}_{safe}.png"
                shutil.copy2(src, trace_dir / dst_name)

                info = await client.send_request(
                    "get_image_info", {"title": title},
                )
                meta = await _get_window_metadata(client, title)
                meta.update(info)
                meta["thumbnail"] = dst_name
                meta["thumbnail_width"] = thumb.get("width")
                meta["thumbnail_height"] = thumb.get("height")
                (trace_dir / f"{prefix}_{safe}.json").write_text(
                    json.dumps(meta, indent=2),
                )

                hashes[title] = new_hash
                snapshots_ref[title] = dst_name

            results.append({
                "title": title,
                "snapshot": snapshots_ref[title],
                "changed": changed,
            })
        except Exception as exc:
            print(
                f"[fiji-mcp] Trace snapshot failed for {title}: {exc}",
                file=sys.stderr,
            )
    return results


async def _get_window_metadata(client: FijiClient, title: str) -> dict:
    """Retrieve the image subtitle, calibration, and window geometry."""
    meta: dict[str, str] = {}
    try:
        escaped = title.replace("\\", "\\\\").replace('"', '\\"')
        result = await client.send_request("run_ij_macro", {"code": (
            f'selectImage("{escaped}");\n'
            'print("subtitle:" + getInfo("image.subtitle"));\n'
            'print("bit_depth:" + bitDepth());\n'
            "getPixelSize(unit, pw, ph);\n"
            'print("pixel_unit:" + unit);\n'
            'print("pixel_width:" + pw);\n'
            'print("pixel_height:" + ph);\n'
            'print("zoom:" + getZoom() * 100);\n'
            "getLocationAndSize(x, y, w, h);\n"
            'print("win_x:" + x);\n'
            'print("win_y:" + y);\n'
            'print("win_w:" + w);\n'
            'print("win_h:" + h);\n'
        )})
        for line in result.get("stdout", "").strip().split("\n"):
            if ":" in line:
                key, _, val = line.partition(":")
                meta[key.strip()] = val.strip()
    except Exception:
        pass
    return meta


def _safe_title(title: str) -> str:
    """Sanitize a window title for use as a filename component."""
    safe = title.replace("/", "_").replace(" ", "_").replace("\\", "_")
    # Drop file extension
    if "." in safe:
        safe = safe.rsplit(".", 1)[0]
    return safe[:80]


def _file_hash(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()
