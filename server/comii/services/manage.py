"""Folder creation, rename and move (all paths are real directories on disk)."""

from __future__ import annotations

import shutil
from pathlib import Path

from fastapi import HTTPException

from ..models import BatchResult, NodeKind
from . import library

RESERVED = {".", ".."}


def _validate_name(name: str) -> str:
    candidate = str(name or "").strip()
    if not candidate:
        raise HTTPException(status_code=400, detail="名称不能为空")
    if candidate in RESERVED or "/" in candidate or "\\" in candidate or ":" in candidate:
        raise HTTPException(status_code=400, detail="名称不能包含 / \\ : 或为 . / ..")
    if candidate.startswith("."):
        raise HTTPException(status_code=400, detail="名称不能以点开头")
    return candidate


def create_folder(parent: str, name: str, kind: str = "") -> str:
    clean_name = _validate_name(name)
    root = library.library_root()
    parent_rel = library.normalize_path(parent)
    parent_dir = root if not parent_rel else library.resolve_directory(parent_rel)
    target = parent_dir / clean_name
    if target.exists():
        raise HTTPException(status_code=409, detail="同名文件夹已存在")
    target.mkdir(parents=True)
    if kind in {"manga", "video", "mixed"}:
        library.write_kind_marker(target, kind)
    return f"{parent_rel}/{clean_name}".lstrip("/")


def rename(raw_path: str, new_name: str) -> str:
    clean_name = _validate_name(new_name)
    root = library.library_root()
    normalized = library.normalize_path(raw_path)
    if not normalized:
        raise HTTPException(status_code=400, detail="不能重命名库根目录")
    source = library.resolve_directory(normalized)
    target = source.parent / clean_name
    if target == source:
        return normalized
    if target.exists():
        raise HTTPException(status_code=409, detail="目标名称已存在")
    shutil.move(str(source), str(target))
    parent_rel = str(Path(normalized).parent).replace("\\", "/")
    return f"{parent_rel}/{clean_name}".lstrip("/") if parent_rel != "." else clean_name


def move(raw_paths: list[str], destination: str) -> BatchResult:
    root = library.library_root()
    destination_rel = library.normalize_path(destination)
    destination_dir = root if not destination_rel else library.resolve_directory(destination_rel)
    result = BatchResult()

    for raw in raw_paths:
        try:
            normalized = library.normalize_path(raw)
            if not normalized:
                raise HTTPException(status_code=400, detail="不能移动库根目录")
            source = library.resolve_directory(normalized)
            target = destination_dir / source.name
            if source == target:
                result.succeeded.append(normalized)
                continue
            if target.exists():
                raise HTTPException(status_code=409, detail="目标位置已存在同名项目")
            source_resolved = str(source.resolve())
            destination_resolved = str(destination_dir.resolve())
            if destination_resolved == source_resolved or destination_resolved.startswith(
                source_resolved + str(Path("/"))
            ):
                raise HTTPException(status_code=400, detail="不能移动到自身内部")
            shutil.move(str(source), str(target))
            result.succeeded.append(normalized)
        except HTTPException as exc:
            result.failed.append({"path": raw, "message": str(exc.detail)})
        except OSError as exc:
            result.failed.append({"path": raw, "message": str(exc)})

    result.status = "ok" if not result.failed else ("partial" if result.succeeded else "error")
    return result
