package com.comii.app.ui.screens

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
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.comii.app.ui.theme.ComiiTheme

@Composable
fun HomeScreen(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.toastMessage) {
        val message = state.toastMessage
        if (message != null) {
            snackbar.showSnackbar(message)
            state.consumeToast()
        }
    }

    Scaffold(
        containerColor = palette.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            ComiiTabBar(selected = tab, onSelect = { tab = it })
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> LibraryTab(state = state, nav = nav)
                1 -> DownloadsTab(state = state, nav = nav)
                else -> SettingsTab(state = state, nav = nav)
            }
        }
    }
}

@Composable
private fun ComiiTabBar(selected: Int, onSelect: (Int) -> Unit) {
    val palette = ComiiTheme.palette
    val tabs = listOf(
        Triple("媒体库", Icons.Rounded.AutoStories, 0),
        Triple("下载", Icons.Rounded.Download, 1),
        Triple("设置", Icons.Rounded.Settings, 2),
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
                TabItem(label = label, icon = icon, selected = selected == index) { onSelect(index) }
            }
        }
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val palette = ComiiTheme.palette
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) palette.accent else palette.textTertiary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 10.sp,
            color = if (selected) palette.accent else palette.textTertiary,
        )
    }
}
