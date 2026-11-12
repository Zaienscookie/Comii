"""Comii v1 API used by the native app (and any other client)."""

from __future__ import annotations

import time
from pathlib import Path

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse, Response
from starlette.background import BackgroundTask

from ..config import settings
from ..models import (
    ActionResult,
    BatchResult,
    BrowseResponse,
    CreateFolderRequest,
    ImportResult,
    MoveRequest,
    PingResponse,
    ProgressUpdate,
    ReadingProgress,
    RenameRequest,
    SeriesDetail,
    StatsResponse,
    TrashEntry,
    TrashListResponse,
)
from ..services import archive, importers, library, manage, media, progress, thumbs, trash

router = APIRouter(prefix=settings.api_prefix, tags=["comii"])

FEATURES = [
    "browse",
    "reader",
    "video",
    "thumbnails",
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
    return library.series_detail(path, progress_lookup=progress.get)


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
) -> ImportResult:
    _check_content_length(request)
    return importers.import_uploads(files, folder, name)


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
