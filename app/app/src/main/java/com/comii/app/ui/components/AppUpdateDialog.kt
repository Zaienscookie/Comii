package com.comii.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comii.app.data.AppUpdateInfo
import com.comii.app.data.AppUpdater
import com.comii.app.ui.AppState
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

/** 有新版本时才展示；下载完成后调系统安装器。 */
@Composable
fun AppUpdateDialog(state: AppState, info: AppUpdateInfo, onDismiss: () -> Unit) {
    val palette = ComiiTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        containerColor = palette.card,
        title = {
            Text("发现新版本 ${info.versionName}", color = palette.textPrimary, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column {
                Text(
                    "当前 ${AppUpdater.currentVersionName} → ${info.versionName}",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                )
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(info.notes, color = palette.textPrimary, fontSize = 13.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("安装包 ${formatBytes(info.size)}", color = palette.textTertiary, fontSize = 11.sp)
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text("正在下载 ${(progress * 100).toInt()}%", color = palette.textSecondary, fontSize = 11.sp)
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = palette.danger, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    downloading = true
                    error = null
                    scope.launch {
                        runCatching { AppUpdater.download(context, state.serverUrl, info) { progress = it } }
                            .onSuccess { file ->
                                runCatching { AppUpdater.install(context, file) }
                                    .onSuccess { onDismiss() }
                                    .onFailure {
                                        downloading = false
                                        error = "无法打开安装器，请在系统设置里允许 Comii 安装应用（${it.message}）"
                                    }
                            }
                            .onFailure {
                                downloading = false
                                error = "下载失败：${it.message}"
                            }
                    }
                },
            ) {
                Text(if (downloading) "下载中…" else "下载并安装", color = palette.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = onDismiss) { Text("稍后", color = palette.textSecondary) }
            }
        },
    )
}
