package com.comii.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import com.comii.app.ui.AppState
import com.comii.app.ui.theme.ComiiTheme

@Composable
fun PlayerScreen(state: AppState, path: String, startName: String, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf(path.substringAfterLast('/')) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            setHandleAudioBecomingNoisy(true)
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    LaunchedEffect(path, startName) {
        val offline = state.repository.offline.find(path)
        val api = state.repository.apiOrNull()
        val items = mutableListOf<MediaItem>()
        var startIndex = 0
        if (offline != null && offline.videos.isNotEmpty()) {
            title = offline.name
            offline.videos.forEachIndexed { index, name ->
                if (name == startName) startIndex = index
                items.add(MediaItem.fromUri(Uri.fromFile(state.repository.offline.videoFile(path, name))))
            }
        } else if (api != null) {
            val detail = runCatching { api.series(path) }.getOrNull()
            if (detail == null) {
                error = "无法加载视频列表"
            } else {
                title = detail.name
                detail.videos.forEachIndexed { index, name ->
                    if (name == startName) startIndex = index
                    items.add(MediaItem.fromUri(api.videoUrl(path, name)))
                }
            }
        } else {
            error = "未配置服务器"
        }
        if (items.isNotEmpty()) {
            player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0)
            player.prepare()
        } else if (error == null) {
            error = "这个系列没有视频"
        }
        loading = false
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (loading) {
            CircularProgressIndicator(color = palette.accent, modifier = Modifier.align(Alignment.Center))
        }
        if (error != null) {
            Text(error ?: "", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        setShowNextButton(true)
                        setShowPreviousButton(true)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.Rounded.ArrowBackIosNew,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                title,
                color = Color.White,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
