package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.comii.app.data.GalleryItem
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

private const val PHOTO_PAGE = 300

/** 「照片」：整库所有图片 + 视频，vivo 相册那种 4 列方格。 */
@Composable
fun PhotosTab(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val gridState = rememberLazyGridState()

    suspend fun loadFirst() {
        loading = true
        runCatching { state.repository.gallery(limit = PHOTO_PAGE, offset = 0, sort = "recent") }
            .onSuccess {
                items = it.items
                total = it.total
            }
        loading = false
    }

    suspend fun loadMore() {
        if (loadingMore || items.isEmpty() || items.size >= total) return
        loadingMore = true
        runCatching {
            state.repository.gallery(limit = PHOTO_PAGE, offset = items.size, sort = "recent")
        }.onSuccess {
            items = items + it.items
            total = it.total
        }
        loadingMore = false
    }

    LaunchedEffect(refreshKey) { loadFirst() }

    LaunchedEffect(gridState, items.size, total) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last -> if (last >= items.size - 12) loadMore() }
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
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 110.dp),
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
                                            // 「照片」是整库相册：按全局图片序号打开，不能退回单系列
                                            val imageIndex = items
                                                .takeWhile { !(it.series_path == item.series_path && it.name == item.name) }
                                                .count { it.kind != "video" }
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
                    if (loadingMore) {
                        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
    // 切回照片页时不要重新拉整库，除非用户点刷新
    LaunchedEffect(Unit) { if (items.isEmpty()) delay(0) }
}
