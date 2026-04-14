from fiji_mcp.action_log import ActionLog


def _evt(category="command", event="command_finished", source="user", **data):
    return {
        "type": "event",
        "category": category,
        "event": event,
        "source": source,
        "data": data,
        "timestamp": 1000,
    }


class TestAppendAndCount:
    def test_starts_empty(self):
        log = ActionLog()
        assert log.count == 0
        assert log.earliest_index == 0

    def test_append_increments_count(self):
        log = ActionLog()
        log.append(_evt())
        log.append(_evt())
        assert log.count == 2

    def test_append_returns_index(self):
        log = ActionLog()
        assert log.append(_evt()) == 0
        assert log.append(_evt()) == 1
        assert log.append(_evt()) == 2


class TestGetRecent:
    def test_returns_last_n(self):
        log = ActionLog()
        for i in range(10):
            log.append(_evt(val=i))
        events, total = log.get_recent(count=3)
        assert len(events) == 3
        assert total == 10
        assert events[-1]["data"]["val"] == 9
        assert events[0]["data"]["val"] == 7

    def test_with_offset(self):
        log = ActionLog()
        for i in range(10):
            log.append(_evt(val=i))
        events, _ = log.get_recent(count=3, offset=5)
        assert len(events) == 3
        assert events[-1]["data"]["val"] == 4
        assert events[0]["data"]["val"] == 2

    def test_count_exceeds_available(self):
        log = ActionLog()
        log.append(_evt(val=0))
        events, total = log.get_recent(count=100)
        assert len(events) == 1
        assert total == 1

    def test_empty_log(self):
        log = ActionLog()
        events, total = log.get_recent()
        assert events == []
        assert total == 0


class TestFiltering:
    def test_filter_by_source(self):
        log = ActionLog()
        log.append(_evt(source="user"))
        log.append(_evt(source="mcp"))
        log.append(_evt(source="user"))
        events, _ = log.get_recent(count=10, source="user")
        assert len(events) == 2
        assert all(e["source"] == "user" for e in events)

    def test_filter_by_categories(self):
        log = ActionLog()
        log.append(_evt(category="command"))
        log.append(_evt(category="image", event="image_opened"))
        log.append(_evt(category="roi", event="roi_created"))
        events, _ = log.get_recent(count=10, categories=["command", "roi"])
        assert len(events) == 2

    def test_combined_filters(self):
        log = ActionLog()
        log.append(_evt(category="command", source="user"))
        log.append(_evt(category="command", source="mcp"))
        log.append(_evt(category="image", source="user", event="image_opened"))
        events, _ = log.get_recent(count=10, source="user", categories=["command"])
        assert len(events) == 1


class TestGetRange:
    def test_absolute_indices(self):
        log = ActionLog()
        for i in range(10):
            log.append(_evt(val=i))
        events, total = log.get_range(start=3, end=6)
        assert len(events) == 4  # indices 3, 4, 5, 6
        assert events[0]["data"]["val"] == 3
        assert events[-1]["data"]["val"] == 6
        assert total == 10

    def test_negative_indices(self):
        log = ActionLog()
        for i in range(10):
            log.append(_evt(val=i))
        events, _ = log.get_range(start=-3, end=-1)
        assert len(events) == 3
        assert events[0]["data"]["val"] == 7
        assert events[-1]["data"]["val"] == 9

    def test_with_filters(self):
        log = ActionLog()
        log.append(_evt(category="command", val=0))
        log.append(_evt(category="image", event="image_opened", val=1))
        log.append(_evt(category="command", val=2))
        events, _ = log.get_range(start=0, end=2, categories=["command"])
        assert len(events) == 2


class TestEviction:
    def test_ring_buffer_evicts_oldest(self):
        log = ActionLog(max_size=5)
        for i in range(10):
            log.append(_evt(val=i))
        assert log.count == 10
        assert log.earliest_index == 5
        events, _ = log.get_recent(count=100)
        assert len(events) == 5
        assert events[0]["data"]["val"] == 5

    def test_evicted_range_clamped(self):
        log = ActionLog(max_size=5)
        for i in range(10):
            log.append(_evt(val=i))
        events, _ = log.get_range(start=0, end=9)
        assert len(events) == 5
        assert events[0]["data"]["val"] == 5


class TestClear:
    def test_clear_resets(self):
        log = ActionLog()
        log.append(_evt())
        log.clear()
        assert log.count == 0
        events, _ = log.get_recent()
        assert events == []


class TestInternalFieldStripping:
    def test_no_underscore_fields_in_output(self):
        log = ActionLog()
        log.append(_evt(val=1))
        events, _ = log.get_recent()
        for event in events:
            assert all(not k.startswith("_") for k in event.keys())
