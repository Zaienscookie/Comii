"""Comii v1 API used by the native app (and any other client)."""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, Response
from starlette.background import BackgroundTask

from .. import auth
from ..config import settings
from ..models import (
    ActionResult,
    AlbumCoverRequest,
    AppUpdateInfo,
    BatchResult,
    BrowseResponse,
    CollectionResponse,
    CreateFolderRequest,
    GalleryItem,
    GalleryResponse,
    ImportResult,
    MediaDeleteRequest,
    MoveRequest,
    PingResponse,
    ProgressUpdate,
    ReadingProgress,
    RenameRequest,
    SearchResponse,
    SeriesDetail,
    SeriesTagsResponse,
    SetCoverRequest,
    SetFolderKindRequest,
    StatsResponse,
    TagCount,
    TagCreateRequest,
    TaggedResponse,
    TagsResponse,
    TagsUpdateRequest,
    TrashEntry,
    TrashListResponse,
)
from ..services import (
    archive,
    images,
    importers,
    library,
    manage,
    media,
    media_trash,
    progress,
    tags,
    thumbs,
    trash,
    updates,
)
from ..services import pdfs, personas

router = APIRouter(prefix=settings.api_prefix, tags=["comii"])

FEATURES = [
    "browse",
    "reader",
    "video",
    "thumbnails",
    "gallery",
    "cover",
    "folders",
    "rename",
    "move",
    "trash",
    "upload",
    "pdf",
    "export",
    "progress",
    "stats",
    "logs",
    "quality",
    "tags",
]


@router.get("/ping", response_model=PingResponse)
def ping() -> PingResponse:
    return PingResponse(
        app=settings.app_name,
        version=settings.version,
        library="",
        server_time=time.time(),
        features=FEATURES,
    )


# ---------------------------------------------------------------- browsing
@router.get("/browse", response_model=BrowseResponse)
def browse(request: Request, path: str = "") -> BrowseResponse:
    user = auth.require_user(request)
    if (path == "收藏夹" or path.startswith("收藏夹/")) and user["role"] not in ("owner", "admin"):
        raise HTTPException(status_code=403, detail="收藏夹仅管理员可用")
    if not auth.can_see_path(user, path):
        raise HTTPException(status_code=403, detail="没有查看权限")
    data = library.browse(path)
    items = [node for node in auth.filter_nodes(user, data.items) if node.name != "收藏夹" or user["role"] in ("owner", "admin")]
    return BrowseResponse(path=data.path, parent=data.parent, items=items, total=len(items))


@router.get("/series", response_model=SeriesDetail)
def series(request: Request, path: str) -> SeriesDetail:
    auth.require_path(request, path, "view")
    detail = library.series_detail(path, progress_lookup=progress.get)
    detail.tags = tags.tags_for(detail.path)
    return detail


@router.get("/search", response_model=SearchResponse)
def search(request: Request, q: str = "", limit: int = 200) -> SearchResponse:
    user = auth.require_user(request)
    items = auth.filter_nodes(user, library.search(q, limit=limit))
    return SearchResponse(query=q, items=items, total=len(items))


@router.get("/collection", response_model=CollectionResponse)
def collection(request: Request, path: str = "") -> CollectionResponse:
    user = auth.require_user(request)
    if not auth.can_see_path(user, path):
        raise HTTPException(status_code=403, detail="没有查看权限")
    normalized = library.normalize_path(path)
    items = auth.filter_nodes(user, library.collect_series(normalized))
    return CollectionResponse(path=normalized, items=items, total=len(items))


@router.get("/folders")
def folders(request: Request) -> dict:
    user = auth.require_user(request)
    items = [item for item in library.folder_tree() if auth.can_see_path(user, item["path"])]
    return {"items": items}


@router.get("/gallery", response_model=GalleryResponse)
def gallery(
    request: Request,
    kind: str = "all",
    limit: int = 120,
    offset: int = 0,
    path: str = "",
    sort: str = "recent",
) -> GalleryResponse:
    user = auth.require_user(request)
    if not auth.can_see_path(user, path):
        raise HTTPException(status_code=403, detail="没有查看权限")
    rows, total = library.list_gallery(
        kind=kind, limit=limit, offset=offset, scope=path, sort=sort
    )
    rows = [row for row in rows if auth.can(user, row["series_path"], "view")]
    return GalleryResponse(
        items=[GalleryItem(**row) for row in rows],
        total=total,
        offset=offset,
        limit=limit,
    )


