"""Import pipelines: PDF pages, CBZ/ZIP archives and plain file uploads.

Everything ends up as a real series directory inside the library so the
folder structure always mirrors what the client sees.
"""

from __future__ import annotations

import io
import re
import shutil
import tempfile
import zipfile
from pathlib import Path
from typing import Iterable

from fastapi import HTTPException, UploadFile

from ..config import settings
from ..models import ImportResult
from . import library

ARCHIVE_EXTENSIONS = {".zip", ".cbz", ".7z"}
PDF_EXTENSIONS = {".pdf"}
MAX_NAME_LENGTH = 120


def sanitize_name(raw: str, fallback: str = "untitled") -> str:
    candidate = str(raw or "").strip()
    candidate = re.sub(r'[\\/:*?"<>|\x00-\x1f]', "_", candidate)
    candidate = candidate.strip(" .")
    candidate = candidate[:MAX_NAME_LENGTH]
    return candidate or fallback


def _direct_directory(folder: str) -> tuple[Path, str]:
    """Upload into the current folder instead of creating a new series folder."""
    normalized = library.normalize_path(folder)
    if not normalized:
        raise HTTPException(status_code=400, detail="直接上传必须指定目标文件夹")
    base = library.resolve_directory(normalized)
    return base, base.name


def _target_directory(folder: str, name: str) -> tuple[Path, str]:
    root = library.library_root()
    folder_rel = library.normalize_path(folder)
    if not folder_rel:
        base = root
    else:
        try:
            base = library.resolve_directory(folder_rel)
        except HTTPException:
            # 上传时允许直接填一个新文件夹，自动创建
            base = root / folder_rel
            base.mkdir(parents=True, exist_ok=True)
    clean_name = sanitize_name(name)
    target = base / clean_name
    if target.exists():
        raise HTTPException(status_code=409, detail=f"名称已存在: {clean_name}")
    return target, clean_name


def _relative(target: Path) -> str:
    return str(target.relative_to(library.library_root())).replace("\\", "/")


def _count(directory: Path) -> tuple[int, int]:
    images, videos = library.list_media(directory)
    return len(images), len(videos)


def _write_pdf_pages(pdf_bytes: bytes, dpi: int) -> list[bytes]:
    try:
        import pypdfium2 as pdfium
    except ImportError as exc:  # pragma: no cover - dependency missing
        raise HTTPException(status_code=501, detail="服务器缺少 PDF 渲染组件 pypdfium2") from exc

    pages: list[bytes] = []
    scale = max(0.5, min(6.0, dpi / 72.0))
    document = pdfium.PdfDocument(pdf_bytes)
    try:
        for index in range(len(document)):
            page = document[index]
            bitmap = page.render(scale=scale)
            image = bitmap.to_pil().convert("RGB")
            buffer = io.BytesIO()
            image.save(buffer, "JPEG", quality=88, optimize=True)
            pages.append(buffer.getvalue())
            page.close()
    finally:
        document.close()
    return pages


def import_pdf(raw: bytes, folder: str, name: str, dpi: int = 200, direct: bool = False) -> ImportResult:
    pages = _write_pdf_pages(raw, dpi)
    if not pages:
        raise HTTPException(status_code=400, detail="PDF 中没有可渲染的页面")
    target, clean_name = _direct_directory(folder) if direct else _target_directory(folder, name)
    target.mkdir(parents=True, exist_ok=True)
    for index, payload in enumerate(pages, start=1):
        (target / f"{index:04d}.jpg").write_bytes(payload)
    images, videos = _count(target)
    return ImportResult(
        name=clean_name,
        path=_relative(target),
        pages=len(pages),
        images=images,
        videos=videos,
        total_entries=len(pages),
    )


def _is_ignored_archive_entry(name: str) -> bool:
    parts = name.replace("\\", "/").split("/")
    if any(part.startswith(".") for part in parts if part):
        return True
    if any(part.lower() in {"__macosx", "thumbs.db"} for part in parts):
        return True
    return False


def _unique_destination(staging: Path, safe: str, extension: str, used: set[str]) -> Path:
    """Fast name de-duplication (the old while-exists loop was O(n²) for big packs)."""
    candidate = staging / safe
    if safe not in used:
        used.add(safe)
        return candidate
    stem = Path(safe).stem
    counter = 1
    while True:
        name = f"{stem}_{counter}{extension}"
        if name not in used:
            used.add(name)
            return staging / name
        counter += 1


