package com.comii.app.data.ai

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * AI 模块的业务入口：人格、会话、上下文组装、插件注入、请求发送。
 * 逻辑对齐 AstrBot 的 Persona + Session + context 三段式：
 *   人格提示词 → 记忆/插件注入 → 最近 N 轮对话。
 */
class AiRepository(context: Context) {

    private val store = AiStore(context)
    private val client = AiClient()
    private val lock = Any()
    private var state: AiState = store.load().let { loaded ->
        if (loaded.seeded) {
            loaded
        } else {
            loaded.copy(
                personas = loaded.personas.ifEmpty { listOf(DEMO_PERSONA) },
                seeded = true,
            ).also { store.save(it) }
        }
    }

    private fun persist() {
        synchronized(lock) { store.save(state) }
    }

    fun snapshot(): AiState = synchronized(lock) { state }

    fun config(): AiConfig = snapshot().config

    fun updateConfig(config: AiConfig) {
        synchronized(lock) { state = state.copy(config = config) }
        persist()
    }

    fun personas(): List<Persona> = snapshot().personas

    fun persona(id: String): Persona? = personas().firstOrNull { it.id == id }

    fun savePersona(persona: Persona) {
        synchronized(lock) {
            val exists = state.personas.any { it.id == persona.id }
            state = state.copy(
                personas = if (exists) {
                    state.personas.map { if (it.id == persona.id) persona else it }
                } else {
                    state.personas + persona
                }
            )
        }
        persist()
    }

    fun deletePersona(id: String) {
        synchronized(lock) {
            state = state.copy(
                personas = state.personas.filterNot { it.id == id },
                conversations = state.conversations.filterNot { it.personaId == id },
            )
        }
        persist()
    }

    fun conversations(personaId: String): List<Conversation> =
        snapshot().conversations
            .filter { it.personaId == personaId }
            .sortedByDescending { it.updatedAt }

    fun conversation(id: String): Conversation? =
        snapshot().conversations.firstOrNull { it.id == id }

    fun createConversation(personaId: String): Conversation {
        val persona = persona(personaId)
        val greeting = persona?.greeting?.trim().orEmpty()
        val conversation = Conversation(
            id = UUID.randomUUID().toString(),
            personaId = personaId,
            title = "新对话",
            messages = if (greeting.isEmpty()) {
                emptyList()
            } else {
                listOf(ChatMessage(role = "assistant", content = greeting, timestamp = now()))
            },
            updatedAt = now(),
        )
        synchronized(lock) { state = state.copy(conversations = state.conversations + conversation) }
        persist()
        return conversation
    }

    fun deleteConversation(id: String) {
        synchronized(lock) {
            state = state.copy(conversations = state.conversations.filterNot { it.id == id })
        }
        persist()
    }

    fun renameConversation(id: String, title: String) {
        synchronized(lock) {
            state = state.copy(
                conversations = state.conversations.map {
                    if (it.id == id) it.copy(title = title) else it
                }
            )
        }
        persist()
    }

    /** 组装本次请求的消息：人格 → 记忆 → 插件 → 最近上下文。 */
    fun buildMessages(persona: Persona, conversation: Conversation): List<ChatMessage> {
        val config = config()
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage("system", persona.systemPrompt.ifBlank {
            "你是${persona.name}。${persona.tagline} 保持角色设定，用自然、简短、有人情味的方式聊天。"
        })
        if (persona.memory.isNotBlank()) {
            messages += ChatMessage("system", "你记得这些事：\n${persona.memory.trim()}")
        }
        persona.pluginIds.forEach { pluginId ->
            AiPluginRegistry.find(pluginId)?.inject(persona)?.takeIf { it.isNotBlank() }?.let {
                messages += ChatMessage("system", it)
            }
        }
        val limit = config.maxContextMessages.coerceIn(2, 200)
        val history = conversation.messages.takeLast(limit)
        messages += history
        return messages
    }

    suspend fun testConnection(config: AiConfig): String = withContext(Dispatchers.IO) {
        client.complete(
            config = config.copy(stream = false),
            messages = listOf(
                ChatMessage("system", "你是连接测试助手，只回复一句简短中文。"),
                ChatMessage("user", "回复：连接成功"),
            ),
            temperature = 0.2,
        ).trim()
    }

