package com.comii.app.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comii.app.ui.AppState
import com.comii.app.ui.components.BottomSheet
import com.comii.app.ui.components.SegmentControl
import com.comii.app.ui.theme.ComiiTheme
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ReaderScreen(
    state: AppState,
    path: String,
    startPage: Int = -1,
    startName: String = "",
    all: Boolean = false,
    nav: NavHostController,
) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val portrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val repository = state.repository
    val settings = repository.settings

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf(path.substringAfterLast('/')) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var allItems by remember { mutableStateOf<List<com.comii.app.data.GalleryItem>>(emptyList()) }
    var localDir by remember { mutableStateOf<File?>(null) }
    var serverBase by remember { mutableStateOf(repository.apiOrNull()) }

    var mode by rememberSaveable { mutableStateOf(settings.readerMode) }
    var tapTurn by remember { mutableStateOf(settings.tapToTurn) }
    var rotateLandscape by remember { mutableStateOf(settings.landscapeRotate) }
    var showProgress by remember { mutableStateOf(settings.showProgressBar) }

    var showBar by remember { mutableStateOf(true) }
    var showGrid by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { names.size })

    fun model(name: String): Any? {
        val dir = localDir
        return if (dir != null) File(dir, name) else serverBase?.imageUrl(path, name)
    }

    fun thumbModel(name: String): Any? {
        val dir = localDir
        return if (dir != null) File(dir, name) else serverBase?.thumbUrl(path, name)
    }

    /** 「全部」模式下按全局位置取图。 */
    fun pageModel(index: Int): Any? {
        if (!all) return model(names.getOrNull(index) ?: return null)
        val item = allItems.getOrNull(index) ?: return null
        return serverBase?.imageUrl(item.series_path, item.name)
    }

    fun goTo(index: Int) {
        val target = index.coerceIn(0, (names.size - 1).coerceAtLeast(0))
        scope.launch {
            if (mode == "horizontal") pagerState.scrollToPage(target) else listState.scrollToItem(target)
        }
        current = target
    }

    LaunchedEffect(all, path, startName) {
        if (!all) return@LaunchedEffect
        val api = repository.apiOrNull()
        if (api == null) {
            error = "未配置服务器"
            loading = false
            return@LaunchedEffect
        }
        val response = runCatching {
            repository.gallery(limit = 5000, offset = 0, path = "", sort = "recent")
        }.getOrNull()
        val items = response?.items?.filter { it.kind != "video" }.orEmpty()
        if (items.isEmpty()) {
            error = "没有可阅读的图片"
            loading = false
            return@LaunchedEffect
        }
        allItems = items
        names = items.map { it.name }
        val found = items.indexOfFirst {
            it.series_path == path && (startName.isEmpty() || it.name == startName)
        }
        current = if (found >= 0) found else 0
        title = items.getOrNull(current)?.series_name ?: "全部"
        loading = false
        delay(60)
        if (mode == "horizontal") pagerState.scrollToPage(current) else listState.scrollToItem(current)
    }

    LaunchedEffect(path, startPage) {
        if (all) return@LaunchedEffect
        val offline = repository.offline.find(path)
        if (offline != null && offline.images.isNotEmpty()) {
            names = offline.images
            localDir = repository.offline.imagesDir(path)
            title = offline.name
            val page = if (startPage >= 0) startPage else settings.localPage(path)
            current = page.coerceIn(0, (offline.images.size - 1).coerceAtLeast(0))
            loading = false
        } else {
            val api = repository.apiOrNull()
            if (api == null) {
                error = "未配置服务器"
                loading = false
                return@LaunchedEffect
            }
            runCatching { api.series(path) }
                .onSuccess { detail ->
                    names = detail.images
                    title = detail.name
                    val serverPage = detail.progress?.page ?: 0
                    val saved = if (startPage >= 0) {
                        startPage
                    } else {
                        maxOf(settings.localPage(path), serverPage)
                    }
                    if (saved > 0) {
                        current = saved.coerceIn(0, (detail.images.size - 1).coerceAtLeast(0))
                    }
                }
                .onFailure { error = it.message ?: "加载失败" }
            loading = false
        }
        val saved = current
        if (saved > 0 && names.isNotEmpty()) {
            delay(60)
            if (mode == "horizontal") pagerState.scrollToPage(saved) else listState.scrollToItem(saved)
            current = saved
        }
    }

    LaunchedEffect(mode, names.size) {
        if (names.isEmpty()) return@LaunchedEffect
        delay(40)
        val target = current
        if (current > 0) {
            if (mode == "horizontal") pagerState.scrollToPage(target) else listState.scrollToItem(target)
        }
    }

    LaunchedEffect(listState, names.size) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (index != current) current = index
            if (all) title = allItems.getOrNull(index)?.series_name ?: title
        }
    }
    LaunchedEffect(pagerState, names.size) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (page != current) current = page
            if (all) title = allItems.getOrNull(page)?.series_name ?: title
        }
    }

    LaunchedEffect(current, names.size, allItems) {
        if (names.isEmpty()) return@LaunchedEffect
        delay(500)
        if (all) {
            val item = allItems.getOrNull(current) ?: return@LaunchedEffect
            val series = allItems.filter { it.series_path == item.series_path }
            val local = series.indexOfFirst { it.name == item.name }.coerceAtLeast(0)
            repository.saveProgress(item.series_path, local, series.size)
        } else {
            repository.saveProgress(path, current, names.size)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accent)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error ?: "加载失败", color = Color.White)
                    Spacer(Modifier.height(10.dp))
                    Text("点击左上角返回", color = Color.Gray, fontSize = 13.sp)
                }
            }
            names.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("这个系列没有图片", color = Color.White)
            }
            else -> {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(tapTurn, mode, names.size) {
                            detectTapGestures { offset ->
                                if (!tapTurn) {
                                    showBar = !showBar
                                    return@detectTapGestures
                                }
                                val width = size.width
                                when {
                                    offset.x < width / 3f -> goTo(current - 1)
                                    offset.x > width * 2f / 3f -> goTo(current + 1)
                                    else -> showBar = !showBar
                                }
                            }
                        }
                ) {
                    if (mode == "horizontal") {
                        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                            ReaderPageImage(
                                model = pageModel(page),
                                rotate = rotateLandscape && portrait,
                                fullHeight = true,
                                pageHeight = screenHeight,
                            )
                        }
                    } else {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(names.size) { index ->
                                ReaderPageImage(
                                    model = pageModel(index),
                                    rotate = rotateLandscape && portrait,
                                    fullHeight = false,
                                    pageHeight = screenHeight,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showBar && names.isNotEmpty()) {
            // top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "返回", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Text(
                    title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("${current + 1} / ${names.size}", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                IconButton(onClick = { showGrid = true }) {
                    Icon(Icons.Rounded.GridView, contentDescription = "页码", tint = Color.White)
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Rounded.Tune, contentDescription = "阅读设置", tint = Color.White)
                }
            }

            if (showProgress) {
                // 贴在最底部的一条细线，不挡画面
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.18f)),
                ) {
                    val ratio = if (names.size <= 1) 1f else (current + 1).toFloat() / names.size
                    Box(
                        Modifier
                            .fillMaxWidth(ratio.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(palette.accent),
                    )
                }
            }
        }
    }

    if (showGrid) {
        BottomSheet(onDismiss = { showGrid = false }) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("跳到页面", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(names.size) { index ->
                        Box(
                            Modifier
                                .height(130.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(palette.separator)
                                .pointerInput(index) {
                                    detectTapGestures {
                                        goTo(index)
                                        showGrid = false
                                    }
                                }
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(thumbModel(names[index])).crossfade(true).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.6f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text("${index + 1}", color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showSettings) {
        BottomSheet(onDismiss = { showSettings = false }) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("阅读设置", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                SegmentControl(
                    options = listOf("上下滚动", "左右翻页"),
                    selectedIndex = if (mode == "vertical") 0 else 1,
                    onSelect = {
                        mode = if (it == 0) "vertical" else "horizontal"
                        settings.readerMode = mode
                        goTo(current)
                    },
                )
                Spacer(Modifier.height(12.dp))
                SettingsSwitchRow("点击翻页", tapTurn) { tapTurn = it; settings.tapToTurn = it }
                SettingsSwitchRow("旋转横图", rotateLandscape) { rotateLandscape = it; settings.landscapeRotate = it }
                SettingsSwitchRow("显示进度条", showProgress) { showProgress = it; settings.showProgressBar = it }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = ComiiTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = palette.textPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = palette.accent,
            ),
        )
    }
}

@Composable
private fun ReaderPageImage(
    model: Any?,
    rotate: Boolean,
    fullHeight: Boolean,
    pageHeight: androidx.compose.ui.unit.Dp,
) {
    val context = LocalContext.current
    var imageSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (fullHeight || rotate) Modifier.height(pageHeight) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        val wide = imageSize.width > imageSize.height && imageSize.height > 0
        if (rotate && wide && containerHeight > containerWidth) {
            val displayHeight = containerHeight
            val displayWidth = displayHeight * (imageSize.height.toFloat() / imageSize.width.toFloat())
            AsyncImage(
                model = ImageRequest.Builder(context).data(model).crossfade(false).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .width(displayHeight)
                    .height(displayWidth)
                    .rotate(90f),
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    if (size.width > 0 && size.height > 0) {
                        imageSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
                    }
                },
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(model).crossfade(false).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth(),
                onSuccess = { state ->
                    val size = state.painter.intrinsicSize
                    if (size.width > 0 && size.height > 0) {
                        imageSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
                    }
                },
            )
        }
    }
}
