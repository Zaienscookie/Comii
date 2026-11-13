"""Compatibility layer for the original browser UI.

The Web UI in ``web/static`` was written against the flat ``/api/*`` routes of
the first server. It still ships with Comii, so the same behaviours are
exposed here on top of the new service layer. The native app uses ``/api/v1``.
"""

from __future__ import annotations

import concurrent.futures
import socket
import time
from pathlib import Path

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from starlette.background import BackgroundTask

from ..config import settings
from ..models import NodeKind
from ..services import archive, importers, library, manage, media, media_trash, tags, thumbs, trash

router = APIRouter(prefix="/api", tags=["legacy-web"])

WEB_ROOT = settings.project_root / "web"
STATIC_ROOT = WEB_ROOT / "static"


def _legacy_thumb_url(path: str, name: str) -> str:
    return f"/api/thumb/{path}/{name}"


def _legacy_video_thumb_url(path: str, name: str) -> str:
    return f"/api/video_thumb/{path}/{name}"


def _series_kind(images: list[Path], videos: list[Path]) -> str:
    if images and videos:
        return "mixed"
    if videos:
        return "video"
    return "manga"


def _build_tree(directory: Path, prefix: str = "") -> list[dict]:
    items: list[dict] = []
    for entry in library.visible_directories(directory):
        rel = f"{prefix}/{entry.name}".lstrip("/")
        images, videos = library.list_media(entry)
        children = library.visible_directories(entry)

        if not children:
            if images or videos:
                custom = library.custom_cover_name(entry)
                if custom:
                    cover = _legacy_thumb_url(rel, custom)
                elif images:
                    cover = _legacy_thumb_url(rel, images[0].name)
                else:
                    cover = _legacy_video_thumb_url(rel, videos[0].name)
                items.append(
                    {
                        "name": entry.name,
                        "path": rel,
                        "kind": "series",
                        "subkind": _series_kind(images, videos),
                        "images": len(images),
                        "videos": len(videos),
                        "cover": cover,
                    }
                )
            else:
                items.append(
                    {
                        "name": entry.name,
                        "path": rel,
                        "kind": "folder",
                        "children": [],
                        "images": 0,
                        "videos": 0,
                        "cover": None,
                    }
                )
            continue

        # Album: has sub-folders, may also hold its own photos / videos.
        cover = None
        custom = library.custom_cover_name(entry)
        if custom:
            cover = _legacy_thumb_url(rel, custom)
        elif images:
            cover = _legacy_thumb_url(rel, images[0].name)
        elif videos:
            cover = _legacy_video_thumb_url(rel, videos[0].name)
        configured = library.album_cover_relative(entry)
        if configured:
            wanted = f"{rel}/{configured}"
            children_tree = _build_tree(entry, rel)
            cover = next((child.get("cover") for child in children_tree if child.get("path") == wanted), None) or cover
        else:
            children_tree = _build_tree(entry, rel)
        if not cover:
            cover = next((child.get("cover") for child in children_tree if child.get("cover")), None)
        items.append(
            {
                "name": entry.name,
                "path": rel,
                "kind": "folder",
                "children": children_tree,
                "images": len(images),
                "videos": len(videos),
                "forced_type": library.read_forced_kind(entry),
                "cover": cover,
                }
            )
    return items


# ------------------------------------------------------------------ pages
def _page(name: str) -> HTMLResponse:
    page = STATIC_ROOT / name
    if not page.is_file():
        raise HTTPException(status_code=404, detail="页面不存在")
    return HTMLResponse(page.read_text(encoding="utf-8"))


@router.get("/page/index", response_class=HTMLResponse)
def legacy_index() -> HTMLResponse:
    return _page("index.html")


# ------------------------------------------------------------------ data
@router.get("/tree")
def tree() -> dict:
    return {"tree": _build_tree(library.library_root())}


@router.get("/gallery")
def gallery(kind: str = "all", limit: int = 300, offset: int = 0) -> dict:
    rows, total = library.list_gallery(kind=kind, limit=limit, offset=offset)
    items = []
    for row in rows:
        if row["kind"] == "image":
            thumb = _legacy_thumb_url(row["series_path"], row["name"])
        else:
            thumb = _legacy_video_thumb_url(row["series_path"], row["name"])
        items.append(
            {
                "series": row["series_path"],
                "series_name": row["series_name"],
                "name": row["name"],
                "kind": row["kind"],
                "thumb": thumb,
                "index": row["index"],
                "mtime": row["mtime"],
            }
        )
    return {"items": items, "total": total}