@router.post("/cover", response_model=ActionResult)
def set_cover(request: Request, payload: SetCoverRequest) -> ActionResult:
    auth.require_path(request, payload.path, "manage")
    library.set_cover(payload.path, payload.name)
    return ActionResult(status="ok", path=payload.path)


@router.post("/favorites", response_model=ActionResult)
def add_favorite(request: Request, payload: dict) -> ActionResult:
    """Create a symlink under <library>/收藏夹/<collection>/.

    Body: {"path": "album/or/series", "name": "optional file name", "collection": "默认"}
    """
    raw_path = str(payload.get("path") or "").strip()
    name = str(payload.get("name") or "").strip()
    auth.require_path(request, raw_path, "manage")
    collection = str(payload.get("collection") or "默认").strip() or "默认"
    collection = Path(collection).name
    root = library.library_root()
    if name:
        source = library.resolve_file(raw_path, name)
    else:
        source = library.resolve_directory(raw_path)
    try:
        source.relative_to(root)
    except ValueError:
        raise HTTPException(status_code=400, detail="只能收藏媒体库内的内容")
    favorite_root = root / "收藏夹"
    if favorite_root == source or favorite_root in source.parents:
        raise HTTPException(status_code=400, detail="不能收藏收藏夹本身")
    target_dir = favorite_root / collection
    target_dir.mkdir(parents=True, exist_ok=True)
    source_real = os.path.realpath(source)
    for existing in target_dir.iterdir():
        if existing.is_symlink() and os.path.realpath(existing) == source_real:
            return ActionResult(status="ok", path=str(existing.relative_to(root)))
    target = target_dir / source.name
    counter = 1
    while target.exists() or target.is_symlink():
        target = target_dir / f"{source.stem}_{counter}{source.suffix}"
        counter += 1
    os.symlink(source, target)
    return ActionResult(status="ok", path=str(target.relative_to(root)))


@router.delete("/favorites/collection", response_model=ActionResult)
def delete_favorite_collection(request: Request, name: str) -> ActionResult:
    """删掉一个收藏夹分类（只允许删 收藏夹 下的子目录）。"""
    user = auth.require_user(request)
    if user["role"] not in ("owner", "admin"):
        raise HTTPException(status_code=403, detail="没有权限")
    collection = Path(str(name or "")).name.strip()
    if not collection or collection in (".", ".."):
        raise HTTPException(status_code=400, detail="分类名不合法")
    root = library.library_root() / "收藏夹" / collection
    if not root.is_dir():
        raise HTTPException(status_code=404, detail="分类不存在")
    import shutil
    shutil.rmtree(root, ignore_errors=True)
    return ActionResult(status="ok", message=f"已删除收藏夹「{collection}」")


@router.post("/favorites/remove", response_model=ActionResult)
def remove_favorite_post(request: Request, payload: dict) -> ActionResult:
    return remove_favorite(request, str(payload.get("path") or ""), str(payload.get("name") or ""))


@router.delete("/favorites", response_model=ActionResult)
def remove_favorite(request: Request, path: str, name: str = "") -> ActionResult:
    """取消收藏：删掉 收藏夹 里指向这个文件/目录的链接。"""
    auth.require_path(request, path, "manage")
    root = library.library_root() / "收藏夹"
    source = library.resolve_file(path, name) if name else library.resolve_directory(path)
    target = os.path.realpath(source)
    removed = 0
    if root.is_dir():
        for collection in root.iterdir():
            if not collection.is_dir() or collection.name.startswith("."):
                continue
            for entry in collection.iterdir():
                try:
                    if os.path.realpath(entry) == target:
                        entry.unlink()
                        removed += 1
                except OSError:
                    continue
    if removed == 0:
        raise HTTPException(status_code=404, detail="没有找到对应的收藏")
    return ActionResult(status="ok", message=f"已取消收藏（{removed} 处）")


# ---------------------------------------------------------------- highlights
def _highlights_file():
    return settings.data_root / "highlights.json"


