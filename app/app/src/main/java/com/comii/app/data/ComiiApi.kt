package com.comii.app.data

import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val code: Int, override val message: String) : Exception(message)

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
    }
}

val ComiiJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

/** Thin REST client for one server address. */
class ComiiApi(val baseUrl: String) {

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }

    fun url(path: String): String = baseUrl.trimEnd('/') + path

    private fun api(path: String): String = url("/api/v1$path")

    private suspend fun execute(request: Request, client: OkHttpClient = Http.client): String =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { ComiiJson.decodeFromString<ApiError>(text).detail }
                        .getOrNull()
                    throw ApiException(response.code, detail ?: "请求失败 (${response.code})")
                }
                text
            }
        }

    private suspend fun get(path: String): String =
        execute(Request.Builder().url(api(path)).get().build())

    private suspend fun post(path: String, body: String): String =
        execute(
            Request.Builder()
                .url(api(path))
                .post(body.toRequestBody(JSON_MEDIA))
                .build()
        )

    private suspend fun put(path: String, body: String): String =
        execute(
            Request.Builder()
                .url(api(path))
                .put(body.toRequestBody(JSON_MEDIA))
                .build()
        )

    private suspend fun delete(path: String): String =
        execute(Request.Builder().url(api(path)).delete().build())

    private inline fun <reified T> decode(text: String): T = ComiiJson.decodeFromString(text)

    suspend fun ping(): PingResponse = decode(get("/ping"))

    suspend fun browse(path: String): BrowseResponse = decode(get("/browse?path=${encode(path)}"))

    suspend fun series(path: String): SeriesDetail = decode(get("/series?path=${encode(path)}"))

    suspend fun search(query: String): SearchResponse =
        decode(get("/search?q=${encode(query)}&limit=200"))

    suspend fun trash(): TrashListResponse = decode(get("/trash"))

    suspend fun progressAll(): Map<String, ReadingProgress> = decode(get("/progress"))

    suspend fun createFolder(parent: String, name: String, kind: String): ActionResult =
        decode(post("/folders", ComiiJson.encodeToString(CreateFolderBody(parent, name, kind))))

    suspend fun rename(path: String, newName: String): ActionResult =
        decode(post("/rename", ComiiJson.encodeToString(RenameBody(path, newName))))

    suspend fun move(paths: List<String>, to: String): BatchResult =
        decode(post("/move", ComiiJson.encodeToString(MoveBody(paths, to))))

    suspend fun deleteItem(path: String): ActionResult =
        decode(delete("/items?path=${encode(path)}"))

    suspend fun deleteItems(paths: List<String>): BatchResult =
        decode(post("/items/delete", ComiiJson.encodeToString(DeleteBody(paths))))

    suspend fun restore(entryId: String): ActionResult =
        decode(post("/trash/$entryId/restore", ""))

    suspend fun purge(entryId: String): ActionResult =
        decode(delete("/trash/$entryId"))

    suspend fun saveProgress(path: String, page: Int, total: Int): ReadingProgress =
        decode(put("/progress", ComiiJson.encodeToString(ProgressBody(path, page, total))))

    suspend fun upload(multipart: MultipartBody): ImportResult = run {
        val request = Request.Builder().url(api("/upload")).post(multipart).build()
        decode(execute(request))
    }

    fun imageUrl(path: String, name: String): String =
        api("/media/image?path=${encode(path)}&name=${encode(name)}")

    fun thumbUrl(path: String, name: String): String =
        api("/media/thumb?path=${encode(path)}&name=${encode(name)}")

    fun videoUrl(path: String, name: String): String =
        api("/media/video?path=${encode(path)}&name=${encode(name)}")

    fun videoThumbUrl(path: String, name: String): String =
        api("/media/video_thumb?path=${encode(path)}&name=${encode(name)}")

    fun resolve(relative: String?): String? {
        if (relative.isNullOrBlank()) return null
        return if (relative.startsWith("http")) relative else url(relative)
    }

    fun exportUrl(path: String): String = api("/export?path=${encode(path)}")

    suspend fun probePing(): PingResponse = decode(
        execute(Request.Builder().url(api("/ping")).get().build(), Http.probeClient)
    )

    /** True when the URL answers a Comii ping. */
    suspend fun isComiiServer(): Boolean = runCatching { probePing().status == "ok" }.getOrDefault(false)

    fun requestBuilder(path: String): Request.Builder = Request.Builder().url(api(path))

    fun rawRequest(path: String): Request = Request.Builder().url(api(path)).get().build()

    fun multipartBody(): MultipartBody.Builder = MultipartBody.Builder().setType(MultipartBody.FORM)

    class UploadPart(val fileName: String, val body: RequestBody)

    fun buildUpload(parts: List<UploadPart>, folder: String, name: String): MultipartBody {
        val builder = multipartBody().addFormDataPart("folder", folder).addFormDataPart("name", name)
        parts.forEach { part ->
            builder.addFormDataPart("files", part.fileName, part.body)
        }
        return builder.build()
    }
}
