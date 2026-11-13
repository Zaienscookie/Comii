package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.comii.app.data.ai.AiPluginRegistry
import com.comii.app.data.ai.Persona
import com.comii.app.data.ai.VirtualLife
import com.comii.app.ui.AppState
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.theme.ComiiTheme
import java.util.UUID
import kotlinx.coroutines.launch

private val PERSONA_COLORS = listOf(
    0xFF0A84FF, 0xFF34C759, 0xFFFF9F0A, 0xFFFF375F, 0xFFAF52DE, 0xFF5AC8FA,
)

@Composable
fun AiPersonaScreen(state: AppState, personaId: String?, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val ai = state.ai
    val scope = rememberCoroutineScope()
    val existing = remember(personaId) { personaId?.let { ai.persona(it) } }

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var tagline by remember { mutableStateOf(existing?.tagline.orEmpty()) }
    var systemPrompt by remember { mutableStateOf(existing?.systemPrompt.orEmpty()) }
    var greeting by remember { mutableStateOf(existing?.greeting.orEmpty()) }
    var memory by remember { mutableStateOf(existing?.memory.orEmpty()) }
    var color by remember { mutableStateOf(existing?.color ?: PERSONA_COLORS[0]) }
    var pluginIds by remember { mutableStateOf(existing?.pluginIds.orEmpty()) }
    var temperature by remember { mutableFloatStateOf((existing?.temperature ?: 0.8).toFloat()) }
    var city by remember { mutableStateOf(existing?.vlife?.city.orEmpty()) }
    var occupation by remember { mutableStateOf(existing?.vlife?.occupation.orEmpty()) }
    var schedule by remember { mutableStateOf(existing?.vlife?.schedule.orEmpty()) }
    var mood by remember { mutableStateOf(existing?.vlife?.mood.orEmpty()) }
    var outfit by remember { mutableStateOf(existing?.vlife?.outfit.orEmpty()) }
    var generating by remember { mutableStateOf(false) }
    var proactive by remember { mutableStateOf(existing?.proactive ?: false) }
    var proactiveHours by remember {
        mutableFloatStateOf((existing?.proactiveIntervalHours ?: 6).toFloat())
    }
    var compacting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (name.isBlank()) {
            error = "先给角色起个名字"
            return
        }
        val persona = Persona(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            tagline = tagline.trim(),
            systemPrompt = systemPrompt.trim(),
            greeting = greeting.trim(),
            memory = memory.trim(),
            color = color,
            pluginIds = pluginIds,
            temperature = temperature.toDouble(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            vlife = VirtualLife(
                enabled = pluginIds.contains("vlife"),
                city = city.trim(),
                occupation = occupation.trim(),
                schedule = schedule.trim(),
                mood = mood.trim(),
                outfit = outfit.trim(),
                day = existing?.vlife?.day.orEmpty(),
            ),
            proactive = proactive,
            proactiveIntervalHours = proactiveHours.toInt(),
            lastProactiveAt = existing?.lastProactiveAt ?: 0L,
        )
        ai.savePersona(persona)
        nav.popBackStack()
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "返回", tint = palette.accent, modifier = Modifier.size(20.dp))
            }
            Text(
                if (existing == null) "新建角色" else "编辑角色",
                color = palette.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { save() }) {
                Text("保存", color = palette.accent, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            error?.let {
                Text(it, color = palette.danger, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
            }

            field("名字", name, { name = it }, singleLine = true)
            field("一句话简介", tagline, { tagline = it }, singleLine = true)
            field(
                "人格设定（system prompt）",
                systemPrompt,
                { systemPrompt = it },
                minLines = 5,
                placeholder = "例如：你叫小满，在旧书店工作……说话温和简短，会记得对方说过的事。",
            )
            field(
                "开场白",
                greeting,
                { greeting = it },
                minLines = 2,
                placeholder = "打开对话时角色说的第一句话",
            )
            field(
                "长期记忆 / 用户档案",
                memory,
                { memory = it },
                minLines = 4,
                placeholder = "例如：对方叫阿澈，怕冷，喜欢旧书……（每次对话都会带上）",
            )

            Spacer(Modifier.height(16.dp))
            Text("主题色", color = palette.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PERSONA_COLORS.forEach { value ->
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(value.toInt()))
                            .clickable { color = value },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (color == value) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("温度 ${String.format("%.2f", temperature)}", color = palette.textSecondary, fontSize = 12.sp)
            Slider(
                value = temperature,
                onValueChange = { temperature = it },
                valueRange = 0f..1.5f,
            )

            Spacer(Modifier.height(10.dp))
            Text("插件（demo）", color = palette.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            AiPluginRegistry.all.forEach { plugin ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.card)
                        .clickable {
                            pluginIds = if (plugin.id in pluginIds) pluginIds - plugin.id else pluginIds + plugin.id
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(plugin.name, color = palette.textPrimary, fontSize = 14.sp)
                        Text(plugin.description, color = palette.textSecondary, fontSize = 11.sp)
                    }
                    Switch(
                        checked = plugin.id in pluginIds,
                        onCheckedChange = {
                            pluginIds = if (it) pluginIds + plugin.id else pluginIds - plugin.id
                        },
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("虚拟人生（参考 AstrBot 插件）", color = palette.textSecondary, fontSize = 12.sp)
            Text(
                "开启上面的「虚拟人生」插件后，聊天会自然带上今天的安排、心情和穿搭。",
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
            field("城市", city, { city = it }, singleLine = true, placeholder = "例如：杭州")
            field("身份 / 职业", occupation, { occupation = it }, singleLine = true, placeholder = "例如：旧书店店员 / 大学生")
            field(
                "今天的安排",
                schedule,
                { schedule = it },
                minLines = 5,
                placeholder = "上午 9:00 在书店整理旧书\n下午 ……\n晚上 ……",
            )
            field("当前心情", mood, { mood = it }, singleLine = true, placeholder = "例如：有点困，但心情不错")
            field("今天的穿搭", outfit, { outfit = it }, singleLine = true, placeholder = "例如：米色针织衫 + 长裙")
            TextButton(
                enabled = existing != null && !generating,
                onClick = {
                    val id = existing?.id ?: return@TextButton
                    scope.launch {
                        generating = true
                        error = null
                        runCatching { ai.generateDailyLife(id) }
                            .onSuccess {
                                val fresh = ai.persona(id)
                                schedule = fresh?.vlife?.schedule.orEmpty()
                                mood = fresh?.vlife?.mood.orEmpty()
                                outfit = fresh?.vlife?.outfit.orEmpty()
                            }
                            .onFailure { error = "生成失败：${it.message}" }
                        generating = false
                    }
                },
            ) {
                Text(
                    when {
                        generating -> "正在生成今日日程…"
                        existing == null -> "保存角色后可生成今日日程"
                        else -> "生成今日日程"
                    },
                    color = if (existing != null && !generating) palette.accent else palette.textTertiary,
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("主动发消息", color = palette.textPrimary, fontSize = 14.sp)
                    Text(
                        "打开 App 时检查，超过间隔就让 TA 主动发一条并弹通知",
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                    )
                }
                Switch(checked = proactive, onCheckedChange = { proactive = it })
            }
            if (proactive) {
                Text(
                    "间隔 ${proactiveHours.toInt()} 小时",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                )
                Slider(
                    value = proactiveHours,
                    onValueChange = { proactiveHours = it },
                    valueRange = 1f..24f,
                    steps = 22,
                )
            }
            TextButton(
                enabled = existing != null && !compacting,
                onClick = {
                    val id = existing?.id ?: return@TextButton
                    scope.launch {
                        compacting = true
                        error = null
                        runCatching { ai.compactMemory(id) }
                            .onSuccess { state.showToast("记忆已整理") }
                            .onFailure { error = "整理失败：${it.message}" }
                        compacting = false
                    }
                },
            ) {
                Text(
                    if (compacting) "正在整理记忆…" else "把旧对话归纳进长期记忆",
                    color = if (existing != null) palette.accent else palette.textTertiary,
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(24.dp))
            PrimaryButton(text = "保存角色", onClick = { save() })

            if (existing != null) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = {
                    if (confirmDelete) {
                        ai.deletePersona(existing.id)
                        nav.popBackStack()
                    } else {
                        confirmDelete = true
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (confirmDelete) "再点一次确认删除" else "删除这个角色",
                        color = palette.danger,
                        fontSize = 13.sp,
                    )
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = false,
    minLines: Int = 1,
    placeholder: String = "",
) {
    val palette = ComiiTheme.palette
    Spacer(Modifier.height(14.dp))
    Text(label, color = palette.textSecondary, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    TextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        minLines = minLines,
        placeholder = { Text(placeholder.ifBlank { label }, color = palette.textTertiary, fontSize = 13.sp) },
        shape = RoundedCornerShape(12.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = palette.card,
            unfocusedContainerColor = palette.card,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = palette.textPrimary,
            unfocusedTextColor = palette.textPrimary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
