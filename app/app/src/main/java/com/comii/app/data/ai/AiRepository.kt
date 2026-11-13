package com.comii.app.data.ai

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    private fun now() = System.currentTimeMillis()

    companion object {
        private val DEMO_PERSONA = Persona(
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
