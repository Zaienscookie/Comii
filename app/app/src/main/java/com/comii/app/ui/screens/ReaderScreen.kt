package com.comii.app.ui.screens

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
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
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comii.app.data.Http
import com.comii.app.ui.AppState
import com.comii.app.ui.components.BottomSheet
import com.comii.app.ui.components.SegmentControl
import com.comii.app.ui.theme.ComiiTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ALL_PROGRESS_KEY = "__comii_all__"

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
    val autoRotate = settings.autoRotate
    val activity = context as? android.app.Activity
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
    val launch = remember { com.comii.app.ui.ReaderLaunch.consume(path) }
    val allMode = launch?.all ?: all
    val initialPage = launch?.index ?: startPage
    val initialName = launch?.name ?: startName

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf(path.substringAfterLast('/')) }
    var names by remember { mutableStateOf<List<String>>(emptyList()) }
    var allItems by remember { mutableStateOf<List<com.comii.app.data.GalleryItem>>(emptyList()) }
    var localDir by remember { mutableStateOf<File?>(null) }
    var serverBase by remember { mutableStateOf(repository.apiOrNull()) }

    var mode by rememberSaveable { mutableStateOf(settings.readerMode) }
    var tapTurn by remember { mutableStateOf(settings.tapToTurn) }
    var rotateLandscape by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(settings.showProgressBar) }
    var quality by remember { mutableStateOf(settings.imageQuality) }
    var imageWidth by remember { mutableIntStateOf(repository.imageWidth()) }
    var prefetchAhead by remember { mutableIntStateOf(repository.prefetchPages()) }

    var showBar by remember { mutableStateOf(true) }
    var manualLandscape by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showEditMenu by remember { mutableStateOf(false) }
    var current by remember { mutableIntStateOf(0) }
    var initializing by remember { mutableStateOf(allMode) }
    val viewMode = if (allMode) "horizontal" else mode

    val listState = rememberLazyListState()
    val filmState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { names.size })

    LaunchedEffect(current, allMode) {
        if (allMode && names.isNotEmpty()) {
            runCatching { filmState.scrollToItem((current - 2).coerceAtLeast(0)) }
        }
    }

    fun model(name: String): Any? {
        val dir = localDir
        return if (dir != null) File(dir, name) else serverBase?.viewUrl(path, name, imageWidth)
    }

    fun thumbModel(name: String): Any? {
        val dir = localDir
        return if (dir != null) File(dir, name) else serverBase?.thumbUrl(path, name)
    }

    /** 「全部」模式下按全局位置取图。 */
    fun pageModel(index: Int): Any? {
        if (!allMode) return model(names.getOrNull(index) ?: return null)
        val item = allItems.getOrNull(index) ?: return null
        return serverBase?.viewUrl(item.series_path, item.name, imageWidth)
    }

    fun goTo(index: Int) {
        val target = index.coerceIn(0, (names.size - 1).coerceAtLeast(0))
        scope.launch {
            if (viewMode == "horizontal") pagerState.scrollToPage(target) else listState.scrollToItem(target)
        }
        current = target
    }

    fun currentRelativePath(): String? {
        if (allMode) {
            val item = allItems.getOrNull(current) ?: return null
            return "${item.series_path}/${item.name}"
        }
        val name = names.getOrNull(current) ?: return null
        return if (path.isBlank()) name else "$path/$name"
    }

    fun shareCurrent() {
        val item = if (allMode) allItems.getOrNull(current) else null
        val name = item?.name ?: names.getOrNull(current) ?: return
        val series = item?.series_path ?: path
        val url = serverBase?.imageUrl(series, name)
        val local = localDir?.let { File(it, name) }
        scope.launch {
            val uri = runCatching {
                withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "share").apply { mkdirs() }
                    val target = File(directory, "${System.currentTimeMillis()}_${name.substringAfterLast('/')}")
                    if (local != null && local.isFile) {
                        local.copyTo(target, overwrite = true)
                    } else {
                        val link = url ?: error("没有可分享的原图")
                        Http.client.newCall(
                            okhttp3.Request.Builder().url(link).get().build()
                        ).execute().use { response ->
                            if (!response.isSuccessful) error("下载原图失败")
                            val body = response.body ?: error("下载原图失败")
                            body.byteStream().use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                    }
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        target,
                    )
                }
            }.getOrElse {
                state.showToast("分享失败：${it.message}")
                return@launch
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "分享原图"))
        }
    }

    fun deleteCurrent() {
        val target = currentRelativePath() ?: return
        if (target.startsWith("comic/R18/") || target.startsWith("video/R18/")) {
            state.showToast("R18 是只读资源，不能删除")
            return
        }
        scope.launch {
            runCatching { repository.delete(listOf(target)) }
                .onSuccess {
                    state.showToast("已移入回收站")
                    nav.popBackStack()
                }
                .onFailure { state.showToast("删除失败：${it.message}") }
        }
    }

    LaunchedEffect(allMode, path, initialName) {
        if (!allMode) return@LaunchedEffect
        initializing = true
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
            it.series_path == path && (initialName.isEmpty() || it.name == initialName)
        }
        val savedAll = settings.localPage(ALL_PROGRESS_KEY)
        current = when {
            initialPage >= 0 -> initialPage.coerceIn(0, items.lastIndex)
            savedAll > 0 -> savedAll.coerceIn(0, items.lastIndex)
            found >= 0 -> found
            else -> 0
        }
        title = items.getOrNull(current)?.series_name ?: "全部"
        loading = false
        delay(60)
        if (viewMode == "horizontal") pagerState.scrollToPage(current) else listState.scrollToItem(current)
        delay(80)
        initializing = false
    }

    LaunchedEffect(path, initialPage) {
        if (allMode) return@LaunchedEffect
        val offline = repository.offline.find(path)
        if (offline != null && offline.images.isNotEmpty()) {
            names = offline.images
            localDir = repository.offline.imagesDir(path)
            title = offline.name
            val page = if (initialPage >= 0) initialPage else settings.localPage(path)
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
                    val saved = if (initialPage >= 0) {
                        initialPage
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
            if (viewMode == "horizontal") pagerState.scrollToPage(saved) else listState.scrollToItem(saved)
            current = saved
        }
    }

    LaunchedEffect(mode, names.size) {
        if (names.isEmpty() || allMode) return@LaunchedEffect
        delay(40)
        val target = current
        if (current > 0) {
            if (viewMode == "horizontal") pagerState.scrollToPage(target) else listState.scrollToItem(target)
        }
    }

    LaunchedEffect(listState, names.size) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (initializing) return@collect
            if (index != current) current = index
            if (allMode) title = allItems.getOrNull(index)?.series_name ?: title
        }
    }
    LaunchedEffect(pagerState, names.size) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            if (initializing) return@collect
            if (page != current) current = page
            if (allMode) title = allItems.getOrNull(page)?.series_name ?: title
        }
    }

    LaunchedEffect(current, names.size, allItems, prefetchAhead, imageWidth) {
        if (names.isEmpty()) return@LaunchedEffect
        // 向后预加载 N 页（可配置），向前 1 页，翻页时不再黑一下
        val loader = coil.Coil.imageLoader(context)
        val indices = (current - 1)..(current + prefetchAhead.coerceIn(0, 12))
        indices.forEach { index ->
            if (index in names.indices) {
                runCatching {
                    loader.enqueue(
                        coil.request.ImageRequest.Builder(context)
                            .data(pageModel(index))
                            .build()
                    )
                }
            }
        }
        delay(500)
        if (allMode) {
            repository.saveProgress(ALL_PROGRESS_KEY, current, allItems.size)
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
                    if (viewMode == "horizontal") {
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
                IconButton(onClick = { showEditMenu = true }) {
                    Icon(Icons.Rounded.Edit, contentDescription = "编辑", tint = Color.White)
                }
                IconButton(onClick = {
                    val next = !manualLandscape
                    manualLandscape = next
                    activity?.requestedOrientation = if (next) {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }) {
                    Icon(Icons.Rounded.ScreenRotation, contentDescription = "横竖屏切换", tint = Color.White)
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
        if (allMode && showBar && names.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .background(Color.Black.copy(alpha = 0.62f)),
            ) {
                LazyRow(
                    state = filmState,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(66.dp),
                ) {
                    items(names.size) { index ->
                        val item = allItems.getOrNull(index)
                        val thumb = item?.let { serverBase?.thumbUrl(it.series_path, it.name) }
                        Box(
                            Modifier
                                .width(42.dp)
                                .fillMaxHeight()
                                .padding(vertical = 5.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (index == current) palette.accent.copy(alpha = 0.45f)
                                    else Color.White.copy(alpha = 0.08f)
                                )
                                .clickable { goTo(index) },
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(thumb)
                                    .crossfade(false)
                                    .build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { shareCurrent() }) {
                        Icon(Icons.Rounded.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("分享", color = Color.White, fontSize = 12.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { deleteCurrent() }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("删除", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showEditMenu) {
        BottomSheet(onDismiss = { showEditMenu = false }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text("编辑", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = {
                    showEditMenu = false
                    shareCurrent()
                }) {
                    Icon(Icons.Rounded.Share, contentDescription = null, tint = palette.accent, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("分享原图", color = palette.textPrimary, fontSize = 15.sp)
                }
                TextButton(onClick = {
                    showEditMenu = false
                    deleteCurrent()
                }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = palette.danger, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("删除", color = palette.danger, fontSize = 15.sp)
                }
                Spacer(Modifier.height(16.dp))
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
                SettingsSwitchRow("显示进度条", showProgress) { showProgress = it; settings.showProgressBar = it }

                Spacer(Modifier.height(16.dp))
                Text("图片清晰度", color = palette.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                SegmentControl(
                    options = listOf("原图", "高清", "标准", "流畅"),
                    selectedIndex = qualityIndex(quality),
                    onSelect = { index ->
                        quality = listOf("original", "high", "standard", "smooth")[index]
                        settings.imageQuality = quality
                        imageWidth = repository.imageWidth()
                    },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (quality == "original") "原图最清晰，但 Tailscale 下最慢" else "服务端按宽度缩放后传输，弱网更流畅",
                    color = palette.textTertiary,
                    fontSize = 11.sp,
                )

                Spacer(Modifier.height(14.dp))
                Text("预加载页数", color = palette.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                SegmentControl(
                    options = listOf("关闭", "3 页", "5 页", "8 页"),
                    selectedIndex = prefetchIndex(prefetchAhead),
                    onSelect = { index ->
                        prefetchAhead = listOf(0, 3, 5, 8)[index]
                        settings.prefetchPages = prefetchAhead
                    },
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private fun qualityIndex(value: String): Int = when (value) {
    "original" -> 0
    "high" -> 1
    "smooth" -> 3
    else -> 2
}

private fun prefetchIndex(value: Int): Int = when {
    value <= 0 -> 0
    value <= 3 -> 1
    value <= 5 -> 2
    else -> 3
}

@Composable
private fun SettingsSwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {    val palette = ComiiTheme.palette
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
    var zoom by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    val gestureModifier = Modifier.pointerInput(fullHeight) {
        if (fullHeight) {
            detectTransformGestures { _, pan, scaleChange, _ ->
                zoom = (zoom * scaleChange).coerceIn(1f, 4f)
                panOffset = if (zoom > 1f) panOffset + pan else Offset.Zero
            }
        }
    }
    val zoomModifier = Modifier.graphicsLayer {
        scaleX = zoom
        scaleY = zoom
        translationX = panOffset.x
        translationY = panOffset.y
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (fullHeight) Modifier.height(pageHeight) else Modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        val imageRequest = ImageRequest.Builder(context).data(model).crossfade(false).build()
        if (fullHeight) {
            if (maxWidth > maxHeight) {
                // 横屏：整张图缩放到屏幕内，避免只看到一半
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(gestureModifier)
                        .then(zoomModifier),
                )
            } else {
                // 竖屏：按宽度铺满，长图在页内上下滚动
                Box(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = pageHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = imageRequest,
                            contentDescription = null,
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(gestureModifier)
                                .then(zoomModifier),
                        )
                    }
                }
            }
        } else {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .then(gestureModifier)
                    .then(zoomModifier),
            )
        }
    }
}

