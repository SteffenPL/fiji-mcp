import pytest

import fiji_mcp.server as srv


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
