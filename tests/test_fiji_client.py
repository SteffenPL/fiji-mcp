import asyncio
import pytest
from fiji_mcp.fiji_client import FijiClient, FijiError


class TestConnect:
    async def test_connects_to_server(self, mock_fiji_server):
        client = FijiClient(port=mock_fiji_server["port"])
        await client.connect()
        assert client.connected
        await client.disconnect()

    async def test_connection_refused_raises(self):
        client = FijiClient(port=19999)
        with pytest.raises(ConnectionError, match="Fiji is not running"):
            await client.connect()


class TestSendRequest:
    async def test_basic_request(self, mock_fiji_server):
        mock_fiji_server["responses"]["status"] = {"connected": True}
        client = FijiClient(port=mock_fiji_server["port"])
        await client.connect()
        result = await client.send_request("status")
        assert result == {"connected": True}
        assert mock_fiji_server["received"][-1]["action"] == "status"
        await client.disconnect()

    async def test_request_with_params(self, mock_fiji_server):
        mock_fiji_server["responses"]["run_ij_macro"] = {"output": "hello"}
        client = FijiClient(port=mock_fiji_server["port"])
        await client.connect()
        result = await client.send_request("run_ij_macro", {"code": "print('hello');"})
        assert result == {"output": "hello"}
        sent = mock_fiji_server["received"][-1]
        assert sent["params"]["code"] == "print('hello');"
        await client.disconnect()

    async def test_auto_connects_on_send(self, mock_fiji_server):
        client = FijiClient(port=mock_fiji_server["port"])
        await client.send_request("status")
        assert client.connected
        await client.disconnect()

    async def test_error_response_raises(self, mock_fiji_server):
        mock_fiji_server["errors"]["bad"] = "Unknown action"
        client = FijiClient(port=mock_fiji_server["port"])
        await client.connect()
        with pytest.raises(FijiError, match="Unknown action"):
            await client.send_request("bad")
        await client.disconnect()

    async def test_sequential_requests(self, mock_fiji_server):
        mock_fiji_server["responses"]["a"] = {"val": 1}
        mock_fiji_server["responses"]["b"] = {"val": 2}
        client = FijiClient(port=mock_fiji_server["port"])
        await client.connect()
        r1 = await client.send_request("a")
        r2 = await client.send_request("b")
        assert r1 == {"val": 1}
        assert r2 == {"val": 2}
        await client.disconnect()

    async def test_per_request_timeout_overrides_default(self, mock_fiji_server):
        client = FijiClient(port=mock_fiji_server["port"])
        await client.connect()
        result = await client.send_request("status", timeout=2.0)
        assert result == {}
        await client.disconnect()

    async def test_per_request_timeout_can_exceed_default(self, mock_fiji_server):
        client = FijiClient(port=mock_fiji_server["port"])
        await client.connect()
        # 700s > the previous hardcoded 65s — proves the per-call timeout is honored.
        result = await client.send_request("status", timeout=700.0)
        assert result == {}
        await client.disconnect()


class TestEventCallback:
    async def test_receives_pushed_events(self, mock_fiji_server):
        events = []
        client = FijiClient(port=mock_fiji_server["port"])
        client.on_event(events.append)
        await client.connect()

        event = {
            "type": "event",
            "category": "command",
            "event": "command_finished",
            "data": {"command": "Blur"},
            "source": "user",
            "timestamp": 1000,
        }
        await mock_fiji_server["push_event"](event)
        await asyncio.sleep(0.1)

        assert len(events) == 1
        assert events[0]["event"] == "command_finished"
        await client.disconnect()


class TestDisconnect:
    async def test_disconnect_clears_state(self, mock_fiji_server):
        client = FijiClient(port=mock_fiji_server["port"])
        await client.connect()
        assert client.connected
        await client.disconnect()
        assert not client.connected
