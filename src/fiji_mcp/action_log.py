"""Ring buffer for Fiji events with monotonic indexing and filtering."""

from collections import deque


class ActionLog:

    def __init__(self, max_size: int = 10_000):
        self._buffer: deque[dict] = deque(maxlen=max_size)
        self._total_count: int = 0

    @property
    def count(self) -> int:
        return self._total_count

    @property
    def earliest_index(self) -> int:
        if not self._buffer:
            return self._total_count
        return self._buffer[0]["_index"]

    def append(self, event: dict) -> int:
        index = self._total_count
        self._buffer.append({**event, "_index": index})
        self._total_count += 1
        return index

    def get_recent(
        self,
        count: int = 20,
        offset: int = 0,
        source: str | None = None,
        categories: list[str] | None = None,
    ) -> tuple[list[dict], int]:
        filtered = _apply_filters(list(self._buffer), source, categories)
        end = len(filtered) - offset
        start = max(0, end - count)
        return _strip_internal(filtered[start:end]), self._total_count

    def get_range(
        self,
        start: int,
        end: int,
        source: str | None = None,
        categories: list[str] | None = None,
    ) -> tuple[list[dict], int]:
        start = self._resolve(start)
        end = self._resolve(end)
        items = [e for e in self._buffer if start <= e["_index"] <= end]
        items = _apply_filters(items, source, categories)
        return _strip_internal(items), self._total_count

    def clear(self):
        self._buffer.clear()
        self._total_count = 0

    def _resolve(self, idx: int) -> int:
        return self._total_count + idx if idx < 0 else idx


def _apply_filters(
    items: list[dict], source: str | None, categories: list[str] | None
) -> list[dict]:
    if source:
        items = [e for e in items if e.get("source") == source]
    if categories:
        items = [e for e in items if e.get("category") in categories]
    return items


def _strip_internal(events: list[dict]) -> list[dict]:
    return [{k: v for k, v in e.items() if not k.startswith("_")} for e in events]
