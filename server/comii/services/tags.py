"""User defined tags with optional groups.

Storage (``data/tags.json``)::

    {
      "version": 2,
      "tagGroups": {"作者": "作者", "恋爱": "题材"},   # tag -> group
      "series": {"comic/xxx": ["作者", "恋爱"]}
    }

The old v1 format (``{"comic/xxx": ["tag"]}``) is migrated on read.
"""

from __future__ import annotations

import json
import threading
from pathlib import Path
from typing import Iterable

from ..config import settings

MAX_TAGS = 64
MAX_TAG_LENGTH = 32
DEFAULT_GROUP = "未分组"

_lock = threading.Lock()


def _clean_name(raw: str) -> str:
    value = str(raw or "").strip().replace("#", "").strip()
    return value[:MAX_TAG_LENGTH]


def _clean_list(tags: Iterable[str]) -> list[str]:
    result: list[str] = []
    for raw in tags:
        value = _clean_name(raw)
        if value and value not in result:
            result.append(value)
        if len(result) >= MAX_TAGS:
            break
    return result


def _data() -> dict:
    try:
        raw = json.loads(settings.tags_file.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raw = {}
    if not isinstance(raw, dict):
        raw = {}
    # v1 -> v2
    if "series" not in raw:
        series: dict[str, list[str]] = {}
        for path, tags in raw.items():
            if isinstance(tags, list):
                clean = _clean_list(tags)
                if clean:
                    series[str(path)] = clean
        raw = {"version": 2, "tagGroups": {}, "series": series}
    raw.setdefault("tagGroups", {})
    raw.setdefault("series", {})
    if not isinstance(raw["tagGroups"], dict):
        raw["tagGroups"] = {}
    if not isinstance(raw["series"], dict):
        raw["series"] = {}
    raw["version"] = 2
    return raw


def _save(data: dict) -> None:
    settings.tags_file.parent.mkdir(parents=True, exist_ok=True)
    temporary = settings.tags_file.with_suffix(".json.tmp")
    temporary.write_text(
        json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True),
        encoding="utf-8",
    )
    temporary.replace(settings.tags_file)


def groups() -> list[str]:
    data = _data()
    known = sorted({str(v) for v in data["tagGroups"].values() if str(v).strip()})
    return known


def create_tag(name: str, group: str = DEFAULT_GROUP) -> dict:
    tag = _clean_name(name)
    if not tag:
        return {"name": "", "group": DEFAULT_GROUP}
    label = str(group or "").strip()[:MAX_TAG_LENGTH] or DEFAULT_GROUP
    with _lock:
        data = _data()
        data["tagGroups"][tag] = label
        _save(data)
    return {"name": tag, "group": label}


def delete_tag(name: str) -> int:
    tag = _clean_name(name)
    removed = 0
    with _lock:
        data = _data()
        data["tagGroups"].pop(tag, None)
        for path, tags in list(data["series"].items()):
            if tag in tags:
                left = [item for item in tags if item != tag]
                removed += 1
                if left:
                    data["series"][path] = left
                else:
                    data["series"].pop(path, None)
        _save(data)
    return removed


def tags_for(path: str) -> list[str]:
    return list(_data()["series"].get(str(path or ""), []))


def update_tags(
    paths: Iterable[str],
    tags: Iterable[str],
    mode: str = "add",
) -> int:
    """Add / remove / replace tags on one or more paths. Returns touched count."""
    clean = _clean_list(tags)
    targets = [str(path).strip().strip("/") for path in paths if str(path or "").strip()]
    if not targets:
        return 0
    touched = 0
    with _lock:
        data = _data()
        for path in targets:
            current = data["series"].get(path, [])
            if mode == "replace":
                updated = clean
            elif mode == "remove":
                updated = [item for item in current if item not in clean]
            else:
                updated = current + [item for item in clean if item not in current]
            if updated:
                data["series"][path] = updated
            else:
                data["series"].pop(path, None)
            for tag in clean:
                data["tagGroups"].setdefault(tag, DEFAULT_GROUP)
            touched += 1
        _save(data)
    return touched


def set_tags(path: str, tags: Iterable[str]) -> list[str]:
    update_tags([path], tags, mode="replace")
    return tags_for(path)


def all_tags() -> list[dict]:
    data = _data()
    counts: dict[str, int] = {}
    for tags in data["series"].values():
        for tag in tags:
            counts[tag] = counts.get(tag, 0) + 1
    for tag in data["tagGroups"]:
        counts.setdefault(tag, 0)
    ordered = sorted(counts.items(), key=lambda item: (-item[1], item[0].lower()))
    return [
        {"name": name, "count": count, "group": data["tagGroups"].get(name, DEFAULT_GROUP)}
        for name, count in ordered
    ]


def series_paths(tag: str) -> list[str]:
    clean = _clean_name(tag)
    if not clean:
        return []
    paths = [path for path, tags in _data()["series"].items() if clean in tags]
    return sorted(paths, key=str.lower)


def prune(existing: set[str]) -> int:
    with _lock:
        data = _data()
        missing = [path for path in data["series"] if path not in existing]
        if missing:
            for path in missing:
                data["series"].pop(path, None)
            _save(data)
        return len(missing)
