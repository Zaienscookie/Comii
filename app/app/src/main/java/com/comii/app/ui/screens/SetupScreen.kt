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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comii.app.ui.AppState
import com.comii.app.ui.components.LargeTitle
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    state: AppState,
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    val settings = state.repository.settings
    var history by remember { mutableStateOf(settings.serverHistory()) }
    var address by remember {
        mutableStateOf(
            state.serverUrl.ifBlank { history.firstOrNull() ?: "192.168.2.126:8001" }
        )
    }
    var accessToken by remember { mutableStateOf(settings.accessToken) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf(listOf<String>()) }

    fun normalize(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://$value"
        return value.trimEnd('/')
    }

    fun test(url: String, then: ((Boolean) -> Unit)? = null) {
        if (url.isBlank()) {
            status = "请输入服务器地址"
            statusOk = false
            return
        }
        busy = true
        status = "正在连接…"
        statusOk = false
        scope.launch {
            val result = runCatching { state.repository.testPing(url) }
            busy = false
            result.onSuccess { ping ->
                statusOk = ping.status == "ok"
                status = if (statusOk) "连接成功 · ${ping.app} v${ping.version}" else "设备有响应，但不是 Comii 服务"
                if (statusOk) {
                    settings.addServerHistory(url)
                    history = settings.serverHistory()
                }
                then?.invoke(statusOk)
            }.onFailure { error ->
                statusOk = false
                val message = error.message ?: "无法访问"
                status = if (message.contains("404")) {
                    "这个端口不是数据接口，请改用 8001"
                } else {
                    "连接失败：$message"
                }
                then?.invoke(false)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(palette.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 34.dp),
        ) {
            Spacer(Modifier.height(28.dp))
            Text("Comii", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = palette.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text("连接你的个人漫画服务器", fontSize = 16.sp, color = palette.textSecondary)
            Spacer(Modifier.height(34.dp))

            Text("服务器地址", fontSize = 13.sp, color = palette.textSecondary)
            Spacer(Modifier.height(8.dp))
            TextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                placeholder = { Text("192.168.89.200:8001", color = palette.textTertiary) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                shape = RoundedCornerShape(14.dp),
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

            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.height(14.dp))
            Text("服务器访问令牌（可选）", fontSize = 13.sp, color = palette.textSecondary)
            Spacer(Modifier.height(8.dp))
            TextField(
                value = accessToken,
                onValueChange = { accessToken = it },
                singleLine = true,
                placeholder = { Text("CF / 公网白名单 Token", color = palette.textTertiary) },
                shape = RoundedCornerShape(14.dp),
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
            Text(
                "局域网可留空；走 Cloudflare 或公网时填服务器配置的 COMII_ACCESS_TOKEN。",
                fontSize = 11.sp,
                color = palette.textTertiary,
            )

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { test(normalize(address)) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.accent)
                    } else {
                        Icon(Icons.Rounded.Dns, contentDescription = null, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("测试连接")
                }
                OutlinedButton(
                    onClick = {
                        scanning = true
                        found = emptyList()
                        scope.launch {
                            val port = address.substringAfterLast(':', "8001").toIntOrNull() ?: 8001
                            val result = runCatching { state.repository.scanLan(port) { url -> found = found + url } }
                            scanning = false
                            found = result.getOrDefault(found).distinct()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (scanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.accent)
                    } else {
                        Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("扫描局域网")
                }
            }

            if (status != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (statusOk) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = palette.success, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        status.orEmpty(),
                        color = if (statusOk) palette.success else palette.danger,
                        fontSize = 13.sp,
                    )
                }
            }

            if (history.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("历史记录", fontSize = 13.sp, color = palette.textSecondary)
                Spacer(Modifier.height(8.dp))
                history.forEach { url ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(palette.card, RoundedCornerShape(14.dp))
                            .clickable {
                                address = url.removePrefix("http://")
                                test(url)
                            }
                            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            url,
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (url == state.serverUrl) {
                            Text("当前", color = palette.accent, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            settings.removeServerHistory(url)
                            history = settings.serverHistory()
                        }) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "删除记录",
                                tint = palette.textTertiary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            if (found.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("发现的服务器", fontSize = 13.sp, color = palette.textSecondary)
                Spacer(Modifier.height(8.dp))
                found.forEach { url ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(palette.card, RoundedCornerShape(14.dp))
                            .clickable {
                                address = url.removePrefix("http://")
                                test(url)
                            }
                            .padding(horizontal = 16.dp, vertical = 13.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(palette.success, RoundedCornerShape(4.dp))
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(url, color = palette.textPrimary, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            Text("使用", color = palette.accent, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(30.dp))
            PrimaryButton(
                text = if (onCancel != null) "保存" else "开始使用",
                enabled = address.isNotBlank() && !busy,
                onClick = {
                    val url = normalize(address)
                    test(url) { ok ->
                        if (ok) {
                            settings.accessToken = accessToken.trim()
                            com.comii.app.data.AuthTokenProvider.accessToken = accessToken.trim()
                            state.setServer(url)
                            state.showToast("已连接 ${url}")
                            onDone()
                        }
                    }
                },
            )
            if (onCancel != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text(if (state.hasServer) "取消" else "稍后设置", color = palette.textSecondary) }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "提示：手机和服务器需在同一局域网。数据接口端口是 8001（WebUI 是 8000），例如 192.168.89.200:8001。",
                fontSize = 12.sp,
                color = palette.textTertiary,
            )
        }
    }
}