def _extract_media(
    source, staging: Path, suffix: str = ".zip"
) -> tuple[int, int, int, list[str], int]:
    """Extract images *and* videos from an archive.

    Returns (images, videos, total_entries, skipped_type_summary, failed).
    ``source`` may be raw bytes or a Path (large uploads are spooled to disk).
    """
    if suffix == ".7z":
        return _extract_7z_media(source, staging)

    images = 0
    videos = 0
    total = 0
    failed = 0
    skipped: dict[str, int] = {}
    used: set[str] = set()

    handle = io.BytesIO(source) if isinstance(source, (bytes, bytearray)) else source
    with zipfile.ZipFile(handle) as bundle:
        for info in bundle.infolist():
            if info.is_dir():
                continue
            total += 1
            entry_name = info.filename.replace("\\", "/")
            if _is_ignored_archive_entry(entry_name):
                skipped["隐藏文件"] = skipped.get("隐藏文件", 0) + 1
                continue
            extension = Path(entry_name).suffix.lower()
            is_image = extension in library.IMAGE_EXTENSIONS
            is_video = extension in library.VIDEO_EXTENSIONS
            if not is_image and not is_video:
                key = extension or "无扩展名"
                skipped[key] = skipped.get(key, 0) + 1
                continue
            destination = _unique_destination(staging, Path(entry_name).name, extension, used)
            try:
                with bundle.open(info) as src, destination.open("wb") as dst:
                    shutil.copyfileobj(src, dst)
                if is_image:
                    images += 1
                else:
                    videos += 1
            except Exception:
                failed += 1
                destination.unlink(missing_ok=True)

    summary = [f"{name}×{count}" for name, count in
               sorted(skipped.items(), key=lambda item: -item[1])[:6]]
    if failed:
        summary.append(f"读取失败×{failed}")
    return images, videos, total, summary, failed


def _extract_7z_media(source, staging: Path) -> tuple[int, int, int, list[str], int]:
    try:
        import py7zr
    except ImportError as exc:  # pragma: no cover - optional dependency
        raise HTTPException(status_code=501, detail="服务器缺少 7z 组件 py7zr") from exc

    temporary = staging / ".comii_7z"
    temporary.mkdir(parents=True, exist_ok=True)
    images = 0
    videos = 0
    total = 0
    failed = 0
    skipped: dict[str, int] = {}
    used: set[str] = set()
    try:
        handle = io.BytesIO(source) if isinstance(source, (bytes, bytearray)) else source
        with py7zr.SevenZipFile(handle, mode="r") as archive:
            archive.extractall(path=temporary)
        for item in sorted(temporary.rglob("*")):
            if not item.is_file():
                continue
            total += 1
            relative = item.relative_to(temporary).as_posix()
            if _is_ignored_archive_entry(relative):
                skipped["隐藏文件"] = skipped.get("隐藏文件", 0) + 1
                continue
            extension = item.suffix.lower()
            is_image = extension in library.IMAGE_EXTENSIONS
            is_video = extension in library.VIDEO_EXTENSIONS
            if not is_image and not is_video:
                key = extension or "无扩展名"
                skipped[key] = skipped.get(key, 0) + 1
                continue
            destination = _unique_destination(staging, item.name, extension, used)
            try:
                shutil.move(str(item), str(destination))
                if is_image:
                    images += 1
                else:
                    videos += 1
            except Exception:
                failed += 1
    finally:
        shutil.rmtree(temporary, ignore_errors=True)
    summary = [f"{name}×{count}" for name, count in
               sorted(skipped.items(), key=lambda item: -item[1])[:6]]
    if failed:
        summary.append(f"读取失败×{failed}")
    return images, videos, total, summary, failed


def _flatten_single_directory(staging: Path) -> None:
    entries = [entry for entry in staging.iterdir()]
    directories = [entry for entry in entries if entry.is_dir()]
    if len(directories) == 1 and len(entries) == 1:
        inner = directories[0]
        for child in inner.iterdir():
            shutil.move(str(child), str(staging / child.name))
        inner.rmdir()


