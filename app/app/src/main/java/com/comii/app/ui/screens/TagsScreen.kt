package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.comii.app.data.LibraryNode
import com.comii.app.data.NodeKind
import com.comii.app.data.TagCount
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.ComiiTopBar
import com.comii.app.ui.components.CoverImage
import com.comii.app.ui.theme.ComiiTheme

/** 标签总览：列出所有标签，点一个就列出带这个标签的系列。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsScreen(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    var tags by remember { mutableStateOf<List<TagCount>>(emptyList()) }
    var active by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<LibraryNode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadingItems by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        tags = runCatching { state.repository.tags() }.getOrDefault(emptyList())
        loading = false
    }

    LaunchedEffect(active) {
        if (active.isBlank()) {
            items = emptyList()
            return@LaunchedEffect
        }
        loadingItems = true
        items = runCatching { state.repository.tagged(active) }.getOrDefault(emptyList())
        loadingItems = false
    }

    Column(Modifier.fillMaxSize().background(palette.background)) {
        ComiiTopBar(title = "标签", onBack = { nav.popBackStack() })
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accent)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
            ) {
                item {
                    if (tags.isEmpty()) {
                        Text(
                            "还没有标签。打开一个系列 →「+ 标签」即可添加，之后可以在这里按标签找。",
                            fontSize = 13.sp,
                            color = palette.textSecondary,
                        )
                        Spacer(Modifier.height(12.dp))
                    } else {
                        FlowRow {
                            tags.forEach { tag ->
                                TagChip(
                                    text = "${tag.name}（${tag.count}）",
                                    selected = tag.name == active,
                                    onClick = { active = if (active == tag.name) "" else tag.name },
                                )
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (active.isBlank()) "选择一个标签查看系列" else "「$active」· ${items.size} 个系列",
                        fontSize = 13.sp,
                        color = palette.textSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (loadingItems) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = palette.accent)
                        }
                    }
                }
                items(items, key = { it.path }) { node ->
                    TagSeriesRow(
                        node = node,
                        cover = state.repository.mediaUrl(node.cover),
                        onClick = { nav.navigate(Routes.series(node.path)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TagSeriesRow(node: LibraryNode, cover: String?, onClick: () -> Unit) {
    val palette = ComiiTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .pointerInput(node.path) { detectTapGestures { onClick() } }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            model = cover,
            kind = node.kind,
            modifier = Modifier.width(54.dp).aspectRatio(0.7f),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    node.isFolder -> "${node.child_count ?: 0} 项"
                    node.kind == NodeKind.VIDEO -> "${node.video_count} 个视频"
                    else -> "${node.image_count} 页"
                },
                fontSize = 11.5.sp,
                color = palette.textSecondary,
            )
        }
        Text("›", fontSize = 20.sp, color = palette.textTertiary)
    }
}
