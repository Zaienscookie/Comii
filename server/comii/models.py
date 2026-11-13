"""API models shared by the Web UI and the native app."""

from __future__ import annotations

from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


class NodeKind(str, Enum):
    folder = "folder"
    manga = "manga"
    video = "video"
    mixed = "mixed"


class LibraryNode(BaseModel):
    name: str
    path: str
    kind: NodeKind
    cover: Optional[str] = None
    image_count: int = 0
    video_count: int = 0
    child_count: Optional[int] = None
    forced_kind: Optional[str] = None
    updated_at: Optional[float] = None
    size: Optional[int] = None


class BrowseResponse(BaseModel):
    path: str = ""
    parent: Optional[str] = None
    items: list[LibraryNode] = Field(default_factory=list)
    total: int = 0


class SearchResponse(BaseModel):
    query: str = ""
    items: list[LibraryNode] = Field(default_factory=list)
    total: int = 0


class CollectionResponse(BaseModel):
    path: str = ""
    items: list[LibraryNode] = Field(default_factory=list)
    total: int = 0


class ReadingProgress(BaseModel):
    page: int = 0
    total: int = 0
    updated_at: Optional[float] = None


class SeriesDetail(BaseModel):
    name: str
    path: str
    kind: NodeKind
    cover: Optional[str] = None
    images: list[str] = Field(default_factory=list)
    videos: list[str] = Field(default_factory=list)
    image_count: int = 0
    video_count: int = 0
    progress: Optional[ReadingProgress] = None
    tags: list[str] = Field(default_factory=list)


class ServiceInfo(BaseModel):
    name: str
    version: str
    api: str
    library: str
    features: list[str] = Field(default_factory=list)


class PingResponse(BaseModel):
    status: str = "ok"
    app: str
    version: str
    library: str
    server_time: float = 0
    features: list[str] = Field(default_factory=list)


class CreateFolderRequest(BaseModel):
    parent: str = ""
    name: str
    kind: str = ""


class SetFolderKindRequest(BaseModel):
    path: str
    kind: str = ""


class RenameRequest(BaseModel):
    path: str
    name: str


class MoveRequest(BaseModel):
    paths: list[str] = Field(default_factory=list)
    to: str = ""


class DeleteRequest(BaseModel):
    paths: list[str] = Field(default_factory=list)


class ActionResult(BaseModel):
    status: str = "ok"
    path: Optional[str] = None
    message: Optional[str] = None


class BatchResult(BaseModel):
    status: str = "ok"
    succeeded: list[str] = Field(default_factory=list)
    failed: list[dict[str, str]] = Field(default_factory=list)


class TrashEntry(BaseModel):
    id: str
    name: str
    original_path: str
    kind: NodeKind = NodeKind.manga
    image_count: int = 0
    video_count: int = 0
    size: int = 0
    deleted_at: float
    expires_at: float
    remaining_seconds: int = 0


class TrashListResponse(BaseModel):
    items: list[TrashEntry] = Field(default_factory=list)
    total: int = 0


class ProgressUpdate(BaseModel):
    path: str
    page: int
    total: int = 0


class SetCoverRequest(BaseModel):
    path: str
    name: str = ""


class AlbumCoverRequest(BaseModel):
    """Pick a child series as the cover of a folder (album)."""
    path: str
    series: str = ""


class GalleryItem(BaseModel):
    series_path: str
    series_name: str
    name: str
    kind: str = "image"
    thumb: str
    index: int = 0
    mtime: float = 0


class GalleryResponse(BaseModel):
    items: list[GalleryItem] = Field(default_factory=list)
    total: int = 0
    offset: int = 0
    limit: int = 0


class ImportResult(BaseModel):
    status: str = "ok"
    name: str
    path: str
    pages: int = 0
    images: int = 0
    videos: int = 0
    # 压缩包导入诊断：总条目、被跳过的数量与类型分布
    total_entries: int = 0
    skipped: int = 0
    skipped_types: list[str] = Field(default_factory=list)


class StatsResponse(BaseModel):
    series_count: int = 0
    folder_count: int = 0
    image_count: int = 0
    video_count: int = 0
    total_bytes: int = 0
    library: str = ""


class TagCount(BaseModel):
    name: str
    count: int = 0


class TagsResponse(BaseModel):
    tags: list[TagCount] = Field(default_factory=list)


class SeriesTagsResponse(BaseModel):
    path: str = ""
    tags: list[str] = Field(default_factory=list)


class TagsUpdateRequest(BaseModel):
    path: str
    tags: list[str] = Field(default_factory=list)


class TaggedResponse(BaseModel):
    tag: str = ""
    items: list[LibraryNode] = Field(default_factory=list)
    total: int = 0


class AppUpdateInfo(BaseModel):
    available: bool = False
    versionCode: int = 0
    versionName: str = ""
    notes: str = ""
    size: int = 0
    sha256: str = ""
    url: str = ""