def _load_highlights() -> dict:
    try:
        return json.loads(_highlights_file().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _save_highlights(data: dict) -> None:
    settings.data_root.mkdir(parents=True, exist_ok=True)
    _highlights_file().write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


@router.get("/highlights")
def list_highlights(request: Request, path: str, name: str) -> dict:
    auth.require_user(request)
    key = f"{path}|{name}"
    return {"items": _load_highlights().get(key, [])}


@router.post("/highlights")
def add_highlight(request: Request, payload: dict) -> dict:
    auth.require_path(request, str(payload.get("path") or ""), "manage")
    key = f"{payload.get('path')}|{payload.get('name')}"
    time_ms = int(payload.get("time_ms") or 0)
    label = str(payload.get("label") or "").strip()[:40]
    data = _load_highlights()
    items = [item for item in data.get(key, []) if abs(int(item.get("time_ms", 0)) - time_ms) > 1500]
    items.append({"time_ms": time_ms, "label": label})
    items.sort(key=lambda item: item["time_ms"])
    data[key] = items
    _save_highlights(data)
    return {"items": items}


@router.delete("/highlights")
def delete_highlight(request: Request, path: str, name: str, time_ms: int) -> dict:
    auth.require_path(request, path, "manage")
    key = f"{path}|{name}"
    data = _load_highlights()
    data[key] = [item for item in data.get(key, []) if int(item.get("time_ms", 0)) != time_ms]
    _save_highlights(data)
    return {"items": data[key]}


@router.post("/highlights/delete")
def delete_highlight_post(request: Request, payload: dict) -> dict:
    return delete_highlight(
        request,
        str(payload.get("path") or ""),
        str(payload.get("name") or ""),
        int(payload.get("time_ms") or 0),
    )


@router.post("/favorites/collection/delete", response_model=ActionResult)
def delete_favorite_collection_post(request: Request, payload: dict) -> ActionResult:
    return delete_favorite_collection(request, str(payload.get("name") or ""))


@router.get("/favorites/status")
def favorite_status(request: Request, path: str = "", name: str = "") -> dict:
    user = auth.require_user(request)
    if path:
        auth.require_path(request, path, "view")
    root = library.library_root()
    favorite_root = root / "收藏夹"
    collections = []
    if favorite_root.is_dir():
        for entry in sorted(favorite_root.iterdir(), key=lambda item: item.name):
            if entry.is_dir() and not entry.name.startswith("."):
                collections.append(entry.name)
    favorited = False
    if path and name:
        try:
            source = library.resolve_file(path, name)
            source_real = os.path.realpath(source)
            for collection in collections:
                for entry in (favorite_root / collection).iterdir():
                    if entry.is_symlink() and os.path.realpath(entry) == source_real:
                        favorited = True
                        break
                if favorited:
                    break
        except Exception:
            pass
    return {"favorited": favorited, "collections": collections}


@router.get("/stats", response_model=StatsResponse)
def stats(request: Request) -> StatsResponse:
    auth.require_role(request, "owner", "admin")
    series_count, folder_count, images, videos, total_bytes = library.library_stats()
    return StatsResponse(
        series_count=series_count,
        folder_count=folder_count,
        image_count=images,
        video_count=videos,
        total_bytes=total_bytes,
        library=str(settings.library_root),
    )


# ------------------------------------------------------------------ media
@router.get("/media/image")
def media_image(request: Request, path: str, name: str) -> Response:
    auth.require_path(request, path, "download")
    source = library.resolve_file(path, name)
    if source.suffix.lower() == ".svg":
        return FileResponse(source, media_type="image/svg+xml")
    return media.file_response(source, request)


@router.get("/media/info")
def media_info(request: Request, path: str, name: str) -> dict:
    """Basic file info + EXIF metadata for the app's detail sheet."""
    auth.require_path(request, path, "view")
    source = library.resolve_file(path, name)
    stat = source.stat()
    payload = {
        "path": path,
        "name": name,
        "size": stat.st_size,
        "mtime": library.safe_mtime(source),
        "kind": "video" if library.is_video(source) else "image",
        "width": None,
        "height": None,
        "format": source.suffix.lower().lstrip("."),
        "camera": "",
        "date_taken": "",
        "exposure": "",
        "f_number": "",
        "iso": "",
        "focal_length": "",
        "lens": "",
    }
    if library.is_image(source):
        try:
            from PIL import Image

            with Image.open(source) as image:
                payload["width"], payload["height"] = image.size
                payload["format"] = (image.format or payload["format"]).lower()
                exif = image.getexif()
                payload["camera"] = " ".join(
                    str(exif.get(tag) or "").strip() for tag in (271, 272)
                ).strip()
                payload["date_taken"] = str(exif.get(36867) or exif.get(306) or "")
                exposure = exif.get(33434)
                if exposure:
                    try:
                        payload["exposure"] = f"{float(exposure):g}s"
                    except Exception:
                        payload["exposure"] = str(exposure)
                f_number = exif.get(33437)
                if f_number:
                    try:
                        payload["f_number"] = f"f/{float(f_number):g}"
                    except Exception:
                        payload["f_number"] = str(f_number)
                iso = exif.get(34855)
                if iso:
                    payload["iso"] = str(iso)
                focal = exif.get(37386)
                if focal:
                    try:
                        payload["focal_length"] = f"{float(focal):g}mm"
                    except Exception:
                        payload["focal_length"] = str(focal)
                payload["lens"] = str(exif.get(42036) or "")
        except Exception:
            pass
    return payload


@router.get("/media/thumb")
def media_thumb(request: Request, path: str, name: str) -> Response:
    auth.require_path(request, path, "view")
    source = library.resolve_file(path, name)
    if source.suffix.lower() == ".svg":
        return FileResponse(source, media_type="image/svg+xml")
    thumbnail = thumbs.image_thumbnail(source, path, name)
    if thumbnail is not None:
        return FileResponse(
            thumbnail,
            media_type="image/jpeg",
            headers={"Cache-Control": "public, max-age=86400"},
        )
    return media.file_response(source, request)


@router.get("/media/view")
def media_view(request: Request, path: str, name: str, w: int = 1080) -> Response:
    """Page image, optionally downscaled for slow links (Tailscale / mobile data)."""
    auth.require_path(request, path, "view")
    source = library.resolve_file(path, name)
    if source.suffix.lower() == ".svg":
        return FileResponse(
            source,
            media_type="image/svg+xml",
            headers={"Content-Disposition": "attachment", "X-Content-Type-Options": "nosniff"},
        )
    resized = images.resized_image(source, path, name, w)
    if resized is not None:
        return FileResponse(
            resized,
            media_type="image/jpeg",
            headers={"Cache-Control": "private, max-age=604800"},
        )
    # 没有生成缩放图时绝不直接回退原文件：原图属于 download 权限
    auth.require_path(request, path, "download")
    return media.file_response(source, request)


@router.post("/media/delete", response_model=ActionResult)
def media_delete(request: Request, payload: MediaDeleteRequest) -> ActionResult:
    """删除单张 / 多张图片或视频（移入 data/trash/media，可人工找回）。"""
    auth.require_path(request, payload.path, "delete")
    deleted = media_trash.delete_files(payload.path, payload.names)
    return ActionResult(status="ok", path=payload.path, message=f"已删除 {deleted} 项")


@router.get("/media/video")
def media_video(request: Request, path: str, name: str) -> Response:
    # 保留 viewer 观看能力：流式播放只需 view；整段下载走 download 接口
    auth.require_path(request, path, "view")
    source = library.resolve_file(path, name)
    return media.file_response(source, request)


@router.get("/media/video_thumb")
def media_video_thumb(request: Request, path: str, name: str) -> Response:
    auth.require_path(request, path, "view")
    source = library.resolve_file(path, name)
    thumbnail = thumbs.video_thumbnail(source, path, name)
    if thumbnail is not None:
        return FileResponse(
            thumbnail,
            media_type="image/jpeg",
            headers={"Cache-Control": "public, max-age=86400"},
        )
    return FileResponse(source)


# ---------------------------------------------------------------- folders
@router.post("/folders", response_model=ActionResult)
def create_folder(request: Request, payload: CreateFolderRequest) -> ActionResult:
    auth.require_path(request, payload.parent, "create")
    try:
        created = manage.create_folder(payload.parent, payload.name, payload.kind)
    except OSError as exc:
        raise HTTPException(status_code=400, detail=f"无法创建目录（只读或权限不足）：{exc.strerror or exc}") from exc
    return ActionResult(status="ok", path=created)


@router.post("/folders/kind", response_model=ActionResult)
def set_folder_kind(request: Request, payload: SetFolderKindRequest) -> ActionResult:
    auth.require_path(request, payload.path, "manage")
    manage.set_kind(payload.path, payload.kind)
    return ActionResult(status="ok", path=payload.path)


@router.post("/rename", response_model=ActionResult)
def rename(request: Request, payload: RenameRequest) -> ActionResult:
    auth.require_path(request, payload.path, "manage")
    renamed = manage.rename(payload.path, payload.name)
    return ActionResult(status="ok", path=renamed)


@router.post("/move", response_model=BatchResult)
def move(request: Request, payload: MoveRequest) -> BatchResult:
    for source in payload.paths:
        auth.require_path(request, source, "move")
    auth.require_path(request, payload.to, "move")
    return manage.move(payload.paths, payload.to)


# ----------------------------------------------------------------- delete
@router.delete("/items", response_model=ActionResult)
def delete_item(request: Request, path: str) -> ActionResult:
    auth.require_path(request, path, "delete")
    entry = trash.soft_delete(path)
    return ActionResult(status="ok", path=entry.original_path, message="已移入回收站")


@router.post("/items/delete", response_model=BatchResult)
def delete_items(request: Request, payload: dict) -> BatchResult:
    paths = payload.get("paths") or []
    result = BatchResult()
    for raw in paths:
        try:
            auth.require_path(request, str(raw), "delete")
            entry = trash.soft_delete(raw)
            result.succeeded.append(entry.original_path)
        except HTTPException as exc:
            result.failed.append({"path": str(raw), "message": str(exc.detail)})
        except OSError as exc:
            result.failed.append({"path": str(raw), "message": str(exc)})
    result.status = "ok" if not result.failed else ("partial" if result.succeeded else "error")
    return result


# ------------------------------------------------------------------ trash
@router.get("/trash", response_model=TrashListResponse)
def trash_list(request: Request) -> TrashListResponse:
    auth.require_path(request, "", "delete")
    entries = trash.list_entries() + media_trash.list_entries()
    return TrashListResponse(items=entries, total=len(entries))


@router.get("/trash/thumb")
def trash_thumb(request: Request, entry_id: str):
    """回收站条目的预览图：媒体条目直接返回文件，系列条目取目录内第一张图。"""
    auth.require_path(request, "", "delete")
    from fastapi import Response as _Resp
    if entry_id.startswith("media|"):
        target = media_trash._resolve(entry_id)
        if target is None:
            raise HTTPException(status_code=404, detail="条目不存在")
        payload = target.read_bytes()
        media_type = "image/jpeg"
        if target.suffix.lower() == ".png":
            media_type = "image/png"
        elif target.suffix.lower() == ".gif":
            media_type = "image/gif"
        elif target.suffix.lower() in {".mp4", ".webm", ".mov", ".mkv"}:
            raise HTTPException(status_code=415, detail="视频暂不支持预览图")
        return _Resp(content=payload, media_type=media_type)
    root = settings.trash_root / entry_id / "data"
    if not root.is_dir():
        raise HTTPException(status_code=404, detail="条目不存在")
    for path in sorted(root.rglob("*")):
        if path.is_file() and path.suffix.lower() in {".jpg", ".jpeg", ".png", ".gif", ".webp"}:
            return _Resp(content=path.read_bytes(), media_type="image/jpeg")
    raise HTTPException(status_code=404, detail="没有预览图")


@router.post("/trash/{entry_id}/restore", response_model=ActionResult)
def trash_restore(request: Request, entry_id: str) -> ActionResult:
    auth.require_path(request, "", "delete")
    restored = media_trash.restore(entry_id) if entry_id.startswith("media|") else trash.restore(entry_id)
    return ActionResult(status="ok", path=restored, message="已恢复")


@router.delete("/trash/{entry_id}", response_model=ActionResult)
def trash_purge(request: Request, entry_id: str) -> ActionResult:
    auth.require_path(request, "", "delete")
    if entry_id.startswith("media|"):
        media_trash.purge(entry_id)
    else:
        trash.purge(entry_id)
    return ActionResult(status="ok", message="已彻底删除")


@router.post("/trash/sweep", response_model=ActionResult)
def trash_sweep(request: Request) -> ActionResult:
    auth.require_path(request, "", "delete")
    removed = trash.sweep()
    return ActionResult(status="ok", message=f"已清理 {removed} 项")


# ----------------------------------------------------------------- export
@router.get("/export")
def export(request: Request, path: str, include_video: bool = True) -> FileResponse:
    auth.require_path(request, path, "download")
    handle, filename = archive.build_export(path, include_video=include_video)
    return FileResponse(
        handle,
        media_type="application/zip",
        filename=filename,
        background=BackgroundTask(lambda: handle.unlink(missing_ok=True)),
    )


# ----------------------------------------------------------------- upload
def _check_content_length(request: Request) -> None:
    raw = request.headers.get("content-length")
    if not raw:
        return
    try:
        length = int(raw)
    except ValueError:
        return
    limit = settings.max_upload_mb * 1024 * 1024
    if limit > 0 and length > limit:
        raise HTTPException(
            status_code=413, detail=f"上传内容超过 {settings.max_upload_mb} MB 限制"
        )


@router.post("/upload", response_model=ImportResult)
async def upload(
    request: Request,
    files: list[UploadFile] = File(...),
    folder: str = Form(""),
    name: str = Form(""),
    direct: bool = Form(False),
) -> ImportResult:
    _check_content_length(request)
    auth.require_path(request, folder, "upload")
    return importers.import_uploads(files, folder, name, direct)


@router.post("/import/pdf", response_model=ImportResult)
async def import_pdf(
    request: Request,
    file: UploadFile = File(...),
    folder: str = Form(""),
    name: str = Form(""),
    dpi: int = Form(200),
) -> ImportResult:
    _check_content_length(request)
    payload = await file.read()
    if not payload:
        raise HTTPException(status_code=400, detail="PDF 文件为空")
    return importers.import_pdf(payload, folder, name or Path(file.filename or "document").stem, dpi)


# --------------------------------------------------------------- progress
@router.get("/progress", response_model=dict[str, ReadingProgress])
def progress_all() -> dict[str, ReadingProgress]:
    return progress.get_many()


@router.get("/progress/one", response_model=ReadingProgress)
def progress_one(path: str) -> ReadingProgress:
    item = progress.get(path)
    if item is None:
        raise HTTPException(status_code=404, detail="暂无阅读记录")
    return item


@router.put("/progress", response_model=ReadingProgress)
def progress_update(payload: ProgressUpdate) -> ReadingProgress:
    return progress.update(payload.path, payload.page, payload.total)


@router.post("/album_cover", response_model=ActionResult)
def set_album_cover(request: Request, payload: AlbumCoverRequest) -> ActionResult:
    """Use one series inside a folder as the folder card cover (empty = default)."""
    auth.require_path(request, payload.path, "manage")
    library.set_album_cover(payload.path, payload.series)
    return ActionResult(status="ok", path=payload.path)


@router.get("/tunnel/edge")
def tunnel_edge(request: Request) -> dict:
    """返回宿主机探针写下的 Cloudflare 边缘信息（用于给客户端网络建议）。"""
    auth.require_user(request)
    path = settings.data_root / "tunnel_edge.json"
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"edge": "unknown", "region": "unknown", "advice": "暂时没有边缘信息"}


