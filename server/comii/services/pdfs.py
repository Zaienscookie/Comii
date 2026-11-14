"""PDF viewing support: first page as cover, pages rendered on demand."""

from __future__ import annotations

import hashlib
import threading
from pathlib import Path
from typing import Optional
from urllib.parse import quote

from fastapi import HTTPException

from ..config import settings

PDF_SUFFIX = ".pdf"
_lock = threading.Lock()


def is_pdf(path: Path | str) -> bool:
    return str(path).lower().endswith(PDF_SUFFIX)


def resolve_pdf(raw_path: str) -> Path:
    from . import library
    parts = library.split_relative_path(raw_path)
    if not parts or not is_pdf(parts[-1]):
        raise HTTPException(status_code=400, detail="不是 PDF 文件")
    candidate = (library.library_root() / Path(*parts)).resolve()
    root = library.library_root().resolve()
    if root not in candidate.parents or not candidate.is_file():
        raise HTTPException(status_code=404, detail="PDF 不存在")
    return candidate


def _document(path: Path):
    try:
        import pypdfium2 as pdfium
    except ImportError as exc:  # pragma: no cover - image ships the wheel
        raise HTTPException(status_code=501, detail="服务器缺少 PDF 渲染组件 pypdfium2") from exc
    try:
        return pdfium.PdfDocument(str(path))
    except Exception as exc:
        raise HTTPException(status_code=422, detail=f"无法打开 PDF：{exc}") from exc


def info(raw_path: str) -> dict:
    from . import library as lib
    path = resolve_pdf(raw_path)
    document = _document(path)
    try:
        pages = len(document)
    finally:
        document.close()
    return {
        "path": "/".join(lib.split_relative_path(raw_path)),
        "name": path.stem,
        "pages": pages,
        "size": path.stat().st_size,
    }


def page_bytes(raw_path: str, index: int, width: int = 1440) -> bytes:
    path = resolve_pdf(raw_path)
    width = max(240, min(int(width or 1440), 2400))
    cache_dir = settings.cache_root / "pdf"
    cache_dir.mkdir(parents=True, exist_ok=True)
    key = hashlib.sha1(f"{path}:{path.stat().st_mtime}:{index}:{width}".encode()).hexdigest()
    target = cache_dir / f"{key}.png"
    if target.is_file():
        return target.read_bytes()

    document = _document(path)
    try:
        if index < 0:
            index = 0
        if index >= len(document):
            raise HTTPException(status_code=404, detail="页码超出范围")
        page = document[index]
        try:
            base_width = float(page.get_width()) or 1.0
            scale = width / base_width
            bitmap = page.render(scale=scale)
            try:
                image = bitmap.to_pil()
                target.parent.mkdir(parents=True, exist_ok=True)
                image.save(target, format="PNG", optimize=True)
            finally:
                bitmap.close()
        finally:
            page.close()
    finally:
        document.close()
    return target.read_bytes()


def cover_url(raw_path: str, width: int = 480) -> str:
    return f"/api/v1/media/pdf?path={quote(raw_path)}&page=0&w={width}"


def pdf_nodes(directory: Path, prefix: str) -> list[dict]:
    """PDF files directly inside ``directory`` become book-like nodes."""
    items: list[dict] = []
    try:
        entries = [p for p in directory.iterdir() if p.is_file() and is_pdf(p.name) and not p.name.startswith(".")]
    except OSError:
        return items
    for entry in sorted(entries, key=lambda p: p.name.lower()):
        rel = f"{prefix}/{entry.name}".lstrip("/")
        try:
            stat = entry.stat()
        except OSError:
            continue
        items.append(
            {
                "name": entry.stem,
                "path": rel,
                "cover": cover_url(rel),
                "updated_at": stat.st_mtime,
                "size": stat.st_size,
            }
        )
    return items
