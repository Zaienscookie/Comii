package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comii.app.ui.AppState
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.components.SegmentControl
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(state: AppState) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(state.serverUrl) }
    var token by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(0) } // 0 token, 1 password
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { block() }
                .onFailure { error = it.message ?: "登录失败" }
            busy = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Comii", color = palette.textPrimary, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Text("登录媒体库", color = palette.textSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(26.dp))
        TextField(
            value = server,
            onValueChange = { server = it },
            singleLine = true,
            label = { Text("服务器地址") },
            placeholder = { Text("http://100.74.140.98:8001") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        SegmentControl(
            options = listOf("访问令牌", "账号密码"),
            selectedIndex = mode,
            onSelect = { mode = it },
        )
        Spacer(Modifier.height(14.dp))
        if (mode == 0) {
            TextField(
                value = token,
                onValueChange = { token = it },
                singleLine = true,
                label = { Text("Token") },
                placeholder = { Text("在 WebUI 用户管理里生成") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "WebUI → 用户与权限 → API Token；也可以填账号登录后使用。",
                color = palette.textTertiary,
                fontSize = 11.sp,
            )
        } else {
            TextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                label = { Text("用户名") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                label = { Text("密码") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(18.dp))
        if (busy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = palette.accent, modifier = Modifier.width(22.dp).height(22.dp))
            }
        } else {
            PrimaryButton(
                text = "登录",
                onClick = {
                    if (server.isBlank()) {
                        error = "请填写服务器地址"
                    } else if (mode == 0 && token.isBlank()) {
                        error = "请填写 Token"
                    } else if (mode == 1 && (username.isBlank() || password.isBlank())) {
                        error = "请填写用户名和密码"
                    } else if (mode == 0) {
                        run { state.loginWithToken(server, token) }
                    } else {
                        run { state.loginWithPassword(server, username, password) }
                    }
                },
            )
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = palette.danger, fontSize = 12.sp)
        }
        Spacer(Modifier.height(16.dp))
        if (mode == 1) {
            TextButton(
                onClick = {
                    if (server.isBlank() || username.isBlank() || password.isBlank()) {
                        error = "先填写服务器、用户名和密码"
                    } else {
                        run {
                            state.repository.authSetup(server, username, password)
                            state.loginWithPassword(server, username, password)
                        }
                    }
                },
            ) {
                Text("首次使用？创建 Owner 账号", color = palette.accent, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "服务端 8001 现在需要登录。旧版 App 会提示 401，请使用 3.1.0 以上版本。",
            color = palette.textTertiary,
            fontSize = 10.sp,
        )
    }
}

