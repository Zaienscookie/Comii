"""ZIP export of a series or folder."""

from __future__ import annotations

import re
import tempfile
import zipfile
from pathlib import Path

from ..config import settings
from . import library


def _safe_stem(name: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]", "_", name)[:80] or "export"


def build_export(raw_path: str, include_video: bool = True) -> tuple[Path, str]:
    root = library.resolve_directory(raw_path)
    normalized = library.normalize_path(raw_path)
    label = Path(normalized).name or "library"
    settings.data_root.mkdir(parents=True, exist_ok=True)

    handle = tempfile.NamedTemporaryFile(
        prefix="comii_export_", suffix=".zip", dir=settings.data_root, delete=False
    )
    handle.close()
    target = Path(handle.name)

    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for entry in sorted(root.rglob("*"), key=lambda item: library.natural_sort_key(str(item))):
            if not entry.is_file():
                continue
            if entry.name.startswith("."):
                continue
            if library.is_image(entry):
                pass
            elif library.is_video(entry):
                if not include_video:
                    continue
            else:
                continue
            relative = entry.relative_to(root)
            bundle.write(entry, arcname=str(Path(label) / relative))

    filename = f"export_{_safe_stem(normalized)}.zip"
    return target, filename
