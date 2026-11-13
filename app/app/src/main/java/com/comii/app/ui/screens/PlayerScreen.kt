package com.comii.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import com.comii.app.ui.AppState
import com.comii.app.ui.theme.ComiiTheme

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

@Composable
fun PlayerScreen(state: AppState, path: String, startName: String, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val context = LocalContext.current
    val settings = state.repository.settings

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf(path.substringAfterLast('/')) }
    var muted by remember { mutableStateOf(settings.videoMuted) }
    var speed by remember { mutableFloatStateOf(1f) }
    var pitchFollows by remember { mutableStateOf(settings.videoPitchFollowsSpeed) }
    var showSpeed by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun applyPlayback(activePlayer: ExoPlayer) {
        activePlayer.volume = if (muted) 0f else 1f
        activePlayer.playbackParameters = PlaybackParameters(
            speed,
            if (pitchFollows) speed else 1f,
        )
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_ONE
            setHandleAudioBecomingNoisy(true)
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    LaunchedEffect(muted, speed, pitchFollows) { applyPlayback(player) }

    LaunchedEffect(path, startName) {
        val offline = state.repository.offline.find(path)
        val api = state.repository.apiOrNull()
        val items = mutableListOf<MediaItem>()
        var startIndex = 0
        if (offline != null && offline.videos.isNotEmpty()) {
            title = offline.name
            offline.videos.forEachIndexed { itemIndex, name ->
                if (name == startName) startIndex = itemIndex
                items.add(MediaItem.fromUri(Uri.fromFile(state.repository.offline.videoFile(path, name))))
            }
        } else if (api != null) {
            val detail = runCatching { api.series(path) }.getOrNull()
            if (detail == null) {
                error = "无法加载视频列表"
            } else {
                title = detail.name
                detail.videos.forEachIndexed { itemIndex, name ->
                    if (name == startName) startIndex = itemIndex
                    items.add(MediaItem.fromUri(api.videoUrl(path, name)))
                }
            }
        } else {
            error = "未配置服务器"
        }
        count = items.size
        if (items.isNotEmpty()) {
            player.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0)
            player.prepare()
            index = startIndex
        } else if (error == null) {
            error = "这个系列没有视频"
        }
        applyPlayback(player)
        loading = false
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                index = player.currentMediaItemIndex
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 左右滑动切换上一个 / 下一个视频
    var dragTotal by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(count) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onHorizontalDrag = { _, amount -> dragTotal += amount },
                    onDragEnd = {
                        if (count > 1) {
                            if (dragTotal < -90 && player.hasNextMediaItem()) {
                                player.seekToNextMediaItem()
                            } else if (dragTotal > 90 && player.hasPreviousMediaItem()) {
                                player.seekToPreviousMediaItem()
                            }
                        }
                    },
                )
            }
    ) {
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
                .background(Color.Black.copy(alpha = 0.38f))
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
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (count > 1) {
                    Text(
                        "第 ${index + 1} / $count 集 · 左右滑动切换",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
            }
            IconButton(onClick = {
                muted = !muted
                settings.videoMuted = muted
            }) {
                Icon(
                    if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    contentDescription = if (muted) "取消静音" else "静音",
                    tint = Color.White,
                )
            }
            Box {
                IconButton(onClick = { showSpeed = true }) {
                    Icon(Icons.Rounded.Speed, contentDescription = "播放速度", tint = Color.White)
                }
                DropdownMenu(
                    expanded = showSpeed,
                    onDismissRequest = { showSpeed = false },
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.elevated),
                ) {
                    SPEEDS.forEach { value ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (value == 1f) "1.0x（原速）" else "${value}x",
                                    color = if (speed == value) palette.accent else palette.textPrimary,
                                    fontSize = 14.sp,
                                )
                            },
                            onClick = {
                                speed = value
                                showSpeed = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("音调跟随速度", color = palette.textPrimary, fontSize = 13.sp)
                                    Text(
                                        if (pitchFollows) "变速时声音一起变高/变低" else "保持原音调",
                                        color = palette.textSecondary,
                                        fontSize = 10.sp,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Switch(
                                    checked = pitchFollows,
                                    onCheckedChange = {
                                        pitchFollows = it
                                        settings.videoPitchFollowsSpeed = it
                                    },
                                )
                            }
                        },
                        onClick = { },
                    )
                }
            }
            IconButton(onClick = {
                landscape = !landscape
                activity?.requestedOrientation = if (landscape) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }) {
                Icon(Icons.Rounded.ScreenRotation, contentDescription = "横竖屏切换", tint = Color.White)
            }
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
