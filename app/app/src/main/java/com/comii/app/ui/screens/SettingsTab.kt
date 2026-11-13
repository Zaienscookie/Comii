package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.comii.app.ui.AppState
import com.comii.app.ui.components.LargeTitle
import com.comii.app.ui.components.SegmentControl
import com.comii.app.ui.components.Separator
import com.comii.app.ui.components.SettingsGroup
import com.comii.app.ui.components.SettingsRow
import com.comii.app.ui.components.formatBytes
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

@Composable
fun SettingsTab(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    val repository = state.repository
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var manualUpdate by remember { mutableStateOf<com.comii.app.data.AppUpdateInfo?>(null) }
    var tapTurn by remember { mutableStateOf(repository.settings.tapToTurn) }
    var landscape by remember { mutableStateOf(repository.settings.landscapeRotate) }
    var showProgress by remember { mutableStateOf(repository.settings.showProgressBar) }
    var themeIndex by remember {
        mutableIntStateOf(
            when (state.themeMode) {
                "light" -> 1
                "dark" -> 2
                else -> 0
            }
        )
    }
    var readerModeIndex by remember {
        mutableIntStateOf(if (repository.settings.readerMode == "horizontal") 1 else 0)
    }
    var videoMuted by remember { mutableStateOf(repository.settings.videoMuted) }
    var pitchFollow by remember { mutableStateOf(repository.settings.videoPitchFollowsSpeed) }
    var entryPrompt by remember { mutableStateOf(repository.settings.entryPrompt) }
    var privacyBlur by remember { mutableStateOf(repository.settings.privacyBlur) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        LargeTitle("设置", "Comii 2.9.1")
        Spacer(Modifier.height(18.dp))

        SettingsGroup("服务器") {
            SettingsRow(
                title = "服务器地址",
                subtitle = state.serverUrl.ifBlank { "未配置" },
                showChevron = true,
                onClick = { nav.navigate("settings/server") },
            )
            Separator()
            SettingsRow(
                title = "资料库路径",
                subtitle = repository.settings.serverName.ifBlank { "由服务器决定" },
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingsGroup("外观") {
            Column(Modifier.padding(14.dp)) {
                Text("主题", fontSize = 14.sp, color = palette.textPrimary)
                Spacer(Modifier.height(10.dp))
                SegmentControl(
                    options = listOf("跟随系统", "浅色", "深色"),
                    selectedIndex = themeIndex,
                    onSelect = {
                        themeIndex = it
                        state.setTheme(listOf("auto", "light", "dark")[it])
                    },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        SettingsGroup("阅读") {
            Column(Modifier.padding(14.dp)) {
                Text("翻页方式", fontSize = 14.sp, color = palette.textPrimary)
                Spacer(Modifier.height(10.dp))
                SegmentControl(
                    options = listOf("上下滚动", "左右翻页"),
                    selectedIndex = readerModeIndex,
                    onSelect = {
                        readerModeIndex = it
                        repository.settings.readerMode = if (it == 0) "vertical" else "horizontal"
                    },
                )
            }
            Separator()
            ToggleRow(
                title = "点击屏幕翻页",
                subtitle = "点左侧上一页，右侧下一页",
                checked = tapTurn,
                onChange = { tapTurn = it; repository.settings.tapToTurn = it },
            )
            Separator()
            ToggleRow(
                title = "旋转横图",
                subtitle = "竖屏时自动把跨页横图旋转 90°",
                checked = landscape,
                onChange = { landscape = it; repository.settings.landscapeRotate = it },
            )
            Separator()
            ToggleRow(
                title = "显示阅读进度",
                subtitle = "阅读器底部的进度条",
                checked = showProgress,
                onChange = { showProgress = it; repository.settings.showProgressBar = it },
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingsGroup("视频") {
            ToggleRow(
                title = "默认静音",
                subtitle = "打开视频时先静音，播放页右上角可随时开声音",
                checked = videoMuted,
                onChange = { videoMuted = it; repository.settings.videoMuted = it },
            )
            Separator()
            ToggleRow(
                title = "倍速时音调跟随",
                subtitle = "开启后加速声音变尖、减速变低沉；关闭则保持原音调",
                checked = pitchFollow,
                onChange = { pitchFollow = it; repository.settings.videoPitchFollowsSpeed = it },
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingsGroup("隐私") {
            ToggleRow(
                title = "切到后台时隐藏内容",
                subtitle = "退到后台时应用界面变模糊，最近任务里也看不到内容",
                checked = privacyBlur,
                onChange = { privacyBlur = it; repository.settings.privacyBlur = it },
            )
            Separator()
            ToggleRow(
                title = "启动提示",
                subtitle = "每次打开 App 先显示一句提示",
                checked = entryPrompt,
                onChange = { entryPrompt = it; repository.settings.entryPrompt = it },
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingsGroup("存储") {
            SettingsRow(title = "离线内容", subtitle = formatBytes(repository.offline.totalBytes()))
            Separator()
            SettingsRow(
                title = "清除全部离线文件",
                subtitle = "只删除手机上的副本",
                onClick = {
                    repository.offline.all().forEach { repository.offline.delete(it.path) }
                    state.showToast("已清除离线文件")
                },
                showChevron = true,
            )
        }

        Spacer(Modifier.height(18.dp))
        SettingsGroup("诊断") {
            CrashLogRow()
        }

        Spacer(Modifier.height(18.dp))
        SettingsGroup("关于") {
            SettingsRow(title = "Comii", subtitle = "个人漫画仓库 · 原生客户端")
            Separator()
            SettingsRow(title = "服务端 API", subtitle = "/api/v1 · 与服务器版本兼容")
            Separator()
            SettingsRow(
                title = "检查更新",
                subtitle = if (checking) {
                    "正在检查…"
                } else {
                    "当前 v${com.comii.app.data.AppUpdater.currentVersionName}"
                },
                showChevron = true,
                onClick = {
                    if (!checking) {
                        if (!state.hasServer) {
                            state.showToast("先在设置里配置服务器地址")
                        } else {
                            checking = true
                            scope.launch {
                                val info = runCatching {
                                    com.comii.app.data.AppUpdater.check(state.serverUrl)
                                }.getOrNull()
                                checking = false
                                repository.settings.lastUpdateCheck = System.currentTimeMillis()
                                if (info == null) {
                                    state.showToast("已是最新版本")
                                } else {
                                    manualUpdate = info
                                }
                            }
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(110.dp))
    }

    manualUpdate?.let { info ->
        com.comii.app.ui.components.AppUpdateDialog(
            state = state,
            info = info,
            onDismiss = { manualUpdate = null },
        )
    }
}

@Composable
private fun CrashLogRow() {
    val palette = ComiiTheme.palette
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var log by remember { mutableStateOf(com.comii.app.data.CrashLog.read(context)) }
    var show by remember { mutableStateOf(false) }

    SettingsRow(
        title = "上次闪退日志",
        subtitle = if (log == null) "没有记录" else "点击查看 / 复制给开发者",
        showChevron = log != null,
        onClick = if (log != null) ({ show = true }) else null,
    )

    if (show && log != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { show = false },
            containerColor = palette.card,
            title = { Text("闪退日志", color = palette.textPrimary) },
            text = {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(log.orEmpty(), fontSize = 11.sp, color = palette.textSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(log.orEmpty()))
                    show = false
                }) { Text("复制并关闭", color = palette.accent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    com.comii.app.data.CrashLog.clear(context)
                    log = null
                    show = false
                }) { Text("清除", color = palette.textSecondary) }
            },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val palette = ComiiTheme.palette
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = palette.textPrimary)
            Text(subtitle, fontSize = 11.5.sp, color = palette.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = palette.accent,
                uncheckedTrackColor = palette.separator,
            ),
        )
    }
}







