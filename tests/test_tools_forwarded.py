import pytest
from unittest.mock import AsyncMock

import fiji_mcp.server as srv


@pytest.fixture(autouse=True)
def mock_client(monkeypatch):
    """Replace _get_client with a mock that returns an AsyncMock client."""
    client = AsyncMock()
    client.connected = True
    client.send_request = AsyncMock(return_value={})

    async def fake_get_client():
        return client

    monkeypatch.setattr(srv, "_get_client", fake_get_client)
    return client


class TestRunIjMacro:
    async def test_forwards_code(self, mock_client):
        mock_client.send_request.return_value = {"output": "hello", "active_image": "t.tif"}
        result = await srv.run_ij_macro("print('hello');")
        mock_client.send_request.assert_called_once_with(
            "run_ij_macro", {"code": "print('hello');"}
        )
        assert result["output"] == "hello"

    async def test_includes_args(self, mock_client):
        await srv.run_ij_macro("code", args="arg1")
        mock_client.send_request.assert_called_once_with(
            "run_ij_macro", {"code": "code", "args": "arg1"}
        )


class TestRunScript:
    async def test_forwards_language_and_code(self, mock_client):
        await srv.run_script("python", "print(1)")
        mock_client.send_request.assert_called_once_with(
            "run_script", {"language": "python", "code": "print(1)"}
        )


class TestRunCommand:
    async def test_forwards_command(self, mock_client):
        await srv.run_command("Gaussian Blur...", args="sigma=2")
        mock_client.send_request.assert_called_once_with(
            "run_command", {"command": "Gaussian Blur...", "args": "sigma=2"}
        )

    async def test_no_args(self, mock_client):
        await srv.run_command("Invert")
        mock_client.send_request.assert_called_once_with(
            "run_command", {"command": "Invert"}
        )


class TestListImages:
    async def test_no_params(self, mock_client):
        mock_client.send_request.return_value = {"images": [{"title": "a.tif"}]}
        result = await srv.list_images()
        mock_client.send_request.assert_called_once_with("list_images")
        assert len(result["images"]) == 1


class TestGetImageInfo:
    async def test_by_title(self, mock_client):
        await srv.get_image_info(title="a.tif")
        mock_client.send_request.assert_called_once_with(
            "get_image_info", {"title": "a.tif"}
        )

    async def test_by_id(self, mock_client):
        await srv.get_image_info(image_id=42)
        mock_client.send_request.assert_called_once_with(
            "get_image_info", {"id": 42}
        )


class TestSaveImage:
    async def test_defaults(self, mock_client):
        await srv.save_image("a.tif")
        mock_client.send_request.assert_called_once_with(
            "save_image", {"title": "a.tif", "format": "tiff"}
        )

    async def test_custom_format_and_path(self, mock_client):
        await srv.save_image("a.tif", format="png", path="/tmp/out.png")
        mock_client.send_request.assert_called_once_with(
            "save_image", {"title": "a.tif", "format": "png", "path": "/tmp/out.png"}
        )


class TestListCommands:
    async def test_forwards_pattern(self, mock_client):
        await srv.list_commands("blur")
        mock_client.send_request.assert_called_once_with(
            "list_commands", {"pattern": "blur"}
        )


class TestGetResultsTable:
    async def test_no_path(self, mock_client):
        await srv.get_results_table()
        mock_client.send_request.assert_called_once_with("get_results_table")

    async def test_with_path(self, mock_client):
        await srv.get_results_table(path="/tmp/r.csv")
        mock_client.send_request.assert_called_once_with(
            "get_results_table", {"path": "/tmp/r.csv"}
        )


class TestGetLog:
    async def test_default_count(self, mock_client):
        await srv.get_log()
        mock_client.send_request.assert_called_once_with("get_log", {"count": 50})

    async def test_custom_count(self, mock_client):
        await srv.get_log(count=10)
        mock_client.send_request.assert_called_once_with("get_log", {"count": 10})


class TestStatus:
    async def test_merges_action_count(self, mock_client):
        mock_client.send_request.return_value = {
            "fiji_version": "2.16.0",
            "uptime_s": 100,
        }
        result = await srv.status()
        assert result["fiji_version"] == "2.16.0"
        assert "action_count" in result


class TestSetEventCategories:
    async def test_forwards_categories(self, mock_client):
        await srv.set_event_categories(["command", "image"])
        mock_client.send_request.assert_called_once_with(
            "set_event_categories", {"categories": ["command", "image"]}
        )
