package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.comii.app.data.ai.ChatMessage
import com.comii.app.data.ai.Conversation
import com.comii.app.ui.AppState
import com.comii.app.ui.components.BottomSheet
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

@Composable
fun AiChatScreen(state: AppState, personaId: String, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val ai = state.ai
    val scope = rememberCoroutineScope()
    val persona = remember(personaId) { ai.persona(personaId) }

    var conversation by remember { mutableStateOf<Conversation?>(null) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf(false) }
    var streamText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showSessions by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    LaunchedEffect(personaId, reloadKey) {
        val conversations = ai.conversations(personaId)
        val current = conversations.firstOrNull() ?: ai.createConversation(personaId)
        conversation = current
        messages = current.messages
    }

    LaunchedEffect(messages.size, streamText) {
        val total = messages.size + if (streaming) 1 else 0
        if (total > 0) runCatching { listState.animateScrollToItem(total - 1) }
    }

    fun send() {
        val text = draft.trim()
        val current = conversation ?: return
        if (text.isEmpty() || streaming) return
        draft = ""
        error = null
        streaming = true
        streamText = ""
        messages = messages + ChatMessage("user", text, System.currentTimeMillis())
        scope.launch {
            try {
                ai.send(personaId, current.id, text) { delta -> streamText += delta }
                messages = ai.conversation(current.id)?.messages ?: messages
            } catch (failure: Exception) {
                error = failure.message ?: "请求失败"
                messages = ai.conversation(current.id)?.messages ?: messages
            } finally {
                streaming = false
                streamText = ""
            }
        }
    }

    Column(Modifier.fillMaxSize().background(palette.background).imePadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "返回", tint = palette.accent, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier.size(34.dp).clip(CircleShape)
                    .background(Color((persona?.color ?: 0xFF0A84FF).toInt()).copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(persona?.name?.take(1) ?: "?", color = Color((persona?.color ?: 0xFF0A84FF).toInt()), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(persona?.name ?: "角色", color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "上下文 ${ai.config().maxContextMessages} 条 · 本机存储",
                    color = palette.textSecondary,
                    fontSize = 10.sp,
                )
            }
            IconButton(onClick = {
                conversation?.let { current ->
                    val fresh = ai.createConversation(personaId)
                    conversation = fresh
                    messages = fresh.messages
                    reloadKey++
                }
            }) {
                Icon(Icons.Rounded.Add, contentDescription = "新对话", tint = palette.accent)
            }
            IconButton(onClick = { showSessions = true }) {
                Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = "会话", tint = palette.accent)
            }
            IconButton(onClick = { nav.navigate("ai/persona?personaId=${personaId}") }) {
                Icon(Icons.Rounded.Edit, contentDescription = "编辑角色", tint = palette.accent)
            }
        }

        if (error != null) {
            Text(
                error ?: "",
                color = palette.danger,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        if (messages.isEmpty() && !streaming) {
            EmptyState(
                icon = Icons.Rounded.ChatBubbleOutline,
                title = "和${persona?.name ?: "角色"}聊点什么",
                subtitle = "它会记住这个人格设定和最近的对话",
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { it.timestamp.toString() + it.role + it.content.hashCode() }) { message ->
                    MessageBubble(message)
                }
                if (streaming) {
                    item(key = "streaming") {
                        MessageBubble(
                            ChatMessage("assistant", streamText.ifEmpty { "…" }, 0),
                            streaming = true,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.elevated)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("说点什么…", color = palette.textTertiary) },
                maxLines = 4,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = palette.card,
                    unfocusedContainerColor = palette.card,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary,
                ),
            )
            Spacer(Modifier.width(8.dp))
            if (streaming) {
                CircularProgressIndicator(
                    color = palette.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                IconButton(
                    onClick = { send() },
                    enabled = draft.isNotBlank(),
                ) {
                    Icon(
                        Icons.Rounded.Send,
                        contentDescription = "发送",
                        tint = if (draft.isNotBlank()) palette.accent else palette.textTertiary,
                    )
                }
            }
        }
    }

    if (showSessions) {
        val sessions = ai.conversations(personaId)
        BottomSheet(onDismiss = { showSessions = false }) {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("会话", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        val fresh = ai.createConversation(personaId)
                        conversation = fresh
                        messages = fresh.messages
                        showSessions = false
                    }) { Text("新对话", color = palette.accent) }
                }
                sessions.forEach { session ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (session.id == conversation?.id) palette.accent.copy(alpha = 0.12f) else palette.card)
                            .clickable {
                                conversation = session
                                messages = session.messages
                                showSessions = false
                            }
                            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(session.title, color = palette.textPrimary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${session.messages.size} 条消息",
                                color = palette.textSecondary,
                                fontSize = 11.sp,
                            )
                        }
                        IconButton(onClick = {
                            ai.deleteConversation(session.id)
                            if (session.id == conversation?.id) {
                                val remaining = ai.conversations(personaId)
                                val next = remaining.firstOrNull() ?: ai.createConversation(personaId)
                                conversation = next
                                messages = next.messages
                            }
                            reloadKey++
                        }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "删除会话", tint = palette.danger, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, streaming: Boolean = false) {
    val palette = ComiiTheme.palette
    val isUser = message.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    )
                )
                .background(if (isUser) palette.accent else palette.card)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                message.content + if (streaming) "▍" else "",
                color = if (isUser) Color.White else palette.textPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}
