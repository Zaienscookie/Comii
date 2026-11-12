package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comii.app.data.NodeKind
import com.comii.app.data.SeriesDetail
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.ComiiTopBar
import com.comii.app.ui.components.ConfirmDialog
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.components.formatBytes
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

@Composable
fun SeriesScreen(state: AppState, path: String, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var detail by remember { mutableStateOf<SeriesDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var askDownload by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        loading = true
        runCatching { state.repository.series(path) }
            .onSuccess { detail = it; error = null }
            .onFailure { error = it.message }
        loading = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ComiiTopBar(
            title = detail?.name ?: "系列",
            onBack = { nav.popBackStack() },
            actions = {
                androidx.compose.material3.IconButton(onClick = { askDownload = true }) {
                    Icon(Icons.Rounded.Download, contentDescription = "下载", tint = palette.accent)
                }
            },
        )

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accent)
            }
            error != null -> EmptyState(Icons.Rounded.PlayCircle, "加载失败", error)
            detail != null -> {
                val series = detail!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
                ) {
                    item {
                        Row {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(state.repository.mediaUrl(series.cover))
                                    .crossfade(true)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(112.dp)
                                    .aspectRatio(0.68f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(palette.card),
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    series.name,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.textPrimary,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    buildString {
                                        if (series.images.isNotEmpty()) append("${series.images.size} 页")
                                        if (series.images.isNotEmpty() && series.videos.isNotEmpty()) append(" · ")
                                        if (series.videos.isNotEmpty()) append("${series.videos.size} 个视频")
                                    },
                                    fontSize = 13.sp,
                                    color = palette.textSecondary,
                                )
                                Spacer(Modifier.height(14.dp))
                                if (series.images.isNotEmpty()) {
                                    val page = series.progress?.page ?: 0
                                    PrimaryButton(
                                        text = if (page > 0) "继续阅读 · 第 ${page + 1} 页" else "开始阅读",
                                        onClick = { nav.navigate(Routes.reader(path)) },
                                        modifier = Modifier.height(44.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }

                    if (series.videos.isNotEmpty()) {
                        item {
                            Text("视频", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                            Spacer(Modifier.height(10.dp))
                        }
                        items(series.videos) { name ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 10.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(palette.card)
                                    .pointerInput(name) {
                                        detectTapGestures { nav.navigate(Routes.player(path, name)) }
                                    }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .width(96.dp)
                                        .height(58.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(palette.separator)
                                ) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(state.repository.mediaUrl("/api/v1/media/video_thumb?path=${com.comii.app.data.ComiiApi.encode(path)}&name=${com.comii.app.data.ComiiApi.encode(name)}"))
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    Icon(
                                        Icons.Rounded.PlayCircle,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.9f),
                                        modifier = Modifier.align(Alignment.Center).size(26.dp),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        name,
                                        fontSize = 14.sp,
                                        color = palette.textPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text("›", fontSize = 20.sp, color = palette.textTertiary)
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(8.dp))
                        if (downloadProgress != null) {
                            val (done, total) = downloadProgress!!
                            Column {
                                Text(
                                    "正在下载 ${done}/${total.coerceAtLeast(1)}",
                                    fontSize = 12.sp,
                                    color = palette.textSecondary,
                                )
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { if (total > 0) done.toFloat() / total else 0f },
                                    color = palette.accent,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            val downloaded = state.repository.offline.find(path)
                            Text(
                                if (downloaded != null) {
                                    "已离线 · ${formatBytes(downloaded.totalBytes)}"
                                } else {
                                    "下载后可离线阅读（含视频）"
                                },
                                fontSize = 12.sp,
                                color = palette.textTertiary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (askDownload) {
        ConfirmDialog(
            title = "下载到本机",
            message = "将保存这个系列的全部图片和视频，下载过程中请保持网络连接。",
            confirmText = "开始下载",
            onConfirm = {
                askDownload = false
                if (downloadProgress == null) {
                    scope.launch {
                        downloadProgress = 0 to 0
                        runCatching {
                            state.repository.downloadSeries(path, includeVideos = true) { done, total ->
                                downloadProgress = done to total
                            }
                        }.onSuccess { state.showToast("下载完成：${it.name}") }
                            .onFailure { state.showToast("下载失败：${it.message}") }
                        downloadProgress = null
                    }
                }
            },
            onDismiss = { askDownload = false },
        )
    }
}
