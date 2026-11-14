package com.comii.app.ui.screens

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.StarBorder
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

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
    var favorited by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var currentName by remember { mutableStateOf(startName) }
    var showHighlights by remember { mutableStateOf(false) }
    var marks by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var askCollection by remember { mutableStateOf(false) }
    var collectionInput by remember { mutableStateOf("默认") }
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
        favorited = runCatching { state.repository.favoriteStatus(path, startName).first }.getOrDefault(false)
    }

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
        // 继续上次播放位置
        val saved = state.repository.settings.videoPosition(path, startName)
        if (saved > 3000) runCatching { player.seekTo(saved) }
        loading = false
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                index = player.currentMediaItemIndex
                scope.launch {
                    runCatching {
                        val detail = state.repository.series(path)
                        detail.videos.getOrNull(player.currentMediaItemIndex)?.let { currentName = it }
                    }
                }
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
                        seekTarget = null
                    },
                    onHorizontalDrag = { _, amount ->
                        dragAccum += amount
                        val duration = player.duration
                        // 先走一小段死区，避免轻轻一划就跳很远；整屏约 75 秒，比之前弱一半
                        val slop = viewConfiguration.touchSlop
                        if (duration > 0L && kotlin.math.abs(dragAccum) > slop) {
                            val effective = dragAccum - slop * kotlin.math.sign(dragAccum)
                            val deltaMs = (effective / size.width.toFloat()) * 75_000f
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
        // 左侧上下滑 = 亮度，右侧上下滑 = 音量
        var hint by remember { mutableStateOf<String?>(null) }
        var hintJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        val window = (context as? android.app.Activity)?.window
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var startX = 0f
                    var startVolume = 0f
                    var startBrightness = 0f
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            startX = offset.x
                            startVolume = player.volume
                            startBrightness = window?.attributes?.screenBrightness ?: 0.5f
                        },
                        onVerticalDrag = { change, amount ->
                            val fraction = -amount / size.height.toFloat()
                            if (startX > size.width * 0.55f) {
                                val next = (player.volume + fraction).coerceIn(0f, 1f)
                                player.volume = next
                                hint = "音量 ${(next * 100).toInt()}%"
                            } else if (startX < size.width * 0.45f) {
                                val next = (startBrightness + fraction).coerceIn(0.05f, 1f)
                                if (window != null) {
                                    val attrs = window.attributes
                                    attrs.screenBrightness = next
                                    window.attributes = attrs
                                }
                                hint = "亮度 ${(next * 100).toInt()}%"
                            }
                            change.consume()
                            hintJob?.cancel()
                            hintJob = scope.launch {
                                kotlinx.coroutines.delay(900)
                                hint = null
                            }
                        },
                    )
                }
        )
        hint?.let { text ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(text, color = Color.White, fontSize = 15.sp)
            }
        }

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
        AnimatedVisibility(
            visible = showChrome,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = androidx.compose.animation.slideInVertically(initialOffsetY = { -it }) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { -it }) + androidx.compose.animation.fadeOut(),
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.38f))
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 4.dp, vertical = 6.dp)
                .padding(top = 8.dp),
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
            IconButton(onClick = {
                scope.launch {
                    if (favorited) {
                        runCatching { state.repository.removeFavorite(path, currentName) }
                            .onSuccess { favorited = false; state.showToast("已取消收藏") }
                            .onFailure { state.showToast("取消失败：${it.message}") }
                    } else {
                        askCollection = true
                    }
                }
            }) {
                Icon(
                    if (favorited) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = "收藏",
                    tint = if (favorited) Color(0xFFFFC107) else Color.White,
                )
            }

        }
        }
    }

    if (askCollection) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { askCollection = false },
            containerColor = palette.card,
            title = { Text("收藏到哪个收藏夹？", color = palette.textPrimary) },
            text = {
                Column {
                    Text("留空或写「默认」会放进默认收藏夹", color = palette.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = collectionInput,
                        onValueChange = { collectionInput = it },
                        singleLine = true,
                        label = { Text("收藏夹名称") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = collectionInput.trim().ifBlank { "默认" }
                    askCollection = false
                    scope.launch {
                        runCatching { state.repository.addFavorite(path, currentName, target) }
                            .onSuccess { favorited = true; state.showToast("已收藏到「$target」") }
                            .onFailure { state.showToast("收藏失败：${it.message}") }
                    }
                }) { Text("收藏", color = palette.accent) }
            },
            dismissButton = { TextButton(onClick = { askCollection = false }) { Text("取消") } },
        )
    }
    if (showHighlights) {
        LaunchedEffect(currentName, showHighlights) {
            marks = runCatching { state.repository.highlights(path, currentName) }.getOrDefault(emptyList())
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showHighlights = false },
            containerColor = palette.card,
            title = { Text("高潮标记", color = palette.textPrimary) },
            text = {
                Column(Modifier.heightIn(max = 320.dp)) {
                    if (marks.isEmpty()) {
                        Text("还没有标记，播放时点书签按钮添加", color = palette.textSecondary, fontSize = 13.sp)
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn {
                            items(marks.size) { i ->
                                val (ms, label) = marks[i]
                                Row(
                                    Modifier.fillMaxWidth().pointerInput(ms) {
                                        detectTapGestures {
                                            player.seekTo(ms)
                                            showHighlights = false
                                        }
                                    }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(formatClock(ms), color = palette.accent, fontSize = 14.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(label.ifBlank { "跳转到这里" }, color = palette.textSecondary, fontSize = 12.sp)
                                    Spacer(Modifier.weight(1f))
                                    IconButton(onClick = {
                                        scope.launch {
                                            runCatching { state.repository.removeHighlight(path, currentName, ms) }
                                                .onSuccess { marks = marks.filterNot { it.first == ms } }
                                        }
                                    }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "删除标记", tint = palette.textTertiary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHighlights = false }) { Text("关闭", color = palette.accent) }
            },
        )
    }
    if (showTuning) {
        BottomSheet(onDismiss = { showTuning = false }) {
            Column(
                Modifier
                    .padding(horizontal = 22.dp)
                    .heightIn(max = 540.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("播放速度与音调", color = palette.textPrimary, fontSize = 18.sp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "速度 ${String.format(Locale.US, "%.2fx", speed)}",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                )
                Slider(
                    value = speedToSlider(speed),
                    onValueChange = { speed = (sliderToSpeed(it) * 20f).toInt() / 20f },
                    valueRange = 0f..1f,
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
                    value = valueToSlider(pitch, 0.5f, 2f),
                    onValueChange = { pitch = (sliderToValue(it, 0.5f, 2f) * 100f).toInt() / 100f },
                    valueRange = 0f..1f,
                    steps = 0,
                    enabled = !pitchFollows,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("低沉 0.50", color = palette.textTertiary, fontSize = 10.sp)
                    Text("原音 1.00", color = palette.textTertiary, fontSize = 10.sp)
                    Text("偏高 2.00", color = palette.textTertiary, fontSize = 10.sp)
                }
                Spacer(Modifier.height(16.dp))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        onClick = {
                            val ms = player.currentPosition
                            if (ms > 0) {
                                scope.launch {
                                    runCatching { state.repository.addHighlight(path, currentName, ms) }
                                        .onSuccess {
                                            marks = state.repository.highlights(path, currentName)
                                            state.showToast("已标记 ${formatClock(ms)}")
                                        }
                                        .onFailure { state.showToast("标记失败：${it.message}") }
                                }
                            }
                        },
                    ) { Text("标记高潮", color = palette.accent) }
                    TextButton(onClick = { showTuning = false; showHighlights = true }) {
                        Text("高潮列表", color = palette.accent)
                    }
                }
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

/** 对称滑杆：1.0 永远在正中间（0.5x 与 5x 分列两侧）。 */
private fun valueToSlider(value: Float, min: Float, max: Float): Float {
    val v = value.coerceIn(min, max)
    return if (v <= 1f) 0.5f * (v - min) / (1f - min)
    else 0.5f + 0.5f * (v - 1f) / (max - 1f)
}

private fun sliderToValue(pos: Float, min: Float, max: Float): Float {
    val p = pos.coerceIn(0f, 1f)
    return if (p <= 0.5f) min + (p / 0.5f) * (1f - min)
    else 1f + ((p - 0.5f) / 0.5f) * (max - 1f)
}

private fun speedToSlider(speed: Float): Float = valueToSlider(speed, 0.5f, 5f)

private fun sliderToSpeed(pos: Float): Float = sliderToValue(pos, 0.5f, 5f)
