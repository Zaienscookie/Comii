package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.comii.app.data.NodeKind
import com.comii.app.data.OfflineSeries
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.BottomSheet
import com.comii.app.ui.components.ConfirmDialog
import com.comii.app.ui.components.CoverImage
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.components.LargeTitle
import com.comii.app.ui.components.SegmentControl
import com.comii.app.ui.components.VideoThumbnail
import com.comii.app.ui.components.formatBytes
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

private const val UNCATEGORIZED = "未分类"

/** One local page, used by the "全部排列" view so a whole download can be read in order. */
private data class OfflineImage(
    val series: OfflineSeries,
    val name: String,
    val index: Int,
    val isVideo: Boolean = false,
)

@Composable
fun DownloadsTab(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()

    var version by remember { mutableIntStateOf(0) }
    var categories by remember { mutableStateOf(safeCategories(state)) }
    var activeCategory by rememberSaveable { mutableStateOf("") }
    var layout by rememberSaveable { mutableStateOf(state.repository.settings.downloadsLayout) }
    var mainFilter by rememberSaveable { mutableStateOf("all") }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<List<OfflineSeries>>(emptyList()) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var sheetSeries by remember { mutableStateOf<OfflineSeries?>(null) }
    var startInCreate by remember { mutableStateOf(false) }

    val allSeries = remember(version) { state.repository.offline.all() }
    var query by remember { mutableStateOf("") }
    val visibleSeries = remember(allSeries, query) {
        if (query.isBlank()) allSeries
        else allSeries.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    val validCategories = remember(categories) {
        categories.filter { it.isNotBlank() && it != UNCATEGORIZED }.distinct()
    }
    val flatMode = layout == "flat"
    val filteredSeries = remember(visibleSeries, mainFilter) {
        when (mainFilter) {
            "manga" -> visibleSeries.filter { it.kind == NodeKind.MANGA }
            "video" -> visibleSeries.filter { it.kind == NodeKind.VIDEO }
            else -> visibleSeries
        }
    }
    val layoutOptions = when (mainFilter) {
        "manga" -> listOf("按文件夹", "全部图片")
        "video" -> listOf("按文件夹", "全部视频")
        else -> listOf("按文件夹", "全部")
    }

    val sections = remember(filteredSeries, activeCategory, validCategories, version) {
        val groups = LinkedHashMap<String, MutableList<OfflineSeries>>()
        groups[UNCATEGORIZED] = mutableListOf()
        validCategories.forEach { groups[it] = mutableListOf() }
        filteredSeries.forEach { series ->
            val key = series.category.ifBlank { UNCATEGORIZED }
            groups.getOrPut(key) { mutableListOf() }.add(series)
        }
        val result = mutableListOf<Pair<String, List<OfflineSeries>>>()
        val keys = if (activeCategory.isBlank()) listOf(UNCATEGORIZED) + validCategories else listOf(activeCategory)
        keys.forEach { key ->
            val list = groups[key].orEmpty()
            if (list.isNotEmpty()) result.add(key to list)
        }
        result
    }

    // 平铺视图：全部 = 图片+视频；漫画 = 只图片；视频 = 只视频
    val flatMedia = remember(filteredSeries, version, flatMode, mainFilter) {
        if (!flatMode) emptyList() else filteredSeries
            .sortedWith(compareBy({ it.category }, { it.name }))
            .flatMap { series ->
                val images = if (mainFilter == "video") emptyList() else {
                    series.images.mapIndexed { index, name -> OfflineImage(series, name, index) }
                }
                val videos = if (mainFilter == "manga") emptyList() else {
                    series.videos.mapIndexed { index, name ->
                        OfflineImage(series, name, series.images.size + index, true)
                    }
                }
                images + videos
            }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                LargeTitle("下载", "${allSeries.size} 个系列 · ${formatBytes(state.repository.offline.totalBytes())}")
            }
            IconButton(onClick = { version++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = palette.accent)
            }
            IconButton(onClick = {
                sheetSeries = null
                startInCreate = true
                showCategorySheet = true
            }) {
                Icon(Icons.Rounded.CreateNewFolder, contentDescription = "新建文件夹", tint = palette.accent)
            }
            if (allSeries.isNotEmpty()) {
                TextButton(onClick = {
                    editing = !editing
                    if (!editing) selected = emptySet()
                }) { Text(if (editing) "完成" else "编辑", color = palette.accent, fontWeight = FontWeight.Medium) }
            }
        }

        Spacer(Modifier.height(6.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            com.comii.app.ui.components.SearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索离线内容",
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            SegmentControl(
                options = listOf("全部", "漫画", "视频"),
                selectedIndex = when (mainFilter) {
                    "manga" -> 1
                    "video" -> 2
                    else -> 0
                },
                onSelect = { index ->
                    mainFilter = when (index) {
                        1 -> "manga"
                        2 -> "video"
                        else -> "all"
                    }
                    activeCategory = ""
                    editing = false
                    selected = emptySet()
                },
            )
            Spacer(Modifier.height(8.dp))
            SegmentControl(
                options = layoutOptions,
                selectedIndex = if (flatMode) 1 else 0,
                onSelect = { index ->
                    layout = if (index == 0) "group" else "flat"
                    state.repository.settings.downloadsLayout = layout
                    editing = false
                    selected = emptySet()
                },
            )
            if (!flatMode) {
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        Box(Modifier.weight(1f)) {
            when {
                filteredSeries.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.Download,
                    title = when {
                        query.isNotBlank() -> "没有匹配的下载"
                        mainFilter == "video" -> "还没有离线视频"
                        mainFilter == "manga" -> "还没有离线漫画"
                        else -> "还没有离线内容"
                    },
                    subtitle = if (query.isNotBlank()) {
                        "换个关键词试试"
                    } else {
                        "在 Comii 里长按系列，选择「下载到本地」，没有网络也能看"
                    },
                )
                flatMode -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 108.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(flatMedia, key = { "${it.series.path}/${it.name}" }) { media ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.card)
                                .pointerInput(media.series.path, media.name) {
                                    detectTapGestures {
                                        if (media.isVideo) {
                                            nav.navigate(Routes.player(media.series.path, media.name))
                                        } else {
                                            nav.navigate(Routes.reader(media.series.path, media.index))
                                        }
                                    }
                                }
                        ) {
                            if (media.isVideo) {
                                VideoThumbnail(
                                    file = state.repository.offline.videoFile(media.series.path, media.name),
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Box(
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(5.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.5f))
                                        .padding(3.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = "视频",
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            } else {
                                AsyncImage(
                                    model = state.repository.offline.imageFile(media.series.path, media.name),
                                    contentDescription = media.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(5.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.5f))
                                    .padding(horizontal = 5.dp, vertical = 2.dp)
                            ) {
                                Text("${media.index + 1}", color = Color.White, fontSize = 10.sp)
                            }
                        }
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 108.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    sections.forEach { (label, list) ->
                        item(key = "header_$label", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.textSecondary,
                                modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 2.dp),
                            )
                        }
                        items(list, key = { it.path }) { item ->
                            val cover = state.repository.offline.coverFile(item)
                            OfflineCard(
                                item = item,
                                cover = cover,
                                videoFile = item.videos.firstOrNull()?.let {
                                    state.repository.offline.videoFile(item.path, it)
                                },
                                editing = editing,
                                selected = selected.contains(item.path),
                                onClick = {
                                    if (editing) {
                                        selected = if (selected.contains(item.path)) selected - item.path else selected + item.path
                                    } else {
                                        openOffline(item, nav)
                                    }
                                },
                                onLongClick = {
                                    if (editing) {
                                        selected = if (selected.contains(item.path)) selected - item.path else selected + item.path
                                    } else {
                                        editing = true
                                        selected = selected + item.path
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (editing) {
                DownloadsEditBar(
                    count = selected.size,
                    allSelected = allSeries.isNotEmpty() && selected.containsAll(allSeries.map { it.path }),
                    onToggleAll = {
                        val everything = allSeries.map { it.path }
                        selected = if (selected.containsAll(everything)) emptySet() else everything.toSet()
                    },
                    onDelete = {
                        pendingDelete = allSeries.filter { selected.contains(it.path) }
                    },
                    onMove = {
                        sheetSeries = null
                        startInCreate = false
                        showCategorySheet = true
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }

    if (pendingDelete.isNotEmpty()) {
        val count = pendingDelete.size
        ConfirmDialog(
            title = "删除 $count 个下载？",
            message = "将从本机删除这些离线文件，服务器上的内容不受影响。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                val targets = pendingDelete
                scope.launch {
                    targets.forEach { runCatching { state.repository.offline.delete(it.path) } }
                    pendingDelete = emptyList()
                    selected = emptySet()
                    editing = false
                    version++
                    state.showToast("已删除 $count 个下载")
                }
            },
            onDismiss = { pendingDelete = emptyList() },
        )
    }

    if (showCategorySheet) {
        CategorySheet(
            series = sheetSeries,
            categories = validCategories,
            startInCreate = startInCreate,
            onAssign = { name ->
                val targets = if (sheetSeries != null) listOf(sheetSeries!!) else allSeries.filter { selected.contains(it.path) }
                scope.launch {
                    runCatching {
                        targets.forEach { state.repository.offline.setCategory(it.path, name) }
                    }.onSuccess {
                        version++
                        editing = false
                        selected = emptySet()
                        state.showToast("已移动到「${name.ifBlank { UNCATEGORIZED }}」")
                    }.onFailure { state.showToast("移动失败：${it.message}") }
                }
                showCategorySheet = false
                sheetSeries = null
                startInCreate = false
            },
            onCreate = { name ->
                scope.launch {
                    runCatching { state.repository.offline.createCategory(name) }
                        .onSuccess { updated ->
                            categories = updated
                            version++
                            state.showToast("分类已创建「$name」")
                        }
                        .onFailure { state.showToast("创建失败：${it.message}") }
                }
                showCategorySheet = false
                sheetSeries = null
                startInCreate = false
            },
            onDismiss = {
                showCategorySheet = false
                sheetSeries = null
                startInCreate = false
            },
        )
    }
}

@Composable
private fun OfflineCard(
    item: OfflineSeries,
    cover: java.io.File?,
    videoFile: java.io.File?,
    editing: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val palette = ComiiTheme.palette
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .pointerInput(item.path, editing) {
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
            if (cover != null) {
                CoverImage(model = cover, kind = item.kind, modifier = Modifier.fillMaxSize())
            } else if (item.kind == NodeKind.VIDEO && videoFile != null) {
                VideoThumbnail(file = videoFile, modifier = Modifier.fillMaxSize())
            } else {
                CoverImage(model = null, kind = item.kind, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    if (item.kind == NodeKind.VIDEO) "视频" else "漫画",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
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
                    if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            item.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = palette.textPrimary,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            when {
                item.kind == NodeKind.VIDEO -> "${item.videos.size} 个视频 · ${formatBytes(item.totalBytes)}"
                else -> "${item.images.size} 页 · ${formatBytes(item.totalBytes)}"
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 11.5.sp,
            color = palette.textSecondary,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun DownloadsEditBar(
    count: Int,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ComiiTheme.palette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.elevated)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已选 $count 项", color = palette.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleAll) {
                Text(
                    if (allSelected) "取消全选" else "全选",
                    color = palette.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadAction(
                text = "移动到分类",
                icon = Icons.Rounded.DriveFileMove,
                enabled = count > 0,
                onClick = onMove,
                modifier = Modifier.weight(1f),
            )
            DownloadAction(
                text = "删除",
                icon = Icons.Rounded.Delete,
                enabled = count > 0,
                onClick = onDelete,
                danger = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DownloadAction(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val palette = ComiiTheme.palette
    val background = when {
        !enabled -> palette.card.copy(alpha = 0.5f)
        danger -> palette.danger
        else -> palette.card
    }
    val contentColor = when {
        !enabled -> palette.textTertiary
        danger -> Color.White
        else -> palette.accent
    }
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(enabled = enabled) { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp), tint = contentColor)
        Spacer(Modifier.width(5.dp))
        Text(text, color = contentColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun safeCategories(state: AppState): List<String> =
    runCatching { state.repository.offline.categories() }.getOrDefault(emptyList())

private fun openOffline(item: OfflineSeries, nav: NavHostController) {
    if (item.kind == NodeKind.VIDEO && item.videos.isNotEmpty()) {
        nav.navigate(Routes.player(item.path, item.videos.first()))
    } else {
        nav.navigate(Routes.reader(item.path))
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = ComiiTheme.palette
    Box(
        Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) palette.accent else palette.card)
            .pointerInput(label) { detectTapGestures { onClick() } }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Color.White else palette.textSecondary,
        )
    }
}

@Composable
private fun AddCategoryChip(onClick: () -> Unit) {
    val palette = ComiiTheme.palette
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(palette.card.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, contentDescription = "新建分类", tint = palette.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("新建分类", fontSize = 12.5.sp, color = palette.accent, fontWeight = FontWeight.Medium)
    }
}

/**
 * One sheet handles both "assign to category" and "create category" so we never
 * stack a dialog on top of a bottom sheet (that transition used to crash).
 */
@Composable
private fun CategorySheet(
    series: OfflineSeries?,
    categories: List<String>,
    startInCreate: Boolean,
    onAssign: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = ComiiTheme.palette
    var creating by remember(startInCreate) { mutableStateOf(startInCreate) }
    var name by remember { mutableStateOf("") }

    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DriveFileMove, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (creating) "新建分类" else "移动到分类",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.textPrimary,
                )
            }
            Spacer(Modifier.height(12.dp))

            if (creating) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("分类名称", color = palette.textTertiary) },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions.Default,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = palette.card,
                        unfocusedContainerColor = palette.card,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        val clean = name.trim()
                        if (clean.isNotEmpty() && clean != UNCATEGORIZED) onCreate(clean)
                    },
                    enabled = name.isNotBlank() && name.trim() != UNCATEGORIZED,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("创建分类", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { creating = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("返回分类列表", color = palette.textSecondary)
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    (listOf(UNCATEGORIZED) + categories).forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.card)
                                .pointerInput(option) {
                                    detectTapGestures {
                                        onAssign(if (option == UNCATEGORIZED) "" else option)
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(option, color = palette.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            if (series != null && option == series.category.ifBlank { UNCATEGORIZED }) {
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = palette.accent, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.accent.copy(alpha = 0.1f))
                            .pointerInput(Unit) { detectTapGestures { creating = true } }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("新建分类", color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
