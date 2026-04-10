#!/bin/sh
# Launch the local Fiji installation from the project root.
# Usage: ./launch-fiji.sh

dir=$(dirname "$0")
exec "$dir/Fiji/fiji" "$@"
