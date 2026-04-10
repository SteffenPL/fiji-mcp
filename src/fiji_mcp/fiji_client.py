"""WebSocket client for communicating with Fiji's bridge plugin."""

import asyncio
import json
import os

import websockets
from websockets.protocol import State


class FijiError(Exception):
    """Error returned by Fiji."""


class FijiClient:

    def __init__(self, port: int | None = None):
        self._port = port or int(os.environ.get("FIJI_MCP_PORT", "8765"))
        self._ws = None
        self._req_counter = 0
        self._pending: dict[str, asyncio.Future] = {}
        self._event_callback = None
        self._receive_task: asyncio.Task | None = None

    @property
    def connected(self) -> bool:
        return self._ws is not None and self._ws.state == State.OPEN

    def on_event(self, callback):
        """Register a callback for incoming Fiji events."""
        self._event_callback = callback

    async def connect(self):
        try:
            self._ws = await websockets.connect(
                f"ws://localhost:{self._port}",
                open_timeout=5,
            )
        except (ConnectionRefusedError, OSError) as exc:
            raise ConnectionError(
                "Fiji is not running or fiji-mcp-bridge plugin is not active. "
                "Start Fiji and ensure the plugin is installed."
            ) from exc
        self._receive_task = asyncio.create_task(self._receive_loop())

    async def disconnect(self):
        if self._receive_task:
            self._receive_task.cancel()
            try:
                await self._receive_task
            except asyncio.CancelledError:
                pass
            self._receive_task = None
        if self._ws:
            await self._ws.close()
            self._ws = None

    async def send_request(
        self, action: str, params: dict | None = None,
        timeout: float = 65.0,
    ) -> dict:
        if not self.connected:
            await self.connect()

        self._req_counter += 1
        req_id = f"req_{self._req_counter}"
        request: dict = {"id": req_id, "type": "request", "action": action}
        if params:
            request["params"] = params

        future = asyncio.get_running_loop().create_future()
        self._pending[req_id] = future
        await self._ws.send(json.dumps(request))

        try:
            response = await asyncio.wait_for(future, timeout=timeout)
        except asyncio.TimeoutError:
            raise FijiError(f"Timeout waiting for response to {action}")
        finally:
            self._pending.pop(req_id, None)

        if response.get("status") == "error":
            error = response.get("error", {})
            raise FijiError(error.get("message", "Unknown error"))

        return response.get("result", {})

    async def _receive_loop(self):
        try:
            async for raw in self._ws:
                msg = json.loads(raw)
                if msg.get("type") == "response":
                    req_id = msg.get("id")
                    if req_id in self._pending:
                        self._pending[req_id].set_result(msg)
                elif msg.get("type") == "event":
                    if self._event_callback:
                        self._event_callback(msg)
        except websockets.ConnectionClosed:
            self._ws = None
        except asyncio.CancelledError:
            pass
