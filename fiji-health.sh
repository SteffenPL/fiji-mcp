#!/bin/sh
# Wait for the fiji-mcp bridge to become ready.
# Usage: ./fiji-health.sh [timeout_seconds]
# Exit 0 = bridge ready, Exit 1 = timeout

PORT="${FIJI_MCP_PORT:-8765}"
TIMEOUT="${1:-30}"
INTERVAL=1
ELAPSED=0

status_check() {
    python3 -c "
import asyncio, json, sys
import websockets
async def check():
    try:
        async with websockets.connect('ws://localhost:${PORT}', open_timeout=2) as ws:
            await ws.send(json.dumps({'id':'health','action':'status','params':{}}))
            resp = json.loads(await asyncio.wait_for(ws.recv(), timeout=3))
            sys.exit(0 if 'id' in resp else 1)
    except Exception:
        sys.exit(1)
asyncio.run(check())
" 2>/dev/null
}

while [ "$ELAPSED" -lt "$TIMEOUT" ]; do
    if status_check; then
        echo "[fiji-health] Bridge ready on port $PORT"
        exit 0
    fi
    sleep "$INTERVAL"
    ELAPSED=$((ELAPSED + INTERVAL))
done

echo "[fiji-health] Timeout after ${TIMEOUT}s waiting for bridge on port $PORT" >&2
exit 1
