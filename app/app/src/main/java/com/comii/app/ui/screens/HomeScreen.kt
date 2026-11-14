package com.comii.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.comii.app.ui.AppState
import com.comii.app.data.AppUpdateInfo
import com.comii.app.data.AppUpdater
import com.comii.app.ui.components.AppUpdateDialog
import com.comii.app.ui.theme.ComiiTheme

@Composable
fun HomeScreen(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    val aiUnread by state.ai.unread.collectAsState()

    // 每次启动都检查更新；有新版本直接弹窗，用户点更新即可
    LaunchedEffect(Unit) {
        if (!state.hasServer) return@LaunchedEffect
        updateInfo = runCatching { AppUpdater.check(state.serverUrl) }.getOrNull()
    }

    LaunchedEffect(state.toastMessage) {
        val message = state.toastMessage
        if (message != null) {
            snackbar.showSnackbar(message)
            state.consumeToast()
        }
    }

    // 返回键一级一级退回：设置 → 下载 → 媒体库 → 退出（而不是一下退出）
    BackHandler(enabled = tab != 0) {
        tab = (tab - 1).coerceAtLeast(0)
    }

    Scaffold(
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ComiiTabBar(selected = tab, onSelect = { tab = it }, aiUnread = aiUnread)
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState > initialState
                    val enter = if (forward) {
                        slideInHorizontally(tween(240, easing = FastOutSlowInEasing), initialOffsetX = { it / 5 }) +
                            fadeIn(tween(200, easing = FastOutSlowInEasing))
                    } else {
                        slideInHorizontally(tween(240, easing = FastOutSlowInEasing), initialOffsetX = { -it / 5 }) +
                            fadeIn(tween(200, easing = FastOutSlowInEasing))
                    }
                    val exit = if (forward) {
                        slideOutHorizontally(tween(240, easing = FastOutSlowInEasing), targetOffsetX = { -it / 5 }) +
                            fadeOut(tween(180))
                    } else {
                        slideOutHorizontally(tween(240, easing = FastOutSlowInEasing), targetOffsetX = { it / 5 }) +
                            fadeOut(tween(180))
                    }
                    enter togetherWith exit
                },
            ) { currentTab ->
                when (currentTab) {
                    0 -> PhotosTab(state = state, nav = nav)
                    1 -> LibraryTab(state = state, nav = nav)
                    2 -> DownloadsTab(state = state, nav = nav)
                    3 -> AiScreen(state = state, nav = nav)
                    else -> SettingsTab(state = state, nav = nav)
                }
            }
        }
    }

    updateInfo?.let { info ->
        AppUpdateDialog(state = state, info = info, onDismiss = { updateInfo = null })
    }
}

@Composable
private fun ComiiTabBar(selected: Int, onSelect: (Int) -> Unit, aiUnread: Int) {
    val palette = ComiiTheme.palette
    val tabs = listOf(
        Triple("照片", Icons.Rounded.PhotoLibrary, 0),
        Triple("相册", Icons.Rounded.Folder, 1),
        Triple("下载", Icons.Rounded.Download, 2),
        Triple("AI", Icons.Rounded.AutoAwesome, 3),
        Triple("设置", Icons.Rounded.Settings, 4),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .background(palette.elevated)
    ) {
        Box(Modifier.fillMaxWidth().height(0.7.dp).background(palette.separator))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { (label, icon, index) ->
                TabItem(
                    label = label,
                    icon = icon,
                    selected = selected == index,
                    badge = if (index == 3) aiUnread else 0,
                ) { onSelect(index) }
            }
        }
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val palette = ComiiTheme.palette
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) palette.accent else palette.textTertiary,
                modifier = Modifier.size(24.dp),
            )
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 9.dp, y = (-6).dp)
                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(palette.accent)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge > 99) "99+" else "$badge",
                        color = androidx.compose.ui.graphics.Color.White,
                        fontSize = 9.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 10.sp,
            color = if (selected) palette.accent else palette.textTertiary,
        )
    }
}