# ---------------------------------------------------------------- personas
@router.get("/personas")
def personas_list(request: Request) -> dict:
    user = auth.require_user(request)
    return {"items": personas.list_for(int(user["id"]))}


@router.post("/personas")
def personas_upsert(request: Request, payload: dict) -> dict:
    user = auth.require_user(request)
    return personas.upsert(int(user["id"]), payload)


@router.delete("/personas/{persona_id}")
def personas_delete(request: Request, persona_id: str) -> dict:
    user = auth.require_user(request)
    ok = personas.delete(int(user["id"]), persona_id)
    return {"status": "ok" if ok else "missing"}


# -------------------------------------------------------------------- pdf
@router.get("/pdf/info")
def pdf_info(request: Request, path: str) -> dict:
    user = auth.require_user(request)
    if not auth.can_see_path(user, path):
        raise HTTPException(status_code=403, detail="没有查看权限")
    return pdfs.info(path)


@router.get("/media/pdf")
def pdf_page(request: Request, path: str, page: int = 0, w: int = 1440) -> Response:
    user = auth.require_user(request)
    if not auth.can_see_path(user, path):
        raise HTTPException(status_code=403, detail="没有查看权限")
    payload = pdfs.page_bytes(path, page, w)
    return Response(
        content=payload,
        media_type="image/png",
        headers={"Cache-Control": "public, max-age=86400"},
    )


