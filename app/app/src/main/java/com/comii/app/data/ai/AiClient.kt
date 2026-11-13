package com.comii.app.data.ai

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AiException(override val message: String) : Exception(message)

/**
 * 只保留对话所需的最小 OpenAI 兼容实现：POST /chat/completions。
 * 流式返回按 SSE 解析，非流式直接读 JSON；思维链（reasoning）默认忽略。
 */
class AiClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) return base
        return if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
    }

    private fun request(config: AiConfig, messages: List<ChatMessage>, temperature: Double): Request {
        val lastUserIndex = messages.indexOfLast { it.role == "user" }
        val payload = buildJsonObject {
            put("model", config.model.trim())
            put("temperature", temperature)
            put("stream", config.stream)
            putJsonArray("messages") {
                messages.forEachIndexed { index, message ->
                    addJsonObject {
                        put("role", message.role)
                        val image = message.imagePath
                            ?.takeIf { index == lastUserIndex }
                            ?.let { imageDataUrl(it) }
                        if (image != null) {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", message.content.ifBlank { "看看这张图" })
                                }
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", image) }
                                }
                            }
                        } else {
                            val text = if (message.imagePath != null) {
                                (message.content + "（附图）").trim()
                            } else {
                                message.content
                            }
                            put("content", text)
                        }
                    }
                }
            }
        }
        val builder = Request.Builder()
            .url(endpoint(config.baseUrl))
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/126.0 Mobile Safari/537.36 Comii/2.7",
            )
            .header("Accept", if (config.stream) "text/event-stream" else "application/json")
        if (config.apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer ${config.apiKey.trim()}")
        }
        return builder.build()
    }

    fun stream(
        config: AiConfig,
        messages: List<ChatMessage>,
        temperature: Double,
        onDelta: (String) -> Unit,
    ): String {
        val response = client.newCall(request(config, messages, temperature)).execute()
        response.use {
            if (!it.isSuccessful) throw AiException(errorMessage(it.body?.string().orEmpty(), it.code))
            val source = it.body?.source() ?: throw AiException("服务器没有返回内容")
            val answer = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val content = runCatching {
                    val root = json.parseToJsonElement(payload).jsonObject
                    val choices = root["choices"]?.jsonArray ?: return@runCatching null
                    if (choices.isEmpty()) return@runCatching null
                    choices[0].jsonObject["delta"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (!content.isNullOrEmpty()) {
                    answer.append(content)
                    onDelta(content)
                }
            }
            if (answer.isEmpty()) {
                throw AiException("模型没有返回内容，请检查模型名或接口地址")
            }
            return answer.toString()
        }
    }

    fun complete(
        config: AiConfig,
        messages: List<ChatMessage>,
        temperature: Double,
    ): String {
        val response = client.newCall(request(config, messages, temperature)).execute()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw AiException(errorMessage(body, it.code))
            val content = runCatching {
                val root = json.parseToJsonElement(body).jsonObject
                root["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
                    ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (content.isNullOrBlank()) throw AiException("模型没有返回内容，请检查模型名或接口地址")
            return content
        }
    }

    private fun errorMessage(body: String, code: Int): String {
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull?.let { return it }
            root["message"]?.jsonPrimitive?.contentOrNull?.let { return it }
        }
        return "请求失败（HTTP $code）"
    }

    /** 把本地图片压到最长边 1024，转成 data URL 发给多模态模型。 */
    private fun imageDataUrl(path: String): String? {
        val file = java.io.File(path)
        if (!file.isFile) return null
        val original = android.graphics.BitmapFactory.decodeFile(path) ?: return null
        val maxSide = 1024
        val bitmap = if (original.width <= maxSide && original.height <= maxSide) {
            original
        } else {
            val ratio = minOf(maxSide.toFloat() / original.width, maxSide.toFloat() / original.height)
            android.graphics.Bitmap.createScaledBitmap(
                original,
                (original.width * ratio).toInt().coerceAtLeast(1),
                (original.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
        val buffer = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, buffer)
        if (bitmap !== original) bitmap.recycle()
        original.recycle()
        val encoded = android.util.Base64.encodeToString(buffer.toByteArray(), android.util.Base64.NO_WRAP)
        return "data:image/jpeg;base64,$encoded"
    }
}
