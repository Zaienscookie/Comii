package com.comii.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.comii.app.data.GalleryItem
import com.comii.app.data.LibraryNode
import com.comii.app.data.NodeKind
import com.comii.app.data.ReadingProgress
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.BottomSheet
import com.comii.app.ui.components.ComiiTopBar
import com.comii.app.ui.components.ConfirmDialog
import com.comii.app.ui.components.CoverImage
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.components.LargeTitle
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.components.SearchField
import com.comii.app.ui.components.SegmentControl
import com.comii.app.ui.components.formatBytes
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 相册分页大小：一次取一页，滚到底再续下一页。 */
private const val GALLERY_PAGE = 200

private sealed interface LibraryDialog {
    data object NewFolder : LibraryDialog
    data class Rename(val node: LibraryNode) : LibraryDialog
    data class Move(val nodes: List<LibraryNode>) : LibraryDialog
    data class Delete(val nodes: List<LibraryNode>) : LibraryDialog
    data class SetKind(val node: LibraryNode) : LibraryDialog
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

    // 默认进入「漫画」（comic 文件夹）
    var path by rememberSaveable { mutableStateOf("comic") }
    var pathKind by remember { mutableStateOf<String?>("manga") }
    val history = remember { mutableStateListOf<Pair<String, String?>>() }
    var browseData by remember { mutableStateOf<com.comii.app.data.BrowseResponse?>(null) }
    var progressMap by remember { mutableStateOf<Map<String, ReadingProgress>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var rootItems by remember { mutableStateOf<List<LibraryNode>>(emptyList()) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var sheetItem by remember { mutableStateOf<LibraryNode?>(null) }
    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    var downloadState by remember { mutableStateOf<DownloadProgress?>(null) }
    var downloadHidden by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var searchResults by remember { mutableStateOf<List<LibraryNode>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    // 「全部」和文件夹里的「全部图片」共用同一套分页相册
    var flatItems by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var flatTotal by remember { mutableIntStateOf(0) }
    var flatLoading by remember { mutableStateOf(false) }
    var layout by rememberSaveable { mutableStateOf(state.repository.settings.libraryLayout) }
    val galleryGrid = rememberLazyGridState()
    val folderGrid = rememberLazyGridState()

    val galleryMode = path.isEmpty() || layout == "flat"

    suspend fun reload() {
        loading = true
        error = null
        if (!state.hasServer) {
            browseData = null
            flatItems = emptyList()
            flatTotal = 0
            loading = false
            return
        }
        rootItems = runCatching { state.repository.browse("").items }.getOrDefault(rootItems)
        val target = path
        if (galleryMode) {
            browseData = null
            val sort = if (target.isEmpty()) "recent" else "name"
            runCatching {
                state.repository.gallery(limit = GALLERY_PAGE, offset = 0, path = target, sort = sort)
            }.onSuccess { response ->
                flatItems = response.items
                flatTotal = response.total
            }.onFailure { failure ->
                flatItems = emptyList()
                flatTotal = 0
                if (target.isNotEmpty()) {
                    // 文件夹被移动/重命名时不要卡死：退回上一级
                    state.showToast("找不到「${target.substringAfterLast('/')}」，已回到上一级")
                    history.clear()
                    path = target.substringBeforeLast('/', "")
                    pathKind = null
                } else {
                    error = failure.message ?: "加载失败"
                }
            }
        } else {
            flatItems = emptyList()
            flatTotal = 0
            runCatching { state.repository.browse(target) }.onSuccess { response ->
                browseData = response
                progressMap = runCatching { state.repository.requireApi().progressAll() }
                    .getOrDefault(emptyMap())
            }.onFailure { failure ->
                browseData = null
                if (target.isEmpty()) {
                    error = failure.message ?: "加载失败"
                } else {
                    state.showToast("找不到「${target.substringAfterLast('/')}」，已回到上一级")
                    history.clear()
                    path = target.substringBeforeLast('/', "")
                    pathKind = null
                }
            }
        }
        loading = false
    }

    /** 加载下一页；滚到底自动调用。 */
    suspend fun loadMore() {
        if (!galleryMode || flatLoading || !state.hasServer) return
        if (flatItems.isEmpty() || flatItems.size >= flatTotal) return
        flatLoading = true
        val sort = if (path.isEmpty()) "recent" else "name"
        runCatching {
            state.repository.gallery(limit = GALLERY_PAGE, offset = flatItems.size, path = path, sort = sort)
        }.onSuccess { response ->
            flatItems = flatItems + response.items
            flatTotal = response.total
        }
        flatLoading = false
    }

    LaunchedEffect(path, refreshKey, layout) { reload() }

    LaunchedEffect(path, layout) {
        runCatching { folderGrid.scrollToItem(0) }
        runCatching { galleryGrid.scrollToItem(0) }
    }

    LaunchedEffect(galleryGrid, flatItems.size, flatTotal, galleryMode) {
        if (!galleryMode) return@LaunchedEffect
        snapshotFlow { galleryGrid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                if (last >= flatItems.size - 9) loadMore()
            }
    }

    // 搜索：服务端递归匹配文件夹名、系列名和媒体文件名
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

    // 返回键：只有进入子文件夹后才逐级返回；分类根页面按返回直接退出
    BackHandler(enabled = path.contains('/')) {
        if (history.isNotEmpty()) {
            val (previousPath, previousKind) = history.removeAt(history.lastIndex)
            path = previousPath
            pathKind = previousKind
        } else {
            path = ""
            pathKind = null
        }
    }

    val allItems = browseData?.items.orEmpty()
    val showingSearch = query.isNotBlank()
    // 顶部 Tab 直接对应根目录的 comic / video 文件夹
    val section = when {
        path == "comic" || path.startsWith("comic/") -> 1
        path == "video" || path.startsWith("video/") -> 2
        else -> 0
    }
    val items = allItems
    val gridItems = if (showingSearch) searchResults else items

    fun openNode(node: LibraryNode) {
        when {
            node.isFolder -> {
                history.add(path to pathKind)
                path = node.path
                pathKind = node.forced_kind
            }
            node.kind == NodeKind.VIDEO -> nav.navigate(Routes.series(node.path))
            node.kind == NodeKind.MIXED -> nav.navigate(Routes.series(node.path))
            else -> nav.navigate(Routes.reader(node.path))
        }
    }

    fun goUp() {
        if (history.isNotEmpty()) {
            val (previousPath, previousKind) = history.removeAt(history.lastIndex)
            path = previousPath
            pathKind = previousKind
        } else {
            path = ""
            pathKind = null
        }
    }

    fun startDownload(node: LibraryNode) {
        if (downloadState != null) return
        downloadHidden = false
        if (node.isFolder) {
            downloadState = DownloadProgress(node.name, 0, 0, "个系列")
            downloadJob = scope.launch {
                runCatching {
                    state.repository.downloadFolder(node.path) { done, total ->
                        downloadState = DownloadProgress(node.name, done, total, "个系列")
                    }
                }.onSuccess { count ->
                    state.showToast("已下载「${node.name}」$count 个系列")
                }.onFailure { failure ->
                    if (failure !is kotlinx.coroutines.CancellationException) {
                        state.showToast("下载失败：${failure.message}")
                        refreshKey++
                    }
                }
                downloadState = null
                downloadJob = null
            }
            return
        }
        downloadState = DownloadProgress(node.name, 0, 0, "页")
        downloadJob = scope.launch {
            runCatching {
                state.repository.downloadSeries(node.path, includeVideos = true) { done, total ->
                    downloadState = DownloadProgress(node.name, done, total, "页")
                }
            }.onSuccess { offline ->
                state.showToast("已下载「${offline.name}」${formatBytes(offline.totalBytes)}")
            }.onFailure { failure ->
                if (failure !is kotlinx.coroutines.CancellationException) {
                    state.showToast("下载失败：${failure.message}")
                    refreshKey++
                }
            }
            downloadState = null
            downloadJob = null
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadState = null
        downloadHidden = false
        state.showToast("已取消下载")
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (path.contains('/')) {
                IconButton(onClick = { goUp() }) {
                    Icon(
                        Icons.Rounded.ArrowBackIosNew,
                        contentDescription = "返回",
                        tint = palette.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                if (path.isEmpty()) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            "Comii",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.6).sp,
                            color = palette.accent,
                        )
                        Text(
                            ".",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.accent,
                        )
                    }
                } else {
                    Text(
                        when (path) {
                            "comic" -> "漫画"
                            "video" -> "视频"
                            else -> path.substringAfterLast('/')
                        },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    when {
                        showingSearch -> "搜索「${query.trim()}」· ${gridItems.size} 个结果"
                        galleryMode -> if (flatTotal > 0) "已显示 ${flatItems.size} / $flatTotal 张" else "还没有内容"
                        else -> browseData?.let { "${it.total} 个项目" }.orEmpty()
                    },
                    fontSize = 13.sp,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = palette.accent)
            }
            if (section != 0 && !galleryMode) {
                IconButton(onClick = { dialog = LibraryDialog.NewFolder }) {
                    Icon(Icons.Rounded.CreateNewFolder, contentDescription = "新建文件夹", tint = palette.accent)
                }
            }
            IconButton(onClick = { nav.navigate(Routes.upload(path)) }) {
                Icon(Icons.Rounded.Upload, contentDescription = "上传", tint = palette.accent)
            }
            IconButton(onClick = { nav.navigate("trash") }) {
                Icon(Icons.Rounded.Delete, contentDescription = "回收站", tint = palette.accent)
            }
            if (!galleryMode) {
                TextButton(onClick = {
                    editing = !editing
                    if (!editing) selected = emptySet()
                }) {
                    Text(if (editing) "完成" else "编辑", color = palette.accent, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        if (downloadHidden && downloadState != null) {
            val progress = downloadState!!
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
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
                        progress = {
                            if (progress.total > 0) progress.done.toFloat() / progress.total else 0f
                        },
                        color = palette.accent,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { cancelDownload() }) {
                    Text("取消", color = palette.danger, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            SearchField(query, { query = it }, placeholder = "搜索漫画、视频或文件夹")
            Spacer(Modifier.height(10.dp))
            SegmentControl(
                options = listOf("全部", "漫画", "视频"),
                selectedIndex = section,
                onSelect = { index ->
                    when (index) {
                        0 -> {
                            history.clear()
                            path = ""
                            pathKind = null
                        }
                        else -> {
                            val targetName = if (index == 1) "comic" else "video"
                            val node = rootItems.firstOrNull {
                                it.isFolder && it.name.equals(targetName, ignoreCase = true)
                            }
                            if (node != null) {
                                history.clear()
                                path = node.path
                                pathKind = node.forced_kind
                            } else {
                                state.showToast("没有找到 $targetName 文件夹")
                            }
                        }
                    }
                },
            )
            if (path.isNotEmpty() && !showingSearch) {
                Spacer(Modifier.height(8.dp))
                SegmentControl(
                    options = listOf(
                        "按文件夹",
                        if (path == "video" || path.startsWith("video/")) "全部视频" else "全部图片",
                    ),
                    selectedIndex = if (layout == "flat") 1 else 0,
                    onSelect = { index ->
                        layout = if (index == 0) "folders" else "flat"
                        state.repository.settings.libraryLayout = layout
                        selected = emptySet()
                        editing = false
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f)) {
            when {
                !state.hasServer -> EmptyState(
                    icon = Icons.Rounded.Folder,
                    title = "还没有连接服务器",
                    subtitle = "可以先看「下载」里的离线内容；随时在「设置 → 服务器地址」填上地址和端口（例如 192.168.2.126:8001）",
                    action = {
                        PrimaryButton(
                            text = "去设置服务器",
                            onClick = { nav.navigate("settings/server") },
                            modifier = Modifier.width(200.dp),
                        )
                    },
                )
                loading && flatItems.isEmpty() && browseData == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                galleryMode && flatItems.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.Folder,
                    title = if (path.isEmpty()) "还没有任何漫画或视频" else "这里还没有内容",
                    subtitle = if (path.isEmpty()) "进入 comic / video 分类，点右上角上传内容" else "点右上角上传，或在服务器 library 目录里放入文件夹",
                )
                galleryMode -> GalleryGrid(
                    items = flatItems,
                    total = flatTotal,
                    loadingMore = flatLoading,
                    gridState = galleryGrid,
                    resolveUrl = { state.repository.mediaUrl(it) },
                    onOpen = { item ->
                        if (item.kind == "video") {
                            nav.navigate(Routes.player(item.series_path, item.name))
                        } else {
                            nav.navigate(Routes.reader(item.series_path, item.index))
                        }
                    },
                )
                gridItems.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.Folder,
                    title = if (showingSearch) "没有匹配的项目" else "这里还没有内容",
                    subtitle = if (showingSearch) "换个关键词试试，支持文件夹名、系列名和文件名" else "点右上角上传，或在服务器 library 目录里放入文件夹",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = folderGrid,
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(gridItems, key = { it.path }) { node ->
                        NodeCard(
                            node = node,
                            coverUrl = state.repository.mediaUrl(node.cover),
                            progress = if (showingSearch) null else progressMap[node.path],
                            editing = editing && !showingSearch,
                            selected = selected.contains(node.path),
                            showLocation = showingSearch,
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
                }
            }

            if (editing && !galleryMode) {
                val selectable = allItems.map { it.path }
                EditBar(
                    count = selected.size,
                    allSelected = selectable.isNotEmpty() && selected.containsAll(selectable),
                    onToggleAll = {
                        selected = if (selected.containsAll(selectable)) emptySet() else selectable.toSet()
                    },
                    onMove = {
                        val nodes = allItems.filter { selected.contains(it.path) }
                        if (nodes.isNotEmpty()) dialog = LibraryDialog.Move(nodes)
                    },
                    onRename = {
                        val nodes = allItems.filter { selected.contains(it.path) }
                        if (nodes.size == 1) dialog = LibraryDialog.Rename(nodes.first())
                    },
                    onDelete = {
                        val nodes = allItems.filter { selected.contains(it.path) }
                        if (nodes.isNotEmpty()) dialog = LibraryDialog.Delete(nodes)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }

    sheetItem?.let { node ->
        BottomSheet(onDismiss = { sheetItem = null }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoverImage(
                        model = state.repository.mediaUrl(node.cover),
                        kind = node.kind,
                        modifier = Modifier.width(52.dp).aspectRatio(0.7f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(node.name, fontWeight = FontWeight.SemiBold, color = palette.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(nodeSubtitle(node), fontSize = 12.sp, color = palette.textSecondary)
                    }
                }
                Spacer(Modifier.height(14.dp))
                SheetAction("打开") { sheetItem = null; openNode(node) }
                SheetAction("重命名") { sheetItem = null; dialog = LibraryDialog.Rename(node) }
                SheetAction("移动到…") { sheetItem = null; dialog = LibraryDialog.Move(listOf(node)) }
                if (!node.isFolder) {
                    SheetAction("设置封面") { sheetItem = null; dialog = LibraryDialog.SetCover(node) }
                    SheetAction("下载到本地") { sheetItem = null; startDownload(node) }
                } else {
                    SheetAction("设置类型（漫画 / 视频）") {
                        sheetItem = null
                        dialog = LibraryDialog.SetKind(node)
                    }
                    SheetAction("下载整个文件夹") { sheetItem = null; startDownload(node) }
                }
                SheetAction("删除", danger = true) { sheetItem = null; dialog = LibraryDialog.Delete(listOf(node)) }
                Spacer(Modifier.height(14.dp))
            }
        }
    }

    when (val current = dialog) {
        LibraryDialog.NewFolder -> NewFolderDialog(
            defaultKind = pathKind,
            lockKind = pathKind != null,
            onCreate = { name, kind ->
                scope.launch {
                    runCatching { state.repository.createFolder(path, name, kind) }
                        .onSuccess { result ->
                            state.showToast("文件夹已创建")
                            val created = result.path
                            if (created != null) {
                                history.add(path to pathKind)
                                path = created
                                pathKind = kind
                            } else {
                                refreshKey++
                            }
                        }
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
                    val result = runCatching {
                        state.repository.move(current.nodes.map { it.path }, target)
                    }
                    result.onSuccess { batch ->
                        if (batch.failed.isEmpty()) {
                            state.showToast("已移动到「${target.ifBlank { "根目录" }}」")
                        } else {
                            state.showToast("部分移动失败：${batch.failed.first().message}")
                        }
                        selected = emptySet()
                        refreshKey++
                    }.onFailure { state.showToast("移动失败：${it.message}") }
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        is LibraryDialog.Delete -> ConfirmDialog(
            title = "删除 ${current.nodes.size} 个项目？",
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

        is LibraryDialog.SetKind -> SetKindDialog(
            node = current.node,
            onConfirm = { kind ->
                scope.launch {
                    runCatching { state.repository.setFolderKind(current.node.path, kind) }
                        .onSuccess {
                            state.showToast(if (kind == NodeKind.MANGA) "已设为漫画分类" else "已设为视频分类")
                            refreshKey++
                        }
                        .onFailure { state.showToast("设置失败：${it.message}") }
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

    // 隐藏后不再跟随进度反复弹出，下载在后台继续，结束时用 toast 通知
    downloadState?.takeIf { !downloadHidden }?.let { progress ->
        ConfirmDialog(
            title = "正在下载「${progress.title}」",
            message = if (progress.total > 0) {
                "已下载 ${progress.done} / ${progress.total} ${progress.unit}"
            } else {
                "准备中…"
            },
            confirmText = "后台下载",
            dismissText = "取消下载",
            onConfirm = { downloadHidden = true },
            onDismiss = { cancelDownload() },
        )
    }
}

@Composable
private fun NodeCard(
    node: LibraryNode,
    coverUrl: String?,
    progress: ReadingProgress?,
    editing: Boolean,
    selected: Boolean,
    showLocation: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = ComiiTheme.palette
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .pointerInput(node.path, editing) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
            }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.card)
        ) {
            CoverImage(
                model = coverUrl,
                kind = node.kind,
                modifier = Modifier.fillMaxSize(),
            )
            // kind badge
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(kindLabel(node), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
            }
            if (node.isFolder) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
            if (progress != null && progress.total > 0 && !node.isFolder) {
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
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (selected) palette.accent else Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Text("✓", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            node.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = palette.textPrimary,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            if (showLocation) node.path else nodeSubtitle(node),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.5.sp,
            color = palette.textSecondary,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

private fun kindLabel(node: LibraryNode): String = when {
    node.isFolder -> "文件夹"
    node.kind == NodeKind.VIDEO -> "视频"
    node.kind == NodeKind.MIXED -> "混合"
    else -> "漫画"
}

private fun nodeSubtitle(node: LibraryNode): String = when {
    node.isFolder -> if ((node.child_count ?: 0) == 0) "空文件夹 · 可上传内容" else "${node.child_count ?: 0} 项"
    node.kind == NodeKind.VIDEO -> "${node.video_count} 个视频"
    node.kind == NodeKind.MIXED -> "${node.image_count} 页 · ${node.video_count} 视频"
    else -> "${node.image_count} 页"
}

@Composable
private fun GalleryGrid(
    items: List<GalleryItem>,
    total: Int,
    loadingMore: Boolean,
    gridState: LazyGridState,
    resolveUrl: (String) -> String?,
    onOpen: (GalleryItem) -> Unit,
) {
    val palette = ComiiTheme.palette
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(items, key = { _, it -> "${it.series_path}/${it.name}" }) { position, item ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.card)
                    .pointerInput(item.series_path, item.name) {
                        detectTapGestures { onOpen(item) }
                    }
            ) {
                AsyncImage(
                    model = resolveUrl(item.thumb),
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (item.kind == "video") {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(5.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = "视频",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text("${position + 1}", color = Color.White, fontSize = 10.sp)
                }
            }
        }
        if (loadingMore || items.size < total) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
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
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ComiiTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.elevated)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggleAll) {
            Text(
                if (allSelected) "取消全选" else "全选",
                color = palette.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text("已选 $count 项", color = palette.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onMove, enabled = count > 0) {
            Icon(Icons.Rounded.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp), tint = palette.accent)
            Spacer(Modifier.width(4.dp))
            Text("移动", color = palette.accent, fontSize = 13.sp)
        }
        TextButton(onClick = onRename, enabled = count == 1) {
            Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (count == 1) palette.accent else palette.textTertiary)
            Spacer(Modifier.width(4.dp))
            Text("重命名", color = if (count == 1) palette.accent else palette.textTertiary, fontSize = 13.sp)
        }
        TextButton(onClick = onDelete, enabled = count > 0) {
            Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = palette.danger)
            Spacer(Modifier.width(4.dp))
            Text("删除", color = palette.danger, fontSize = 13.sp)
        }
    }
}

@Composable
private fun NewFolderDialog(
    defaultKind: String?,
    lockKind: Boolean,
    onCreate: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = ComiiTheme.palette
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableIntStateOf(if (defaultKind == NodeKind.VIDEO) 1 else 0) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = { Text("新建文件夹", color = palette.textPrimary) },
        text = {
            Column {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("文件夹名称", color = palette.textTertiary) },
                    shape = RoundedCornerShape(12.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = palette.background,
                        unfocusedContainerColor = palette.background,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                if (lockKind) {
                    Text(
                        "将在当前文件夹内创建，类型沿用上级",
                        fontSize = 12.sp,
                        color = palette.textSecondary,
                    )
                } else {
                    SegmentControl(
                        options = listOf("漫画", "视频"),
                        selectedIndex = kind,
                        onSelect = { kind = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clean = name.trim()
                    if (clean.isNotEmpty()) {
                        onCreate(clean, listOf(NodeKind.MANGA, NodeKind.VIDEO)[kind])
                    }
                },
                enabled = name.isNotBlank(),
            ) { Text("创建", color = palette.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = palette.textSecondary) } },
    )
}

@Composable
private fun SetKindDialog(node: LibraryNode, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val palette = ComiiTheme.palette
    var kind by remember { mutableIntStateOf(if (node.forced_kind == NodeKind.VIDEO) 1 else 0) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = { Text("「${node.name}」属于哪类？", color = palette.textPrimary) },
        text = {
            Column {
                Text(
                    "设置后，这个文件夹只出现在对应的分类里。",
                    fontSize = 13.sp,
                    color = palette.textSecondary,
                )
                Spacer(Modifier.height(12.dp))
                SegmentControl(
                    options = listOf("漫画", "视频"),
                    selectedIndex = kind,
                    onSelect = { kind = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(listOf(NodeKind.MANGA, NodeKind.VIDEO)[kind]) }) {
                Text("保存", color = palette.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = palette.textSecondary) } },
    )
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
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(node.path) {
        images = runCatching { state.repository.series(node.path).images }.getOrDefault(emptyList())
        loading = false
    }

    fun apply(name: String) {
        if (saving) return
        saving = true
        scope.launch {
            runCatching { state.repository.setCover(node.path, name) }
                .onSuccess {
                    state.showToast(if (name.isBlank()) "已恢复默认封面" else "封面已更新")
                    onDone()
                }
                .onFailure { state.showToast("设置失败：${it.message}") }
            saving = false
        }
    }

    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                "选择封面 · ${node.name}",
                color = palette.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
            } else if (images.isEmpty()) {
                Text("这个系列没有图片可作为封面", color = palette.textSecondary, fontSize = 13.sp)
                Spacer(Modifier.height(20.dp))
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
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(3.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text("${index + 1}", color = Color.White, fontSize = 10.sp)
                            }
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
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf<List<com.comii.app.data.ComiiRepository.FolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var target by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        folders = runCatching { state.repository.folderTree() }.getOrDefault(emptyList())
        loading = false
    }

    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("移动到", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            Spacer(Modifier.height(12.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.card)
                ) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                        items(folders.size) { index ->
                            val folder = folders[index]
                            val isCurrent = nodes.any { it.path == folder.path }
                            val disabled = isCurrent || nodes.any { folder.path.startsWith(it.path + "/") || folder.path == it.path }
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
