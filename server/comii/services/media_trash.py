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
