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
        filtered = _dedup_consecutive(filtered)
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
        items = _dedup_consecutive(items)
        return _strip_internal(items), self._total_count

    def clear(self):
        self._buffer.clear()
        self._total_count = 0

    def _resolve(self, idx: int) -> int:
        return self._total_count + idx if idx < 0 else idx


_NOISE_TITLES = {"flatten~canvas"}


def _apply_filters(
    items: list[dict], source: str | None, categories: list[str] | None
) -> list[dict]:
    if source:
        items = [e for e in items if e.get("source") == source]
    if categories:
        items = [e for e in items if e.get("category") in categories]
    # Drop internal Fiji compositing windows that are never meaningful to callers.
    items = [
        e for e in items
        if e.get("data", {}).get("title") not in _NOISE_TITLES
    ]
    return items


# Events where intermediate steps during a drag/update sequence are noise;
# we keep only the final state, grouped by event-type + subject image/title.
_LAST_WINS_BY_SUBJECT = {"roi_modified", "roi_moved", "image_updated"}


def _subject_key(event: dict) -> tuple | None:
    """Return a pooling key for subject-based dedup, or None for exact-match dedup."""
    name = event.get("event")
    if name not in _LAST_WINS_BY_SUBJECT:
        return None
    data = event.get("data", {})
    subject = data.get("image") or data.get("title") or ""
    return (name, subject)


def _dedup_consecutive(items: list[dict]) -> list[dict]:
    """Collapse runs of consecutive redundant events into the last occurrence.

    - For drag/update events (roi_modified, roi_moved, image_updated): pooled
      by event-type + subject, so intermediate drag frames are dropped.
    - For all other events: pooled by exact (event, data) equality.
    Non-event records (annotations, metadata) are always kept as-is.
    """
    if not items:
        return items
    result: list[dict] = []
    i = 0
    while i < len(items):
        if items[i].get("type") != "event":
            result.append(items[i])
            i += 1
            continue
        key = _subject_key(items[i])
        j = i + 1
        if key is not None:
            while (
                j < len(items)
                and items[j].get("type") == "event"
                and _subject_key(items[j]) == key
            ):
                j += 1
        else:
            while (
                j < len(items)
                and items[j].get("type") == "event"
                and items[j].get("event") == items[i].get("event")
                and items[j].get("data") == items[i].get("data")
            ):
                j += 1
        result.append(items[j - 1])
        i = j
    return result


def _strip_internal(events: list[dict]) -> list[dict]:
    return [{k: v for k, v in e.items() if not k.startswith("_")} for e in events]
