package com.comii.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.comii.app.data.NodeKind
import com.comii.app.data.OfflineSeries
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.BottomSheet
import com.comii.app.ui.components.ConfirmDialog
import com.comii.app.ui.components.CoverImage
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.components.LargeTitle
import com.comii.app.ui.components.formatBytes
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

private const val UNCATEGORIZED = "未分类"

@Composable
fun DownloadsTab(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()

    var version by remember { mutableIntStateOf(0) }
    var categories by remember { mutableStateOf(safeCategories(state)) }
    var activeCategory by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<OfflineSeries?>(null) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var sheetSeries by remember { mutableStateOf<OfflineSeries?>(null) }
    var startInCreate by remember { mutableStateOf(false) }

    val allSeries = remember(version) { state.repository.offline.all() }
    val validCategories = remember(categories) {
        categories.filter { it.isNotBlank() && it != UNCATEGORIZED }.distinct()
    }

    val sections = remember(allSeries, activeCategory, validCategories, version) {
        val groups = LinkedHashMap<String, MutableList<OfflineSeries>>()
        groups[UNCATEGORIZED] = mutableListOf()
        validCategories.forEach { groups[it] = mutableListOf() }
        allSeries.forEach { series ->
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

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                LargeTitle("下载", "${allSeries.size} 个系列 · ${formatBytes(state.repository.offline.totalBytes())}")
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryChip("全部", activeCategory.isBlank()) { activeCategory = "" }
            validCategories.forEach { name ->
                CategoryChip(name, activeCategory == name) { activeCategory = name }
            }
            AddCategoryChip {
                sheetSeries = null
                startInCreate = true
                showCategorySheet = true
            }
        }
        Spacer(Modifier.height(10.dp))

        if (allSeries.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Download,
                title = "还没有离线内容",
                subtitle = "在 Comii 里长按系列，选择「下载到本地」，没有网络也能看",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                sections.forEachIndexed { index, (label, list) ->
                    item(key = "header_${index}_$label") {
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(palette.card)
                                .pointerInput(item.path) {
                                    detectTapGestures(
                                        onTap = { openOffline(item, nav) },
                                        onLongPress = {
                                            sheetSeries = item
                                            startInCreate = false
                                            showCategorySheet = true
                                        },
                                    )
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverImage(
                                model = cover,
                                kind = item.kind,
                                modifier = Modifier.width(58.dp).aspectRatio(0.7f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    color = palette.textPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    when {
                                        item.kind == NodeKind.VIDEO -> "${item.videos.size} 个视频 · ${formatBytes(item.totalBytes)}"
                                        else -> "${item.images.size} 页 · ${formatBytes(item.totalBytes)}"
                                    },
                                    fontSize = 12.sp,
                                    color = palette.textSecondary,
                                )
                            }
                            IconButton(onClick = { openOffline(item, nav) }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "打开", tint = palette.accent)
                            }
                            IconButton(onClick = { pendingDelete = item }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "删除下载", tint = palette.textTertiary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    pendingDelete?.let { item ->
        ConfirmDialog(
            title = "删除下载",
            message = "将从本机删除「${item.name}」的离线文件，服务器上的内容不受影响。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                runCatching { state.repository.offline.delete(item.path) }
                pendingDelete = null
                version++
                state.showToast("已删除本地文件")
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showCategorySheet) {
        CategorySheet(
            series = sheetSeries,
            categories = validCategories,
            startInCreate = startInCreate,
            onAssign = { name ->
                val item = sheetSeries
                if (item != null) {
                    scope.launch {
                        runCatching { state.repository.offline.setCategory(item.path, name) }
                            .onSuccess {
                                version++
                                state.showToast("已移动到「${name.ifBlank { UNCATEGORIZED }}」")
                            }
                            .onFailure { state.showToast("移动失败：${it.message}") }
                    }
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
                                Text("✓", color = palette.accent, fontWeight = FontWeight.Bold)
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
