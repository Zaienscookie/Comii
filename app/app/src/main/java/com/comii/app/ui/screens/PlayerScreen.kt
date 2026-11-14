package com.comii.app.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import com.comii.app.data.AuthTokenProvider
import com.comii.app.data.BassEffectController
import com.comii.app.ui.AppState
import com.comii.app.ui.components.BottomSheet
import com.comii.app.ui.components.SegmentControl
import com.comii.app.ui.theme.ComiiTheme
import java.util.Locale
import kotlinx.coroutines.delay

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
    var pitch by remember { mutableFloatStateOf(1f) }
    var pitchFollows by remember { mutableStateOf(settings.videoPitchFollowsSpeed) }
    var bassPreset by remember { mutableStateOf(settings.videoBassPreset) }
    var bassUltra by remember { mutableFloatStateOf(settings.videoBassUltra) }
    var bassLow by remember { mutableFloatStateOf(settings.videoBassLow) }
    var bassMid by remember { mutableFloatStateOf(settings.videoBassMid) }
    var showTuning by remember { mutableStateOf(false) }
    var index by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(false) }
    var showChrome by remember { mutableStateOf(true) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var dragStartPos by remember { mutableLongStateOf(0L) }
    var dragAccum by remember { mutableFloatStateOf(0f) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val activity = context as? android.app.Activity
    val autoRotate = settings.autoRotate
    DisposableEffect(autoRotate) {
        if (autoRotate) {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        onDispose {
            activity?.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 全屏沉浸：隐藏状态栏/导航栏，退出时恢复（顶部灰边就是状态栏区域）
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView)
        }
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
    // 顶栏 4 秒后自动收起，点一下播放器可再唤出
    LaunchedEffect(showChrome) {
        if (showChrome) {
            delay(4000)
            playerView?.hideController()
            showChrome = false
        }
    }

    fun applyPlayback(activePlayer: ExoPlayer) {
        activePlayer.volume = if (muted) 0f else 1f
        activePlayer.playbackParameters = PlaybackParameters(
            speed,
            if (pitchFollows) speed else pitch,
        )
    }

    val player = remember {
        val headers = mutableMapOf<String, String>()
        if (AuthTokenProvider.accessToken.isNotBlank()) {
            headers["X-Comii-Token"] = AuthTokenProvider.accessToken
        }
        if (AuthTokenProvider.token.isNotBlank()) {
            headers["Authorization"] = "Bearer ${AuthTokenProvider.token}"
        }
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                playWhenReady = true
                repeatMode = Player.REPEAT_MODE_ONE
                setHandleAudioBecomingNoisy(true)
            }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    val bassController = remember { BassEffectController() }
    DisposableEffect(player) {
        onDispose { bassController.release() }
    }
    LaunchedEffect(player) {
        repeat(25) {
            if (player.audioSessionId != -1) {
                bassController.attach(player.audioSessionId)
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(200)
        }
    }
    LaunchedEffect(bassUltra, bassLow, bassMid) {
        bassController.update(bassMid, bassLow, bassUltra)
        settings.videoBassPreset = bassPreset
        settings.videoBassUltra = bassUltra
        settings.videoBassLow = bassLow
        settings.videoBassMid = bassMid
    }
    LaunchedEffect(muted, speed, pitch, pitchFollows) { applyPlayback(player) }

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

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(count, player) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragAccum = 0f
                        dragStartPos = player.currentPosition.coerceAtLeast(0L)
                    },
                    onHorizontalDrag = { _, amount ->
                        dragAccum += amount
                        val duration = player.duration
                        if (duration > 0L) {
                            // 整屏宽度 ≈ 3 分钟，拖动时实时预览
                            val deltaMs = (dragAccum / size.width.toFloat()) * 180_000f
                            val target = (dragStartPos + deltaMs.toLong()).coerceIn(0L, duration)
                            seekTarget = target
                            player.seekTo(target)
                        }
                    },
                    onDragEnd = {
                        seekTarget = null
                        dragAccum = 0f
                    },
                    onDragCancel = {
                        seekTarget = null
                        dragAccum = 0f
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
                        resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowNextButton(true)
                        setShowPreviousButton(true)
                        setBackgroundColor(android.graphics.Color.BLACK)
                        setControllerVisibilityListener(
                            PlayerView.ControllerVisibilityListener { visibility ->
                                showChrome = visibility == android.view.View.VISIBLE
                            }
                        )
                        playerView = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        seekTarget?.let { target ->
            val duration = player.duration.coerceAtLeast(1L)
            val delta = (target - dragStartPos) / 1000
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    formatClock(target) + " / " + formatClock(duration),
                    color = Color.White,
                    fontSize = 17.sp,
                )
                if (delta != 0L) {
                    Text(
                        (if (delta > 0) "+" else "") + delta + " 秒",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
        AnimatedVisibility(visible = showChrome, modifier = Modifier.align(Alignment.TopCenter)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
                        "第 ${index + 1} / $count 集 · 左右拖动快进快退",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
            }
            IconButton(onClick = { muted = !muted }) {
                Icon(
                    if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    contentDescription = if (muted) "取消静音" else "静音",
                    tint = Color.White,
                )
            }
            IconButton(onClick = { showTuning = true }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Speed, contentDescription = "播放速度 / 音调", tint = Color.White)
                    Text(
                        String.format(Locale.US, "%.2fx", speed),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 9.sp,
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

    if (showTuning) {
        BottomSheet(onDismiss = { showTuning = false }) {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Text("播放速度与音调", color = palette.textPrimary, fontSize = 18.sp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "速度 ${String.format(Locale.US, "%.2fx", speed)}",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                )
                Slider(
                    value = speed,
                    onValueChange = { speed = (it * 20f).toInt() / 20f },
                    valueRange = 0.5f..5f,
                    steps = 0,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("降速 0.50", color = palette.textTertiary, fontSize = 10.sp)
                    Text("原音 1.00", color = palette.textTertiary, fontSize = 10.sp)
                    Text("加速 5.00", color = palette.textTertiary, fontSize = 10.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("音调跟随速度", color = palette.textPrimary, fontSize = 14.sp)
                        Text(
                            if (pitchFollows) "变速时声音一起变高/变低" else "保持你单独设置的音调",
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    Switch(checked = pitchFollows, onCheckedChange = { pitchFollows = it })
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "音调 ${String.format(Locale.US, "%.2f", pitch)}",
                    color = if (pitchFollows) palette.textTertiary else palette.textSecondary,
                    fontSize = 12.sp,
                )
                Slider(
                    value = pitch,
                    onValueChange = { pitch = it },
                    valueRange = 0.5f..2f,
                    steps = 149,
                    enabled = !pitchFollows,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("低沉 0.50", color = palette.textTertiary, fontSize = 10.sp)
                    Text("原音 1.00", color = palette.textTertiary, fontSize = 10.sp)
                    Text("偏高 2.00", color = palette.textTertiary, fontSize = 10.sp)
                }
                Spacer(Modifier.height(16.dp))
                Text("低音增强", color = palette.textPrimary, fontSize = 15.sp)
                Text("选择音效，不是手动调三段", color = palette.textSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                SegmentControl(
                    options = listOf("原声", "重低音", "极重低音"),
                    selectedIndex = when (bassPreset) {
                        "重低音" -> 1
                        "极重低音" -> 2
                        else -> 0
                    },
                    onSelect = { index ->
                        bassPreset = listOf("原声", "重低音", "极重低音")[index]
                        val values = when (bassPreset) {
                            "重低音" -> Triple(35f, 55f, 40f)
                            "极重低音" -> Triple(55f, 85f, 75f)
                            else -> Triple(0f, 0f, 0f)
                        }
                        bassMid = values.first
                        bassLow = values.second
                        bassUltra = values.third
                    },
                )

                Spacer(Modifier.height(10.dp))
                TextButton(onClick = {
                    speed = 1f
                    pitch = 1f
                    pitchFollows = false
                    bassPreset = "原声"
                    bassMid = 0f
                    bassLow = 0f
                    bassUltra = 0f
                }) {
                    Text("恢复默认（1.00x / 原音）", color = palette.accent, fontSize = 13.sp)
                }
                Spacer(Modifier.height(26.dp))
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}
