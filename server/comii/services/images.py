"""Downscaled page images for slow links (Tailscale / remote reading).

The reader can ask for ``/api/v1/media/view?...&w=1080`` and the server returns a
JPEG that is at most ``w`` pixels wide. Results are cached on disk next to the
thumbnails, so switching quality only costs one resize per page.
"""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
from typing import Optional
from uuid import uuid4

from PIL import Image, ImageOps

from ..config import settings

# Allowed widths keep the cache directory count small and predictable.
QUALITY_WIDTHS = (240, 480, 720, 1080, 1440, 1920)
MAX_WIDTH = 2400

# Re-encode quality for the resized copy (originals are untouched).
JPEG_QUALITY = 82


def clamp_width(width: int) -> int:
    """Snap a requested width to the supported ladder (0 = original)."""
    try:
        value = int(width)
    except (TypeError, ValueError):
        return 1080
    if value <= 0:
        return 0
    for candidate in QUALITY_WIDTHS:
        if value <= candidate:
            return candidate
    return MAX_WIDTH


def _cache_file(path: str, filename: str, width: int, stats) -> Path:
    raw = f"view:{path}/{filename}:{stats.st_mtime_ns}:{stats.st_size}:{width}"
    digest = hashlib.sha1(raw.encode("utf-8")).hexdigest()
    return settings.cache_root / str(width) / f"{digest}.jpg"


def resized_image(source: Path, path: str, filename: str, width: int) -> Optional[Path]:
    """Return a cached downscaled JPEG, or ``None`` when resizing is not possible."""
    try:
        stats = source.stat()
    except OSError:
        return None

    target_width = clamp_width(width)
    if target_width <= 0:
        return None

    target = _cache_file(path, filename, target_width, stats)
    if target.is_file() and target.stat().st_size > 0:
        return target

    try:
        with Image.open(source) as image:
            image = ImageOps.exif_transpose(image)
            image = image.convert("RGB")
            source_width, source_height = image.size
            if not source_width or not source_height:
                return None
            # Never upscale: small originals are served as-is.
            if source_width <= target_width:
                return None
            ratio = target_width / source_width
            size = (target_width, max(1, int(round(source_height * ratio))))
            image = image.resize(size, Image.LANCZOS)
            target.parent.mkdir(parents=True, exist_ok=True)
            temporary = target.with_name(f"{target.stem}.{os.getpid()}.{uuid4().hex[:8]}.tmp")
            image.save(temporary, "JPEG", quality=JPEG_QUALITY, optimize=True, progressive=True)
            temporary.replace(target)
        return target
    except Exception:
        return None


def clear_cache() -> int:
    """Delete every resized copy; returns the number of files removed."""
    root = settings.cache_root
    if not root.is_dir():
        return 0
    removed = 0
    for entry in root.rglob("*.jpg"):
        try:
            entry.unlink()
            removed += 1
        except OSError:
            continue
    return removed
