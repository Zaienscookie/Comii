package com.comii.app.data.ai

import kotlinx.serialization.Serializable

/** 与 OpenAI /chat/completions 兼容的配置；用户可以随时在 App 里改。 */
@Serializable
data class AiConfig(
    val baseUrl: String = "https://yachiyo8000-maxtoken.pages.dev/v1",
    val apiKey: String = "",
    val model: String = "Yachiyo-v8000-flash",
    val temperature: Double = 0.8,
    val maxContextMessages: Int = 20,
    val stream: Boolean = true,
    /** 为 false 时不显示模型的思维链（reasoning 字段）。 */
    val showReasoning: Boolean = false,
    /** 多模态：允许发送图片。 */
    val multimodal: Boolean = false,
    /** 连续发多条时先等一下，合并成一轮再回复。 */
    val aggregate: Boolean = false,
    /** 让模型像真人一样把回复拆成多条短消息。 */
    val segmentReplies: Boolean = false,
)

/** 虚拟人生：每天生成一份生活安排，聊天时注入当前状态（参考 astrbot_plugin_virtual_life）。 */
@Serializable
data class VirtualLife(
    val enabled: Boolean = true,
    val city: String = "",
    val occupation: String = "",
    /** 今天的安排（几行文字，带时间与活动）。 */
    val schedule: String = "",
    val mood: String = "",
    val outfit: String = "",
    /** 生成日期 yyyy-MM-dd，用来判断是否是今天的安排。 */
    val day: String = "",
)

/** 参考 astrbot_plugin_fish_heart 的简化情绪模型：九维 + 状态 + 念头。 */
@Serializable
data class Heart(
    val attachment: Double = 45.0,
    val curiosity: Double = 55.0,
    val reflection: Double = 50.0,
    val duty: Double = 50.0,
    val social: Double = 45.0,
    val fatigue: Double = 20.0,
    val intimacy: Double = 40.0,
    val stress: Double = 15.0,
    val joy: Double = 50.0,
    val blame: Double = 0.0,
    val thought: String = "",
    val plan: String = "",
    val updatedAt: Long = 0L,
    val nextProactiveAt: Long = 0L,
)

/** 一个人格（OC 角色），对应 AstrBot 里的 Persona。 */
@Serializable
data class Persona(
    val id: String,
    val name: String,
    val tagline: String = "",
    val systemPrompt: String = "",
    val greeting: String = "",
    /** 长期记忆 / 用户档案，每次对话都会注入。 */
    val memory: String = "",
    val color: Long = 0xFF0A84FF,
    val pluginIds: List<String> = emptyList(),
    val temperature: Double? = null,
    val createdAt: Long = 0L,
    val vlife: VirtualLife = VirtualLife(),
    /** 主动消息：打开 App 时若超过间隔，会让角色主动发一条并通知。 */
    val proactive: Boolean = false,
    val proactiveIntervalHours: Int = 6,
    val lastProactiveAt: Long = 0L,
    /** 下一次主动消息的时间点（随机生成，避免像定时任务）。 */
    val nextProactiveAt: Long = 0L,
    /** 头像文件路径（App 私有目录）。 */
    val avatarPath: String = "",
    /** 好感度，随聊天自动增长。 */
    val affection: Int = 0,
    val heart: Heart = Heart(),
    /** 情绪灵敏度，越大越容易被带动。 */
    val heartSensitivity: Double = 1.0,
    /** 主动消息基础概率 0~1。 */
    val proactiveChance: Double = 0.6,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = 0L,
    /** 多模态图片路径（仅保存在本机）。 */
    val imagePath: String? = null,
)

/** 一个会话 = 某个人格下的一段连续对话（相当于 AstrBot 的 session）。 */
@Serializable
data class Conversation(
    val id: String,
    val personaId: String,
    val title: String = "新对话",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = 0L,
)

@Serializable
data class AiState(
    val config: AiConfig = AiConfig(),
    val personas: List<Persona> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val seeded: Boolean = false,
)
