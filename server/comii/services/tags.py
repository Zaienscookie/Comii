"""User defined tags per series, stored as JSON under the data directory.

Tags are the only metadata Comii keeps outside of the filesystem, so they are
stored in a single small JSON file and written atomically. Tags follow the
series path: when a series is renamed or moved its tags stay behind, and
``prune`` drops the stale entries every time the file is rewritten.
"""

from __future__ import annotations

import json
import threading
from typing import Iterable

from ..config import settings

MAX_TAGS = 24
MAX_TAG_LENGTH = 32

_lock = threading.Lock()


def _clean(tags: Iterable[str]) -> list[str]:
    result: list[str] = []
    for raw in tags:
        value = str(raw or "").strip().replace("#", "").strip()
        if not value:
            continue
        if len(value) > MAX_TAG_LENGTH:
            value = value[:MAX_TAG_LENGTH]
        if value not in result:
            result.append(value)
        if len(result) >= MAX_TAGS:
            break
    return result


def _load() -> dict[str, list[str]]:
    try:
        data = json.loads(settings.tags_file.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    if not isinstance(data, dict):
        return {}
    result: dict[str, list[str]] = {}
    for path, tags in data.items():
        if isinstance(tags, list):
            clean = _clean(tags)
            if clean:
                result[str(path)] = clean
    return result


def _save(data: dict[str, list[str]]) -> None:
    settings.tags_file.parent.mkdir(parents=True, exist_ok=True)
    temporary = settings.tags_file.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True),
        encoding="utf-8",
    )
    temporary.replace(settings.tags_file)


def tags_for(path: str) -> list[str]:
    return list(_load().get(str(path or ""), []))


def set_tags(path: str, tags: Iterable[str]) -> list[str]:
    key = str(path or "").strip().strip("/")
    clean = _clean(tags)
    with _lock:
        data = _load()
        if clean:
            data[key] = clean
        else:
            data.pop(key, None)
        _save(data)
    return clean


def all_tags() -> list[dict]:
    """Every tag in use, with how many series carry it (most used first)."""
    counts: dict[str, int] = {}
    for tags in _load().values():
        for tag in tags:
            counts[tag] = counts.get(tag, 0) + 1
    ordered = sorted(counts.items(), key=lambda item: (-item[1], item[0].lower()))
    return [{"name": name, "count": count} for name, count in ordered]


def series_paths(tag: str) -> list[str]:
    clean = str(tag or "").strip()
    if not clean:
        return []
    paths = [path for path, tags in _load().items() if clean in tags]
    return sorted(paths, key=str.lower)


def prune(existing: set[str]) -> int:
    """Drop entries whose series no longer exists; returns removed count."""
    with _lock:
        data = _load()
        missing = [path for path in data if path not in existing]
        if missing:
            for path in missing:
                data.pop(path, None)
            _save(data)
        return len(missing)
