#!/bin/sh
# Launch Fiji with the MCP bridge auto-started.
# Usage: ./launch-fiji-bridge.sh [extra Fiji args...]

dir=$(dirname "$0")
export FIJI_MCP_AUTOSTART=1
exec "$dir/Fiji/fiji" "$@"
