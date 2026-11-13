package com.comii.app.data

import kotlinx.serialization.Serializable

/**
 * Wire models. Field names intentionally mirror the server JSON so the
 * Kotlin models and the FastAPI models stay easy to compare.
 */

object NodeKind {
    const val FOLDER = "folder"
    const val ALBUM = "album"
    const val MANGA = "manga"
    const val VIDEO = "video"
    const val MIXED = "mixed"
}

@Serializable
data class LibraryNode(
    val name: String = "",
    val path: String = "",
    val kind: String = NodeKind.FOLDER,
    val cover: String? = null,
    val image_count: Int = 0,
    val video_count: Int = 0,
    val child_count: Int? = null,
    val forced_kind: String? = null,
    val updated_at: Double? = null,
    val size: Long? = null,
) {
    val isAlbum: Boolean get() = kind == NodeKind.ALBUM
    val isFolder: Boolean get() = kind == NodeKind.FOLDER || kind == NodeKind.ALBUM
}

@Serializable
data class CollectionResponse(
    val path: String = "",
    val items: List<LibraryNode> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class GalleryItem(
    val series_path: String = "",
    val series_name: String = "",
    val name: String = "",
    val kind: String = "image",
    val thumb: String = "",
    val index: Int = 0,
    val mtime: Double = 0.0,
)

@Serializable
data class GalleryResponse(
    val items: List<GalleryItem> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
)

@Serializable
data class SetCoverBody(val path: String, val name: String = "")

@Serializable
data class AlbumCoverBody(val path: String, val series: String = "")

@Serializable
data class MediaDeleteBody(val path: String, val names: List<String> = emptyList())

@Serializable
data class BrowseResponse(
    val path: String = "",
    val parent: String? = null,
    val items: List<LibraryNode> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class SearchResponse(
    val query: String = "",
    val items: List<LibraryNode> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class ReadingProgress(
    val page: Int = 0,
    val total: Int = 0,
    val updated_at: Double? = null,
)

@Serializable
data class SeriesDetail(
    val name: String = "",
    val path: String = "",
    val kind: String = NodeKind.MANGA,
    val cover: String? = null,
    val images: List<String> = emptyList(),
    val videos: List<String> = emptyList(),
    val image_count: Int = 0,
    val video_count: Int = 0,
    val progress: ReadingProgress? = null,
    val tags: List<String> = emptyList(),
)

@Serializable
data class TagCount(
    val name: String = "",
    val count: Int = 0,
)

@Serializable
data class TagsResponse(
    val tags: List<TagCount> = emptyList(),
)

@Serializable
data class SeriesTags(
    val path: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class TaggedResponse(
    val tag: String = "",
    val items: List<LibraryNode> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class TagsBody(val path: String, val tags: List<String> = emptyList())

@Serializable
data class TrashEntry(
    val id: String = "",
    val name: String = "",
    val original_path: String = "",
    val kind: String = NodeKind.MANGA,
    val image_count: Int = 0,
    val video_count: Int = 0,
    val size: Long = 0,
    val deleted_at: Double = 0.0,
    val expires_at: Double = 0.0,
    val remaining_seconds: Long = 0,
)

@Serializable
data class TrashListResponse(
    val items: List<TrashEntry> = emptyList(),
    val total: Int = 0,
)

@Serializable
data class PingResponse(
    val status: String = "",
    val app: String = "",
    val version: String = "",
    val library: String = "",
    val server_time: Double = 0.0,
    val features: List<String> = emptyList(),
)

@Serializable
data class ActionResult(
    val status: String = "ok",
    val path: String? = null,
    val message: String? = null,
)

@Serializable
data class BatchFailure(val path: String = "", val message: String = "")

@Serializable
data class BatchResult(
    val status: String = "ok",
    val succeeded: List<String> = emptyList(),
    val failed: List<BatchFailure> = emptyList(),
)

@Serializable
data class ImportResult(
    val status: String = "ok",
    val name: String = "",
    val path: String = "",
    val pages: Int = 0,
    val images: Int = 0,
    val videos: Int = 0,
    val total_entries: Int = 0,
    val skipped: Int = 0,
    val skipped_types: List<String> = emptyList(),
)

@Serializable
data class ApiError(val detail: String? = null)

@Serializable
data class CreateFolderBody(val parent: String, val name: String, val kind: String = "")

@Serializable
data class SetFolderKindBody(val path: String, val kind: String = "")

@Serializable
data class RenameBody(val path: String, val name: String)

@Serializable
data class MoveBody(val paths: List<String>, val to: String)

@Serializable
data class DeleteBody(val paths: List<String>)

@Serializable
data class ProgressBody(val path: String, val page: Int, val total: Int)
