package com.comii.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.NavHostController
import com.comii.app.data.ai.AiConfig
import com.comii.app.ui.AppState
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

@Composable
fun AiSettingsScreen(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val ai = state.ai
    val scope = rememberCoroutineScope()
    val current = remember { ai.config() }
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(ai.exportJson().toByteArray(Charsets.UTF_8))
            }
        }.onSuccess { state.showToast("已导出 AI 数据") }
            .onFailure { state.showToast("导出失败：${it.message}") }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text != null && ai.importJson(text)) {
            state.showToast("已导入 AI 数据")
        } else {
            state.showToast("导入失败：文件格式不对")
        }
    }

    var baseUrl by remember { mutableStateOf(current.baseUrl) }
    var apiKey by remember { mutableStateOf(current.apiKey) }
    var model by remember { mutableStateOf(current.model) }
    var temperature by remember { mutableFloatStateOf(current.temperature.toFloat()) }
    var maxContext by remember { mutableFloatStateOf(current.maxContextMessages.toFloat()) }
    var stream by remember { mutableStateOf(current.stream) }
    var multimodal by remember { mutableStateOf(current.multimodal) }
    var aggregate by remember { mutableStateOf(current.aggregate) }
    var segmentReplies by remember { mutableStateOf(current.segmentReplies) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }

    fun config() = AiConfig(
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        temperature = temperature.toDouble(),
        maxContextMessages = maxContext.toInt(),
        stream = stream,
        showReasoning = current.showReasoning,
        multimodal = multimodal,
        aggregate = aggregate,
        segmentReplies = segmentReplies,
    )

    fun save() {
        ai.updateConfig(config())
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
                "AI 服务设置",
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
            Text(
                "兼容 OpenAI 的 /chat/completions。Key 只保存在本机。",
                color = palette.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))

            label("Base URL")
            input(baseUrl, { baseUrl = it }, placeholder = "https://example.com/v1", keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(12.dp))

            label("API Key")
            input(
                value = apiKey,
                onChange = { apiKey = it },
                placeholder = "sk-...",
                keyboardType = KeyboardType.Password,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    IconButton(onClick = { showKey = !showKey }) {
                        Icon(
                            if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = "显示/隐藏 Key",
                            tint = palette.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
            Spacer(Modifier.height(12.dp))

            label("模型名")
            input(model, { model = it }, placeholder = "Yachiyo-v8000-flash")
            Spacer(Modifier.height(16.dp))

            Text("温度 ${String.format("%.2f", temperature)}", color = palette.textSecondary, fontSize = 12.sp)
            Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..1.5f)

            Text("上下文条数 ${maxContext.toInt()}", color = palette.textSecondary, fontSize = 12.sp)
            Slider(
                value = maxContext,
                onValueChange = { maxContext = it },
                valueRange = 4f..60f,
                steps = 27,
            )

            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("流式输出", color = palette.textPrimary, fontSize = 14.sp)
                    Text("逐字显示回复；关闭则等整段返回", color = palette.textSecondary, fontSize = 11.sp)
                }
                Switch(checked = stream, onCheckedChange = { stream = it })
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("多模态（发送图片）", color = palette.textPrimary, fontSize = 14.sp)
                    Text("开启后聊天框可发相册图片给支持视觉的模型", color = palette.textSecondary, fontSize = 11.sp)
                }
                Switch(checked = multimodal, onCheckedChange = { multimodal = it })
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("分段回复", color = palette.textPrimary, fontSize = 14.sp)
                    Text("让模型像真人一样把回复拆成多条短消息发送", color = palette.textSecondary, fontSize = 11.sp)
                }
                Switch(checked = segmentReplies, onCheckedChange = { segmentReplies = it })
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("合并连续消息", color = palette.textPrimary, fontSize = 14.sp)
                    Text("短时间发多条时先等 1.4 秒，合成一轮再回复", color = palette.textSecondary, fontSize = 11.sp)
                }
                Switch(checked = aggregate, onCheckedChange = { aggregate = it })
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    testing = true
                    result = null
                    scope.launch {
                        val outcome = runCatching { ai.testConnection(config()) }
                        testing = false
                        outcome.onSuccess {
                            resultOk = true
                            result = "连接成功：$it"
                        }.onFailure {
                            resultOk = false
                            result = "连接失败：${it.message}"
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp),
                enabled = !testing,
            ) {
                Text(if (testing) "正在测试…" else "测试连接", color = palette.accent)
            }
            result?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = if (resultOk) palette.success else palette.danger,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("数据备份", color = palette.textSecondary, fontSize = 12.sp)
            Text(
                "更新 App 不会删除人格、记忆和聊天；卸载或清除应用数据才会。可以导出 JSON 备份。",
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { exportLauncher.launch("comii-ai-backup.json") },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Text("导出 AI 数据（JSON）", color = palette.accent)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) {
                Text("从 JSON 导入", color = palette.accent)
            }

            Spacer(Modifier.height(24.dp))
            PrimaryButton(text = "保存设置", onClick = { save() })
            Spacer(Modifier.height(12.dp))
            Text(
                "说明：这是 demo 版 AI 模块，只调用 /chat/completions；" +
                    "人格、记忆、会话历史都在本机 JSON 里，不会上传到 Comii 服务器。",
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun label(text: String) {
    Text(text, color = ComiiTheme.palette.textSecondary, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun input(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = ComiiTheme.palette
    TextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = palette.textTertiary, fontSize = 13.sp) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
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
