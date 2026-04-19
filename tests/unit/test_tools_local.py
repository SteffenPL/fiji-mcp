from pathlib import Path
from unittest.mock import AsyncMock

import pytest

import fiji_mcp.server as srv
from fiji_mcp.fiji_home import FijiInfo


def _evt(category="command", event="command_finished", source="user", **data):
    return {
        "type": "event",
        "category": category,
        "event": event,
        "source": source,
        "data": data,
        "timestamp": 1000,
    }


@pytest.fixture(autouse=True)
def clean_action_log():
    srv._action_log.clear()


class TestGetRecentActions:
    async def test_empty_log(self):
        result = await srv.get_recent_actions()
        assert result == {"actions": [], "total": 0, "returned": 0}

    async def test_returns_recent(self):
        for i in range(5):
            srv._action_log.append(_evt(command=f"cmd_{i}"))
        result = await srv.get_recent_actions(count=3)
        assert result["returned"] == 3
        assert result["total"] == 5

    async def test_source_filter(self):
        srv._action_log.append(_evt(source="user"))
        srv._action_log.append(_evt(source="mcp"))
        result = await srv.get_recent_actions(source="user")
        assert result["returned"] == 1

    async def test_category_filter(self):
        srv._action_log.append(_evt(category="command"))
        srv._action_log.append(_evt(category="image", event="image_opened"))
        result = await srv.get_recent_actions(categories=["command"])
        assert result["returned"] == 1


class TestExportActionsAsMacro:
    async def test_export_commands(self):
        srv._action_log.append(_evt(command="Gaussian Blur...", args="sigma=2"))
        srv._action_log.append(_evt(command="Threshold"))
        result = await srv.export_actions_as_macro(start=0, end=1)
        assert 'run("Gaussian Blur...", "sigma=2");' in result["macro"]
        assert 'run("Threshold");' in result["macro"]
        assert result["event_count"] == 2

    async def test_skips_non_command_events(self):
        srv._action_log.append(_evt(category="image", event="image_opened", title="a.tif"))
        srv._action_log.append(_evt(command="Threshold"))
        result = await srv.export_actions_as_macro(start=0, end=1)
        assert "Threshold" in result["macro"]
        assert "image_opened" not in result["macro"]

    async def test_negative_indices(self):
        for i in range(5):
            srv._action_log.append(_evt(command=f"Cmd{i}"))
        result = await srv.export_actions_as_macro(start=-2, end=-1)
        assert 'run("Cmd3");' in result["macro"]
        assert 'run("Cmd4");' in result["macro"]
        assert result["event_count"] == 2

    async def test_empty_range(self):
        result = await srv.export_actions_as_macro(start=0, end=0)
        assert "No command events" in result["macro"]


class TestGetFijiInfo:
    @pytest.fixture(autouse=True)
    def fake_fiji_home(self, monkeypatch):
        info = FijiInfo(
            path=Path("/fake/Fiji.app"),
            plugins_dir=Path("/fake/Fiji.app/plugins"),
            launcher=Path("/fake/Fiji.app/fiji"),
            java_version="21.0.7",
        )
        monkeypatch.setattr(srv, "resolve_fiji_home", lambda: info)
        monkeypatch.setattr(srv, "_client", None)

    async def test_paths_only_when_bridge_not_connected(self):
        result = await srv.get_fiji_info()
        assert result["fiji_home"] == "/fake/Fiji.app"
        assert result["plugins_dir"] == "/fake/Fiji.app/plugins"
        assert result["java_version"] == "21.0.7"
        assert "plugin_packages" not in result
        assert "plugin_packages_error" not in result

    async def test_merges_plugin_packages_when_connected(self, monkeypatch):
        client = AsyncMock()
        client.connected = True
        client.send_request = AsyncMock(return_value={
            "packages": [
                {"prefix": "inra.ijpb", "command_count": 12,
                 "example_commands": ["Classic Watershed"]},
                {"prefix": "sc.fiji.trackmate", "command_count": 3,
                 "example_commands": ["TrackMate"]},
            ],
        })
        monkeypatch.setattr(srv, "_client", client)

        result = await srv.get_fiji_info()
        client.send_request.assert_called_once_with(
            "list_plugin_packages", timeout=10.0
        )
        assert result["plugin_packages"][0]["prefix"] == "inra.ijpb"
        assert len(result["plugin_packages"]) == 2

    async def test_reports_error_when_bridge_query_fails(self, monkeypatch):
        client = AsyncMock()
        client.connected = True
        client.send_request = AsyncMock(side_effect=RuntimeError("boom"))
        monkeypatch.setattr(srv, "_client", client)

        result = await srv.get_fiji_info()
        assert result["plugin_packages_error"] == "boom"
        assert "plugin_packages" not in result


class TestEventsToMacro:
    def test_command_with_args(self):
        events = [{"event": "command_finished", "data": {"command": "Blur", "args": "sigma=2"}}]
        assert 'run("Blur", "sigma=2");' in srv._events_to_macro(events)

    def test_command_without_args(self):
        events = [{"event": "command_finished", "data": {"command": "Invert", "args": ""}}]
        assert 'run("Invert");' in srv._events_to_macro(events)

    def test_non_command_skipped(self):
        events = [{"event": "image_opened", "data": {"title": "a.tif"}}]
        macro = srv._events_to_macro(events)
        assert "image_opened" not in macro
