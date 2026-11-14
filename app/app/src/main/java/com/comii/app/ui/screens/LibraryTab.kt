package com.comii.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sell
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.comii.app.data.LibraryNode
import com.comii.app.data.NodeKind
import com.comii.app.data.ReadingProgress
import com.comii.app.data.TagCount
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.BottomSheet
import com.comii.app.ui.components.ConfirmDialog
import com.comii.app.ui.components.CoverImage
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.components.SearchField
import com.comii.app.ui.components.formatBytes
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class MediaEntry(val name: String, val isVideo: Boolean, val index: Int)

private sealed interface LibraryDialog {
    data object NewAlbum : LibraryDialog
    data class Rename(val node: LibraryNode) : LibraryDialog
    data class Move(val nodes: List<LibraryNode>) : LibraryDialog
    data class Delete(val nodes: List<LibraryNode>) : LibraryDialog
    data class SetCover(val node: LibraryNode) : LibraryDialog
}

private data class DownloadProgress(
    val title: String,
    val done: Int,
    val total: Int,
    val unit: String,
)

@Composable
fun LibraryTab(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var path by rememberSaveable { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var browseData by remember { mutableStateOf<com.comii.app.data.BrowseResponse?>(null) }
    var media by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var progressMap by remember { mutableStateOf<Map<String, ReadingProgress>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var dragSelectOn by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var sheetItem by remember { mutableStateOf<LibraryNode?>(null) }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var downloadState by remember { mutableStateOf<DownloadProgress?>(null) }
    var downloadHidden by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var searchResults by remember { mutableStateOf<List<LibraryNode>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var selectedMedia by remember { mutableStateOf(setOf<String>()) }
    var askDeleteMedia by remember { mutableStateOf(false) }
    var tagTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    var tagMediaTargets by remember { mutableStateOf<List<String>>(emptyList()) }
    val gridState = rememberLazyGridState()

    suspend fun reload() {
        loading = true
        error = null
        if (!state.hasServer) {
            browseData = null
            media = emptyList()
            loading = false
            return
        }
        val target = path
        var failure: Throwable? = null
        repeat(3) { attempt ->
            val result = runCatching { state.repository.browse(target) }
            result.onSuccess {
                browseData = it
                failure = null
            }.onFailure {
                failure = it
                if (attempt < 2) delay(400L * (attempt + 1))
            }
            if (failure == null) return@repeat
        }
        if (failure != null) {
            browseData = null
            media = emptyList()
            if (target.isEmpty()) {
                error = failure!!.message ?: "加载失败"
            } else {
                state.showToast("找不到「${target.substringAfterLast('/')}」，已回到上一级")
                history.removeLastOrNull()
                path = target.substringBeforeLast('/', "")
            }
            loading = false
            return
        }
        media = if (target.isEmpty()) emptyList() else {
            val detail = runCatching { state.repository.series(target) }.getOrNull()
            buildList {
                detail?.images?.forEachIndexed { index, name -> add(MediaEntry(name, false, index)) }
                detail?.videos?.forEach { name -> add(MediaEntry(name, true, 0)) }
            }
        }
        progressMap = runCatching { state.repository.requireApi().progressAll() }
            .getOrDefault(emptyMap())
        loading = false
    }

    LaunchedEffect(path, refreshKey, state.serverGeneration) { reload() }
    LaunchedEffect(state.serverGeneration) {
        if (state.serverGeneration > 0) {
            history.clear()
            path = ""
            selected = emptySet()
            editing = false
        }
    }
    LaunchedEffect(path) { runCatching { gridState.scrollToItem(0) } }

    LaunchedEffect(query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            searchResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        delay(260)
        searching = true
        searchResults = runCatching { state.repository.search(keyword) }.getOrDefault(emptyList())
        searching = false
    }

    BackHandler(enabled = path.isNotEmpty()) {
        if (history.isNotEmpty()) path = history.removeAt(history.lastIndex) else path = ""
    }

    val allItems = browseData?.items.orEmpty()
        .filterNot { it.name == "#recycle" || it.name == "@eaDir" || it.name == "#snapshot" }
        .sortedWith(compareBy({ if (it.name == "收藏夹") 0 else 1 }, { it.name.lowercase() }))
    val showingSearch = query.isNotBlank()
    val gridItems = if (showingSearch) {
        searchResults.filterNot { it.name == "#recycle" || it.name == "@eaDir" || it.name == "#snapshot" }
    } else {
        allItems
    }
    val albumCount = allItems.count { it.isAlbum || it.isFolder }
    val mediaCount = media.size

    fun openAlbum(node: LibraryNode) {
        history.add(path)
        path = node.path
    }

    fun nodeAt(position: Offset): LibraryNode? {
        val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            position.x >= item.offset.x && position.x <= item.offset.x + item.size.width &&
                position.y >= item.offset.y && position.y <= item.offset.y + item.size.height
        } ?: return null
        return gridItems.getOrNull(info.index)
    }

    fun paintAt(position: Offset, select: Boolean) {
        val node = nodeAt(position) ?: return
        selected = if (select) selected + node.path else selected - node.path
    }

    fun cutSelection(nodes: List<LibraryNode>) {
        if (nodes.isEmpty()) return
        state.setClipboard(nodes.map { it.path }, nodes.joinToString("、") { it.name })
        selected = emptySet()
        selectedMedia = emptySet()
        editing = false
        state.showToast("已剪切 ${nodes.size} 项，进入目标相册后点「粘贴」")
    }

    fun pasteClipboard() {
        val paths = state.clipboardPaths
        if (paths.isEmpty()) return
        val target = path
        if (paths.any { target == it || target.startsWith(it + "/") }) {
            state.showToast("不能粘贴到被剪切目录的内部")
            return
        }
        scope.launch {
            runCatching { state.repository.move(paths, target) }
                .onSuccess { batch ->
                    if (batch.failed.isEmpty()) {
                        state.clearClipboard()
                        state.showToast("已粘贴到「${if (target.isBlank()) "根目录" else target.substringAfterLast('/')}」")
                    } else {
                        state.showToast("部分失败：${batch.failed.first().message}")
                        if (batch.succeeded.isNotEmpty()) state.clearClipboard()
                    }
                    refreshKey++
                }
                .onFailure { state.showToast("粘贴失败：${it.message}") }
        }
    }

    fun openNode(node: LibraryNode) {
        // 任何自己含媒体的文件夹都先进平铺页，点图片后才进入阅读。
        val leaf = node.child_count == null || node.child_count == 0
        val hasMedia = leaf && (node.image_count > 0 || node.video_count > 0 ||
            node.kind == NodeKind.MANGA || node.kind == NodeKind.VIDEO)
        when {
            hasMedia -> nav.navigate(Routes.series(node.path))
            node.isAlbum || node.isFolder -> openAlbum(node)
            else -> nav.navigate(Routes.series(node.path))
        }
    }

    fun startDownload(node: LibraryNode) {
        if (downloadState != null) return
        downloadHidden = false
        if (node.isAlbum || node.isFolder) {
            downloadState = DownloadProgress(node.name, 0, 0, "个系列")
            val job = state.repository.startDownloadFolder(node.path, onProgress = { done, total ->
                downloadState = DownloadProgress(node.name, done, total, "个系列")
            }, onError = { state.showToast("下载失败：${it.message}") })
            downloadJob = job
            scope.launch {
                job.join()
                downloadState = null
            }
        } else {
            downloadState = DownloadProgress(node.name, 0, 0, "页")
            val job = state.repository.startDownloadSeries(node.path, includeVideos = true, onProgress = { done, total ->
                downloadState = DownloadProgress(node.name, done, total, "页")
            }, onError = { state.showToast("下载失败：${it.message}") })
            downloadJob = job
            scope.launch {
                job.join()
                downloadState = null
            }
        }
    }

    if (path == "收藏夹") {
        FavoritesFlatScreen(state, nav, onBack = {
            if (history.isNotEmpty()) path = history.removeAt(history.lastIndex) else path = ""
        })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 6.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (path.isNotEmpty()) {
                IconButton(onClick = {
                    if (history.isNotEmpty()) path = history.removeAt(history.lastIndex) else path = ""
                }) {
                    Icon(
                        Icons.Rounded.ArrowBackIosNew,
                        contentDescription = "返回",
                        tint = palette.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (path.isEmpty()) "相册" else path.substringAfterLast('/'),
                    fontSize = if (path.isEmpty()) 32.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        showingSearch -> "搜索「${query.trim()}」· ${gridItems.size} 个结果"
                        else -> buildString {
                            if (albumCount > 0) append("$albumCount 个相册")
                            if (albumCount > 0 && mediaCount > 0) append(" · ")
                            if (mediaCount > 0) append("$mediaCount 项")
                            if (albumCount == 0 && mediaCount == 0) append(if (path.isEmpty()) "还没有相册" else "空相册")
                        }
                    },
                    fontSize = 12.5.sp,
                    color = palette.textSecondary,
                )
            }
            IconButton(onClick = {
                showSearch = !showSearch
                if (!showSearch) query = ""
            }) {
                Icon(Icons.Rounded.Search, contentDescription = "搜索", tint = palette.accent, modifier = Modifier.size(22.dp))
            }
            if (state.clipboardPaths.isNotEmpty()) {
                TextButton(onClick = { pasteClipboard() }) {
                    Text("粘贴", color = palette.accent, fontWeight = FontWeight.Medium)
                }
            }
            if (state.can("delete", path) || state.can("move", path) || state.can("manage", path)) {
                TextButton(onClick = {
                    editing = !editing
                    if (!editing) selected = emptySet()
                }) {
                    Text(if (editing) "完成" else "编辑", color = palette.accent, fontWeight = FontWeight.Medium)
                }
            }
            if (state.can("create", path)) {
                IconButton(onClick = { dialog = LibraryDialog.NewAlbum }) {
                    Icon(Icons.Rounded.Add, contentDescription = "新建相册", tint = palette.accent, modifier = Modifier.size(24.dp))
                }
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "更多", tint = palette.accent, modifier = Modifier.size(22.dp))
            }
        }

        AnimatedVisibility(visible = showSearch) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                SearchField(query, { query = it }, placeholder = "搜索相册、系列或文件名")
            }
        }

        if (downloadHidden && downloadState != null) {
            val progress = downloadState!!
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.card)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "后台下载「${progress.title}」 ${progress.done}/${progress.total.coerceAtLeast(1)}",
                        fontSize = 12.sp,
                        color = palette.textPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
                        color = palette.accent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = {
                    downloadJob?.cancel()
                    downloadJob = null
                    downloadState = null
                    downloadHidden = false
                }) { Text("取消", color = palette.danger, fontSize = 12.sp) }
            }
        }

        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = path,
                transitionSpec = {
                    val forward = targetState.length >= initialState.length
                    (slideInHorizontally(tween(220)) { if (forward) it / 5 else -it / 5 } +
                        fadeIn(tween(180))) togetherWith
                        (slideOutHorizontally(tween(200)) { if (forward) -it / 8 else it / 8 } +
                            fadeOut(tween(150)))
                },
                label = "album-nav",
            ) { _ ->
            when {
                !state.hasServer -> EmptyState(
                    icon = Icons.Rounded.Folder,
                    title = "还没有连接服务器",
                    subtitle = "可以先看「下载」里的离线内容；随时在「设置 → 服务器地址」填上地址和端口",
                    action = {
                        PrimaryButton(
                            text = "去设置服务器",
                            onClick = { nav.navigate("settings/server") },
                            modifier = Modifier.width(200.dp),
                        )
                    },
                )
                loading && browseData == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
                error != null -> EmptyState(
                    icon = Icons.Rounded.Folder,
                    title = "加载失败",
                    subtitle = error,
                    action = { PrimaryButton("重试", onClick = { refreshKey++ }, modifier = Modifier.width(160.dp)) },
                )
                showingSearch && searching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
                gridItems.isEmpty() && media.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.Folder,
                    title = if (showingSearch) "没有匹配的相册" else "空相册",
                    subtitle = if (showingSearch) "换个关键词试试" else "点右上角「+」新建相册，或从菜单里导入内容",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    userScrollEnabled = !editing,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(editing, gridItems) {
                            if (editing) {
                                detectDragGestures(
                                    onDragStart = { position ->
                                        val node = nodeAt(position)
                                        dragSelectOn = node != null && !selected.contains(node.path)
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
                    items(gridItems, key = { it.path }) { node ->
                        AlbumCard(
                            node = node,
                            coverUrl = state.repository.mediaUrl(node.cover),
                            progress = if (showingSearch) null else progressMap[node.path],
                            editing = editing && !showingSearch,
                            selected = selected.contains(node.path),
                            compact = true,
                            onClick = {
                                if (editing && !showingSearch) {
                                    selected = if (selected.contains(node.path)) selected - node.path else selected + node.path
                                } else {
                                    if (showingSearch) query = ""
                                    openNode(node)
                                }
                            },
                            onLongClick = {
                                if (editing && !showingSearch) {
                                    selected = if (selected.contains(node.path)) selected - node.path else selected + node.path
                                } else {
                                    sheetItem = node
                                }
                            },
                        )
                    }
                    if (media.isNotEmpty()) {
                        items(media, key = { "media:${it.name}:${it.index}" }) { entry ->
                            val model = state.repository.apiOrNull()?.let { api ->
                                if (entry.isVideo) api.videoThumbUrl(path, entry.name) else api.thumbUrl(path, entry.name)
                            }
                            MediaTile(
                                model = model,
                                entry = entry,
                                selected = selectedMedia.contains(entry.name),
                                selecting = selectedMedia.isNotEmpty(),
                                onClick = {
                                    if (selectedMedia.isNotEmpty()) {
                                        selectedMedia = if (selectedMedia.contains(entry.name)) selectedMedia - entry.name else selectedMedia + entry.name
                                    } else if (entry.isVideo) {
                                        nav.navigate(Routes.player(path, entry.name))
                                    } else {
                                        nav.navigate(Routes.reader(path, entry.index))
                                    }
                                },
                                onLongClick = { selectedMedia = selectedMedia + entry.name },
                            )
                        }
                    }
                }
            }
            }

            if (editing && !showingSearch && selected.isNotEmpty()) {
                EditBar(
                    count = selected.size,
                    onMove = {
                        val nodes = allItems.filter { selected.contains(it.path) }
                        if (nodes.isNotEmpty()) cutSelection(nodes)
                    },
                    onRename = {
                        val nodes = allItems.filter { selected.contains(it.path) }
                        if (nodes.size == 1) dialog = LibraryDialog.Rename(nodes.first())
                    },
                    onTag = { tagTargets = allItems.filter { selected.contains(it.path) }.map { it.path } },
                    onDelete = {
                        val nodes = allItems.filter { selected.contains(it.path) }
                        if (nodes.isNotEmpty()) dialog = LibraryDialog.Delete(nodes)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            if (selectedMedia.isNotEmpty()) {
                MediaBar(
                    count = selectedMedia.size,
                    onShare = {
                        val picked = media.filter { selectedMedia.contains(it.name) }
                        scope.launch {
                            val api = state.repository.apiOrNull()
                            if (api == null) {
                                state.showToast("未配置服务器")
                            } else {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching { shareMedia(context, api, path, picked) }.isSuccess
                                }
                                if (!ok) state.showToast("分享失败")
                            }
                        }
                    },
                    onTag = { tagMediaTargets = media.filter { selectedMedia.contains(it.name) }.map { it.name } },
                    onDelete = { askDeleteMedia = true },
                    onCancel = { selectedMedia = emptySet() },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }

    if (menuOpen) {
        BottomSheet(onDismiss = { menuOpen = false }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                if (state.can("upload", path)) {
                    MenuRow("导入内容", Icons.Rounded.Upload) { menuOpen = false; nav.navigate(Routes.upload(path)) }
                }
                if (state.can("manage", path)) {
                    MenuRow("标签", Icons.Rounded.Sell) { menuOpen = false; nav.navigate("tags") }
                }
                if (state.can("delete", path)) {
                    MenuRow("回收站", Icons.Rounded.Delete) { menuOpen = false; nav.navigate("trash") }
                }
                MenuRow("刷新", Icons.Rounded.Refresh) { menuOpen = false; refreshKey++ }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    if (tagTargets.isNotEmpty() || tagMediaTargets.isNotEmpty()) {
        TagBatchDialog(
            state = state,
            paths = tagTargets,
            mediaPath = path,
            mediaNames = tagMediaTargets,
            onDismiss = { tagTargets = emptyList(); tagMediaTargets = emptyList() },
            onDone = {
                tagTargets = emptyList()
                tagMediaTargets = emptyList()
                selected = emptySet()
                selectedMedia = emptySet()
                editing = false
                refreshKey++
            },
        )
    }

    sheetItem?.let { node ->
        BottomSheet(onDismiss = { sheetItem = null }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverImage(
                        model = state.repository.mediaUrl(node.cover),
                        kind = node.kind,
                        modifier = Modifier.width(52.dp).aspectRatio(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(node.name, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(nodeSubtitle(node), fontSize = 12.sp, color = palette.textSecondary)
                    }
                }
                Spacer(Modifier.height(14.dp))
                SheetAction("打开") { sheetItem = null; openNode(node) }
                if (state.can("manage", node.path)) {
                    SheetAction("重命名") { sheetItem = null; dialog = LibraryDialog.Rename(node) }
                }
                if (state.can("move", node.path)) {
                    SheetAction("剪切") { sheetItem = null; cutSelection(listOf(node)) }
                }
                if (node.isAlbum || node.isFolder) {
                    if (state.can("manage", node.path)) {
                        SheetAction("设为相册封面") {
                            sheetItem = null
                            if (path.isEmpty()) {
                                state.showToast("请先进入一个相册")
                            } else {
                                scope.launch {
                                    runCatching { state.repository.setAlbumCover(path, node.path) }
                                        .onSuccess { state.showToast("已设为相册封面"); refreshKey++ }
                                        .onFailure { state.showToast("设置失败：${it.message}") }
                                }
                            }
                        }
                        SheetAction("恢复默认封面") {
                            sheetItem = null
                            scope.launch {
                                runCatching { state.repository.setAlbumCover(node.path, "") }
                                    .onSuccess { state.showToast("已恢复默认封面"); refreshKey++ }
                                    .onFailure { state.showToast("操作失败：${it.message}") }
                            }
                        }
                        SheetAction("收藏到收藏夹") {
                            sheetItem = null
                            scope.launch {
                                runCatching { state.repository.addFavorite(node.path) }
                                    .onSuccess { state.showToast("已收藏到收藏夹") }
                                    .onFailure { state.showToast("收藏失败：${it.message}") }
                            }
                        }
                    }
                    if (state.can("download", node.path)) {
                        SheetAction("下载整个相册") { sheetItem = null; startDownload(node) }
                    }
                } else {
                    if (state.can("manage", node.path)) {
                        SheetAction("设置封面") { sheetItem = null; dialog = LibraryDialog.SetCover(node) }
                    }
                    if (state.can("download", node.path)) {
                        SheetAction("下载到本地") { sheetItem = null; startDownload(node) }
                    }
                }
                if (state.can("manage", node.path)) {
                    SheetAction("标签…") { sheetItem = null; tagTargets = listOf(node.path) }
                }
                if (state.can("delete", node.path)) {
                    SheetAction("删除", danger = true) { sheetItem = null; dialog = LibraryDialog.Delete(listOf(node)) }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    when (val current = dialog) {
        LibraryDialog.NewAlbum -> NewAlbumDialog(
            onCreate = { name ->
                scope.launch {
                    runCatching { state.repository.createFolder(path, name, "") }
                        .onSuccess { state.showToast("相册已创建"); refreshKey++ }
                        .onFailure { state.showToast("创建失败：${it.message}") }
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        is LibraryDialog.Rename -> RenameDialog(
            original = current.node.name,
            onConfirm = { newName ->
                scope.launch {
                    runCatching { state.repository.rename(current.node.path, newName) }
                        .onSuccess { state.showToast("已重命名"); refreshKey++ }
                        .onFailure { state.showToast("重命名失败：${it.message}") }
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        is LibraryDialog.Move -> MoveDialog(
            state = state,
            nodes = current.nodes,
            onConfirm = { target ->
                scope.launch {
                    runCatching { state.repository.move(current.nodes.map { it.path }, target) }
                        .onSuccess { batch ->
                            state.showToast(
                                if (batch.failed.isEmpty()) "已移动到「${target.ifBlank { "根目录" }}」"
                                else "部分移动失败：${batch.failed.first().message}"
                            )
                            selected = emptySet()
                            refreshKey++
                        }
                        .onFailure { state.showToast("移动失败：${it.message}") }
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        is LibraryDialog.Delete -> ConfirmDialog(
            title = "删除 ${current.nodes.size} 个相册？",
            message = "删除后进入回收站，3 天后自动清理。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                scope.launch {
                    runCatching { state.repository.delete(current.nodes.map { it.path }) }
                        .onSuccess { state.showToast("已移入回收站"); selected = emptySet(); editing = false; refreshKey++ }
                        .onFailure { state.showToast("删除失败：${it.message}") }
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        is LibraryDialog.SetCover -> CoverPickerDialog(
            state = state,
            node = current.node,
            onDone = { refreshKey++; dialog = null },
            onDismiss = { dialog = null },
        )

        null -> Unit
    }

    if (askDeleteMedia) {
        ConfirmDialog(
            title = "删除 ${selectedMedia.size} 项？",
            message = "会从相册里移除这些图片 / 视频（服务器上会移到 data/trash/media，仍可手动找回）。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                val names = selectedMedia.toList()
                askDeleteMedia = false
                scope.launch {
                    runCatching { state.repository.deleteMedia(path, names) }
                        .onSuccess {
                            selectedMedia = emptySet()
                            state.showToast("已删除 ${names.size} 项")
                            refreshKey++
                        }
                        .onFailure { state.showToast("删除失败：${it.message}") }
                }
            },
            onDismiss = { askDeleteMedia = false },
        )
    }

    downloadState?.takeIf { !downloadHidden }?.let { progress ->
        ConfirmDialog(
            title = "正在下载「${progress.title}」",
            message = if (progress.total > 0) "已下载 ${progress.done} / ${progress.total} ${progress.unit}" else "准备中…",
            confirmText = "隐藏",
            dismissText = "后台继续",
            onConfirm = { downloadHidden = true },
            onDismiss = { downloadHidden = true },
        )
    }
}

@Composable
private fun MenuRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val palette = ComiiTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .pointerInput(text) { detectTapGestures { onClick() } }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = palette.textPrimary, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(110), label = "press")
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
    ) { content() }
}

@Composable
private fun AlbumCard(
    node: LibraryNode,
    coverUrl: String?,
    progress: ReadingProgress?,
    editing: Boolean,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = ComiiTheme.palette
    Column(Modifier.fillMaxWidth()) {
        Pressable(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(if (compact) 8.dp else 12.dp))
                    .background(palette.card)
            ) {
                CoverImage(
                    model = coverUrl,
                    kind = node.kind,
                    modifier = Modifier.fillMaxSize(),
                )
                if (node.isAlbum || node.isFolder) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        Text(if (node.name == "收藏夹") "★" else "${node.child_count ?: 0}", color = if (node.name == "收藏夹") palette.accent else Color.White, fontSize = if (node.name == "收藏夹") 12.sp else 9.sp)
                    }
                }
                if (progress != null && progress.total > 0 && !node.isAlbum) {
                    val fraction = ((progress.page + 1).toFloat() / progress.total).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        color = palette.accent,
                        trackColor = Color.Black.copy(alpha = 0.35f),
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    )
                }
                if (editing) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(if (selected) palette.accent else Color.Black.copy(alpha = 0.35f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(if (compact) 3.dp else 5.dp))
        Text(
            node.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = if (compact) 11.sp else 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = palette.textPrimary,
        )
        Text(
            nodeSubtitle(node),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = if (compact) 9.5.sp else 11.sp,
            color = palette.textSecondary,
        )
    }
}

@Composable
private fun MediaTile(
    model: Any?,
    entry: MediaEntry,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = ComiiTheme.palette
    Pressable(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(palette.card)
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
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "视频", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            if (selecting || selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (selected) palette.accent else Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Text("✓", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MediaBar(
    count: Int,
    onShare: () -> Unit,
    onTag: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ComiiTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.elevated)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已选 $count 项", color = palette.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onTag) { Text("标签", color = palette.accent, fontSize = 12.sp) }
        TextButton(onClick = onShare) { Text("分享", color = palette.accent, fontSize = 12.sp) }
        TextButton(onClick = onDelete) { Text("删除", color = palette.danger, fontSize = 12.sp) }
        TextButton(onClick = onCancel) { Text("取消", color = palette.textSecondary, fontSize = 12.sp) }
    }
}

private fun nodeSubtitle(node: LibraryNode): String = when {
    node.isAlbum || node.isFolder -> buildString {
        val children = node.child_count ?: 0
        if (children > 0) append("$children 个子相册")
        val own = node.image_count + node.video_count
        if (own > 0) {
            if (isNotEmpty()) append(" · ")
            append("$own 项")
        }
        if (isEmpty()) append("空相册")
    }
    node.kind == NodeKind.VIDEO -> "${node.video_count} 个视频"
    node.kind == NodeKind.MIXED -> "${node.image_count} 图 · ${node.video_count} 视频"
    else -> "${node.image_count} 页"
}

@Composable
private fun SheetAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    val palette = ComiiTheme.palette
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.card)
            .pointerInput(text) { detectTapGestures { onClick() } }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(text, color = if (danger) palette.danger else palette.textPrimary, fontSize = 15.sp)
    }
}

@Composable
private fun EditBar(
    count: Int,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onTag: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ComiiTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.elevated)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已选 $count", color = palette.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onMove, enabled = count > 0) {
            Text("剪切", color = palette.accent, fontSize = 12.sp)
        }
        TextButton(onClick = onRename, enabled = count == 1) {
            Text("重命名", color = if (count == 1) palette.accent else palette.textTertiary, fontSize = 12.sp)
        }
        TextButton(onClick = onTag, enabled = count > 0) {
            Text("标签", color = palette.accent, fontSize = 12.sp)
        }
        TextButton(onClick = onDelete, enabled = count > 0) {
            Text("删除", color = palette.danger, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NewAlbumDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    val palette = ComiiTheme.palette
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = { Text("新建相册", color = palette.textPrimary) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("相册名称", color = palette.textTertiary) },
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions.Default,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = palette.background,
                    unfocusedContainerColor = palette.background,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { name.trim().takeIf { it.isNotEmpty() }?.let(onCreate) },
                enabled = name.isNotBlank(),
            ) { Text("创建", color = palette.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = palette.textSecondary) } },
    )
}

@Composable
private fun RenameDialog(original: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val palette = ComiiTheme.palette
    var value by remember { mutableStateOf(original) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = { Text("重命名", color = palette.textPrimary) },
        text = {
            TextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = palette.background,
                    unfocusedContainerColor = palette.background,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { value.trim().takeIf { it.isNotEmpty() && it != original }?.let(onConfirm) },
                enabled = value.isNotBlank() && value.trim() != original,
            ) { Text("保存", color = palette.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = palette.textSecondary) } },
    )
}

@Composable
private fun MoveDialog(
    state: AppState,
    nodes: List<LibraryNode>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = ComiiTheme.palette
    var folders by remember { mutableStateOf<List<com.comii.app.data.ComiiRepository.FolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        folders = runCatching { state.repository.folderTree() }.getOrDefault(emptyList())
        loading = false
    }

    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("移动到相册", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(12.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(14.dp)).background(palette.card)
                ) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                        items(folders.size) { index ->
                            val folder = folders[index]
                            val disabled = nodes.any { it.path == folder.path || folder.path.startsWith(it.path + "/") }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .pointerInput(folder.path, disabled) {
                                        detectTapGestures { if (!disabled) target = folder.path }
                                    }
                                    .background(if (target == folder.path) palette.accent.copy(alpha = 0.12f) else Color.Transparent)
                                    .padding(start = (16 + folder.depth * 16).dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Folder, contentDescription = null, tint = if (disabled) palette.textTertiary else palette.accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    folder.name,
                                    color = if (disabled) palette.textTertiary else palette.textPrimary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (target == folder.path) {
                                    Spacer(Modifier.weight(1f))
                                    Text("✓", color = palette.accent, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                text = "移动到「${folders.firstOrNull { it.path == target }?.name ?: "根目录"}」",
                enabled = !loading,
                onClick = { onConfirm(target) },
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun CoverPickerDialog(
    state: AppState,
    node: LibraryNode,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(node.path) {
        images = runCatching { state.repository.series(node.path).images }.getOrDefault(emptyList())
        loading = false
    }

    fun apply(name: String) {
        scope.launch {
            runCatching { state.repository.setCover(node.path, name) }
                .onSuccess {
                    state.showToast(if (name.isBlank()) "已恢复默认封面" else "封面已更新")
                    onDone()
                }
                .onFailure { state.showToast("设置失败：${it.message}") }
        }
    }

    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("选择封面 · ${node.name}", color = palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(images.size) { index ->
                        val name = images[index]
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.card)
                                .pointerInput(name) { detectTapGestures { apply(name) } }
                        ) {
                            AsyncImage(
                                model = state.repository.apiOrNull()?.thumbUrl(node.path, name),
                                contentDescription = name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            TextButton(onClick = { apply("") }, modifier = Modifier.fillMaxWidth()) {
                Text("恢复默认封面（第一页）", color = palette.textSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 批量打标签：给选中的相册 / 系列，或当前相册里选中的照片打标签。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagBatchDialog(
    state: AppState,
    paths: List<String>,
    mediaPath: String,
    mediaNames: List<String>,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    var allTags by remember { mutableStateOf<List<TagCount>>(emptyList()) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var input by remember { mutableStateOf("") }
    var groupInput by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        allTags = runCatching { state.repository.tags() }.getOrNull()?.tags.orEmpty()
    }

    val targets = if (paths.isNotEmpty()) paths else mediaNames.map { "$mediaPath/$it" }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = {
            Text(
                "打标签 · ${if (paths.isNotEmpty()) "${paths.size} 个相册" else "${mediaNames.size} 个文件"}",
                color = palette.textPrimary,
                fontSize = 17.sp,
            )
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                allTags.groupBy { it.group.ifBlank { "未分组" } }.forEach { (group, tags) ->
                    Text(group, color = palette.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    FlowRow {
                        tags.forEach { tag ->
                            TagChip(
                                text = tag.name,
                                selected = picked.contains(tag.name),
                                onClick = {
                                    picked = if (picked.contains(tag.name)) picked - tag.name else picked + tag.name
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text("新标签", color = palette.textTertiary, fontSize = 13.sp) },
                        shape = RoundedCornerShape(10.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = palette.background,
                            unfocusedContainerColor = palette.background,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextField(
                        value = groupInput,
                        onValueChange = { groupInput = it },
                        singleLine = true,
                        placeholder = { Text("分组", color = palette.textTertiary, fontSize = 13.sp) },
                        shape = RoundedCornerShape(10.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = palette.background,
                            unfocusedContainerColor = palette.background,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.width(96.dp),
                    )
                    TextButton(
                        enabled = input.isNotBlank(),
                        onClick = {
                            val name = input.trim()
                            if (name.isNotEmpty()) {
                                picked = picked + name
                                input = ""
                                scope.launch {
                                    runCatching { state.repository.createTag(name, groupInput.trim()) }
                                        .onSuccess { created -> allTags = allTags + created }
                                }
                            }
                        },
                    ) { Text("添加", color = palette.accent) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && picked.isNotEmpty(),
                onClick = {
                    saving = true
                    scope.launch {
                        val ok = runCatching {
                            state.repository.saveTags(targets, picked.toList(), mode = "add")
                        }.isSuccess
                        saving = false
                        if (ok) {
                            state.showToast("标签已保存")
                            onDone()
                        } else {
                            state.showToast("保存失败")
                        }
                    }
                },
            ) { Text("保存", color = palette.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            TextButton(onClick = {
                if (picked.isEmpty()) {
                    onDismiss()
                } else {
                    saving = true
                    scope.launch {
                        runCatching { state.repository.saveTags(targets, picked.toList(), mode = "remove") }
                        saving = false
                        state.showToast("已移除选中标签")
                        onDone()
                    }
                }
            }) { Text("移除", color = palette.textSecondary) }
        },
    )
}

/** Download the picked media and hand them to the system share sheet. */
private suspend fun shareMedia(
    context: android.content.Context,
    api: com.comii.app.data.ComiiApi,
    path: String,
    entries: List<MediaEntry>,
) {
    val directory = java.io.File(context.cacheDir, "share").apply { mkdirs() }
    val uris = entries.mapNotNull { entry ->
        runCatching {
            val url = if (entry.isVideo) api.videoUrl(path, entry.name) else api.imageUrl(path, entry.name)
            val target = java.io.File(directory, entry.name)
            com.comii.app.data.Http.client.newCall(
                okhttp3.Request.Builder().url(url).get().build()
            ).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.byteStream()?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
        }.getOrNull()
    }
    if (uris.isEmpty()) return
    val intent = android.content.Intent(
        if (uris.size == 1) android.content.Intent.ACTION_SEND else android.content.Intent.ACTION_SEND_MULTIPLE
    ).apply {
        type = if (entries.any { it.isVideo }) "video/*" else "image/*"
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (uris.size == 1) putExtra(android.content.Intent.EXTRA_STREAM, uris.first())
        else putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
    }
    context.startActivity(android.content.Intent.createChooser(intent, "分享"))
}







