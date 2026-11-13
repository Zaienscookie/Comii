package com.comii.app.data.ai

import android.content.Context
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
            "你是${persona.name}。${persona.tagline} 你有自己独立的生活，可以出门、见朋友、做自己的事；" +
                "保持角色设定，用自然、简短、有人情味的方式聊天。"
        })
        messages += ChatMessage(
            "system",
            "始终以角色身份说话，不要自称 AI/模型，不要提模型名称或参数；不要解释你是如何生成回复的。",
        )
        if (persona.memory.isNotBlank()) {
            messages += ChatMessage("system", "你记得这些事：\n${persona.memory.trim()}")
        }
        messages += ChatMessage("system", heartPrompt(persona))
        if (config.segmentReplies) {
            messages += ChatMessage(
                "system",
                "如果你想像真人发消息一样连发几条，请用 ||| 分隔每条消息，每条 1-2 句；" +
                    "不需要分段时就正常回复，不要强行加分隔符。",
            )
        }
        persona.pluginIds.forEach { pluginId ->
            AiPluginRegistry.find(pluginId)?.inject(persona)?.takeIf { it.isNotBlank() }?.let {
                messages += ChatMessage("system", it)
            }
        }
        val hasImage = conversation.messages.lastOrNull { it.role == "user" }?.imagePath != null
        if (hasImage) {
            messages += ChatMessage("system", "对方刚发来一张图片，请先理解图片内容再回答，不要忽略这张图。")
        }
        val limit = config.maxContextMessages.coerceIn(2, 200)
        if (config.segmentReplies) {
            messages += ChatMessage("system", "这一轮请把回复拆成 2-4 条短消息，用 ||| 分隔。")
        }
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
            appendLine("今天是 $today。请按角色设定自然地安排这一天，不要套用「8 点起床 / 12 点午饭」这类模板；")
            appendLine("时间、地点、活动要贴合角色身份和性格，可以出门、上班、见朋友。如果设定信息很少，就安排简单的一天。")
            appendLine("输出 4-6 行，带具体时间；最后两行分别写「心情：…」和「穿搭：…」。")
            appendLine("只输出安排本身，不要解释。")
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
        imagePath: String? = null,
        appendUser: Boolean = true,
        onMessage: (ChatMessage) -> Unit = {},
        onDelta: (String) -> Unit = {},
    ): ChatMessage = withContext(Dispatchers.IO + NonCancellable) {
        val persona = persona(personaId)?.let {
            it.copy(affection = (it.affection + 1).coerceAtMost(100))
        } ?: throw AiException("角色不存在")
        val withHeart = updateHeart(persona, text)
        savePersona(withHeart)
        val conversation = conversation(conversationId) ?: throw AiException("会话不存在")
        val config = config()
        val userMessage = ChatMessage("user", text.trim(), now(), imagePath)
        var current = if (appendUser) {
            conversation.copy(
                messages = conversation.messages + userMessage,
                updatedAt = now(),
                title = if (conversation.title == "新对话") text.trim().take(18) else conversation.title,
            )
        } else {
            conversation
        }
        replaceConversation(current)

        val context = buildMessages(withHeart, current)
        val temperature = withHeart.temperature ?: config.temperature
        val answer = try {
            if (config.stream) {
                client.stream(config, context, temperature, onDelta)
            } else {
                client.complete(config, context, temperature).also(onDelta)
            }
        } catch (error: Exception) {
            throw if (error is AiException) error else AiException(error.message ?: "请求失败")
        }

        val parts = if (config.segmentReplies) {
            val byDelimiter = answer.split("|||").map { it.trim() }.filter { it.isNotEmpty() }
            if (byDelimiter.size > 1) {
                byDelimiter
            } else {
                answer.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
            }
        } else {
            emptyList()
        }
        val answers = parts.ifEmpty { listOf(answer.trim()) }
        var last = ChatMessage("assistant", answers.last(), now())
        answers.forEachIndexed { index, part ->
            val message = ChatMessage("assistant", part, now() + index)
            current = current.copy(messages = current.messages + message, updatedAt = now())
            replaceConversation(current)
            onMessage(message)
            last = message
            if (answers.size > 1 && index != answers.lastIndex) delay(650)
        }
        runCatching { maybeSummarize(withHeart, current) }
        last
    }

    /** 把回复中连续发来的多条消息合并成一次请求，但消息本身仍逐条保存。 */
    suspend fun sendBatch(
        personaId: String,
        conversationId: String,
        outgoing: List<ChatMessage>,
        onMessage: (ChatMessage) -> Unit = {},
        onDelta: (String) -> Unit = {},
    ): ChatMessage = withContext(Dispatchers.IO + NonCancellable) {
        if (outgoing.isEmpty()) throw AiException("没有要发送的消息")
        val persona = persona(personaId)?.let {
            it.copy(affection = (it.affection + outgoing.size).coerceAtMost(100))
        } ?: throw AiException("角色不存在")
        val joined = outgoing.joinToString("\n") { it.content }
        val withHeart = updateHeart(persona, joined)
        savePersona(withHeart)
        val conversation = conversation(conversationId) ?: throw AiException("会话不存在")
        val config = config()
        var current = conversation.copy(
            messages = conversation.messages + outgoing,
            updatedAt = now(),
            title = if (conversation.title == "新对话") joined.take(18) else conversation.title,
        )
        replaceConversation(current)
        val context = buildMessages(withHeart, current)
        val temperature = withHeart.temperature ?: config.temperature
        val answer = try {
            if (config.stream) client.stream(config, context, temperature, onDelta)
            else client.complete(config, context, temperature).also(onDelta)
        } catch (error: Exception) {
            throw if (error is AiException) error else AiException(error.message ?: "请求失败")
        }
        val parts = if (config.segmentReplies) {
            val byDelimiter = answer.split("|||").map { it.trim() }.filter { it.isNotEmpty() }
            if (byDelimiter.size > 1) byDelimiter else answer.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }
        val answers = parts.ifEmpty { listOf(answer.trim()) }
        var last = ChatMessage("assistant", answers.last(), now())
        answers.forEachIndexed { index, part ->
            val message = ChatMessage("assistant", part, now() + index)
            current = current.copy(messages = current.messages + message, updatedAt = now())
            replaceConversation(current)
            onMessage(message)
            last = message
            if (answers.size > 1 && index != answers.lastIndex) delay(650)
        }
        runCatching { maybeSummarize(withHeart, current) }
        last
    }

    /** 先把用户消息写进会话（用于回复中继续发消息时立即上屏）。 */
    suspend fun appendUserMessage(
        personaId: String,
        conversationId: String,
        text: String,
        imagePath: String? = null,
    ): ChatMessage = withContext(Dispatchers.IO) {
        val conversation = conversation(conversationId) ?: throw AiException("会话不存在")
        val message = ChatMessage("user", text.trim(), now(), imagePath)
        replaceConversation(
            conversation.copy(
                messages = conversation.messages + message,
                updatedAt = now(),
                title = if (conversation.title == "新对话") text.trim().take(18) else conversation.title,
            )
        )
        message
    }

    /** 九维情绪/状态/念头：简化版「鱼的心」。 */
    private fun updateHeart(persona: Persona, text: String): Persona {
        val heart = persona.heart
        val timestamp = now()
        val hours = if (heart.updatedAt > 0) {
            ((timestamp - heart.updatedAt) / 3_600_000.0).coerceIn(0.0, 72.0)
        } else {
            0.0
        }
        fun decay(value: Double, baseline: Double, rate: Double): Double {
            val factor = if (value > baseline) 1 - rate * hours else 1.0
            return (baseline + (value - baseline) * factor).coerceIn(0.0, 100.0)
        }
        var attachment = decay(heart.attachment, 45.0, 0.01)
        var curiosity = decay(heart.curiosity, 55.0, 0.03)
        var reflection = decay(heart.reflection, 50.0, 0.04)
        var duty = decay(heart.duty, 50.0, 0.03)
        var social = decay(heart.social, 45.0, 0.04)
        var fatigue = decay(heart.fatigue, 20.0, 0.08)
        var intimacy = decay(heart.intimacy, 40.0, 0.01)
        var stress = decay(heart.stress, 15.0, 0.04)
        var joy = decay(heart.joy, 50.0, 0.05)
        var blame = (heart.blame - 3.0 * hours).coerceIn(0.0, 100.0)

        val lowered = text.lowercase()
        val sens = persona.heartSensitivity.coerceIn(0.3, 2.0)
        val praise = listOf("厉害", "真棒", "喜欢", "谢谢", "可爱", "贴心", "乖", "好看")
        val apology = listOf("对不起", "抱歉", "我错了", "别生气", "原谅")
        val blameWords = listOf("烦", "滚", "闭嘴", "讨厌", "别烦", "不用你")
        val question = listOf("？", "?", "吗", "怎么", "为什么", "帮我")

        if (praise.any { lowered.contains(it) }) {
            joy += 4.0 * sens
            attachment += 2.0 * sens
            intimacy += 2.0 * sens
            stress -= 2.0 * sens
        }
        if (apology.any { lowered.contains(it) }) {
            blame -= 12.0 * sens
            stress -= 5.0 * sens
        }
        if (blameWords.any { lowered.contains(it) }) {
            blame += 18.0 * sens
            stress += 8.0 * sens
            joy -= 5.0 * sens
            attachment -= 2.0 * sens
        }
        if (question.any { lowered.contains(it) }) curiosity += 1.5 * sens
        social += 1.2 * sens
        intimacy += 0.1 * sens
        fatigue += 0.4 * sens

        val state = heartState(blame)
        val thoughts = when (state) {
            "生气" -> listOf("还在想刚才那句话", "有点委屈，不想先服软", "想听你解释清楚")
            "委屈" -> listOf("想被哄一下", "是不是我太黏人了", "想等你先开口")
            "害怕" -> listOf("怕你真的不想理我了", "想确认你还在", "不太敢多说")
            "回避" -> listOf("想安静一会儿", "暂时不想解释", "先冷一冷")
            else -> listOf("想找他说话", "想知道他今天怎么样", "想被夸一句", "想安静待一会儿", "想分享刚看到的东西")
        }
        val thought = if (heart.thought.isBlank() || timestamp - heart.updatedAt > 2 * 3_600_000L) {
            thoughts.random()
        } else {
            heart.thought
        }
        val updated = heart.copy(
            attachment = attachment.coerceIn(0.0, 100.0),
            curiosity = curiosity.coerceIn(0.0, 100.0),
            reflection = reflection.coerceIn(0.0, 100.0),
            duty = duty.coerceIn(0.0, 100.0),
            social = social.coerceIn(0.0, 100.0),
            fatigue = fatigue.coerceIn(0.0, 100.0),
            intimacy = intimacy.coerceIn(0.0, 100.0),
            stress = stress.coerceIn(0.0, 100.0),
            joy = joy.coerceIn(0.0, 100.0),
            blame = blame.coerceIn(0.0, 100.0),
            thought = thought,
            updatedAt = timestamp,
        )
        return persona.copy(heart = updated)
    }

    private fun heartState(blame: Double): String = when {
        blame >= 80 -> "回避"
        blame >= 70 -> "害怕"
        blame >= 55 -> "生气"
        blame >= 25 -> "委屈"
        else -> "日常"
    }

    private fun heartPrompt(persona: Persona): String {
        val heart = persona.heart
        val state = heartState(heart.blame)
        val closeness = when {
            heart.intimacy >= 70 -> "很亲近，说话可以更放松、更黏一点"
            heart.intimacy >= 50 -> "关系不错，像熟人一样自然"
            else -> "还在慢慢熟悉，保持一点分寸"
        }
        return buildString {
            appendLine("你此刻的状态：$state。")
            appendLine("依恋 ${heart.attachment.toInt()}，亲密 ${heart.intimacy.toInt()}，压力 ${heart.stress.toInt()}，疲惫 ${heart.fatigue.toInt()}，喜悦 ${heart.joy.toInt()}。")
            appendLine("关系：$closeness。")
            appendLine("你现在心里在惦记：${heart.thought}。")
            append("说话方式要符合这个状态，不要把数值直接说出来。如果状态是生气/委屈，可以短一点、带点情绪；如果开心，可以主动分享。")
        }
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
        val heart = persona.heart
        val now = now()
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        // 人不会凌晨发消息：8 点前、23 点后不主动
        if (hour < 8 || hour >= 23) return@withContext null
        if (heart.nextProactiveAt == 0L) {
            savePersona(
                persona.copy(
                    heart = heart.copy(
                        nextProactiveAt = now + (1..4).random() * 3_600_000L,
                    )
                )
            )
            return@withContext null
        }
        if (now < heart.nextProactiveAt) return@withContext null
        if (heart.blame >= 80) {
            savePersona(persona.copy(heart = heart.copy(nextProactiveAt = now + 2 * 3_600_000L)))
            return@withContext null
        }
        val modifier = when {
            heart.blame >= 55 -> 0.4
            heart.fatigue >= 75 -> 0.6
            heart.intimacy >= 65 -> 1.4
            else -> 1.0
        }
        val chance = (persona.proactiveChance.coerceIn(0.05, 0.95) * modifier).coerceIn(0.05, 0.95)
        if (kotlin.random.Random.nextDouble() > chance) {
            savePersona(
                persona.copy(
                    heart = heart.copy(nextProactiveAt = now + (1..3).random() * 3_600_000L),
                )
            )
            return@withContext null
        }
        val conversation = conversations(personaId).firstOrNull() ?: createConversation(personaId)
        val config = config()
        val instructions = ChatMessage(
            "system",
            "现在没有新消息。请以${persona.name}的身份主动发一条 1-3 句的短消息。" +
                "要像真人随手发来的：可以分享刚刚在做的小事、吐槽、突然想到对方、发一句没头没尾的话；" +
                "不要每次都问候、不要用固定句式、不要解释你在做什么。结合当前时间、虚拟生活状态和最近聊过的事。",
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
        // 下一次随机落在 2~6 小时后，并保证仍在 8:00~23:00 之间
        var next = now + ((2..6).random() * 3_600_000L) + kotlin.random.Random.nextLong(0, 1_800_000L)
        val nextCalendar = java.util.Calendar.getInstance().apply { timeInMillis = next }
        val nextHour = nextCalendar.get(java.util.Calendar.HOUR_OF_DAY)
        if (nextHour >= 23 || nextHour < 8) {
            nextCalendar.add(java.util.Calendar.DAY_OF_MONTH, if (nextHour >= 23) 1 else 0)
            nextCalendar.set(java.util.Calendar.HOUR_OF_DAY, 9)
            nextCalendar.set(java.util.Calendar.MINUTE, (0..50).random())
            next = nextCalendar.timeInMillis
        }
        savePersona(
            persona.copy(
                lastProactiveAt = now,
                nextProactiveAt = next,
                heart = heart.copy(nextProactiveAt = next, updatedAt = now),
            )
        )
        message
    }

    /** 让模型生成一份「主动消息时刻表」，默认只在设置里展开查看。 */
    suspend fun ensureProactivePlan(personaId: String): String = withContext(Dispatchers.IO) {
        val persona = persona(personaId) ?: return@withContext ""
        if (!persona.proactive || persona.heart.plan.isNotBlank()) return@withContext persona.heart.plan
        val prompt = ChatMessage(
            "system",
            "根据角色的生活节奏，为今天列 3-5 个可能主动发消息的时间点。" +
                "要像真人：分布在上午/下午/晚上，避开睡觉时间，不要均匀间隔；" +
                "每行格式「HH:mm 可能因为什么开口」。只输出列表，不要解释。",
        )
        val text = runCatching {
            client.complete(
                config = config().copy(stream = false),
                messages = listOf(prompt),
                temperature = 0.9,
            )
        }.getOrNull()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            savePersona(persona.copy(heart = persona.heart.copy(plan = text)))
        }
        text
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
