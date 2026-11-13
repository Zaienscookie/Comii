package com.comii.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.comii.app.data.ai.AiPluginRegistry
import com.comii.app.data.ai.Persona
import com.comii.app.data.ai.VirtualLife
import com.comii.app.ui.AppState
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.theme.ComiiTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

private val PERSONA_COLORS = listOf(
    0xFF0A84FF, 0xFF34C759, 0xFFFF9F0A, 0xFFFF375F, 0xFFAF52DE, 0xFF5AC8FA,
)

@Composable
fun AiPersonaScreen(state: AppState, personaId: String?, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val ai = state.ai
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(personaId) { personaId?.let { ai.persona(it) } }

    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var tagline by remember { mutableStateOf(existing?.tagline.orEmpty()) }
    var systemPrompt by remember { mutableStateOf(existing?.systemPrompt.orEmpty()) }
    var pluginIds by remember { mutableStateOf(existing?.pluginIds.orEmpty()) }
    var temperature by remember { mutableFloatStateOf((existing?.temperature ?: 0.8).toFloat()) }
    var proactive by remember { mutableStateOf(existing?.proactive ?: false) }
    var avatarPath by remember { mutableStateOf(existing?.avatarPath.orEmpty()) }
    var cropSource by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input)
            }
        }.onSuccess { bitmap ->
            if (bitmap != null) cropSource = bitmap else error = "无法读取这张图片"
        }.onFailure { error = "头像读取失败：${it.message}" }
    }

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
            greeting = existing?.greeting.orEmpty(),
            memory = existing?.memory.orEmpty(),
            color = existing?.color ?: PERSONA_COLORS[0],
            pluginIds = pluginIds,
            temperature = temperature.toDouble(),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            vlife = (existing?.vlife ?: VirtualLife()).copy(enabled = pluginIds.contains("vlife")),
            proactive = proactive,
            proactiveIntervalHours = existing?.proactiveIntervalHours ?: 6,
            lastProactiveAt = existing?.lastProactiveAt ?: 0L,
            avatarPath = avatarPath,
            affection = existing?.affection ?: 0,
        )
        ai.savePersona(persona)
        nav.popBackStack()
    }

    Column(Modifier.fillMaxSize().background(palette.background).statusBarsPadding()) {
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

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(palette.card)
                        .clickable { pickAvatar.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    val avatar = avatarPath.takeIf { it.isNotBlank() }?.let { File(it) }
                    if (avatar != null && avatar.isFile) {
                        AsyncImage(
                            model = avatar,
                            contentDescription = "头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.textTertiary)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("头像", color = palette.textPrimary, fontSize = 14.sp)
                    Text("点一下从相册选择", color = palette.textSecondary, fontSize = 11.sp)
                }
            }

            field("名字", name, { name = it }, singleLine = true)
            field("一句话简介", tagline, { tagline = it }, singleLine = true)
            field(
                "人格设定（system prompt）",
                systemPrompt,
                { systemPrompt = it },
                minLines = 5,
                placeholder = "例如：你叫小满，在旧书店工作……说话温和简短，有自己的生活。",
            )

            Spacer(Modifier.height(16.dp))
            Text("插件", color = palette.textSecondary, fontSize = 12.sp)
            Text(
                "「虚拟人生」会自动生成每天的生活安排、心情、穿搭，并在聊天时带上，不需要手动填。",
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
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

            Spacer(Modifier.height(14.dp))
            Text("温度 ${String.format("%.2f", temperature)}", color = palette.textSecondary, fontSize = 12.sp)
            Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..1.5f)

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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

            Spacer(Modifier.height(20.dp))
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

    cropSource?.let { source ->
        AvatarCropDialog(
            source = source,
            onDismiss = { cropSource = null },
            onConfirm = { bitmap ->
                runCatching {
                    val directory = File(context.filesDir, "ai_avatars").apply { mkdirs() }
                    val target = File(directory, "avatar-${System.currentTimeMillis()}.jpg")
                    target.outputStream().use { output ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
                    }
                    target.absolutePath
                }.onSuccess { avatarPath = it }
                    .onFailure { error = "头像保存失败：${it.message}" }
                cropSource = null
            },
        )
    }
}

@Composable
private fun AvatarCropDialog(
    source: android.graphics.Bitmap,
    onDismiss: () -> Unit,
    onConfirm: (android.graphics.Bitmap) -> Unit,
) {
    val palette = ComiiTheme.palette
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var offsetX by remember(source) { mutableFloatStateOf(0f) }
    var offsetY by remember(source) { mutableFloatStateOf(0f) }
    val preview = remember(source, zoom, offsetX, offsetY) {
        cropAvatarBitmap(source, zoom, offsetX, offsetY)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = { Text("裁剪头像", color = palette.textPrimary) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "预览",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(180.dp).clip(CircleShape),
                )
                Spacer(Modifier.height(10.dp))
                Text("缩放 ${String.format("%.2f", zoom)}", color = palette.textSecondary, fontSize = 12.sp)
                Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..3f)
                Text("左右 ${String.format("%.2f", offsetX)}", color = palette.textSecondary, fontSize = 12.sp)
                Slider(value = offsetX, onValueChange = { offsetX = it }, valueRange = -1f..1f)
                Text("上下 ${String.format("%.2f", offsetY)}", color = palette.textSecondary, fontSize = 12.sp)
                Slider(value = offsetY, onValueChange = { offsetY = it }, valueRange = -1f..1f)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(preview) }) {
                Text("使用", color = palette.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = palette.textSecondary) }
        },
    )
}

private fun cropAvatarBitmap(
    source: android.graphics.Bitmap,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
): android.graphics.Bitmap {
    val side = (minOf(source.width, source.height) / zoom).toInt().coerceAtLeast(16)
    val maxLeft = (source.width - side).coerceAtLeast(0)
    val maxTop = (source.height - side).coerceAtLeast(0)
    val left = ((maxLeft / 2f) + offsetX * (maxLeft / 2f)).toInt().coerceIn(0, maxLeft)
    val top = ((maxTop / 2f) + offsetY * (maxTop / 2f)).toInt().coerceIn(0, maxTop)
    val cropped = android.graphics.Bitmap.createBitmap(source, left, top, side, side)
    return android.graphics.Bitmap.createScaledBitmap(cropped, 512, 512, true)
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
