package com.comii.app.data.ai

import android.content.Context
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** AI 配置 / 人格 / 会话的本地持久化（App 私有目录，不上传服务器）。 */
class AiStore(context: Context) {

    private val file = File(context.filesDir, "ai_state.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val lock = Any()

    fun load(): AiState {
        synchronized(lock) {
            if (!file.isFile) return AiState()
            return runCatching {
                json.decodeFromString<AiState>(file.readText())
            }.getOrDefault(AiState())
        }
    }

    fun save(state: AiState) {
        synchronized(lock) {
            val temporary = File(file.parentFile, "ai_state.json.tmp")
            temporary.writeText(json.encodeToString(state))
            if (!temporary.renameTo(file)) {
                file.writeText(temporary.readText())
                temporary.delete()
            }
        }
    }
}
