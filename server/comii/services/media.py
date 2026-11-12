"""Binary media responses with HTTP Range support (video seeking)."""

from __future__ import annotations

import mimetypes
import re
from pathlib import Path
from typing import Iterator

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse, Response, StreamingResponse

_RANGE_PATTERN = re.compile(r"bytes=(\d*)-(\d*)$")
_CHUNK_SIZE = 256 * 1024


def _iter_range(handle, offset: int, length: int) -> Iterator[bytes]:
    handle.seek(offset)
    remaining = length
    try:
        while remaining > 0:
            chunk = handle.read(min(_CHUNK_SIZE, remaining))
            if not chunk:
                return
            remaining -= len(chunk)
            yield chunk
    finally:
        handle.close()


def file_response(path: Path, request: Request, media_type: str | None = None) -> Response:
    stats = path.stat()
    file_size = stats.st_size
    content_type = media_type or mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    range_header = request.headers.get("range", "").strip()

    if file_size == 0 or not range_header:
        return FileResponse(
            path,
            media_type=content_type,
            headers={"Accept-Ranges": "bytes", "Cache-Control": "public, max-age=3600"},
        )

    match = _RANGE_PATTERN.match(range_header)
    if not match:
        raise HTTPException(status_code=416, detail="不支持的 Range 请求")

    start_raw, end_raw = match.groups()
    start = int(start_raw) if start_raw else 0
    end = int(end_raw) if end_raw else file_size - 1
    if start >= file_size or start > end:
        raise HTTPException(status_code=416, detail="Range 超出文件范围")
    end = min(end, file_size - 1)
    length = end - start + 1

    headers = {
        "Content-Range": f"bytes {start}-{end}/{file_size}",
        "Accept-Ranges": "bytes",
        "Content-Length": str(length),
        "Cache-Control": "public, max-age=3600",
    }
    return StreamingResponse(
        _iter_range(path.open("rb"), start, length),
        status_code=206,
        media_type=content_type,
        headers=headers,
    )
