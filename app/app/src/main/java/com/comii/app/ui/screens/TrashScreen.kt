package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.comii.app.data.TrashEntry
import com.comii.app.ui.AppState
import com.comii.app.ui.components.ComiiTopBar
import com.comii.app.ui.components.ConfirmDialog
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.components.Separator
import com.comii.app.ui.components.formatBytes
import com.comii.app.ui.components.formatRemaining
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

@Composable
fun TrashScreen(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TrashEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var pendingPurge by remember { mutableStateOf<TrashEntry?>(null) }

    suspend fun reload() {
        loading = true
        items = runCatching { state.repository.trash().items }.getOrDefault(emptyList())
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().background(palette.background).statusBarsPadding()) {
        ComiiTopBar(title = "回收站", onBack = { nav.popBackStack() })
        Text(
            "删除的内容会保留 3 天，之后自动清理。",
            fontSize = 12.sp,
            color = palette.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        Spacer(Modifier.height(6.dp))

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.accent)
            }
            items.isEmpty() -> EmptyState(Icons.Rounded.DeleteSweep, "回收站是空的", "删除的系列会先放在这里")
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { entry ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.card)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = palette.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${entry.original_path} · ${formatBytes(entry.size)}",
                                    fontSize = 11.5.sp,
                                    color = palette.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatRemaining(entry.remaining_seconds),
                                    fontSize = 11.5.sp,
                                    color = palette.danger,
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { state.repository.restore(entry.id) }
                                        .onSuccess { state.showToast("已恢复「${entry.name}」"); reload() }
                                        .onFailure { state.showToast("恢复失败：${it.message}") }
                                }
                            }) { Text("恢复", color = palette.accent, fontSize = 13.sp) }
                            IconButton(onClick = { pendingPurge = entry }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "彻底删除", tint = palette.danger, modifier = Modifier.size(20.dp))
                            }
                        }
                        Separator(inset = 0)
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }

    pendingPurge?.let { entry ->
        ConfirmDialog(
            title = "彻底删除「${entry.name}」？",
            message = "此操作不可恢复。",
            confirmText = "彻底删除",
            destructive = true,
            onConfirm = {
                scope.launch {
                    runCatching { state.repository.purge(entry.id) }
                        .onSuccess { state.showToast("已彻底删除"); reload() }
                        .onFailure { state.showToast("删除失败：${it.message}") }
                }
                pendingPurge = null
            },
            onDismiss = { pendingPurge = null },
        )
    }
}
