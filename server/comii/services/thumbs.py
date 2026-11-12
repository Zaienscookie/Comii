"""Cover / page thumbnails with an on-disk cache."""

from __future__ import annotations

import hashlib
import shutil
import subprocess
from pathlib import Path
from typing import Optional

from PIL import Image, ImageDraw, ImageOps

from ..config import settings

THUMBNAIL_SIZE = (400, 600)
COVER_RATIO = 2 / 3


def _cache_target(prefix: str, path: str, filename: str, stats) -> Path:
    raw = f"{prefix}:{path}/{filename}:{stats.st_mtime_ns}:{stats.st_size}"
    digest = hashlib.sha1(raw.encode("utf-8")).hexdigest()
    return settings.thumb_root / f"{digest}.jpg"


def _write_atomic(image: Image.Image, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".tmp")
    image.save(temporary, "JPEG", quality=82, optimize=True)
    temporary.replace(target)


def image_thumbnail(source: Path, path: str, filename: str) -> Optional[Path]:
    try:
        stats = source.stat()
    except OSError:
        return None
    target = _cache_target("image", path, filename, stats)
    if target.is_file() and target.stat().st_size > 0:
        return target

    try:
        with Image.open(source) as image:
            image = ImageOps.exif_transpose(image)
            image = image.convert("RGB")
            width, height = image.size
            if not width or not height:
                return None
            current_ratio = width / height
            if current_ratio > COVER_RATIO:
                crop_width = int(height * COVER_RATIO)
                left = (width - crop_width) // 2
                image = image.crop((left, 0, left + crop_width, height))
            else:
                crop_height = int(width / COVER_RATIO)
                top = (height - crop_height) // 2
                image = image.crop((0, top, width, top + crop_height))
            image = image.resize(THUMBNAIL_SIZE, Image.LANCZOS)
            _write_atomic(image, target)
        return target
    except Exception:
        return None


def _video_placeholder(size: tuple[int, int] = THUMBNAIL_SIZE) -> Image.Image:
    image = Image.new("RGB", size, (28, 28, 30))
    draw = ImageDraw.Draw(image)
    center_x, center_y = size[0] // 2, size[1] // 2
    draw.polygon(
        [
            (center_x - 34, center_y - 52),
            (center_x - 34, center_y + 52),
            (center_x + 56, center_y),
        ],
        fill=(10, 132, 255),
    )
    return image


def video_thumbnail(source: Path, path: str, filename: str) -> Optional[Path]:
    try:
        stats = source.stat()
    except OSError:
        return None
    target = _cache_target("video", path, filename, stats)
    if target.is_file() and target.stat().st_size > 0:
        return target

    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg:
        try:
            subprocess.run(
                [
                    ffmpeg,
                    "-y",
                    "-ss",
                    "1",
                    "-i",
                    str(source),
                    "-frames:v",
                    "1",
                    "-vf",
                    "scale=400:-1",
                    str(target),
                ],
                capture_output=True,
                timeout=25,
            )
            if target.is_file() and target.stat().st_size > 0:
                return target
        except Exception:
            pass

    try:
        _write_atomic(_video_placeholder(), target)
        return target
    except Exception:
        return None
