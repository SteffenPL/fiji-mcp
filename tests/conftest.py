import json
import pytest
from websockets.asyncio.server import serve


@pytest.fixture
async def mock_fiji_server():
    """Mock Fiji WebSocket server for testing."""
    received = []
    responses = {}  # action -> result dict (ok response)
    errors = {}  # action -> error message
    connections = []

    async def handler(ws):
        connections.append(ws)
        try:
            async for raw in ws:
                msg = json.loads(raw)
                received.append(msg)
                action = msg.get("action")
                req_id = msg.get("id")
                if action in errors:
                    resp = {
                        "id": req_id,
                        "type": "response",
                        "status": "error",
                        "error": {"message": errors[action], "type": "Error"},
                    }
                elif action in responses:
                    resp = {
                        "id": req_id,
                        "type": "response",
                        "status": "ok",
                        "result": responses[action],
                    }
                else:
                    resp = {
                        "id": req_id,
                        "type": "response",
                        "status": "ok",
                        "result": {},
                    }
                await ws.send(json.dumps(resp))
        except Exception:
            pass
        finally:
            connections.remove(ws)

    async with serve(handler, "localhost", 0) as server:
        port = server.sockets[0].getsockname()[1]

        async def push_event(event):
            for ws in connections:
                await ws.send(json.dumps(event))

        yield {
            "port": port,
            "received": received,
            "responses": responses,
            "errors": errors,
            "push_event": push_event,
        }
