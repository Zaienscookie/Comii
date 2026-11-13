"""Library scanning, path safety and node construction."""

from __future__ import annotations

import re
import time
from pathlib import Path
from typing import Iterable, Optional
from urllib.parse import urlencode

from fastapi import HTTPException

from ..config import settings
from ..models import BrowseResponse, LibraryNode, NodeKind, SeriesDetail

IMAGE_EXTENSIONS = frozenset(
    {".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".avif", ".svg"}
)
VIDEO_EXTENSIONS = frozenset(
    {".mp4", ".webm", ".mov", ".mkv", ".m4v", ".avi", ".ogv", ".ts"}
)
KIND_MARKER = ".comii_type"
LEGACY_KIND_MARKER = ".komi_type"
COVER_MARKER = ".comii_cover"
VALID_KINDS = {"manga", "video", "mixed"}


def natural_sort_key(name: str) -> list[object]:
    return [int(part) if part.isdigit() else part.lower() for part in re.split(r"(\d+)", name)]


def is_image(path: Path) -> bool:
    return path.suffix.lower() in IMAGE_EXTENSIONS


def is_video(path: Path) -> bool:
    return path.suffix.lower() in VIDEO_EXTENSIONS


def split_relative_path(raw: str) -> list[str]:
    """Split a client supplied relative path and reject anything dangerous."""
    text = str(raw or "").replace("\\", "/").strip()
    parts = [part for part in text.split("/") if part not in ("", ".")]
    for part in parts:
        if part == ".." or part.startswith(".") or ":" in part:
            raise HTTPException(status_code=400, detail="路径不合法")
    return parts


def normalize_path(raw: str) -> str:
    return "/".join(split_relative_path(raw))


def library_root() -> Path:
    return settings.library_root.resolve()


def _ensure_inside(target: Path, root: Path) -> Path:
    resolved = target.resolve()
    if resolved != root and root not in resolved.parents:
        raise HTTPException(status_code=400, detail="路径不合法")
    return resolved


def resolve_directory(raw: str) -> Path:
    root = library_root()
    parts = split_relative_path(raw)
    target = _ensure_inside(root.joinpath(*parts), root)
    if not target.is_dir():
        raise HTTPException(status_code=404, detail="目录不存在")
    return target


def resolve_file(raw_directory: str, filename: str) -> Path:
    if not filename or filename in (".", "..") or "/" in filename or "\\" in filename:
        raise HTTPException(status_code=400, detail="文件名不合法")
    base = resolve_directory(raw_directory)
    target = _ensure_inside(base / filename, base)
    if not target.is_file():
        raise HTTPException(status_code=404, detail="文件不存在")
    return target


def media_url(kind: str, path: str, filename: str) -> str:
    query = urlencode({"path": path, "name": filename})
    return f"{settings.api_prefix}/media/{kind}?{query}"


def list_media(directory: Path) -> tuple[list[Path], list[Path]]:
    images: list[Path] = []
    videos: list[Path] = []
    try:
        entries = list(directory.iterdir())
    except OSError:
        return images, videos
    for entry in entries:
        if not entry.is_file():
            continue
        if is_image(entry):
            images.append(entry)
        elif is_video(entry):
            videos.append(entry)
    images.sort(key=lambda item: natural_sort_key(item.name))
    videos.sort(key=lambda item: natural_sort_key(item.name))
    return images, videos


def _kind_for(images: list[Path], videos: list[Path]) -> NodeKind:
    if images and videos:
        return NodeKind.mixed
    if videos:
        return NodeKind.video
    return NodeKind.manga


def read_forced_kind(directory: Path) -> Optional[str]:
    for marker in (KIND_MARKER, LEGACY_KIND_MARKER):
        candidate = directory / marker
        try:
            if candidate.is_file():
                value = candidate.read_text(encoding="utf-8").strip().lower()
                if value in VALID_KINDS:
                    return value
        except OSError:
            continue
    return None


def write_kind_marker(directory: Path, kind: str) -> None:
    if kind in VALID_KINDS:
        (directory / KIND_MARKER).write_text(kind, encoding="utf-8")


ALBUM_COVER_MARKER = ".comii_album_cover"


def album_cover_relative(directory: Path) -> Optional[str]:
    """Custom album cover: a relative child path stored in .comii_album_cover."""
    marker = directory / ALBUM_COVER_MARKER
    try:
        if not marker.is_file():
            return None
        raw = marker.read_text(encoding="utf-8").strip()
    except OSError:
        return None
    if not raw:
        return None
    parts = [part for part in raw.replace("\\", "/").split("/") if part not in ("", ".")]
    if not parts or any(part == ".." for part in parts):
        return None
    candidate = directory.joinpath(*parts)
    if not candidate.is_dir():
        return None
    return "/".join(parts)


def album_cover_child(directory: Path) -> Optional[Path]:
    relative = album_cover_relative(directory)
    if relative is None:
        return None
    return directory.joinpath(*relative.split("/"))


def set_album_cover(raw_folder: str, raw_series: str) -> None:
    """Pick one series inside a folder as the folder (album) cover."""
    folder = resolve_directory(raw_folder)
    marker = folder / ALBUM_COVER_MARKER
    clean = str(raw_series or "").strip().strip("/")
    if not clean:
        if marker.is_file():
            marker.unlink()
        return
    series = resolve_directory(clean)
    if folder not in series.parents:
        raise HTTPException(status_code=400, detail="只能选择这个文件夹里的系列作为封面")
    relative = series.relative_to(folder).as_posix()
    try:
        marker.write_text(relative, encoding="utf-8")
    except OSError:
        raise HTTPException(status_code=400, detail="这个文件夹是只读的，无法设置封面")


def first_series_cover(directory: Path, path: str, depth: int = 0) -> Optional[str]:
    """Depth-first lookup of the first available series cover below a folder."""
    if depth > 4:
        return None
    for child in visible_directories(directory):
        child_path = f"{path}/{child.name}".lstrip("/")
        node = _series_node(child, child_path)
        if node is not None and node.cover:
            return node.cover
        nested = first_series_cover(child, child_path, depth + 1)
        if nested:
            return nested
    return None


def custom_cover_name(directory: Path) -> Optional[str]:
    """A user-picked cover stored in <series>/.comii_cover (file name)."""
    marker = directory / COVER_MARKER
    try:
        if not marker.is_file():
            return None
        name = marker.read_text(encoding="utf-8").strip()
    except OSError:
        return None
    if not name or "/" in name or "\\" in name:
        return None
    candidate = directory / name
    if candidate.is_file() and is_image(candidate):
        return name
    return None


def set_cover(raw_path: str, name: str) -> None:
    directory = resolve_directory(raw_path)
    marker = directory / COVER_MARKER
    clean = str(name or "").strip()
    if not clean:
        if marker.is_file():
            marker.unlink()
        return
    if "/" in clean or "\\" in clean or clean in (".", ".."):
        raise HTTPException(status_code=400, detail="封面文件名不合法")
    candidate = directory / clean
    if not candidate.is_file() or not is_image(candidate):
        raise HTTPException(status_code=404, detail="封面图片不存在")
    marker.write_text(clean, encoding="utf-8")


def _cover_for(
    path: str,
    images: list[Path],
    videos: list[Path],
    directory: Optional[Path] = None,
) -> Optional[str]:
    if directory is not None:
        custom = custom_cover_name(directory)
        if custom:
            return media_url("thumb", path, custom)
    if images:
        return media_url("thumb", path, images[0].name)
    if videos:
        return media_url("video_thumb", path, videos[0].name)
    return None


def _latest_mtime(directory: Path) -> Optional[float]:
    try:
        return max((entry.stat().st_mtime for entry in directory.iterdir()), default=None)
    except OSError:
        return None


def _series_node(directory: Path, path: str) -> Optional[LibraryNode]:
    images, videos = list_media(directory)
    if not images and not videos:
        return None
    kind = _kind_for(images, videos)
    forced = read_forced_kind(directory)
    if forced:
        kind = NodeKind(forced)
    size = 0
    for item in images + videos:
        try:
            size += item.stat().st_size
        except OSError:
            pass
    return LibraryNode(
        name=directory.name,
        path=path,
        kind=kind,
        cover=_cover_for(path, images, videos, directory),
        image_count=len(images),
        video_count=len(videos),
        updated_at=_latest_mtime(directory),
        size=size,
    )


def visible_directories(directory: Path) -> list[Path]:
    try:
        entries = [
            entry
            for entry in directory.iterdir()
            if entry.is_dir() and not entry.name.startswith(".")
        ]
    except OSError:
        return []
    entries.sort(key=lambda item: natural_sort_key(item.name))
    return entries


def _iter_series(directory: Path, prefix: str = "") -> Iterable[tuple[Path, str]]:
    for child in visible_directories(directory):
        child_path = f"{prefix}/{child.name}".lstrip("/")
        images, videos = list_media(child)
        if images or videos:
            yield child, child_path
        else:
            yield from _iter_series(child, child_path)


def browse(raw_path: str = "") -> BrowseResponse:
    parts = split_relative_path(raw_path)
    normalized = "/".join(parts)
    base = library_root() if not parts else resolve_directory(normalized)

    items: list[LibraryNode] = []
    for directory in visible_directories(base):
        child_path = f"{normalized}/{directory.name}".lstrip("/")
        series = _series_node(directory, child_path)
        if series is not None:
            items.append(series)
            continue

        children = visible_directories(directory)
        # Empty folders are real folders the user just created: keep them visible
        # so uploads can target them.
        cover = None
        album = album_cover_child(directory)
        if album is not None:
            album_relative = album.relative_to(directory).as_posix()
            album_path = f"{child_path}/{album_relative}".lstrip("/")
            preview = _series_node(album, album_path)
            cover = preview.cover if preview is not None else first_series_cover(album, album_path)
        if cover is None:
            cover = first_series_cover(directory, child_path)

        items.append(
            LibraryNode(
                name=directory.name,
                path=child_path,
                kind=NodeKind.folder,
                cover=cover,
                child_count=len(children),
                forced_kind=read_forced_kind(directory),
            )
        )

    parent = "/".join(parts[:-1]) if parts else None
    return BrowseResponse(path=normalized, parent=parent, items=items, total=len(items))


def series_detail(raw_path: str, progress_lookup=None) -> SeriesDetail:
    directory = resolve_directory(raw_path)
    images, videos = list_media(directory)
    if not images and not videos:
        raise HTTPException(status_code=404, detail="该目录不是漫画/视频系列")
    normalized = normalize_path(raw_path)
    kind = _kind_for(images, videos)
    forced = read_forced_kind(directory)
    if forced:
        kind = NodeKind(forced)
    progress = progress_lookup(normalized) if progress_lookup else None
    return SeriesDetail(
        name=directory.name,
        path=normalized,
        kind=kind,
        cover=_cover_for(normalized, images, videos, directory),
        images=[item.name for item in images],
        videos=[item.name for item in videos],
        image_count=len(images),
        video_count=len(videos),
        progress=progress,
    )


def library_stats() -> tuple[int, int, int, int, int]:
    """Return (series, folders, images, videos, bytes)."""
    series_count = 0
    image_count = 0
    video_count = 0
    total_bytes = 0
    folder_count = 0
    root = library_root()
    for directory, _ in _iter_series(root):
        images, videos = list_media(directory)
        series_count += 1
        image_count += len(images)
        video_count += len(videos)
        for item in images + videos:
            try:
                total_bytes += item.stat().st_size
            except OSError:
                pass
    for entry in root.rglob("*"):
        if entry.is_dir() and not entry.name.startswith("."):
            folder_count += 1
    return series_count, folder_count, image_count, video_count, total_bytes


def unix_now() -> float:
    return time.time()


def search(raw_query: str, limit: int = 200) -> list[LibraryNode]:
    """Recursively match folder names, series names and media file names."""
    query = str(raw_query or "").strip().lower()
    if not query:
        return []
    limit = max(1, min(int(limit or 200), 500))
    results: list[LibraryNode] = []

    def walk(directory: Path, prefix: str) -> None:
        if len(results) >= limit:
            return
        for child in visible_directories(directory):
            if len(results) >= limit:
                return
            child_path = f"{prefix}/{child.name}".lstrip("/")
            images, videos = list_media(child)
            if images or videos:
                matched = query in child.name.lower() or any(
                    query in item.name.lower() for item in images + videos
                )
                if matched:
                    node = _series_node(child, child_path)
                    if node is not None:
                        results.append(node)
                continue
            if query in child.name.lower():
                children = visible_directories(child)
                results.append(
                    LibraryNode(
                        name=child.name,
                        path=child_path,
                        kind=NodeKind.folder,
                        child_count=len(children),
                        forced_kind=read_forced_kind(child),
                    )
                )
            walk(child, child_path)

    walk(library_root(), "")
    return results


def node_for_path(raw_path: str) -> Optional[LibraryNode]:
    """Rebuild a LibraryNode for one relative path (tag results, deep links)."""
    parts = split_relative_path(raw_path)
    if not parts:
        return None
    normalized = "/".join(parts)
    try:
        directory = resolve_directory(normalized)
    except HTTPException:
        return None
    node = _series_node(directory, normalized)
    if node is not None:
        return node
    children = visible_directories(directory)
    cover = None
    album = album_cover_child(directory)
    if album is not None:
        album_relative = album.relative_to(directory).as_posix()
        album_path = f"{normalized}/{album_relative}"
        preview = _series_node(album, album_path)
        cover = preview.cover if preview is not None else first_series_cover(album, album_path)
    if cover is None:
        cover = first_series_cover(directory, normalized)
    return LibraryNode(
        name=directory.name,
        path=normalized,
        kind=NodeKind.folder,
        cover=cover,
        child_count=len(children),
        forced_kind=read_forced_kind(directory),
    )


def collect_series(raw_path: str = "") -> list[LibraryNode]:
    """Every series below a folder (recursive), used for whole-folder downloads."""
    parts = split_relative_path(raw_path)
    normalized = "/".join(parts)
    base = library_root() if not parts else resolve_directory(normalized)
    items: list[LibraryNode] = []

    def walk(directory: Path, prefix: str) -> None:
        for child in visible_directories(directory):
            child_path = f"{prefix}/{child.name}".lstrip("/")
            images, videos = list_media(child)
            if images or videos:
                node = _series_node(child, child_path)
                if node is not None:
                    items.append(node)
                continue
            walk(child, child_path)

    walk(base, normalized)
    return items


def list_gallery(
    kind: str = "all",
    limit: int = 120,
    offset: int = 0,
    scope: str = "",
    sort: str = "recent",
) -> tuple[list[dict], int]:
    """Flat listing of images and videos, optionally scoped to one folder.

    * ``sort="recent"`` – camera-roll order (newest file first), used by 全部.
    * ``sort="name"``   – folder order then page order, used by the "全部排列"
      view inside a folder so readers can scroll a whole author start to finish.
    """
    wanted = kind if kind in {"image", "video"} else "all"
    rows: list[dict] = []
    parts = split_relative_path(scope)
    normalized = "/".join(parts)
    base = library_root() if not parts else resolve_directory(normalized)

    for directory, rel in _iter_series(base, normalized):
        images, videos = list_media(directory)
        series_name = directory.name
        if wanted in ("all", "image"):
            for index, item in enumerate(images):
                try:
                    mtime = item.stat().st_mtime
                except OSError:
                    mtime = 0
                rows.append(
                    {
                        "series_path": rel,
                        "series_name": series_name,
                        "name": item.name,
                        "kind": "image",
                        "thumb": media_url("thumb", rel, item.name),
                        "index": index,
                        "mtime": mtime,
                    }
                )
        if wanted in ("all", "video"):
            for index, item in enumerate(videos):
                try:
                    mtime = item.stat().st_mtime
                except OSError:
                    mtime = 0
                rows.append(
                    {
                        "series_path": rel,
                        "series_name": series_name,
                        "name": item.name,
                        "kind": "video",
                        "thumb": media_url("video_thumb", rel, item.name),
                        "index": index,
                        "mtime": mtime,
                    }
                )

    if sort != "name":
        rows.sort(key=lambda row: row["mtime"], reverse=True)
    total = len(rows)
    start = max(0, offset)
    end = start + max(1, min(limit, 500))
    return rows[start:end], total


