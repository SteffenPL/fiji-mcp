"""``fiji-mcp install`` — copy the bridge plugin JAR into a Fiji installation."""

from __future__ import annotations

import argparse
import shutil
import sys
from importlib import resources
from pathlib import Path

from fiji_mcp.fiji_home import FijiInfo, FijiNotFoundError, resolve_fiji_home, save_fiji_path

JAR_NAME = "fiji-mcp-bridge-0.1.0.jar"


def run(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(
        prog="fiji-mcp install",
        description="Install the fiji-mcp bridge plugin into a Fiji installation.",
    )
    parser.add_argument(
        "--fiji-home",
        help="Path to Fiji.app (overrides FIJI_HOME env and auto-discovery)",
    )
    args = parser.parse_args(argv)

    # 1. Resolve Fiji
    try:
        info: FijiInfo = resolve_fiji_home(explicit=args.fiji_home)
    except FijiNotFoundError as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"Fiji found at: {info.path}")

    # 2. Java version check
    if info.java_version:
        print(f"Bundled Java version: {info.java_version}")
        major = _java_major(info.java_version)
        if major is not None and major < 8:
            print(
                f"Warning: The bridge plugin requires Java 8+, "
                f"but Fiji bundles Java {info.java_version}.",
                file=sys.stderr,
            )
    else:
        print("Could not detect bundled Java version (not fatal).")

    # 3. Copy JAR
    jar_source = _bundled_jar()
    if jar_source is None:
        print(
            f"Error: bundled JAR '{JAR_NAME}' not found in package data.",
            file=sys.stderr,
        )
        sys.exit(1)

    dest = info.plugins_dir / JAR_NAME
    if dest.exists():
        print(f"Replacing existing plugin: {dest}")
    else:
        print(f"Installing plugin to: {dest}")

    shutil.copy2(str(jar_source), str(dest))

    # 4. Save resolved Fiji path for runtime
    save_fiji_path(info.path)
    print(f"Fiji path saved to .fiji-path (used at runtime)")
    print("Done.")


def _bundled_jar() -> Path | None:
    """Locate the pre-built JAR bundled as package data."""
    ref = resources.files("fiji_mcp") / "data" / JAR_NAME
    # resources.files may return a Traversable; as_posix works for real files
    try:
        path = Path(str(ref))
        if path.is_file():
            return path
    except Exception:
        pass
    return None


def _java_major(version_string: str) -> int | None:
    """Extract the major version number from a Java version string."""
    # "1.8.0_392" → 8, "21.0.7" → 21
    parts = version_string.split(".")
    try:
        first = int(parts[0])
        if first == 1 and len(parts) > 1:
            return int(parts[1])
        return first
    except (ValueError, IndexError):
        return None
