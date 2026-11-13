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

object VirtualLifePlugin : AiPlugin {
    override val id = "vlife"
    override val name = "虚拟人生"
    override val description = "每天生成生活安排，聊天时带入当前活动、心情、穿搭（参考 AstrBot 插件）"
    override fun inject(persona: Persona): String? {
        val life = persona.vlife
        if (!life.enabled) return null
        val today = java.time.LocalDate.now().toString()
        val time = java.text.SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
        val builder = StringBuilder()
        builder.append("现在是 $today $time。")
        if (life.city.isNotBlank()) builder.append("你生活在${life.city}。")
        if (life.occupation.isNotBlank()) builder.append("你的身份是${life.occupation}。")
        if (life.schedule.isNotBlank()) {
            if (life.day == today) {
                builder.append("\n你今天的安排：\n${life.schedule.trim()}")
            } else {
                builder.append("\n（今天的安排还没更新，按平常的节奏生活即可。）")
            }
        }
        if (life.mood.isNotBlank()) builder.append("\n当前心情：${life.mood.trim()}。")
        if (life.outfit.isNotBlank()) builder.append("\n今天穿着：${life.outfit.trim()}。")
        builder.append("\n请自然地把这些状态带进对话，不要机械复述，除非对方问起。")
        return builder.toString()
    }
}

object AiPluginRegistry {
    val all: List<AiPlugin> = listOf(TimePlugin, AmbientPlugin)

    fun find(id: String): AiPlugin? = all.firstOrNull { it.id == id }
}