# ------------------------------------------------------------------- tags
@router.get("/tags", response_model=TagsResponse)
def tags_all() -> TagsResponse:
    """所有标签（含分组与使用次数）+ 已有分组，用于「历史标签」建议。"""
    return TagsResponse(
        tags=[TagCount(**item) for item in tags.all_tags()],
        groups=tags.groups(),
    )


@router.post("/tags/create", response_model=TagCount)
def tags_create(request: Request, payload: TagCreateRequest) -> TagCount:
    auth.require_path(request, "", "manage")
    created = tags.create_tag(payload.name, payload.group)
    return TagCount(name=created["name"], count=0, group=created["group"])


@router.delete("/tags/{name}", response_model=ActionResult)
def tags_delete(request: Request, name: str) -> ActionResult:
    auth.require_path(request, "", "manage")
    removed = tags.delete_tag(name)
    return ActionResult(status="ok", message=f"已从 {removed} 个相册移除标签")


@router.get("/tags/series", response_model=SeriesTagsResponse)
def tags_series(path: str) -> SeriesTagsResponse:
    normalized = library.normalize_path(path)
    return SeriesTagsResponse(path=normalized, tags=tags.tags_for(normalized))


@router.put("/tags", response_model=SeriesTagsResponse)
def tags_update(request: Request, payload: TagsUpdateRequest) -> SeriesTagsResponse:
    """给一个或多个路径加 / 删 / 替换标签（多选批量打标签）。"""
    raw_paths = list(payload.paths) if payload.paths else ([payload.path] if payload.path else [])
    normalized: list[str] = []
    for raw in raw_paths:
        auth.require_path(request, str(raw), "manage")
        path = library.normalize_path(raw)
        if not path:
            continue
        library.resolve_directory(path)
        normalized.append(path)
    if not normalized:
        raise HTTPException(status_code=400, detail="路径不合法")
    mode = payload.mode if payload.mode in {"add", "remove", "replace"} else "add"
    tags.update_tags(normalized, payload.tags, mode=mode)
    first = normalized[0]
    return SeriesTagsResponse(path=first, tags=tags.tags_for(first))