@router.post("/cover")
def cover(payload: dict) -> dict:
    library.set_cover(str(payload.get("path", "")), str(payload.get("name", "")))
    return {"status": "ok"}


@router.get("/series/{series:path}")
def series(series: str) -> dict:
    detail = library.series_detail(series)
    return {"name": detail.name, "images": detail.images, "videos": detail.videos}


@router.get("/settings")
def settings_info() -> dict:
    return {"library": str(settings.library_root)}


@router.get("/search")
def search(q: str = "") -> dict:
    """Recursive search over folder names, series names and media file names."""
    items: list[dict] = []
    for node in library.search(q):
        if node.kind.value == "folder":
            items.append(
                {
                    "name": node.name,
                    "path": node.path,
                    "kind": "folder",
                    "children": [],
                    "child_count": node.child_count or 0,
                    "forced_type": None,
                }
            )
            continue
        directory = library.resolve_directory(node.path)
        images, videos = library.list_media(directory)
        cover = (
            _legacy_thumb_url(node.path, images[0].name)
            if images
            else _legacy_video_thumb_url(node.path, videos[0].name)
            if videos
            else None
        )
        items.append(
            {
                "name": node.name,
                "path": node.path,
                "kind": "series",
                "subkind": node.kind.value,
                "images": len(images),
                "videos": len(videos),
                "cover": cover,
            }
        )
    return {"query": q, "items": items, "total": len(items)}


@router.get("/ping")
def ping() -> dict:
    return {
        "status": "ok",
        "app": settings.app_name.lower(),
        "version": settings.version,
        "library": str(settings.library_root),
    }


@router.get("/logs")
def logs(lines: int = 100) -> dict:
    log_file = settings.log_root / "comii.log"
    if not log_file.is_file():
        return {"logs": []}
    try:
        content = log_file.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        return {"logs": [], "error": str(exc)}
    tail = content[-max(0, lines):] if lines else content
    return {"logs": list(reversed(tail))}


# ------------------------------------------------------------------ media
@router.get("/img/{series:path}/{filename}")
def image(request: Request, series: str, filename: str) -> Response:
    source = library.resolve_file(series, filename)
    if source.suffix.lower() == ".svg":
        return FileResponse(source, media_type="image/svg+xml")
    return media.file_response(source, request)


@router.get("/thumb/{series:path}/{filename}")
def thumb(request: Request, series: str, filename: str) -> Response:
    source = library.resolve_file(series, filename)
    thumbnail = thumbs.image_thumbnail(source, series, filename)
    if thumbnail is not None:
        return FileResponse(thumbnail, media_type="image/jpeg")
    return media.file_response(source, request)


@router.get("/video/{series:path}/{filename}")
def video(request: Request, series: str, filename: str) -> Response:
    source = library.resolve_file(series, filename)
    return media.file_response(source, request)


@router.get("/video_thumb/{series:path}/{filename}")
def video_thumb(series: str, filename: str) -> Response:
    source = library.resolve_file(series, filename)
    thumbnail = thumbs.video_thumbnail(source, series, filename)
    if thumbnail is not None:
        return FileResponse(thumbnail, media_type="image/jpeg")
    return FileResponse(source)


# ---------------------------------------------------------------- folders
@router.post("/folder")
def create_folder(payload: dict) -> dict:
    created = manage.create_folder(
        str(payload.get("parent", "")),
        str(payload.get("name", "")),
        str(payload.get("kind", "")),
    )
    return {"status": "ok", "path": created}


@router.post("/rename")
def rename(payload: dict) -> dict:
    name = str(payload.get("name", ""))
    parent = str(payload.get("from", ""))
    new_name = str(payload.get("newName", ""))
    path = f"{parent}/{name}".lstrip("/") if parent else name
    renamed = manage.rename(path, new_name)
    return {"status": "ok", "renamed": True, "newName": renamed.split("/")[-1]}


@router.post("/move")
def move(payload: dict) -> dict:
    destination = str(payload.get("to", ""))
    raw_items = payload.get("items")
    if isinstance(raw_items, list) and raw_items:
        paths = [
            (f"{item.get('from', '')}/{item.get('name', '')}".lstrip("/"))
            for item in raw_items
        ]
    else:
        parent = str(payload.get("from", ""))
        name = str(payload.get("name", ""))
        paths = [f"{parent}/{name}".lstrip("/") if parent else name]

    result = manage.move(paths, destination)
    if result.failed:
        return JSONResponse(
            status_code=200,
            content={
                "status": "partial",
                "moved": len(result.succeeded),
                "errors": [failure["message"] for failure in result.failed],
            },
        )
    return {"status": "ok", "moved": len(result.succeeded), "to": destination}


