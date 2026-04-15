"""Locate and validate a Fiji installation on the local system."""

from __future__ import annotations

import os
import platform
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

# Saved path file, written by ``fiji-mcp install``, read at runtime.
_SAVED_PATH_FILE = Path(__file__).resolve().parent.parent.parent / ".fiji-path"


@dataclass
class FijiInfo:
    path: Path
    plugins_dir: Path
    launcher: Path
    java_version: str | None  # e.g. "21.0.7", None if detection failed


def resolve_fiji_home(explicit: str | None = None) -> FijiInfo:
    """Resolve and validate a Fiji installation.

    Priority: *explicit* arg  →  saved ``.fiji-path``  →  ``FIJI_HOME`` env
    →  auto-discovery.
    Raises ``FijiNotFoundError`` if nothing works.
    """
    candidates: list[Path] = []

    if explicit:
        candidates.append(Path(explicit))
    saved = load_fiji_path()
    if saved:
        candidates.append(saved)
    env = os.environ.get("FIJI_HOME")
    if env:
        candidates.append(Path(env))
    candidates.extend(_discovery_paths())

    for p in candidates:
        p = p.expanduser().resolve()
        info = _validate(p)
        if info is not None:
            return info

    tried = ", ".join(str(c) for c in candidates[:6])
    raise FijiNotFoundError(
        "Could not find a valid Fiji installation.\n"
        f"  Searched: {tried}\n"
        "  Run: uv run fiji-mcp install --fiji-home /path/to/Fiji.app"
    )


class FijiNotFoundError(Exception):
    pass


def save_fiji_path(path: Path) -> None:
    """Persist a resolved Fiji path to ``.fiji-path`` for future sessions."""
    _SAVED_PATH_FILE.write_text(str(path) + "\n")


def load_fiji_path() -> Path | None:
    """Read the saved Fiji path, or return None if not set."""
    if not _SAVED_PATH_FILE.is_file():
        return None
    text = _SAVED_PATH_FILE.read_text().strip()
    return Path(text) if text else None


# ── internal helpers ──────────────────────────────────────────────


def _discovery_paths() -> list[Path]:
    """Best-effort list of common Fiji locations per OS."""
    home = Path.home()
    system = platform.system()

    if system == "Darwin":
        return [
            Path("/Applications/Fiji.app"),
            home / "Applications" / "Fiji.app",
            home / "Fiji.app",
            home / "Desktop" / "Fiji.app",
            home / "Downloads" / "Fiji.app",
        ]
    elif system == "Linux":
        return [
            home / "Fiji.app",
            Path("/opt/Fiji.app"),
            Path("/usr/local/Fiji.app"),
        ]
    elif system == "Windows":
        return [
            home / "Fiji.app",
            home / "Desktop" / "Fiji.app",
            home / "Downloads" / "Fiji.app",
            Path("C:/Fiji.app"),
        ]
    return []


def _validate(path: Path) -> FijiInfo | None:
    """Return ``FijiInfo`` if *path* looks like a valid Fiji root, else None."""
    if not path.is_dir():
        return None
    plugins = path / "plugins"
    if not plugins.is_dir():
        return None
    launcher = _find_launcher(path)
    if launcher is None:
        return None
    java_ver = _detect_java_version(path)
    return FijiInfo(
        path=path,
        plugins_dir=plugins,
        launcher=launcher,
        java_version=java_ver,
    )


def _find_launcher(fiji_root: Path) -> Path | None:
    """Find the ``fiji`` launcher script/executable in the Fiji root."""
    system = platform.system()

    if system == "Darwin":
        # macOS may have Fiji.app/Contents/MacOS/ImageJ-macosx or the
        # top-level fiji script. Prefer the top-level script.
        for name in ("fiji", "ImageJ-macosx"):
            p = fiji_root / name
            if p.is_file():
                return p
        macos_dir = fiji_root / "Contents" / "MacOS"
        if macos_dir.is_dir():
            for f in macos_dir.iterdir():
                if f.name.startswith("ImageJ"):
                    return f
    elif system == "Windows":
        for name in ("fiji.exe", "ImageJ-win64.exe"):
            p = fiji_root / name
            if p.is_file():
                return p
    else:  # Linux
        for name in ("fiji", "ImageJ-linux64"):
            p = fiji_root / name
            if p.is_file():
                return p
    return None


def _detect_java_version(fiji_root: Path) -> str | None:
    """Best-effort detection of the Java version bundled with Fiji."""
    java_dir = fiji_root / "java"
    if not java_dir.is_dir():
        return None

    # Walk into java/<platform>/<jdk-dir>/... looking for a bin/java binary
    java_bin = _find_java_binary(java_dir)
    if java_bin is None:
        return None

    try:
        result = subprocess.run(
            [str(java_bin), "-version"],
            capture_output=True,
            text=True,
            timeout=10,
        )
        # java -version prints to stderr
        output = result.stderr + result.stdout
        m = re.search(r'"(\d+[\d.]*)', output)
        return m.group(1) if m else None
    except Exception:
        return None


def _find_java_binary(java_dir: Path) -> Path | None:
    """Recursively find a ``java`` binary under the Fiji java/ directory."""
    suffix = ".exe" if platform.system() == "Windows" else ""
    target = "java" + suffix
    for root, _dirs, files in os.walk(str(java_dir)):
        if target in files:
            candidate = Path(root) / target
            if candidate.is_file():
                return candidate
    return None