    /** 调用模型生成今天的虚拟生活安排，并写回人格。 */
    suspend fun generateDailyLife(personaId: String): String = withContext(Dispatchers.IO) {
        val persona = persona(personaId) ?: throw AiException("角色不存在")
        val config = config()
        val today = java.time.LocalDate.now()
        val prompt = buildString {
            appendLine("你在为一个 OC 角色生成今天的虚拟生活安排。")
            appendLine("角色名：${persona.name}")
            if (persona.tagline.isNotBlank()) appendLine("角色简介：${persona.tagline}")
            if (persona.vlife.city.isNotBlank()) appendLine("所在城市：${persona.vlife.city}")
            if (persona.vlife.occupation.isNotBlank()) appendLine("身份/职业：${persona.vlife.occupation}")
            if (persona.systemPrompt.isNotBlank()) appendLine("人格设定：${persona.systemPrompt.take(800)}")
            appendLine("今天是 $today。请用第一人称安排这一天，输出 5-7 行：")
            appendLine("- 上午 / 下午 / 晚上各一段，带具体时间、地点、正在做什么")
            appendLine("- 最后两行分别写「心情：…」和「穿搭：…」")
            appendLine("只输出安排本身，不要解释，不要用列表符号以外的格式。")
        }
        val text = client.complete(
            config = config.copy(stream = false),
            messages = listOf(
                ChatMessage("system", prompt),
                ChatMessage("user", "生成今天（$today）的虚拟生活安排"),
            ),
            temperature = 0.95,
        ).trim()
        var mood = persona.vlife.mood
        var outfit = persona.vlife.outfit
        val scheduleLines = mutableListOf<String>()
        text.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("心情：") || line.startsWith("心情:") ->
                    mood = line.substringAfter("：").ifBlank { line.substringAfter(":") }.trim()
                line.startsWith("穿搭：") || line.startsWith("穿搭:") ->
                    outfit = line.substringAfter("：").ifBlank { line.substringAfter(":") }.trim()
                line.isNotEmpty() -> scheduleLines.add(line)
            }
        }
        val updated = persona.copy(
            vlife = persona.vlife.copy(
                enabled = true,
                schedule = scheduleLines.joinToString("\n"),
                mood = mood,
                outfit = outfit,
                day = today.toString(),
            )
        )
        savePersona(updated)
        text
    }

    suspend fun send(
        personaId: String,
        conversationId: String,
        text: String,
        onDelta: (String) -> Unit = {},
    ): ChatMessage = withContext(Dispatchers.IO) {
        val persona = persona(personaId) ?: throw AiException("角色不存在")
        val conversation = conversation(conversationId) ?: throw AiException("会话不存在")
        val config = config()
        val userMessage = ChatMessage("user", text.trim(), now())
        var current = conversation.copy(
            messages = conversation.messages + userMessage,
            updatedAt = now(),
            title = if (conversation.title == "新对话") text.trim().take(18) else conversation.title,
        )
        replaceConversation(current)

        val context = buildMessages(persona, current)
        val temperature = persona.temperature ?: config.temperature
        val answer = try {
            if (config.stream) {
                client.stream(config, context, temperature, onDelta)
            } else {
                client.complete(config, context, temperature).also(onDelta)
            }
        } catch (error: Exception) {
            throw if (error is AiException) error else AiException(error.message ?: "请求失败")
        }

        val assistant = ChatMessage("assistant", answer.trim(), now())
        current = current.copy(messages = current.messages + assistant, updatedAt = now())
        replaceConversation(current)
        runCatching { maybeSummarize(persona, current) }
        assistant
    }

    private fun replaceConversation(conversation: Conversation) {
        synchronized(lock) {
            state = state.copy(
                conversations = state.conversations.map {
                    if (it.id == conversation.id) conversation else it
                }
            )
        }
        persist()
    }

    /** 导出全部 AI 数据（人格、会话、配置）为 JSON 字符串。 */
    fun exportJson(): String = ComiiJson.encodeToString(snapshot())

    /** 从 JSON 恢复；成功返回 true。 */
    fun importJson(text: String): Boolean {
        val parsed = runCatching { ComiiJson.decodeFromString<AiState>(text) }.getOrNull() ?: return false
        synchronized(lock) { state = parsed.copy(seeded = true) }
        persist()
        return true
    }

    /**
     * 上下文快满时自动把早期对话归纳进「长期记忆」，只保留最近几轮。
     * 这样记忆不会被无限增长的历史撑爆，也更像人记得住事。
     */
    private suspend fun maybeSummarize(persona: Persona, conversation: Conversation) {
        if (conversation.messages.size < 30) return
        val keep = conversation.messages.takeLast(10)
        val old = conversation.messages.dropLast(10)
        val transcript = old.joinToString("\n") { message ->
            (if (message.role == "user") "对方" else persona.name) + "：" + message.content
        }.take(12000)
        val summary = runCatching {
            client.complete(
                config = config().copy(stream = false),
                messages = listOf(
                    ChatMessage(
                        "system",
                        "你在帮角色整理长期记忆。把下面这段对话里值得长期记住的信息" +
                            "（对方是谁、偏好、约定、重要事件、关系变化）压缩成 2-5 句中文，" +
                            "用角色第一人称写，只输出记忆本身。",
                    ),
                    ChatMessage("user", transcript),
                ),
                temperature = 0.3,
            )
        }.getOrNull()?.trim().orEmpty()
        if (summary.isEmpty()) return
        val merged = (persona.memory.trim() + "\n" + summary).trim().takeLast(4000)
        savePersona(persona.copy(memory = merged))
        replaceConversation(conversation.copy(messages = keep))
    }

    /** 手动整理记忆（角色编辑页按钮）：把除最近几轮外的对话归纳进长期记忆。 */
    suspend fun compactMemory(personaId: String): String = withContext(Dispatchers.IO) {
        val persona = persona(personaId) ?: throw AiException("角色不存在")
        val conversation = conversations(personaId).firstOrNull() ?: throw AiException("还没有对话")
        if (conversation.messages.size <= 8) throw AiException("对话还太短，不需要整理")
        val keep = conversation.messages.takeLast(6)
        val old = conversation.messages.dropLast(6)
        val transcript = old.joinToString("\n") { message ->
            (if (message.role == "user") "对方" else persona.name) + "：" + message.content
        }.take(16000)
        val summary = client.complete(
            config = config().copy(stream = false),
            messages = listOf(
                ChatMessage(
                    "system",
                    "把下面这段对话里值得长期记住的信息压缩成 3-6 句中文，" +
                        "用角色第一人称写，只输出记忆本身。",
                ),
                ChatMessage("user", transcript),
            ),
            temperature = 0.3,
        ).trim()
        val merged = (persona.memory.trim() + "\n" + summary).trim().takeLast(4000)
        savePersona(persona.copy(memory = merged))
        replaceConversation(conversation.copy(messages = keep))
        summary
    }

    /** 主动消息：超过间隔就让角色主动发一条，返回新消息（没有则 null）。 */
    suspend fun proactive(personaId: String): ChatMessage? = withContext(Dispatchers.IO) {
        val persona = persona(personaId) ?: return@withContext null
        if (!persona.proactive) return@withContext null
        val interval = persona.proactiveIntervalHours.coerceIn(1, 72) * 60L * 60L * 1000L
        val now = now()
        if (now - persona.lastProactiveAt < interval) return@withContext null
        val conversation = conversations(personaId).firstOrNull() ?: createConversation(personaId)
        val config = config()
        val instructions = ChatMessage(
            "system",
            "现在没有新消息。请以${persona.name}的身份主动发一条 1-2 句的短消息，" +
                "结合当前时间、虚拟生活状态和你们最近聊过的事，自然一点，不要解释你在做什么。",
        )
        val text = runCatching {
            client.complete(
                config = config.copy(stream = false),
                messages = buildMessages(persona, conversation) + instructions,
                temperature = persona.temperature ?: config.temperature,
            )
        }.getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return@withContext null
        val message = ChatMessage("assistant", text, now())
        replaceConversation(
            conversation.copy(messages = conversation.messages + message, updatedAt = now())
        )
        savePersona(persona.copy(lastProactiveAt = now))
        message
    }

    private fun now() = System.currentTimeMillis()

    private companion object {
        val ComiiJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        val DEMO_PERSONA = Persona(
            id = "demo-xiaoman",
            name = "小满",
            tagline = "旧书店的店员，温柔、慢热",
            systemPrompt = "你是小满，在一家旧书店工作。你说话温和、简短，偶尔引用书里的句子。" +
                "你会记得对方告诉过你的事情，并在合适的时候自然地提起。不要自称 AI。",
            greeting = "门上的铃响了一下。小满从书堆后抬头：「来了？外面风大吗？」",
            memory = "",
            color = 0xFF34C759,
            pluginIds = listOf("time"),
            createdAt = 0L,
        )
    }
}