@router.delete("/delete/{series:path}")
def delete(series: str) -> dict:
    entry = trash.soft_delete(series)
    return {
        "status": "ok",
        "series": entry.original_path,
        "delete_at": entry.expires_at,
    }


@router.get("/trash")
def trash_list() -> dict:
    now = time.time()
    return {
        "items": [
            {
                "name": entry.original_path,
                "delete_at": entry.expires_at,
                "remaining_seconds": max(0, entry.expires_at - now),
            }
            for entry in trash.list_entries()
        ]
    }


def _entry_id_for_path(series: str) -> str:
    normalized = library.normalize_path(series)
    for entry in trash.list_entries():
        if entry.original_path == normalized:
            return entry.id
    raise HTTPException(status_code=404, detail="回收站中没有该项目")


@router.post("/trash/restore/{series:path}")
def trash_restore(series: str, purge: int = 0) -> dict:
    entry_id = _entry_id_for_path(series)
    if purge:
        trash.purge(entry_id)
        return {"status": "ok", "series": series, "purged": True}
    restored = trash.restore(entry_id)
    return {"status": "ok", "series": restored}


@router.get("/trash/sweep")
def trash_sweep() -> dict:
    removed = trash.sweep()
    return {"status": "ok", "removed": removed}


@router.post("/import/upload")
async def import_upload(
    files: list[UploadFile] = File(...),
    folder: str = Form(""),
    name: str = Form(""),
) -> dict:
    result = importers.import_uploads(files, folder, name)
    return {
        "status": "ok",
        "series": result.name,
        "path": result.path,
        "pages": result.pages,
        "images": result.images,
        "videos": result.videos,
        "total_entries": result.total_entries,
        "skipped": result.skipped,
        "skipped_types": result.skipped_types,
    }


# ---------------------------------------------------------------- import
@router.post("/import/pdf")
async def import_pdf(
    request: Request,
    file: UploadFile = File(...),
    folder: str = Form(""),
    name: str = Form(""),
    dpi: int = 200,
) -> dict:
    payload = await file.read()
    if not payload:
        raise HTTPException(status_code=400, detail="PDF 文件为空")
    result = importers.import_pdf(
        payload,
        folder,
        name or Path(file.filename or "document").stem,
        dpi=max(72, min(600, dpi)),
    )
    return {
        "status": "ok",
        "series": result.name,
        "pages": result.pages,
        "path": result.path,
    }


@router.get("/tags")
def legacy_tags() -> dict:
    return {"tags": tags.all_tags()}


@router.get("/tagged")
def legacy_tagged(tag: str = "") -> dict:
    items = []
    for path in tags.series_paths(tag):
        node = library.node_for_path(path)
        if node is not None:
            items.append(
                {
                    "name": node.name,
                    "path": node.path,
                    "kind": "folder" if (node.child_count or 0) > 0 else "series",
                    "cover": node.cover,
                    "images": node.image_count,
                    "videos": node.video_count,
                }
            )
    return {"tag": tag, "items": items, "total": len(items)}


@router.post("/media/delete")
async def legacy_media_delete(payload: dict) -> dict:
    deleted = media_trash.delete_files(
        str(payload.get("path", "")),
        [str(name) for name in (payload.get("names") or [])],
    )
    return {"status": "ok", "deleted": deleted}


@router.get("/export")
def export(target: str, include_video: bool = False) -> FileResponse:
    handle, filename = archive.build_export(target, include_video=include_video)
    return FileResponse(
        handle,
        media_type="application/zip",
        filename=filename,
        background=BackgroundTask(lambda: handle.unlink(missing_ok=True)),
    )


# ------------------------------------------------------------------ scan
@router.get("/scan")
def scan(port: int = 8000, timeout: float = 0.12) -> dict:
    if not 1 <= port <= 65535:
        raise HTTPException(status_code=400, detail="端口不合法")
    try:
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.connect(("8.8.8.8", 80))
        local_ip = probe.getsockname()[0]
        probe.close()
    except OSError:
        local_ip = "127.0.0.1"
    prefix = ".".join(local_ip.split(".")[:3])

    def check(host: str) -> dict:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        try:
            ok = sock.connect_ex((host, port)) == 0
        except OSError:
            ok = False
        finally:
            sock.close()
        return {"host": host, "port": port, "ok": ok}

    hosts = [f"{prefix}.{index}" for index in range(1, 255)]
    nodes: list[dict] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as pool:
        for result in pool.map(check, hosts):
            if result["ok"]:
                nodes.append(result)
    return {"local_ip": local_ip, "port": port, "nodes": nodes}
