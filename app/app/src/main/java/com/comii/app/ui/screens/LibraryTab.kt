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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One photo / video of the current album. */
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

    // 相册就是真实文件夹：根目录是相册列表，进去可以继续套子相册
    var path by rememberSaveable { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var browseData by remember { mutableStateOf<com.comii.app.data.BrowseResponse?>(null) }
    var media by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var progressMap by remember { mutableStateOf<Map<String, ReadingProgress>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
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
    var selectedMedia by remember { mutableStateOf(setOf<String>()) }
    var askDeleteMedia by remember { mutableStateOf(false) }
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
        var failed: Throwable? = null
        repeat(3) { attempt ->
            val result = runCatching { state.repository.browse(target) }
            result.onSuccess { response ->
                browseData = response
                failed = null
            }.onFailure {
                failed = it
                if (attempt < 2) delay(400L * (attempt + 1))
            }
            if (failed == null) return@repeat
        }
        if (failed != null) {
            browseData = null
            media = emptyList()
            if (target.isEmpty()) {
                error = failed!!.message ?: "加载失败"
            } else {
                state.showToast("找不到「${target.substringAfterLast('/')}」，已回到上一级")
                history.removeLastOrNull()
                path = target.substringBeforeLast('/', "")
            }
            loading = false
            return
        }
        // 当前相册自己也可能直接放着照片 / 视频
        if (target.isNotEmpty()) {
            val detail = runCatching { state.repository.series(target) }.getOrNull()
            val entries = buildList {
                detail?.images?.forEachIndexed { index, name ->
                    add(MediaEntry(name, false, index))
                }
                detail?.videos?.forEach { name ->
                    add(MediaEntry(name, true, 0))
                }
            }
            media = entries
        } else {
            media = emptyList()
        }
        progressMap = runCatching { state.repository.requireApi().progressAll() }
            .getOrDefault(emptyMap())
        loading = false
    }

    LaunchedEffect(path, refreshKey) { reload() }

    LaunchedEffect(path) {
        runCatching { gridState.scrollToItem(0) }
    }

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
        if (history.isNotEmpty()) {
            path = history.removeAt(history.lastIndex)
        } else {
            path = ""
        }
    }

    val allItems = browseData?.items.orEmpty()
    val showingSearch = query.isNotBlank()
    val gridItems = if (showingSearch) searchResults else allItems
    val albumCount = allItems.count { it.isAlbum }
    val mediaCount = media.size

    fun openAlbum(node: LibraryNode) {
        history.add(path)
        path = node.path
    }

    fun openNode(node: LibraryNode) {
        when {
            node.isAlbum || node.isFolder -> openAlbum(node)
            node.kind == NodeKind.MANGA -> nav.navigate(Routes.reader(node.path))
            else -> nav.navigate(Routes.series(node.path))
        }
    }

    fun startDownload(node: LibraryNode) {
        if (downloadState != null) return
        downloadHidden = false
        if (node.isAlbum || node.isFolder) {
            downloadState = DownloadProgress(node.name, 0, 0, "个系列")
            downloadJob = scope.launch {
                runCatching {
                    state.repository.downloadFolder(node.path) { done, total ->
                        downloadState = DownloadProgress(node.name, done, total, "个系列")
                    }
                }.onSuccess { count ->
                    state.showToast("已下载「${node.name}」$count 个系列")
                }.onFailure { state.showToast("下载失败：${it.message}") }
                downloadState = null
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
            }.onFailure { state.showToast("下载失败：${it.message}") }
            downloadState = null
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadState = null
        downloadHidden = false
    }

    Column(Modifier.fillMaxSize()) {
        // ---------------- 顶部工具栏：搜索 / 标签 / 刷新 / 新建 / 上传 / 回收站 / 编辑
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 14.dp),
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
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (path.isEmpty()) "Comii" else path.substringAfterLast('/'),
                    fontSize = if (path.isEmpty()) 30.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (path.isEmpty()) palette.accent else palette.textPrimary,
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
                            if (albumCount == 0 && mediaCount == 0) append(if (path.isEmpty()) "还没有内容" else "空相册")
                        }
                    },
                    fontSize = 12.5.sp,
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = { nav.navigate("tags") }) {
                Icon(Icons.Rounded.Sell, contentDescription = "标签", tint = palette.accent, modifier = Modifier.size(21.dp))
            }
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = palette.accent, modifier = Modifier.size(21.dp))
            }
            IconButton(onClick = { dialog = LibraryDialog.NewAlbum }) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = "新建相册", tint = palette.accent, modifier = Modifier.size(21.dp))
            }
            IconButton(onClick = { nav.navigate(Routes.upload(path)) }) {
                Icon(Icons.Rounded.Upload, contentDescription = "导入", tint = palette.accent, modifier = Modifier.size(21.dp))
            }
            IconButton(onClick = { nav.navigate("trash") }) {
                Icon(Icons.Rounded.Delete, contentDescription = "回收站", tint = palette.accent, modifier = Modifier.size(21.dp))
            }
            if (!showingSearch) {
                TextButton(onClick = {
                    editing = !editing
                    if (!editing) selected = emptySet()
                }) {
                    Text(if (editing) "完成" else "编辑", color = palette.accent, fontSize = 13.sp)
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
                        progress = { if (progress.total > 0) progress.done.toFloat() / progress.total else 0f },
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
            SearchField(query, { query = it }, placeholder = "搜索相册、系列或文件名")
        }
        Spacer(Modifier.height(12.dp))

        Box(Modifier.weight(1f)) {
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
                    subtitle = if (showingSearch) "换个关键词试试" else "点右上角「新建相册」或「导入」放点东西进来",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(gridItems, key = { it.path }) { node ->
                        AlbumCard(
                            node = node,
                            coverUrl = state.repository.mediaUrl(node.cover),
                            progress = if (showingSearch) null else progressMap[node.path],
                            editing = editing && !showingSearch,
                            selected = selected.contains(node.path),
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
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "本相册内容 · $mediaCount 项",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textSecondary,
                                modifier = Modifier.padding(start = 2.dp, top = 6.dp),
                            )
                        }
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
                                        selectedMedia = if (selectedMedia.contains(entry.name)) {
                                            selectedMedia - entry.name
                                        } else {
                                            selectedMedia + entry.name
                                        }
                                    } else if (entry.isVideo) {
                                        nav.navigate(Routes.player(path, entry.name))
                                    } else {
                                        nav.navigate(Routes.reader(path, entry.index))
                                    }
                                },
                                onLongClick = {
                                    selectedMedia = selectedMedia + entry.name
                                },
                            )
                        }
                    }
                }
            }

            if (editing && !showingSearch && selected.isNotEmpty()) {
                EditBar(
                    count = selected.size,
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
                    onDelete = { askDeleteMedia = true },
                    onCancel = { selectedMedia = emptySet() },
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
                if (node.isAlbum || node.isFolder) {
                    SheetAction("设为相册封面") {
                        sheetItem = null
                        scope.launch {
                            runCatching { state.repository.setAlbumCover(node.path, "") }
                            if (path.isNotEmpty()) {
                                runCatching { state.repository.setAlbumCover(path, node.path) }
                                    .onSuccess { state.showToast("已设为相册封面"); refreshKey++ }
                                    .onFailure { state.showToast("设置失败：${it.message}") }
                            } else {
                                state.showToast("请进入一个相册后再设置它的封面")
                            }
                        }
                    }
                    SheetAction("下载整个相册") { sheetItem = null; startDownload(node) }
                } else {
                    SheetAction("设置封面") { sheetItem = null; dialog = LibraryDialog.SetCover(node) }
                    SheetAction("下载到本地") { sheetItem = null; startDownload(node) }
                }
                SheetAction("删除", danger = true) { sheetItem = null; dialog = LibraryDialog.Delete(listOf(node)) }
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

    downloadState?.takeIf { !downloadHidden }?.let { progress ->        ConfirmDialog(
            title = "正在下载「${progress.title}」",
            message = if (progress.total > 0) {
                "已下载 ${progress.done} / ${progress.total} ${progress.unit}"
            } else {
                "准备中…"
            },
            confirmText = "隐藏",
            dismissText = "后台继续",
            onConfirm = { downloadHidden = true },
            onDismiss = { downloadHidden = true },
        )
    }
}

@Composable
private fun AlbumCard(
    node: LibraryNode,
    coverUrl: String?,
    progress: ReadingProgress?,
    editing: Boolean,
    selected: Boolean,
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
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
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
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        "${node.child_count ?: 0} 个子相册",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
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
            nodeSubtitle(node),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.5.sp,
            color = palette.textSecondary,
            modifier = Modifier.padding(horizontal = 2.dp),
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
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.card)
            .pointerInput(entry.name, selecting) {
                detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
            }
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
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "视频", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
        if (selecting || selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) palette.accent else Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Text("✓", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MediaBar(
    count: Int,
    onShare: () -> Unit,
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
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已选 $count 项", color = palette.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onShare) { Text("分享", color = palette.accent, fontSize = 13.sp) }
        TextButton(onClick = onDelete) { Text("删除", color = palette.danger, fontSize = 13.sp) }
        TextButton(onClick = onCancel) { Text("取消", color = palette.textSecondary, fontSize = 13.sp) }
    }
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
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ComiiTheme.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.elevated)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("已选 $count 项", color = palette.textSecondary, fontSize = 13.sp)
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
                onClick = {
                    val clean = name.trim()
                    if (clean.isNotEmpty()) onCreate(clean)
                },
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
                Text("这个相册没有图片可作为封面", color = palette.textSecondary, fontSize = 13.sp)
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
