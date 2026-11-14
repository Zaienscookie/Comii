package com.comii.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.comii.app.data.Http
import com.comii.app.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.comii.app.data.AppLog
import com.comii.app.data.GalleryItem
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.theme.ComiiTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged

private const val PHOTO_PAGE = 500

private fun timelineLabel(mtime: Double): String {
    if (mtime <= 0.0) return "未知时间"
    val formatter = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())
    return formatter.format(Date((mtime * 1000).toLong()))
}


/** 「照片」：整库所有图片 + 视频，vivo 相册那种 4 列方格。 */
@Composable
fun PhotosTab(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    var items by remember { mutableStateOf(state.photosItems) }
    var total by remember { mutableIntStateOf(state.photosTotal) }
    var loading by remember { mutableStateOf(items.isEmpty()) }
    var loadingMore by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var dragSelectOn by remember { mutableStateOf(true) }
    var galleryMode by remember { mutableStateOf(state.repository.settings.photosGallery) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = state.photosScrollIndex,
        initialFirstVisibleItemScrollOffset = state.photosScrollOffset,
    )

    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (items.isNotEmpty() && !loading) {
                    state.photosScrollIndex = index
                    state.photosScrollOffset = offset
                }
            }
    }

    suspend fun loadFirst(force: Boolean = false) {
        if (!force && state.photosLoaded) {
            items = state.photosItems
            total = state.photosTotal
            loading = false
            AppLog.add("photos", "use cache items=${items.size} total=$total")
            return
        }
        if (items.isEmpty()) loading = true
        AppLog.add("photos", "loadFirst force=$force")
        runCatching { state.repository.gallery(limit = PHOTO_PAGE, offset = 0, sort = "recent") }
            .onSuccess {
                items = it.items
                // 旧服务端在 offset=0 时把 total 写成首页条数（500），会让分页误以为到底
                total = if (it.items.size < PHOTO_PAGE) it.items.size else maxOf(it.total, it.items.size + 1)
                state.photosItems = it.items
                state.photosTotal = it.total
                state.photosLoaded = true
                AppLog.add("photos", "loadFirst ok items=${it.items.size} total=${it.total}")
            }
            .onFailure { AppLog.add("photos", "loadFirst failed: ${it.message}") }
        loading = false
    }

    suspend fun loadMore() {
        if (loadingMore || items.isEmpty() || items.size >= total) return
        loadingMore = true
        runCatching {
            state.repository.gallery(limit = PHOTO_PAGE, offset = items.size, sort = "recent")
        }.onSuccess {
            items = items + it.items
            total = if (it.items.size < PHOTO_PAGE) items.size else maxOf(it.total, items.size + 1)
            state.photosItems = items
            state.photosTotal = it.total
            AppLog.add("photos", "loadMore ok size=${items.size}")
        }
        loadingMore = false
    }

    LaunchedEffect(refreshKey, state.serverGeneration) {
        // 只有切换服务器 / 重新登录（generation 变化）才清空；切 Tab 回来直接用缓存
        val switched = state.serverGeneration != state.photosGeneration
        if (switched) {
            state.photosGeneration = state.serverGeneration
            items = emptyList()
            total = 0
            loading = true
        }
        loadFirst(force = refreshKey > 0 || switched)
    }

    LaunchedEffect(gridState, items.size, total) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last -> if (last >= items.size - 12) loadMore() }
    }

    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && state.photosScrollIndex in items.indices) {
            runCatching {
                gridState.scrollToItem(state.photosScrollIndex, state.photosScrollOffset)
            }
        }
    }

    fun keyAt(position: Offset): String? {
        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            position.x >= item.offset.x && position.x <= item.offset.x + item.size.width &&
                position.y >= item.offset.y && position.y <= item.offset.y + item.size.height
        } ?: return null
        val key = info.key?.toString() ?: return null
        if (key.startsWith("timeline:") || key.startsWith("loading")) return null
        return key
    }

    fun paintAt(position: Offset, select: Boolean) {
        val key = keyAt(position) ?: return
        selected = if (select) selected + key else selected - key
    }

    fun itemKey(item: GalleryItem): String = "${item.series_path}/${item.name}"

    fun openItem(item: GalleryItem) {
        if (item.kind == "video") {
            nav.navigate(Routes.player(item.series_path, item.name))
        } else {
            val imageIndex = items
                .takeWhile { !(it.series_path == item.series_path && it.name == item.name) }
                .count { it.kind != "video" }
            nav.navigate(Routes.reader(path = item.series_path, page = imageIndex, all = true, name = item.name))
        }
    }

    fun toggleSelected(item: GalleryItem) {
        val key = itemKey(item)
        selected = if (key in selected) selected - key else selected + key
    }

    fun selectedItems(): List<GalleryItem> = items.filter { itemKey(it) in selected }

    fun shareSelected() {
        val picked = selectedItems()
        if (picked.isEmpty() || sharing) return
        sharing = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val api = state.repository.apiOrNull() ?: return@runCatching false
                    val directory = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                    val uris = picked.mapNotNull { item ->
                        runCatching {
                            val url = if (item.kind == "video") api.videoUrl(item.series_path, item.name)
                            else api.imageUrl(item.series_path, item.name)
                            val safe = (item.series_path.replace('/', '_') + "_" + item.name)
                                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                            val target = java.io.File(directory, safe)
                            Http.client.newCall(okhttp3.Request.Builder().url(url).get().build()).execute().use { response ->
                                if (!response.isSuccessful) return@use false
                                response.body?.byteStream()?.use { input ->
                                    target.outputStream().use { output -> input.copyTo(output) }
                                }
                                true
                            }
                            if (!target.isFile) return@runCatching null
                            androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", target
                            )
                        }.getOrNull()
                    }
                    if (uris.isEmpty()) return@runCatching false
                    val intent = android.content.Intent(
                        if (uris.size == 1) android.content.Intent.ACTION_SEND
                        else android.content.Intent.ACTION_SEND_MULTIPLE
                    ).apply {
                        type = if (picked.any { it.kind == "video" }) "video/*" else "image/*"
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (uris.size == 1) putExtra(android.content.Intent.EXTRA_STREAM, uris.first())
                        else putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "分享"))
                    true
                }.getOrDefault(false)
            }
            sharing = false
            if (!ok) state.showToast("分享失败")
        }
    }

    fun deleteSelected() {
        val groups = selectedItems().groupBy { it.series_path }
        if (groups.isEmpty()) return
        confirmDelete = false
        scope.launch {
            var deleted = 0
            groups.forEach { (series, entries) ->
                runCatching { state.repository.deleteMedia(series, entries.map { it.name }) }
                    .onSuccess { deleted += entries.size }
                    .onFailure { state.showToast("删除失败：${it.message}") }
            }
            selected = emptySet()
            editing = false
            state.showToast("已删除 $deleted 项")
            refreshKey++
        }
    }

    fun openHistory() {
        val path = state.repository.settings.lastViewedPath
        val name = state.repository.settings.lastViewedName
        if (path.isBlank() || name.isBlank()) {
            state.showToast("还没有观看记录")
            return
        }
        val index = items
            .takeWhile { !(it.series_path == path && it.name == name) }
            .count { it.kind != "video" }
        val found = items.any { it.series_path == path && it.name == name }
        if (!found) {
            state.showToast("历史图片不在当前列表，请刷新")
            return
        }
        AppLog.add("photos", "open history $path/$name index=$index")
        nav.navigate(Routes.reader(path, index, all = true, name = name))
    }

    val timeline = remember(items) {
        val groups = LinkedHashMap<String, MutableList<GalleryItem>>()
        items.forEach { item ->
            groups.getOrPut(timelineLabel(item.mtime)) { mutableListOf() }.add(item)
        }
        groups
    }

    Column(Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "照片",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary,
                )
                Text(
                    if (total > 0) "共 $total 项" else "还没有照片",
                    fontSize = 12.5.sp,
                    color = palette.textSecondary,
                )
            }
            if (items.isNotEmpty() && state.authRole in setOf("owner", "admin")) {
                TextButton(onClick = {
                    editing = !editing
                    if (!editing) selected = emptySet()
                }) {
                    Text(if (editing) "完成" else "编辑", color = palette.accent, fontWeight = FontWeight.Medium)
                }
            }
            if (state.repository.settings.lastViewedPath.isNotBlank()) {
                IconButton(onClick = { openHistory() }) {
                    Icon(Icons.Rounded.History, contentDescription = "观看历史", tint = palette.accent)
                }
            }
            IconButton(onClick = {
                galleryMode = !galleryMode
                state.repository.settings.photosGallery = galleryMode
            }) {
                Icon(
                    if (galleryMode) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView,
                    contentDescription = if (galleryMode) "方格模式" else "画廊模式（保持比例）",
                    tint = palette.accent,
                )
            }
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = palette.accent)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f)) {
            when {
                loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
                items.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.PlayArrow,
                    title = "还没有照片",
                    subtitle = "在相册里导入图片或视频后会出现在这里",
                )
                galleryMode -> LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalItemSpacing = 3.dp,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    staggeredItems(items, key = { "${it.series_path}/${it.name}" }) { item ->
                        var ratio by remember(item.series_path, item.name) { mutableFloatStateOf(0f) }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(if (ratio > 0f) Modifier.aspectRatio(ratio) else Modifier.height(180.dp))
                                .clip(RoundedCornerShape(4.dp))
                                .background(palette.card)
                                .pointerInput(item.series_path, item.name, editing) {
                                    detectTapGestures {
                                        if (editing) toggleSelected(item) else openItem(item)
                                    }
                                }
                        ) {
                            AsyncImage(
                                model = state.repository.mediaUrl(item.thumb),
                                contentDescription = item.name,
                                contentScale = ContentScale.FillWidth,
                                onSuccess = { result ->
                                    val w = result.result.drawable.intrinsicWidth
                                    val h = result.result.drawable.intrinsicHeight
                                    if (w > 0 && h > 0) ratio = w.toFloat() / h
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (item.kind == "video") {
                                Box(
                                    Modifier.align(Alignment.Center).size(28.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = "视频", tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    userScrollEnabled = !editing,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(editing, items) {
                            if (editing) {
                                detectDragGestures(
                                    onDragStart = { position ->
                                        val key = keyAt(position)
                                        dragSelectOn = key != null && key !in selected
                                        paintAt(position, dragSelectOn)
                                    },
                                    onDrag = { change, _ ->
                                        paintAt(change.position, dragSelectOn)
                                        change.consume()
                                    },
                                )
                            }
                        },
                ) {
                    timeline.forEach { (label, group) ->
                        item(key = "timeline:$label", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                label,
                                color = palette.textSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                        items(group, key = { "${it.series_path}/${it.name}" }) { item ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(palette.card)
                                .pointerInput(item.series_path, item.name, editing) {
                                    detectTapGestures {
                                        if (editing) {
                                            toggleSelected(item)
                                        } else if (item.kind == "video") {
                                            nav.navigate(Routes.player(item.series_path, item.name))
                                        } else {
                                            // 「照片」是整库相册：按全局图片序号打开，不能退回单系列
                                            val imageIndex = items
                                                .takeWhile { !(it.series_path == item.series_path && it.name == item.name) }
                                                .count { it.kind != "video" }
                                            AppLog.add("photos", "open index=$imageIndex ${item.series_path}/${item.name} total=${items.size}")
                                            nav.navigate(
                                                Routes.reader(
                                                    path = item.series_path,
                                                    page = imageIndex,
                                                    all = true,
                                                    name = item.name,
                                                )
                                            )
                                        }
                                    }
                                }
                        ) {
                            AsyncImage(
                                model = state.repository.mediaUrl(item.thumb),
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (editing) {
                                val isSelected = itemKey(item) in selected
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(5.dp)
                                        .size(22.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(if (isSelected) palette.accent else Color.Black.copy(alpha = 0.4f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                            if (item.kind == "video") {
                                Box(
                                    Modifier
                                        .align(Alignment.Center)
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = "视频",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                    }
                    if (loadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = editing,
                enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { it },
            ) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(palette.elevated)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selected.size} 项", color = palette.textSecondary, fontSize = 12.sp)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            selected = if (selected.size == items.size) emptySet()
                            else items.map { itemKey(it) }.toSet()
                        }) { Text(if (selected.size == items.size) "取消全选" else "全选", color = palette.accent) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { shareSelected() }, enabled = selected.isNotEmpty() && !sharing) {
                            Text(if (sharing) "分享中…" else "分享原图", color = palette.accent)
                        }
                        TextButton(onClick = {
                            val picked = selectedItems()
                            if (picked.isNotEmpty()) {
                                state.setClipboard(picked.map { itemKey(it) }, "${picked.size} 个文件")
                                selected = emptySet()
                                editing = false
                                state.showToast("已剪切 ${picked.size} 项，进入相册后点「粘贴」")
                            }
                        }, enabled = selected.isNotEmpty()) {
                            Text("剪切", color = palette.accent)
                        }
                        TextButton(onClick = { confirmDelete = true }, enabled = selected.isNotEmpty()) {
                            Text("删除", color = palette.danger)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { editing = false; selected = emptySet() }) {
                            Text("取消", color = palette.textSecondary)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "删除 ${selected.size} 项？",
            message = "会将这些图片/视频移到服务器回收站，3 天内可恢复。",
            confirmText = "删除",
            destructive = true,
            onConfirm = { deleteSelected() },
            onDismiss = { confirmDelete = false },
        )
    }
}



