package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import com.comii.app.ui.components.BottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.comii.app.data.AppLog
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

private data class SeriesMedia(
    val name: String,
    val isVideo: Boolean,
    val imageIndex: Int,
)

@Composable
fun SeriesScreen(state: AppState, path: String, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<SeriesDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var askDownload by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var editingTags by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var refreshKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }

    LaunchedEffect(path, state.serverGeneration, refreshKey) {
        loading = true
        runCatching { state.repository.series(path) }
            .onSuccess { detail = it; tags = it.tags; error = null }
            .onFailure { error = it.message }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(palette.background).statusBarsPadding()) {
        ComiiTopBar(
            title = detail?.name ?: "系列",
            onBack = { nav.popBackStack() },
            actions = {
                if (state.can("upload", path)) {
                IconButton(onClick = { nav.navigate(Routes.upload(path)) }) {
                    Icon(Icons.Rounded.Upload, contentDescription = "导入内容", tint = palette.accent)
                }
                }
                if (state.can("download", path)) {
                IconButton(onClick = { askDownload = true }) {
                    Icon(Icons.Rounded.Download, contentDescription = "下载", tint = palette.accent)
                }
                }
                IconButton(onClick = { showMore = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = palette.accent)
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
                val media = remember(series) {
                    (
                        series.images.mapIndexed { index, name -> SeriesMedia(name, false, index) } +
                            series.videos.map { SeriesMedia(it, true, -1) }
                        ).sortedBy { it.name.lowercase() }
                }
                SeriesContent(
                    state = state,
                    path = path,
                    nav = nav,
                    media = media,
                    downloadProgress = downloadProgress,
                )
            }
        }
    }

    if (editingTags) {
        TagEditorDialog(
            state = state,
            path = path,
            seriesName = detail?.name ?: path.substringAfterLast('/'),
            initial = tags,
            onDismiss = { editingTags = false },
            onSaved = { tags = it },
        )
    }

    if (showMore) {
        BottomSheet(onDismiss = { showMore = false }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("更多", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { showMore = false; nav.navigate(Routes.upload(path)) }) {
                    Text("导入内容（上传到当前文件夹）", color = palette.textPrimary, fontSize = 15.sp)
                }
                TextButton(onClick = { showMore = false; editingTags = true }) {
                    Text("标签", color = palette.textPrimary, fontSize = 15.sp)
                }
                TextButton(onClick = { showMore = false; refreshKey++ }) {
                    Text("刷新", color = palette.textPrimary, fontSize = 15.sp)
                }
                TextButton(onClick = { showMore = false; askDownload = true }) {
                    Text("下载到本机", color = palette.textPrimary, fontSize = 15.sp)
                }
                Spacer(Modifier.height(16.dp))
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

@Composable
private fun SeriesContent(
    state: AppState,
    path: String,
    nav: NavHostController,
    media: List<SeriesMedia>,
    downloadProgress: Pair<Int, Int>?,
) {
    val palette = ComiiTheme.palette
    val context = LocalContext.current
    val api = state.repository.apiOrNull()

    Box(Modifier.fillMaxSize().background(palette.background)) {
        if (media.isEmpty()) {
            EmptyState(Icons.Rounded.PlayCircle, "没有内容", "这个文件夹里没有图片或视频")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(media, key = { "${it.isVideo}:${it.name}" }) { entry ->
                    val model = if (entry.isVideo) {
                        api?.videoThumbUrl(path, entry.name)
                    } else {
                        api?.thumbUrl(path, entry.name)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(palette.card)
                            .pointerInput(entry.name, entry.isVideo) {
                                detectTapGestures {
                                    if (entry.isVideo) {
                                        nav.navigate(Routes.player(path, entry.name))
                                    } else {
                                        nav.navigate(Routes.reader(path, entry.imageIndex))
                                    }
                                }
                            },
                    ) {
                        AsyncImage(
                            model = model,
                            contentDescription = entry.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (entry.isVideo) {
                            Box(
                                Modifier
                                    .align(Alignment.Center)
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Rounded.PlayCircle,
                                    contentDescription = "视频",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (downloadProgress != null) {
            val (done, total) = downloadProgress
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(16.dp),
            ) {
                Text("正在下载 ${done}/${total.coerceAtLeast(1)}", color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    color = palette.accent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}


