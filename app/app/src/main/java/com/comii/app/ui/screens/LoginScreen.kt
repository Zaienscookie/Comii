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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comii.app.ui.AppState
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(state: AppState) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(state.serverUrl) }
    var accessToken by remember { mutableStateOf(state.repository.settings.accessToken) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
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

    fun saveAccess() {
        state.repository.settings.accessToken = accessToken.trim()
        com.comii.app.data.AuthTokenProvider.accessToken = accessToken.trim()
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
        Spacer(Modifier.height(8.dp))
        TextField(
            value = accessToken,
            onValueChange = { accessToken = it },
            singleLine = true,
            label = { Text("服务器访问令牌") },
            placeholder = { Text("comii_access_...") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "这是服务器白名单 Token；局域网或 neko 没有开启时留空即可。",
            color = palette.textTertiary,
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(20.dp))
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

        Spacer(Modifier.height(18.dp))
        if (busy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(
                    color = palette.accent,
                    modifier = Modifier.width(22.dp).height(22.dp),
                )
            }
        } else {
            PrimaryButton(
                text = "登录",
                onClick = {
                    if (server.isBlank()) {
                        error = "请填写服务器地址"
                    } else if (username.isBlank() || password.isBlank()) {
                        error = "请填写用户名和密码"
                    } else {
                        saveAccess()
                        run { state.loginWithPassword(server, username, password) }
                    }
                },
            )
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = palette.danger, fontSize = 12.sp)
        }
        Spacer(Modifier.height(14.dp))
        TextButton(
            onClick = {
                if (server.isBlank() || username.isBlank() || password.isBlank()) {
                    error = "先填写服务器、用户名和密码"
                } else {
                    saveAccess()
                    run {
                        state.repository.authSetup(server, username, password)
                        state.loginWithPassword(server, username, password)
                    }
                }
            },
        ) {
            Text("首次使用？创建 Owner 账号", color = palette.accent, fontSize = 12.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "账号登录成功后才进入 App。旧版 3.0.7 需要更新。",
            color = palette.textTertiary,
            fontSize = 10.sp,
        )
    }
}
