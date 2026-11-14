package com.comii.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.comii.app.ui.AppState
import com.comii.app.ui.components.ComiiTopBar
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

@Composable
fun UploadScreen(state: AppState, initialFolder: String, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var folder by remember { mutableStateOf(initialFolder) }
    var direct by remember { mutableStateOf(initialFolder.isNotBlank()) }
    var seriesName by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var uploadedBytes by remember { mutableStateOf(0L) }
    var result by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { selected ->
        uris = selected
        names = selected.map { uri -> displayName(context, uri) }
    }

    fun startUpload() {
        if (uris.isEmpty()) {
            state.showToast("请先选择文件")
            return
        }
        uploading = true
        uploadedBytes = 0L
        result = null
        scope.launch {
            runCatching {
                state.repository.upload(uris, folder.trim(), seriesName.trim(), direct) { bytes ->
                    uploadedBytes += bytes
                }
            }.onSuccess { import ->
                result = buildString {
                    append("已导入「${import.name}」：${import.images} 页 / ${import.videos} 个视频")
                    if (import.skipped > 0) {
                        val types = import.skipped_types.joinToString("、")
                        append("（压缩包共 ${import.total_entries} 项，跳过 ${import.skipped}")
                        if (types.isNotEmpty()) append("：$types")
                        append("）")
                    }
                }
                state.showToast("上传完成")
                uris = emptyList()
                names = emptyList()
            }.onFailure {
                result = "上传失败：${it.message}"
            }
            uploading = false
        }
    }

    Column(Modifier.fillMaxSize().background(palette.background).statusBarsPadding()) {
        ComiiTopBar(title = "导入内容", onBack = { nav.popBackStack() })

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                "支持图片、ZIP/CBZ 压缩包、PDF 和视频。上传后会真实创建在服务器的目标文件夹里。",
                fontSize = 13.sp,
                color = palette.textSecondary,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !uploading,
            ) {
                Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, tint = palette.accent)
                Spacer(Modifier.width(8.dp))
                Text(if (uris.isEmpty()) "选择文件" else "重新选择", color = palette.accent)
            }

            if (names.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(palette.card, RoundedCornerShape(14.dp))
                        .padding(vertical = 4.dp)
                ) {
                    names.forEach { name ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, fontSize = 13.sp, color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            if (direct) {
                Text("上传到当前文件夹", fontSize = 12.sp, color = palette.textSecondary)
                Spacer(Modifier.height(6.dp))
                Text(
                    "/" + initialFolder.trim('/'),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.textPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(palette.card, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "图片/视频会直接放进这个文件夹，不会再新建子文件夹。",
                    fontSize = 12.sp,
                    color = palette.textTertiary,
                )
            } else {
                Text("系列名称（留空则用文件名）", fontSize = 12.sp, color = palette.textSecondary)
                Spacer(Modifier.height(6.dp))
                TextField(
                    value = seriesName,
                    onValueChange = { seriesName = it },
                    singleLine = true,
                    placeholder = { Text("例如：某漫画 第1卷", color = palette.textTertiary) },
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = palette.card,
                        unfocusedContainerColor = palette.card,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))
                Text("目标文件夹（相对路径，留空为根目录）", fontSize = 12.sp, color = palette.textSecondary)
                Spacer(Modifier.height(6.dp))
                TextField(
                    value = folder,
                    onValueChange = { folder = it },
                    singleLine = true,
                    placeholder = { Text("例如：作者A", color = palette.textTertiary) },
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = palette.card,
                        unfocusedContainerColor = palette.card,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(24.dp))
            result?.let {
                Spacer(Modifier.height(14.dp))
                Text(
                    it,
                    fontSize = 13.sp,
                    color = if (it.startsWith("上传失败")) palette.danger else palette.success,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        // 固定底部，选择再多文件也不用来回滑动才能上传
        Column(
            Modifier
                .fillMaxWidth()
                .background(palette.background)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (uploading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = palette.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("正在上传 ${formatSize(uploadedBytes)}", fontSize = 13.sp, color = palette.textSecondary)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = palette.accent)
            } else {
                PrimaryButton(text = "开始上传", enabled = uris.isNotEmpty(), onClick = { startUpload() })
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    return String.format("%.1f MB", kb / 1024)
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "文件"
}
