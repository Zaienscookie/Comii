"""Recycle bin: soft delete with a three day retention window."""

from __future__ import annotations

import json
import os
import shutil
import threading
import time
import uuid
from pathlib import Path

from fastapi import HTTPException

from ..config import settings
from ..models import NodeKind, TrashEntry
from . import library

_lock = threading.RLock()


def _load_index() -> dict[str, dict]:
    try:
        raw = settings.trash_index.read_text(encoding="utf-8")
        data = json.loads(raw)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save_index(index: dict[str, dict]) -> None:
    settings.trash_root.mkdir(parents=True, exist_ok=True)
    temporary = settings.trash_index.with_suffix(".tmp")
    temporary.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temporary, settings.trash_index)


def _payload_dir(entry_id: str) -> Path:
    return settings.trash_root / entry_id / "data"


def _entry_from_meta(entry_id: str, meta: dict) -> TrashEntry:
    now = time.time()
    expires_at = float(meta.get("expires_at", now))
    return TrashEntry(
        id=entry_id,
        name=str(meta.get("name", entry_id)),
        original_path=str(meta.get("original_path", "")),
        kind=NodeKind(meta.get("kind", "manga")),
        image_count=int(meta.get("image_count", 0)),
        video_count=int(meta.get("video_count", 0)),
        size=int(meta.get("size", 0)),
        deleted_at=float(meta.get("deleted_at", now)),
        expires_at=expires_at,
        remaining_seconds=max(0, int(expires_at - now)),
    )


def list_entries() -> list[TrashEntry]:
    with _lock:
        index = _load_index()
        entries = [
            _entry_from_meta(entry_id, meta)
            for entry_id, meta in index.items()
            if _payload_dir(entry_id).exists()
        ]
    entries.sort(key=lambda item: item.deleted_at, reverse=True)
    return entries


def _measure(directory: Path) -> tuple[int, int, int, NodeKind]:
    images = 0
    videos = 0
    size = 0
    for entry in directory.rglob("*"):
        if not entry.is_file():
            continue
        try:
            size += entry.stat().st_size
        except OSError:
            pass
        if library.is_image(entry):
            images += 1
        elif library.is_video(entry):
            videos += 1
    if images and videos:
        kind = NodeKind.mixed
    elif videos:
        kind = NodeKind.video
    else:
        kind = NodeKind.manga
    return images, videos, size, kind


def soft_delete(raw_path: str) -> TrashEntry:
    with _lock:
        source = library.resolve_directory(raw_path)
        normalized = library.normalize_path(raw_path)
        if not normalized:
            raise HTTPException(status_code=400, detail="不能删除库根目录")
        images, videos, size, kind = _measure(source)
        entry_id = uuid.uuid4().hex
        payload = _payload_dir(entry_id)
        payload.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(source), str(payload))

        now = time.time()
        meta = {
            "name": source.name,
            "original_path": normalized,
            "kind": kind.value,
            "image_count": images,
            "video_count": videos,
            "size": size,
            "deleted_at": now,
            "expires_at": now + settings.trash_ttl_seconds,
        }
        index = _load_index()
        index[entry_id] = meta
        _save_index(index)
        return _entry_from_meta(entry_id, meta)


def restore(entry_id: str) -> str:
    with _lock:
        index = _load_index()
        meta = index.get(entry_id)
        if meta is None or not _payload_dir(entry_id).exists():
            raise HTTPException(status_code=404, detail="回收站中不存在该项目")
        original = str(meta.get("original_path", ""))
        target = library.library_root() / original
        if target.exists():
            raise HTTPException(status_code=409, detail="原位置已存在同名项目，请先重命名")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(_payload_dir(entry_id)), str(target))
        shutil.rmtree(settings.trash_root / entry_id, ignore_errors=True)
        index.pop(entry_id, None)
        _save_index(index)
        return original


def purge(entry_id: str) -> None:
    with _lock:
        index = _load_index()
        if entry_id not in index:
            raise HTTPException(status_code=404, detail="回收站中不存在该项目")
        shutil.rmtree(settings.trash_root / entry_id, ignore_errors=True)
        index.pop(entry_id, None)
        _save_index(index)


def sweep() -> int:
    """Delete entries past their retention window; returns how many were removed."""
    with _lock:
        index = _load_index()
        now = time.time()
        removed = 0
        for entry_id, meta in list(index.items()):
            if now >= float(meta.get("expires_at", 0)):
                shutil.rmtree(settings.trash_root / entry_id, ignore_errors=True)
                index.pop(entry_id, None)
                removed += 1
        # Orphan directories without an index entry (e.g. interrupted delete).
        try:
            for child in settings.trash_root.iterdir():
                if not child.is_dir() or child.name in index:
                    continue
                age = now - child.stat().st_mtime
                if age >= settings.trash_ttl_seconds:
                    shutil.rmtree(child, ignore_errors=True)
                    removed += 1
        except OSError:
            pass
        if removed:
            _save_index(index)
        return removed
