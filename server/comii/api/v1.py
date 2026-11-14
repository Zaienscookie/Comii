"""Comii v1 API used by the native app (and any other client)."""

from __future__ import annotations

import os
import time
from pathlib import Path

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, Response
from starlette.background import BackgroundTask

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
        library=str(settings.library_root),
        server_time=time.time(),
        features=FEATURES,
    )


# ---------------------------------------------------------------- browsing
@router.get("/browse", response_model=BrowseResponse)
def browse(path: str = "") -> BrowseResponse:
    return library.browse(path)


@router.get("/series", response_model=SeriesDetail)
def series(path: str) -> SeriesDetail:
    detail = library.series_detail(path, progress_lookup=progress.get)
    detail.tags = tags.tags_for(detail.path)
    return detail


@router.get("/search", response_model=SearchResponse)
def search(q: str = "", limit: int = 200) -> SearchResponse:
    items = library.search(q, limit=limit)
    return SearchResponse(query=q, items=items, total=len(items))


@router.get("/collection", response_model=CollectionResponse)
def collection(path: str = "") -> CollectionResponse:
    normalized = library.normalize_path(path)
    items = library.collect_series(normalized)
    return CollectionResponse(path=normalized, items=items, total=len(items))


@router.get("/gallery", response_model=GalleryResponse)
def gallery(
    kind: str = "all",
    limit: int = 120,
    offset: int = 0,
    path: str = "",
    sort: str = "recent",
) -> GalleryResponse:
    rows, total = library.list_gallery(
        kind=kind, limit=limit, offset=offset, scope=path, sort=sort
    )
    return GalleryResponse(
        items=[GalleryItem(**row) for row in rows],
        total=total,
        offset=offset,
        limit=limit,
    )


@router.post("/cover", response_model=ActionResult)
def set_cover(payload: SetCoverRequest) -> ActionResult:
    library.set_cover(payload.path, payload.name)
    return ActionResult(status="ok", path=payload.path)


@router.post("/favorites", response_model=ActionResult)
def add_favorite(payload: dict) -> ActionResult:
    """Create a symlink under <library>/收藏夹/<collection>/.

    Body: {"path": "album/or/series", "name": "optional file name", "collection": "默认"}
    """
    raw_path = str(payload.get("path") or "").strip()
    name = str(payload.get("name") or "").strip()
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


@router.get("/favorites/status")
def favorite_status(path: str = "", name: str = "") -> dict:
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
def stats() -> StatsResponse:
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
    source = library.resolve_file(path, name)
    if source.suffix.lower() == ".svg":
        return FileResponse(source, media_type="image/svg+xml")
    return media.file_response(source, request)


