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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val UNCATEGORIZED = "未分类"

@Composable
fun DownloadsTab(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()

    var version by remember { mutableIntStateOf(0) }
    var categories by remember { mutableStateOf(state.repository.offline.categories()) }
    var activeCategory by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<OfflineSeries?>(null) }
    var assignTarget by remember { mutableStateOf<OfflineSeries?>(null) }
    var showCreateCategory by remember { mutableStateOf(false) }

    val allSeries = remember(version) { state.repository.offline.all() }

    val sections = remember(allSeries, activeCategory, categories, version) {
        val groups = LinkedHashMap<String, MutableList<OfflineSeries>>()
        groups[UNCATEGORIZED] = mutableListOf()
        categories.forEach { groups[it] = mutableListOf() }
        allSeries.forEach { series ->
            groups.getOrPut(series.category.ifBlank { UNCATEGORIZED }) { mutableListOf() }
                .add(series)
        }
        val result = mutableListOf<Pair<String, List<OfflineSeries>>>()
        val keys = if (activeCategory.isBlank()) {
            listOf(UNCATEGORIZED) + categories
        } else {
            listOf(activeCategory)
        }
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
            categories.forEach { name ->
                CategoryChip(name, activeCategory == name) { activeCategory = name }
            }
            AddCategoryChip {
                showCreateCategory = true
            }
        }
        Spacer(Modifier.height(10.dp))

        if (allSeries.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Download,
                title = "还没有离线内容",
                subtitle = "在媒体库长按系列，选择「下载到本地」，没有网络也能看",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                sections.forEach { (label, list) ->
                    item(key = "header_$label") {
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
                                        onLongPress = { assignTarget = item },
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
                state.repository.offline.delete(item.path)
                pendingDelete = null
                version++
                state.showToast("已删除本地文件")
            },
            onDismiss = { pendingDelete = null },
        )
    }

    assignTarget?.let { item ->
        AssignCategorySheet(
            current = item.category,
            categories = categories,
            onSelect = { name ->
                scope.launch {
                    state.repository.offline.setCategory(item.path, name)
                    version++
                    state.showToast("已移动到「${name.ifBlank { UNCATEGORIZED }}」")
                }
                assignTarget = null
            },
            onCreate = {
                showCreateCategory = true
                assignTarget = null
            },
            onDismiss = { assignTarget = null },
        )
    }

    if (showCreateCategory) {
        NewCategoryDialog(
            onCreate = { name ->
                scope.launch {
                    categories = state.repository.offline.createCategory(name)
                    version++
                    state.showToast("分类已创建「$name」")
                }
                showCreateCategory = false
            },
            onDismiss = { showCreateCategory = false },
        )
    }
}

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

@Composable
private fun NewCategoryDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    val palette = ComiiTheme.palette
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = { Text("新建分类", color = palette.textPrimary) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("分类名称", color = palette.textTertiary) },
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
                onClick = { name.trim().takeIf { it.isNotEmpty() }?.let(onCreate) },
                enabled = name.isNotBlank(),
            ) { Text("创建", color = palette.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = palette.textSecondary) } },
    )
}

@Composable
private fun AssignCategorySheet(
    current: String,
    categories: List<String>,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = ComiiTheme.palette
    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.DriveFileMove, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("移动到分类", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
            }
            Spacer(Modifier.height(12.dp))
            val options = listOf(UNCATEGORIZED) + categories
            options.forEach { name ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.card)
                        .pointerInput(name) { detectTapGestures { onSelect(if (name == UNCATEGORIZED) "" else name) } }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, color = palette.textPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (name == current.ifBlank { UNCATEGORIZED }) {
                        Text("✓", color = palette.accent, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.accent.copy(alpha = 0.1f))
                    .pointerInput(Unit) { detectTapGestures { onCreate() } }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = palette.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("新建分类", color = palette.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
