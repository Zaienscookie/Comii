package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.comii.app.ui.components.ConfirmDialog
import com.comii.app.ui.components.CoverImage
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.components.LargeTitle
import com.comii.app.ui.components.formatBytes
import com.comii.app.ui.theme.ComiiTheme

@Composable
fun DownloadsTab(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    var version by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<OfflineSeries?>(null) }
    val series = remember(version) { state.repository.offline.all() }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                LargeTitle("下载", "${series.size} 个系列 · ${formatBytes(state.repository.offline.totalBytes())}")
            }
        }
        Spacer(Modifier.height(14.dp))

        if (series.isEmpty()) {
            EmptyState(
                icon = Icons.Rounded.Download,
                title = "还没有离线内容",
                subtitle = "在媒体库长按系列，选择「下载到本地」，没有网络也能看",
            )
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(series, key = { it.path }) { item ->
                    val cover = state.repository.offline.coverFile(item)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.card)
                            .pointerInput(item.path) {
                                detectTapGestures {
                                    if (item.kind == NodeKind.VIDEO && item.videos.isNotEmpty()) {
                                        nav.navigate(Routes.player(item.path, item.videos.first()))
                                    } else {
                                        nav.navigate(Routes.reader(item.path))
                                    }
                                }
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
                        IconButton(onClick = {
                            if (item.kind == NodeKind.VIDEO && item.videos.isNotEmpty()) {
                                nav.navigate(Routes.player(item.path, item.videos.first()))
                            } else {
                                nav.navigate(Routes.reader(item.path))
                            }
                        }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "打开", tint = palette.accent)
                        }
                        IconButton(onClick = { pendingDelete = item }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "删除下载", tint = palette.textTertiary, modifier = Modifier.size(20.dp))
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
}