@router.get("/media/info")
def media_info(path: str, name: str) -> dict:
    """Basic file info + EXIF metadata for the app's detail sheet."""
    source = library.resolve_file(path, name)
    stat = source.stat()
    payload = {
        "path": path,
        "name": name,
        "size": stat.st_size,
        "mtime": stat.st_mtime,
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
    source = library.resolve_file(path, name)
    if source.suffix.lower() == ".svg":
        return FileResponse(source, media_type="image/svg+xml")
    resized = images.resized_image(source, path, name, w)
    if resized is not None:
        return FileResponse(
            resized,
            media_type="image/jpeg",
            headers={"Cache-Control": "public, max-age=604800"},
        )
    return media.file_response(source, request)


@router.post("/media/delete", response_model=ActionResult)
def media_delete(payload: MediaDeleteRequest) -> ActionResult:
    """删除单张 / 多张图片或视频（移入 data/trash/media，可人工找回）。"""
    deleted = media_trash.delete_files(payload.path, payload.names)
    return ActionResult(status="ok", path=payload.path, message=f"已删除 {deleted} 项")


@router.get("/media/video")
def media_video(request: Request, path: str, name: str) -> Response:
    source = library.resolve_file(path, name)
    return media.file_response(source, request)


@router.get("/media/video_thumb")
def media_video_thumb(path: str, name: str) -> Response:
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
def create_folder(payload: CreateFolderRequest) -> ActionResult:
    created = manage.create_folder(payload.parent, payload.name, payload.kind)
    return ActionResult(status="ok", path=created)


@router.post("/folders/kind", response_model=ActionResult)
def set_folder_kind(payload: SetFolderKindRequest) -> ActionResult:
    manage.set_kind(payload.path, payload.kind)
    return ActionResult(status="ok", path=payload.path)


@router.post("/rename", response_model=ActionResult)
def rename(payload: RenameRequest) -> ActionResult:
    renamed = manage.rename(payload.path, payload.name)
    return ActionResult(status="ok", path=renamed)


@router.post("/move", response_model=BatchResult)
def move(payload: MoveRequest) -> BatchResult:
    return manage.move(payload.paths, payload.to)


# ----------------------------------------------------------------- delete
@router.delete("/items", response_model=ActionResult)
def delete_item(path: str) -> ActionResult:
    entry = trash.soft_delete(path)
    return ActionResult(status="ok", path=entry.original_path, message="已移入回收站")


@router.post("/items/delete", response_model=BatchResult)
def delete_items(payload: dict) -> BatchResult:
    paths = payload.get("paths") or []
    result = BatchResult()
    for raw in paths:
        try:
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
def trash_list() -> TrashListResponse:
    entries = trash.list_entries()
    return TrashListResponse(items=entries, total=len(entries))


@router.post("/trash/{entry_id}/restore", response_model=ActionResult)
def trash_restore(entry_id: str) -> ActionResult:
    restored = trash.restore(entry_id)
    return ActionResult(status="ok", path=restored, message="已恢复")


@router.delete("/trash/{entry_id}", response_model=ActionResult)
def trash_purge(entry_id: str) -> ActionResult:
    trash.purge(entry_id)
    return ActionResult(status="ok", message="已彻底删除")


@router.post("/trash/sweep", response_model=ActionResult)
def trash_sweep() -> ActionResult:
    removed = trash.sweep()
    return ActionResult(status="ok", message=f"已清理 {removed} 项")


# ----------------------------------------------------------------- export
@router.get("/export")
def export(path: str, include_video: bool = True) -> FileResponse:
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
def set_album_cover(payload: AlbumCoverRequest) -> ActionResult:
    """Use one series inside a folder as the folder card cover (empty = default)."""
    library.set_album_cover(payload.path, payload.series)
    return ActionResult(status="ok", path=payload.path)


# ------------------------------------------------------------------- tags
@router.get("/tags", response_model=TagsResponse)
def tags_all() -> TagsResponse:
    """所有标签（含分组与使用次数）+ 已有分组，用于「历史标签」建议。"""
    return TagsResponse(
        tags=[TagCount(**item) for item in tags.all_tags()],
        groups=tags.groups(),
    )


@router.post("/tags/create", response_model=TagCount)
def tags_create(payload: TagCreateRequest) -> TagCount:
    created = tags.create_tag(payload.name, payload.group)
    return TagCount(name=created["name"], count=0, group=created["group"])


@router.delete("/tags/{name}", response_model=ActionResult)
def tags_delete(name: str) -> ActionResult:
    removed = tags.delete_tag(name)
    return ActionResult(status="ok", message=f"已从 {removed} 个相册移除标签")


@router.get("/tags/series", response_model=SeriesTagsResponse)
def tags_series(path: str) -> SeriesTagsResponse:
    normalized = library.normalize_path(path)
    return SeriesTagsResponse(path=normalized, tags=tags.tags_for(normalized))


@router.put("/tags", response_model=SeriesTagsResponse)
def tags_update(payload: TagsUpdateRequest) -> SeriesTagsResponse:
    """给一个或多个路径加 / 删 / 替换标签（多选批量打标签）。"""
    raw_paths = list(payload.paths) if payload.paths else ([payload.path] if payload.path else [])
    normalized: list[str] = []
    for raw in raw_paths:
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
