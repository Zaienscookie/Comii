package com.comii.app.ui.screens

import androidx.compose.ui.Alignment
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Visibility
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
    var username by remember {
        androidx.compose.runtime.mutableStateOf(state.repository.settings.savedUsername)
    }
    var showToken by remember { androidx.compose.runtime.mutableStateOf(false) }
    var showPassword by remember { androidx.compose.runtime.mutableStateOf(false) }
    var password by remember { mutableStateOf(state.repository.settings.savedPassword) }
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
        state.repository.settings.saveServerToken(server, accessToken)
        com.comii.app.data.AuthTokenProvider.accessToken = accessToken.trim()
    }

    androidx.compose.runtime.LaunchedEffect(server) {
        if (accessToken.isBlank()) {
            val saved = state.repository.settings.tokenForServer(server)
            if (saved.isNotBlank()) accessToken = saved
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(palette.background, palette.elevated.copy(alpha = 0.7f), palette.background)
                )
            )
            .statusBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Comii",
                color = palette.textPrimary,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.4).sp,
            )
            Text(
                "登录媒体库",
                color = palette.textSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 22.dp),
            )

            androidx.compose.material3.Surface(
                color = palette.card,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
                shadowElevation = 0.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
                    LoginField(
                        label = "服务器地址",
                        value = server,
                        onValueChange = { server = it },
                        placeholder = "https://api.example.com",
                        palette = palette,
                    )
                    Spacer(Modifier.height(12.dp))
                    LoginField(
                        label = "服务器访问令牌",
                        value = accessToken,
                        onValueChange = { accessToken = it.filter { ch -> ch.code in 0x21..0x7E } },
                        placeholder = "留空表示该服务器未开启令牌",
                        palette = palette,
                        secret = true,
                        visible = showToken,
                        onToggleVisible = { showToken = !showToken },
                    )

                    Spacer(Modifier.height(18.dp))
                    LoginField(
                        label = "用户名",
                        value = username,
                        onValueChange = { username = it },
                        placeholder = "admin",
                        palette = palette,
                    )
                    Spacer(Modifier.height(12.dp))
                    LoginField(
                        label = "密码",
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "••••••••",
                        palette = palette,
                        secret = true,
                        visible = showPassword,
                        onToggleVisible = { showPassword = !showPassword },
                    )

                    Spacer(Modifier.height(20.dp))
                    if (busy) {
                        Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(22.dp))
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
                                    state.repository.settings.savedUsername = username
                                    state.repository.settings.savedPassword = password
                                    run { state.loginWithPassword(server, username, password) }
                                }
                            },
                        )
                    }
                    error?.let {
                        Text(
                            it,
                            color = palette.danger,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                        )
                    }
                }
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
                            state.repository.settings.savedUsername = username
                            state.repository.settings.savedPassword = password
                            state.loginWithPassword(server, username, password)
                        }
                    }
                },
            ) {
                Text("首次使用？创建 Owner 账号", color = palette.accent, fontSize = 13.sp)
            }
            Text(
                "登录成功后才进入 App",
                color = palette.textTertiary,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun LoginField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    palette: com.comii.app.ui.theme.ComiiPalette,
    secret: Boolean = false,
    visible: Boolean = false,
    onToggleVisible: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            color = palette.textSecondary,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        androidx.compose.material3.TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text(placeholder, color = palette.textTertiary, fontSize = 14.sp) },
            visualTransformation = if (secret && !visible) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.TextFieldDefaults.colors(
                focusedContainerColor = palette.elevated,
                unfocusedContainerColor = palette.elevated,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                cursorColor = palette.accent,
            ),
            trailingIcon = if (onToggleVisible != null) {
                {
                    androidx.compose.material3.IconButton(onClick = onToggleVisible) {
                        Icon(
                            imageVector = if (visible) androidx.compose.material.icons.Icons.Rounded.Visibility
                            else androidx.compose.material.icons.Icons.Rounded.VisibilityOff,
                            contentDescription = if (visible) "隐藏" else "显示",
                            tint = palette.textTertiary,
                        )
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
