"""Delete individual photos / videos from the library.

Series deletion goes through :mod:`comii.services.trash`; this module handles the
"long press one picture" case. Files are moved under ``data/trash/media/<time>``
so a mistake can still be recovered from the NAS filesystem.
"""

from __future__ import annotations

import shutil
import time
from pathlib import Path

from ..config import settings
from . import library


def delete_files(raw_path: str, names: list[str]) -> int:
    directory = library.resolve_directory(raw_path)
    normalized = library.normalize_path(raw_path)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    target_root = settings.trash_root / "media" / stamp
    if normalized:
        target_root = target_root / normalized
    deleted = 0
    for raw in names or []:
        name = str(raw or "")
        if not name or name in (".", "..") or "/" in name or "\\" in name:
            continue
        source = directory / name
        if not source.is_file():
            continue
        try:
            target_root.mkdir(parents=True, exist_ok=True)
            destination = target_root / name
            counter = 1
            while destination.exists():
                destination = target_root / f"{Path(name).stem}_{counter}{Path(name).suffix}"
                counter += 1
            shutil.move(str(source), str(destination))
            deleted += 1
        except OSError:
            continue
    return deleted


def _entries() -> list[dict]:
    """把 data/trash/media/<stamp>/<相对路径...>/<文件> 摊平成条目。"""
    root = settings.trash_root / "media"
    if not root.is_dir():
        return []
    items: list[dict] = []
    for stamp_dir in sorted(root.iterdir(), key=lambda p: p.name, reverse=True):
        if not stamp_dir.is_dir():
            continue
        for file in stamp_dir.rglob("*"):
            if not file.is_file():
                continue
            rel = file.relative_to(stamp_dir).as_posix()
            try:
                stat = file.stat()
                deleted = stamp_dir.stat().st_mtime
            except OSError:
                continue
            items.append(
                {
                    "id": f"media|{stamp_dir.name}|{rel}",
                    "name": file.name,
                    "original_path": rel,
                    "kind": "manga",
                    "image_count": 1,
                    "video_count": 0,
                    "size": stat.st_size,
                    "deleted_at": deleted,
                    "expires_at": deleted + 3 * 86400,
                    "remaining_seconds": int(deleted + 3 * 86400 - time.time()),
                }
            )
    return items


def list_entries() -> list[dict]:
    return _entries()


def _resolve(entry_id: str) -> Path | None:
    parts = str(entry_id or "").split("|", 2)
    if len(parts) != 3 or parts[0] != "media":
        return None
    stamp, rel = parts[1], parts[2]
    if not stamp or not rel or ".." in Path(rel).parts:
        return None
    target = settings.trash_root / "media" / stamp / Path(rel)
    return target if target.is_file() else None


def restore(entry_id: str) -> str:
    source = _resolve(entry_id)
    if source is None:
        raise HTTPException(status_code=404, detail="回收站条目不存在")
    rel = Path("/".join(str(entry_id).split("|", 2)[2].split("/")))
    destination = library.library_root() / rel
    destination.parent.mkdir(parents=True, exist_ok=True)
    counter = 1
    final = destination
    while final.exists():
        final = destination.with_name(f"{destination.stem}_{counter}{destination.suffix}")
        counter += 1
    shutil.move(str(source), str(final))
    return final.relative_to(library.library_root()).as_posix()


def purge(entry_id: str) -> None:
    source = _resolve(entry_id)
    if source is None:
        raise HTTPException(status_code=404, detail="回收站条目不存在")
    source.unlink(missing_ok=True)
