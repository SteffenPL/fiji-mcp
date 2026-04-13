#!/bin/sh
# Launch Fiji with the MCP bridge auto-started.
# Uses -eval to run the "Start Bridge" command after the GUI is up.
# Usage: ./launch-fiji-bridge.sh [extra Fiji args...]

dir=$(dirname "$0")
exec "$dir/Fiji/fiji" -eval 'run("Start Bridge");' "$@"