def import_archive(raw, folder: str, name: str = "", suffix: str = ".zip", direct: bool = False) -> ImportResult:
    """Import a zip/cbz/7z archive (bytes or a spooled file path)."""
    staging = Path(tempfile.mkdtemp(prefix="comii_cbz_"))
    try:
        images, videos, total, skipped_summary, _failed = _extract_media(raw, staging, suffix)
        if not images and not videos:
            detail = "压缩包中没有图片或视频"
            if skipped_summary:
                detail += "（跳过了 " + "、".join(skipped_summary) + "）"
            raise HTTPException(status_code=400, detail=detail)
        _flatten_single_directory(staging)
        target, clean_name = _direct_directory(folder) if direct else _target_directory(folder, name)
        target.mkdir(parents=True, exist_ok=True)
        image_index = 1
        video_index = 1
        for child in sorted(staging.iterdir(), key=lambda item: library.natural_sort_key(item.name)):
            if not child.is_file():
                continue
            extension = child.suffix.lower()
            if extension in library.VIDEO_EXTENSIONS:
                destination = target / f"{video_index:04d}{extension}"
                video_index += 1
            else:
                destination = target / f"{image_index:04d}{extension}"
                image_index += 1
            shutil.move(str(child), str(destination))
        image_count, video_count = _count(target)
        return ImportResult(
            name=clean_name,
            path=_relative(target),
            pages=image_count,
            images=image_count,
            videos=video_count,
            total_entries=total,
            skipped=max(0, total - images - videos),
            skipped_types=skipped_summary,
        )
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def _copy_upload(upload: UploadFile, destination: Path) -> int:
    upload.file.seek(0)
    written = 0
    with destination.open("wb") as handle:
        while True:
            chunk = upload.file.read(1024 * 1024)
            if not chunk:
                break
            written += len(chunk)
            handle.write(chunk)
    return written


def _spool(upload: UploadFile, suffix: str) -> Path:
    """Copy an UploadFile to a temp file on disk (avoids loading GBs into RAM)."""
    handle = tempfile.NamedTemporaryFile(prefix="comii_upload_", suffix=suffix, delete=False)
    path = Path(handle.name)
    try:
        upload.file.seek(0)
        while True:
            chunk = upload.file.read(4 * 1024 * 1024)
            if not chunk:
                break
            handle.write(chunk)
    finally:
        handle.close()
    return path


def import_uploads(files: Iterable[UploadFile], folder: str, name: str = "", direct: bool = False) -> ImportResult:
    uploads = [item for item in files if getattr(item, "filename", None)]
    if not uploads:
        raise HTTPException(status_code=400, detail="没有收到文件")

    first_suffix = Path(uploads[0].filename).suffix.lower()
    fallback_name = Path(uploads[0].filename).stem
    series_name = sanitize_name(name or fallback_name)

    if len(uploads) == 1 and first_suffix in ARCHIVE_EXTENSIONS:
        spooled = _spool(uploads[0], first_suffix)
        try:
            return import_archive(spooled, folder, name or fallback_name, first_suffix, direct)
        finally:
            spooled.unlink(missing_ok=True)
    if len(uploads) == 1 and first_suffix in PDF_EXTENSIONS:
        payload = uploads[0].file.read()
        uploads[0].file.seek(0)
        return import_pdf(payload, folder, name or fallback_name, dpi=200, direct=direct)

    target, clean_name = _direct_directory(folder) if direct else _target_directory(folder, series_name)
    target.mkdir(parents=True, exist_ok=True)
    written_files = 0
    for upload in uploads:
        original = Path(upload.filename)
        suffix = original.suffix.lower()
        stem = sanitize_name(original.stem, fallback=f"file{written_files + 1}")
        destination = target / f"{stem}{suffix}"
        counter = 1
        while destination.exists():
            destination = target / f"{stem}_{counter}{suffix}"
            counter += 1
        if _copy_upload(upload, destination) == 0:
            destination.unlink(missing_ok=True)
            continue
        written_files += 1
    if written_files == 0:
        if not direct:
            shutil.rmtree(target, ignore_errors=True)
        raise HTTPException(status_code=400, detail="上传的文件为空")
    images, videos = _count(target)
    return ImportResult(
        name=clean_name,
        path=_relative(target),
        pages=images,
        images=images,
        videos=videos,
        total_entries=len(uploads),
    )
