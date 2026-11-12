"""Reading progress shared across devices."""

from __future__ import annotations

import json
import os
import threading
import time

from ..config import settings
from ..models import ReadingProgress

_lock = threading.RLock()


def _load() -> dict[str, dict]:
    try:
        data = json.loads(settings.progress_file.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save(data: dict[str, dict]) -> None:
    settings.data_root.mkdir(parents=True, exist_ok=True)
    temporary = settings.progress_file.with_suffix(".tmp")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temporary, settings.progress_file)


def get(path: str) -> ReadingProgress | None:
    with _lock:
        item = _load().get(path)
    if not item:
        return None
    return ReadingProgress(
        page=int(item.get("page", 0)),
        total=int(item.get("total", 0)),
        updated_at=float(item.get("updated_at", 0)) or None,
    )


def get_many() -> dict[str, ReadingProgress]:
    with _lock:
        raw = _load()
    return {
        path: ReadingProgress(
            page=int(value.get("page", 0)),
            total=int(value.get("total", 0)),
            updated_at=float(value.get("updated_at", 0)) or None,
        )
        for path, value in raw.items()
    }


def update(path: str, page: int, total: int = 0) -> ReadingProgress:
    from . import library

    normalized = library.normalize_path(path)
    if not normalized:
        from fastapi import HTTPException

        raise HTTPException(status_code=400, detail="路径不能为空")
    page = max(0, int(page))
    total = max(0, int(total))
    with _lock:
        data = _load()
        data[normalized] = {"page": page, "total": total, "updated_at": time.time()}
        _save(data)
    return ReadingProgress(page=page, total=total, updated_at=time.time())
