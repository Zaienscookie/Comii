package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.comii.app.data.GalleryItem
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.ComiiTopBar
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

/**
 * 收藏夹：顶部可以切换/新建子收藏夹；
 * 下面的内容直接 4 列平铺，不再先显示「默认」文件夹卡片。
 */
@Composable
fun FavoritesFlatScreen(state: AppState, nav: NavHostController, onBack: () -> Unit) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var collections by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedCollection by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    suspend fun reloadCollections() {
        collections = runCatching {
            state.repository.browse("收藏夹").items.map { it.name }
        }.getOrDefault(emptyList())
    }

    suspend fun reloadItems() {
        loading = true
        val route = if (selectedCollection.isBlank()) "收藏夹" else "收藏夹/$selectedCollection"
        items = runCatching {
            state.repository.gallery(limit = 500, offset = 0, path = route, sort = "recent")
        }.getOrNull()?.items.orEmpty()
        loading = false
    }

    LaunchedEffect(state.serverGeneration, selectedCollection) {
        reloadCollections()
        reloadItems()
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        ComiiTopBar(
            title = "收藏夹",
            onBack = onBack,
            actions = {
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "新建收藏夹", tint = palette.accent)
                }
            },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CollectionChip("全部", selectedCollection.isBlank(), onClick = { selectedCollection = "" })
            collections.forEach { name ->
                CollectionChip(
                    name,
                    selectedCollection == name,
                    onClick = { selectedCollection = name },
                    onLongClick = { pendingDelete = name },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
                items.isEmpty() -> EmptyState(Icons.Rounded.Star, "这里还没有收藏", "在图片右上角点星标收藏，或先新建一个收藏夹")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { "${it.series_path}/${it.name}" }) { item ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(palette.card)
                                .pointerInput(item.series_path, item.name) {
                                    detectTapGestures {
                                        if (item.kind == "video") {
                                            nav.navigate(Routes.player(item.series_path, item.name))
                                        } else {
                                            nav.navigate(Routes.reader(item.series_path, item.index, name = item.name))
                                        }
                                    }
                                },
                        ) {
                            AsyncImage(
                                model = state.repository.mediaUrl(item.thumb),
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (item.kind == "video") {
                                Box(
                                    Modifier.align(Alignment.Center).size(26.dp)
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建收藏夹") },
            text = {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text("例如：收藏夹1") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newName.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                runCatching { state.repository.createFolder("收藏夹", name, "") }
                                    .onSuccess {
                                        showCreate = false
                                        newName = ""
                                        selectedCollection = name
                                        reloadCollections()
                                        reloadItems()
                                        state.showToast("已创建「$name」")
                                    }
                                    .onFailure { state.showToast("创建失败：${it.message}") }
                            }
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("创建", color = palette.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("取消", color = palette.textSecondary) }
            },
        )
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun CollectionChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val palette = ComiiTheme.palette
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) palette.accent else palette.card)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (active) Color.White else palette.textPrimary, fontSize = 13.sp)
    }
}
