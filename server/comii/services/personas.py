"""Per-user AI personas stored on the server (account isolated)."""

from __future__ import annotations

import json
import threading
import time
import uuid
from typing import Any

from ..config import settings

_lock = threading.Lock()


def _root():
    path = settings.data_root / "personas"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _file(user_id: int):
    return _root() / f"{int(user_id)}.json"


def _load(user_id: int) -> list[dict[str, Any]]:
    try:
        raw = json.loads(_file(user_id).read_text(encoding="utf-8"))
        return raw if isinstance(raw, list) else []
    except (OSError, ValueError):
        return []


def _save(user_id: int, items: list[dict[str, Any]]) -> None:
    target = _file(user_id)
    tmp = target.with_suffix(".tmp")
    tmp.write_text(json.dumps(items, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(target)


def list_for(user_id: int) -> list[dict[str, Any]]:
    return _load(user_id)


def upsert(user_id: int, payload: dict[str, Any]) -> dict[str, Any]:
    with _lock:
        items = _load(user_id)
        pid = str(payload.get("id") or "").strip()
        record = {
            "id": pid or uuid.uuid4().hex,
            "name": str(payload.get("name") or "").strip()[:40] or "未命名",
            "prompt": str(payload.get("prompt") or "")[:8000],
            "greeting": str(payload.get("greeting") or "")[:2000],
            "avatar": str(payload.get("avatar") or "")[:300],
            "color": int(payload.get("color") or 0),
            "updated_at": time.time(),
        }
        existing = next((i for i, item in enumerate(items) if item.get("id") == record["id"]), -1)
        if existing >= 0:
            items[existing] = record
        else:
            items.append(record)
        _save(user_id, items)
        return record


def delete(user_id: int, persona_id: str) -> bool:
    with _lock:
        items = _load(user_id)
        left = [item for item in items if item.get("id") != persona_id]
        if len(left) == len(items):
            return False
        _save(user_id, left)
        return True
