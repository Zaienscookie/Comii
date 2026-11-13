package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoCamera
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import coil.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiChatScreen(state: AppState, personaId: String, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val ai = state.ai
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var personaVersion by remember { mutableIntStateOf(0) }
    val persona = remember(personaId, personaVersion) { ai.persona(personaId) }
    val config = ai.config()

    var conversation by remember { mutableStateOf<Conversation?>(null) }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var draft by remember { mutableStateOf("") }
    var attachPath by remember { mutableStateOf<String?>(null) }
    var cameraPath by remember { mutableStateOf<String?>(null) }
    var pendingText by remember { mutableStateOf("") }
    var pendingBatch by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var pendingConversationId by remember { mutableStateOf<String?>(null) }
    var activeConversationId by remember { mutableStateOf<String?>(null) }
    var batchJob by remember { mutableStateOf<Job?>(null) }
    var streaming by remember { mutableStateOf(false) }
    var streamText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showSessions by remember { mutableStateOf(false) }
    var showLife by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            delay(420)
            val total = messages.size + if (streaming) 1 else 0
            if (total > 0) runCatching { listState.scrollToItem(total - 1) }
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val directory = java.io.File(context.filesDir, "ai_media").apply { mkdirs() }
            val target = java.io.File(directory, "img-${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        }.onSuccess { attachPath = it.absolutePath }
            .onFailure { error = "图片读取失败：${it.message}" }
    }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) attachPath = cameraPath
    }

    fun launchCamera() {
        runCatching {
            val directory = java.io.File(context.filesDir, "ai_media").apply { mkdirs() }
            val file = java.io.File(directory, "cam-${System.currentTimeMillis()}.jpg").apply { createNewFile() }
            cameraPath = file.absolutePath
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            takePicture.launch(uri)
        }.onFailure { error = "无法打开相机：${it.message}" }
    }

    // 虚拟人生自动运行：当天没有安排就静默生成，不需要手动点
    LaunchedEffect(personaId, personaVersion) {
        val current = ai.persona(personaId) ?: return@LaunchedEffect
        if (!current.vlife.enabled) return@LaunchedEffect
        // 设定太少（比如乱填的 111/411）就不硬套模板日程
        if ((current.systemPrompt.trim().length + current.tagline.trim().length) < 12) return@LaunchedEffect
        val today = java.time.LocalDate.now().toString()
        if (current.vlife.day != today || current.vlife.schedule.isBlank()) {
            runCatching { ai.generateDailyLife(personaId) }
            personaVersion++
        }
    }

    LaunchedEffect(personaId, reloadKey) {
        val conversations = ai.conversations(personaId)
        val current = conversations.firstOrNull() ?: ai.createConversation(personaId)
        conversation = current
        activeConversationId = current.id
        messages = current.messages
    }

    LaunchedEffect(messages.size, streaming) {
        val total = messages.size + if (streaming) 1 else 0
        if (total <= 0) return@LaunchedEffect
        runCatching { listState.scrollToItem(total - 1) }
    }

    var doSendBatchRef: ((String, List<ChatMessage>) -> Unit)? = null

    fun scheduleBatch(delayMs: Long = 550) {
        if (pendingBatch.isEmpty()) return
        val targetId = pendingConversationId ?: return
        batchJob?.cancel()
        batchJob = scope.launch {
            delay(delayMs)
            if (streaming && activeConversationId == targetId) return@launch
            val batch = pendingBatch
            pendingBatch = emptyList()
            doSendBatchRef?.invoke(targetId, batch)
        }
    }

    fun doSendBatch(conversationId: String, batch: List<ChatMessage>) {
        if (batch.isEmpty()) return
        error = null
        streaming = true
        streamText = ""
        scope.launch {
            try {
                ai.sendBatch(
                    personaId = personaId,
                    conversationId = conversationId,
                    outgoing = batch,
                    onMessage = { message ->
                        if (activeConversationId == conversationId) {
                            messages = messages + message
                            streamText = ""
                        }
                    },
                ) { delta -> if (activeConversationId == conversationId) streamText += delta }
                if (activeConversationId == conversationId) {
                    messages = ai.conversation(conversationId)?.messages ?: messages
                }
                personaVersion++
            } catch (failure: Exception) {
                if (activeConversationId == conversationId) {
                    error = failure.message ?: "请求失败"
                    messages = ai.conversation(conversationId)?.messages ?: messages
                }
            } finally {
                streaming = false
                streamText = ""
                if (activeConversationId == conversationId && pendingBatch.isNotEmpty()) {
                    scheduleBatch(200)
                }
            }
        }
    }
    doSendBatchRef = { id, batch -> doSendBatch(id, batch) }

    fun submit() {
        val text = draft.trim()
        val image = attachPath
        if (text.isEmpty() && image == null) return
        val current = conversation ?: return
        if (text.lowercase() in setOf("/reset", "reset", "/zreset", "zreset")) {
            draft = ""
            attachPath = null
            batchJob?.cancel()
            pendingBatch = emptyList()
            pendingConversationId = null
            ai.resetContext(personaId, current.id)
            val refreshed = ai.conversation(current.id)
            conversation = refreshed
            messages = refreshed?.messages ?: emptyList()
            state.showToast("已忘记上下文")
            return
        }
        draft = ""
        attachPath = null
        val message = ChatMessage("user", text, System.currentTimeMillis(), image)
        // 先上屏 + 立刻写入会话，保证发出去就一直在
        messages = messages + message
        scope.launch {
            runCatching { ai.appendMessage(current.id, message) }
        }
        if (pendingBatch.isEmpty()) pendingConversationId = current.id
        pendingBatch = pendingBatch + message
        if (!streaming) scheduleBatch()
    }

    fun switchConversation(target: Conversation) {
        batchJob?.cancel()
        pendingBatch = emptyList()
        pendingConversationId = null
        streaming = false
        streamText = ""
        conversation = target
        activeConversationId = target.id
        messages = target.messages
    }

    Column(Modifier.fillMaxSize().background(palette.background).statusBarsPadding().imePadding()) {
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
                val avatar = persona?.avatarPath?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
                if (avatar != null && avatar.isFile) {
                    AsyncImage(
                        model = avatar,
                        contentDescription = "头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(persona?.name?.take(1) ?: "?", color = Color((persona?.color ?: 0xFF0A84FF).toInt()), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(persona?.name ?: "角色", color = palette.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${heartStateLabel(persona?.heart?.blame ?: 0.0)} · 好感 ${persona?.affection ?: 0} · 上下文 ${config.maxContextMessages} 条",
                    color = palette.textSecondary,
                    fontSize = 10.sp,
                )
            }
            IconButton(onClick = {
                switchConversation(ai.createConversation(personaId))
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

        if (persona?.vlife?.enabled == true) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    persona.vlife.schedule.lines().firstOrNull()?.take(42)?.ifBlank { "今天的安排还没生成" }
                        ?: "今天的安排还没生成",
                    color = palette.textTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showLife = true }) {
                    Text("查看日程", color = palette.accent, fontSize = 11.sp)
                }
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
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.ChatBubbleOutline,
                    title = "和${persona?.name ?: "角色"}聊点什么",
                    subtitle = "它会记住这个人格设定和最近的对话",
                )
            }
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
                        if (streamText.isEmpty()) {
                            TypingBubble()
                        } else {
                            MessageBubble(
                                ChatMessage("assistant", streamText.replace("|||", "\n"), 0),
                                streaming = true,
                            )
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(palette.elevated)) {
            attachPath?.let { path ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = java.io.File(path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("将随下一条消息发送", color = palette.textSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { attachPath = null }) {
                        Icon(Icons.Rounded.Close, contentDescription = "移除图片", tint = palette.textTertiary)
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (config.multimodal) {
                    IconButton(onClick = { pickImage.launch("image/*") }) {
                        Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "发送图片", tint = palette.accent)
                    }
                    IconButton(onClick = { launchCamera() }) {
                        Icon(Icons.Rounded.PhotoCamera, contentDescription = "拍照", tint = palette.accent)
                    }
                }
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { focus ->
                            if (focus.isFocused) {
                                scope.launch {
                                    delay(320)
                                    val total = messages.size + if (streaming) 1 else 0
                                    if (total > 0) runCatching { listState.scrollToItem(total - 1) }
                                }
                            }
                        },
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
            IconButton(
                onClick = { submit() },
                enabled = draft.isNotBlank() || attachPath != null,
            ) {
                Icon(
                    Icons.Rounded.Send,
                    contentDescription = "发送",
                    tint = if (draft.isNotBlank() || attachPath != null) palette.accent else palette.textTertiary,
                )
            }
        }
        }
    }

    if (showLife) {
        val life = persona?.vlife
        BottomSheet(onDismiss = { showLife = false }) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("今天的安排", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Text(
                    life?.schedule?.ifBlank { "还没生成" } ?: "还没生成",
                    color = palette.textPrimary,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(14.dp))
                if (!life?.mood.isNullOrBlank()) {
                    Text("心情：${life?.mood}", color = palette.textSecondary, fontSize = 13.sp)
                }
                if (!life?.outfit.isNullOrBlank()) {
                    Text("穿搭：${life?.outfit}", color = palette.textSecondary, fontSize = 13.sp)
                }
                Spacer(Modifier.height(26.dp))
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
                        switchConversation(ai.createConversation(personaId))
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
                                switchConversation(session)
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
                                switchConversation(next)
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

private fun heartStateLabel(blame: Double): String = when {
    blame >= 80 -> "回避"
    blame >= 70 -> "害怕"
    blame >= 55 -> "生气"
    blame >= 25 -> "委屈"
    else -> "日常"
}

@Composable
private fun TypingBubble() {
    val palette = ComiiTheme.palette
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(600),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(palette.card)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("正在回复", color = palette.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            repeat(3) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(palette.accent.copy(alpha = alpha)),
                )
                Spacer(Modifier.width(3.dp))
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
            Column {
                val image = message.imagePath?.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
                if (image != null && image.isFile) {
                    AsyncImage(
                        model = image,
                        contentDescription = "图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    message.content + if (streaming) "▍" else "",
                    color = if (isUser) Color.White else palette.textPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}
