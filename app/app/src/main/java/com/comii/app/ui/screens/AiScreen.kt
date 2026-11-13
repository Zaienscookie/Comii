package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.comii.app.data.ai.Persona
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.components.LargeTitle
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.theme.ComiiTheme

@Composable
fun AiScreen(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val ai = state.ai
    var version by remember { mutableIntStateOf(0) }
    val personas = remember(version) { ai.personas() }
    val conversations = remember(version) { ai.snapshot().conversations }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                LargeTitle("AI", "${personas.size} 个角色 · 对话只存在本机")
            }
            IconButton(onClick = { nav.navigate("ai/settings") }) {
                Icon(Icons.Rounded.Settings, contentDescription = "AI 设置", tint = palette.accent)
            }
            IconButton(onClick = { nav.navigate("ai/persona") }) {
                Icon(Icons.Rounded.Add, contentDescription = "新建角色", tint = palette.accent)
            }
        }
        Spacer(Modifier.height(12.dp))

        if (personas.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.AutoAwesome,
                title = "还没有角色",
                subtitle = "创建一个 OC 人格，给它写设定、记忆和插件",
                action = { PrimaryButton("创建角色", onClick = { nav.navigate("ai/persona") }, modifier = Modifier.width(180.dp)) },
            )
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 4.dp, bottom = 110.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(personas, key = { it.id }) { persona ->
                    val last = conversations
                        .filter { it.personaId == persona.id }
                        .maxByOrNull { it.updatedAt }
                    PersonaCard(persona = persona, preview = last?.messages?.lastOrNull()?.content) {
                        nav.navigate("ai/chat/${persona.id}")
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.card)
                            .clickable { nav.navigate("ai/persona") }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = palette.accent)
                        Spacer(Modifier.width(10.dp))
                        Text("新建角色", color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaCard(persona: Persona, preview: String?, onClick: () -> Unit) {
    val palette = ComiiTheme.palette
    val color = Color(persona.color.toInt())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.card)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                persona.name.take(1),
                color = color,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(persona.name, color = palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (persona.tagline.isNotBlank()) {
                Text(
                    persona.tagline,
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!preview.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    preview,
                    color = palette.textTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            Icons.Rounded.ChatBubbleOutline,
            contentDescription = "开始对话",
            tint = palette.textTertiary,
            modifier = Modifier
                .size(20.dp)
                .pointerInput(persona.id) { detectTapGestures { onClick() } },
        )
    }
}