@router.get("/tagged", response_model=TaggedResponse)
def tagged(tag: str = "") -> TaggedResponse:
    items: list = []
    for raw in tags.series_paths(tag):
        node = library.node_for_path(raw)
        if node is not None:
            items.append(node)
    return TaggedResponse(tag=tag, items=items, total=len(items))


# ------------------------------------------------------------------ update
@router.get("/app/update", response_model=AppUpdateInfo, tags=["app"])
def app_update() -> AppUpdateInfo:
    return updates.latest_update()


@router.get("/app/update/download", tags=["app"])
def app_update_download() -> FileResponse:
    apk = updates.apk_path()
    if apk is None:
        raise HTTPException(status_code=404, detail="没有可用的更新包")
    return FileResponse(
        apk,
        media_type="application/vnd.android.package-archive",
        filename=apk.name,
    )


# ------------------------------------------------------------------ logs
@router.get("/logs")
def logs(request: Request, lines: int = 100) -> dict:
    auth.require_role(request, "owner", "admin")
    log_file = settings.log_root / "comii.log"
    if not log_file.is_file():
        return {"logs": []}
    try:
        content = log_file.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError as exc:
        return {"logs": [], "error": str(exc)}
    tail = content[-max(0, lines):] if lines else content
    return {"logs": list(reversed(tail))}



