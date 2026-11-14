package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.comii.app.data.GalleryItem
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.ComiiTopBar
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.theme.ComiiTheme

/** 收藏夹：把所有收藏分类里的图片/视频拉平成一个 4 列网格。 */
@Composable
fun FavoritesFlatScreen(state: AppState, nav: NavHostController, onBack: () -> Unit) {
    val palette = ComiiTheme.palette
    var items by remember { mutableStateOf<List<GalleryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(state.serverGeneration) {
        loading = true
        items = runCatching {
            state.repository.gallery(limit = 500, offset = 0, path = "收藏夹", sort = "recent")
        }.getOrNull()?.items.orEmpty()
        loading = false
    }

    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().background(palette.background)) {
        ComiiTopBar(title = "收藏夹", onBack = onBack)
        Box(Modifier.weight(1f)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.accent)
                }
                items.isEmpty() -> EmptyState(Icons.Rounded.Star, "收藏夹是空的", "在图片右上角点星标收藏")
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
}
