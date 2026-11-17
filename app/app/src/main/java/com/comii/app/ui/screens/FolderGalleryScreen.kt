package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
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

/** 整个目录平铺：把某个目录（含所有子目录）的图片/视频一次性列出来。 */
@Composable
fun FolderGalleryScreen(state: AppState, path: String, nav: NavHostController) {
    val palette = ComiiTheme.palette
    var items by remember(path) { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var loading by remember(path) { mutableStateOf(true) }
    var error by remember(path) { mutableStateOf<String?>(null) }
    val title = remember(path) { path.substringAfterLast('/').ifEmpty { path.ifEmpty { "全部图片" } } }

    LaunchedEffect(path) {
        loading = true
        runCatching { state.repository.gallery(limit = 500, offset = 0, path = path) }
            .onSuccess { items = it.items; error = null }
            .onFailure { error = it.message }
        loading = false
    }

    Column(Modifier.fillMaxSize().background(palette.background).statusBarsPadding()) {
        ComiiTopBar(title = title, onBack = { nav.popBackStack() })
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accent)
            }
            error != null -> EmptyState(Icons.Rounded.PlayCircle, "加载失败", error)
            items.isEmpty() -> EmptyState(Icons.Rounded.PlayCircle, "没有图片", "这个目录下没有可显示的图片或视频")
            else -> {
                Text(
                    "共 ${items.size} 项",
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 6.dp),
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 24.dp),
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
                                .clickable {
                                    if (item.kind == "video") {
                                        nav.navigate(Routes.player(item.series_path, item.name))
                                    } else {
                                        nav.navigate(Routes.reader(item.series_path, item.index, name = item.name))
                                    }
                                },
                        ) {
                            AsyncImage(
                                model = state.repository.apiOrNull()?.let { api ->
                                    if (item.kind == "video") api.videoThumbUrl(item.series_path, item.name)
                                    else api.thumbUrl(item.series_path, item.name)
                                },
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (item.kind == "video") {
                                Box(
                                    Modifier
                                        .align(Alignment.Center)
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayCircle,
                                        contentDescription = "视频",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
