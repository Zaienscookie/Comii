package com.comii.app.data.ai

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简插件接口（demo）：在每次请求前向 system 注入一段上下文。
 * 真正的 AstrBot 插件是 Python 侧的能力；App 这里只保留可扩展的注入点。
 */
interface AiPlugin {
    val id: String
    val name: String
    val description: String
    fun inject(persona: Persona): String?
}

object TimePlugin : AiPlugin {
    override val id = "time"
    override val name = "时间感知"
    override val description = "让角色知道当前日期、时间和星期"
    override fun inject(persona: Persona): String {
        val now = Date()
        val date = SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(now)
        val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(now)
        return "现在是 $date $time。你需要自然地意识到当前时间，但不要机械地复述它。"
    }
}

object AmbientPlugin : AiPlugin {
    override val id = "ambient"
    override val name = "环境氛围"
    override val description = "每轮补充一点场景氛围，让对话更像真实相处"
    override fun inject(persona: Persona): String {
        val hour = SimpleDateFormat("H", Locale.CHINA).format(Date()).toIntOrNull() ?: 12
        val scene = when {
            hour < 6 -> "深夜，周围很安静，只有零星灯光。"
            hour < 11 -> "上午，光线明亮，空气里有一点清晨的凉意。"
            hour < 14 -> "午后，时间流速变慢，适合闲聊。"
            hour < 18 -> "下午，光线开始偏暖。"
            hour < 22 -> "傍晚到夜里，气氛放松下来。"
            else -> "夜深了，说话可以更轻一些。"
        }
        return "场景提示：$scene"
    }
}

object AiPluginRegistry {
    val all: List<AiPlugin> = listOf(TimePlugin, AmbientPlugin)

    fun find(id: String): AiPlugin? = all.firstOrNull { it.id == id }
}
