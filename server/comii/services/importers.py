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


def import_pdf(raw: bytes, folder: str, name: str, dpi: int = 200) -> ImportResult:
    pages = _write_pdf_pages(raw, dpi)
    if not pages:
        raise HTTPException(status_code=400, detail="PDF 中没有可渲染的页面")
    target, clean_name = _target_directory(folder, name)
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
    )


def _is_ignored_archive_entry(name: str) -> bool:
    parts = name.replace("\\", "/").split("/")
    if any(part.startswith(".") for part in parts if part):
        return True
    if any(part.lower() in {"__macosx", "thumbs.db"} for part in parts):
        return True
    return False


def _extract_images(archive_bytes: bytes, staging: Path, suffix: str = ".zip") -> int:
    if suffix == ".7z":
        return _extract_7z_images(archive_bytes, staging)
    written = 0
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as bundle:
        for info in bundle.infolist():
            if info.is_dir():
                continue
            entry_name = info.filename
            if _is_ignored_archive_entry(entry_name):
                continue
            suffix = Path(entry_name).suffix.lower()
            if suffix not in library.IMAGE_EXTENSIONS:
                continue
            safe = Path(entry_name).name
            destination = staging / safe
            counter = 1
            while destination.exists():
                destination = staging / f"{Path(safe).stem}_{counter}{suffix}"
                counter += 1
            with bundle.open(info) as source, destination.open("wb") as handle:
                shutil.copyfileobj(source, handle)
            written += 1
    return written


def _extract_7z_images(archive_bytes: bytes, staging: Path) -> int:
    try:
        import py7zr
    except ImportError as exc:  # pragma: no cover - optional dependency
        raise HTTPException(status_code=501, detail="服务器缺少 7z 组件 py7zr") from exc

    temporary = staging / ".comii_7z"
    temporary.mkdir(parents=True, exist_ok=True)
    try:
        with py7zr.SevenZipFile(io.BytesIO(archive_bytes), mode="r") as archive:
            archive.extractall(path=temporary)
        written = 0
        for source in sorted(temporary.rglob("*")):
            if not source.is_file():
                continue
            relative = str(source.relative_to(temporary))
            if _is_ignored_archive_entry(relative):
                continue
            suffix = source.suffix.lower()
            if suffix not in library.IMAGE_EXTENSIONS:
                continue
            destination = staging / source.name
            counter = 1
            while destination.exists():
                destination = staging / f"{source.stem}_{counter}{suffix}"
                counter += 1
            shutil.move(str(source), str(destination))
            written += 1
        return written
    finally:
        shutil.rmtree(temporary, ignore_errors=True)


def _flatten_single_directory(staging: Path) -> None:
    entries = [entry for entry in staging.iterdir()]
    directories = [entry for entry in entries if entry.is_dir()]
    if len(directories) == 1 and len(entries) == 1:
        inner = directories[0]
        for child in inner.iterdir():
            shutil.move(str(child), str(staging / child.name))
        inner.rmdir()


def import_archive(raw: bytes, folder: str, name: str = "", suffix: str = ".zip") -> ImportResult:
    staging = Path(tempfile.mkdtemp(prefix="comii_cbz_"))
    try:
        written = _extract_images(raw, staging, suffix)
        if not written:
            raise HTTPException(status_code=400, detail="压缩包中没有图片")
        _flatten_single_directory(staging)
        target, clean_name = _target_directory(folder, name)
        target.mkdir(parents=True, exist_ok=True)
        index = 1
        for child in sorted(staging.iterdir(), key=lambda item: library.natural_sort_key(item.name)):
            if not child.is_file():
                continue
            suffix = child.suffix.lower()
            destination = target / f"{index:04d}{suffix}"
            shutil.move(str(child), str(destination))
            index += 1
        images, videos = _count(target)
        return ImportResult(name=clean_name, path=_relative(target), pages=images, images=images, videos=videos)
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


def import_uploads(files: Iterable[UploadFile], folder: str, name: str = "") -> ImportResult:
    uploads = [item for item in files if getattr(item, "filename", None)]
    if not uploads:
        raise HTTPException(status_code=400, detail="没有收到文件")

    first_suffix = Path(uploads[0].filename).suffix.lower()
    fallback_name = Path(uploads[0].filename).stem
    series_name = sanitize_name(name or fallback_name)
    payload = uploads[0].file.read()
    uploads[0].file.seek(0)

    if len(uploads) == 1 and first_suffix in ARCHIVE_EXTENSIONS:
        return import_archive(payload, folder, name or fallback_name, first_suffix)
    if len(uploads) == 1 and first_suffix in PDF_EXTENSIONS:
        return import_pdf(payload, folder, name or fallback_name, dpi=200)

    target, clean_name = _target_directory(folder, series_name)
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
        shutil.rmtree(target, ignore_errors=True)
        raise HTTPException(status_code=400, detail="上传的文件为空")
    images, videos = _count(target)
    return ImportResult(
        name=clean_name,
        path=_relative(target),
        pages=images,
        images=images,
        videos=videos,
    )
