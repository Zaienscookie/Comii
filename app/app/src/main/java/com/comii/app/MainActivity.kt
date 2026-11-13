package com.comii.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.comii.app.data.ComiiRepository
import com.comii.app.data.CrashLog
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.screens.AiChatScreen
import com.comii.app.ui.screens.AiPersonaScreen
import com.comii.app.ui.screens.AiSettingsScreen
import com.comii.app.ui.screens.HomeScreen
import com.comii.app.ui.screens.PlayerScreen
import com.comii.app.ui.screens.ReaderScreen
import com.comii.app.ui.screens.SeriesScreen
import com.comii.app.ui.screens.SetupScreen
import com.comii.app.ui.screens.TrashScreen
import com.comii.app.ui.screens.UploadScreen
import com.comii.app.ui.theme.ComiiTheme

class ComiiApplication : Application() {
    lateinit var repository: ComiiRepository
        private set
    lateinit var aiRepository: com.comii.app.data.ai.AiRepository
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        repository = ComiiRepository(this)
        aiRepository = com.comii.app.data.ai.AiRepository(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        setContent {
            val app = application as ComiiApplication
            val state = remember { AppState(app.repository, app.aiRepository) }
            val settings = app.repository.settings
            val dark = when (state.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            ComiiTheme(dark) {
                val view = LocalView.current
                val lifecycleOwner = LocalLifecycleOwner.current
                var paused by remember { mutableStateOf(false) }

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        val window = (view.context as? Activity)?.window
                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> {
                                if (settings.privacyBlur) {
                                    paused = true
                                    if (android.os.Build.VERSION.SDK_INT < 33) {
                                        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                                    }
                                }
                            }
                            Lifecycle.Event.ON_RESUME -> {
                                paused = false
                                // 前台允许正常截图；最近任务用系统 API 隐藏缩略图
                                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                                if (android.os.Build.VERSION.SDK_INT >= 33) {
                                    (view.context as? Activity)?.setRecentsScreenshotEnabled(!settings.privacyBlur)
                                }
                            }
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        val controller = WindowCompat.getInsetsController(window, view)
                        controller.isAppearanceLightStatusBars = !dark
                        controller.isAppearanceLightNavigationBars = !dark
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            (view.context as? Activity)?.setRecentsScreenshotEnabled(!settings.privacyBlur)
                        }
                    }
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .blur(if (paused) 24.dp else 0.dp)
                ) {
                    ComiiNavHost(state)
                }
                if (paused) {
                    PrivacyCover()
                }
            }
        }
    }
}

@Composable
private fun PrivacyCover() {
    val palette = ComiiTheme.palette
    Box(
        Modifier
            .fillMaxSize()
            .background(palette.background.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Comii.", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = palette.accent)
            Text("内容已隐藏", fontSize = 13.sp, color = palette.textSecondary)
        }
    }
}

@Composable
fun ComiiNavHost(state: AppState, nav: NavHostController = rememberNavController()) {
    val settings = state.repository.settings
    var entered by remember { mutableStateOf(!settings.entryPrompt) }

    if (!entered) {
        EntryGate(
            onConfirm = { entered = true },
            onQuit = { },
        )
        return
    }

    // 未配置服务器也能先进入 App：媒体库页会提示去设置里填地址
    val start = "home"
    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = {
            slideInHorizontally(tween(260, easing = FastOutSlowInEasing), initialOffsetX = { it / 6 }) +
                fadeIn(tween(220))
        },
        exitTransition = {
            slideOutHorizontally(tween(240, easing = FastOutSlowInEasing), targetOffsetX = { -it / 6 }) +
                fadeOut(tween(200))
        },
        popEnterTransition = {
            slideInHorizontally(tween(260, easing = FastOutSlowInEasing), initialOffsetX = { -it / 6 }) +
                fadeIn(tween(220))
        },
        popExitTransition = {
            slideOutHorizontally(tween(240, easing = FastOutSlowInEasing), targetOffsetX = { it / 6 }) +
                fadeOut(tween(200))
        },
    ) {
        composable("setup") {
            SetupScreen(
                state = state,
                onDone = {
                    nav.navigate("home") {
                        popUpTo("setup") { inclusive = true }
                    }
                },
                onCancel = { nav.popBackStack() },
            )
        }
        composable("home") {
            HomeScreen(state = state, nav = nav)
        }
        composable("series/{path}") { entry ->
            val path = Routes.decode(entry.arguments?.getString("path").orEmpty())
            SeriesScreen(state = state, path = path, nav = nav)
        }
        composable(
            "reader/{path}?page={page}&all={all}&name={name}",
            arguments = listOf(
                navArgument("page") { type = NavType.IntType; defaultValue = -1 },
                navArgument("all") { type = NavType.IntType; defaultValue = 0 },
                navArgument("name") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val path = Routes.decode(entry.arguments?.getString("path").orEmpty())
            val page = entry.arguments?.getInt("page") ?: -1
            val all = (entry.arguments?.getInt("all") ?: 0) == 1
            val name = Routes.decode(entry.arguments?.getString("name").orEmpty())
            ReaderScreen(
                state = state,
                path = path,
                startPage = page,
                startName = name,
                all = all,
                nav = nav,
            )
        }
        composable("player/{path}/{name}") { entry ->
            val path = Routes.decode(entry.arguments?.getString("path").orEmpty())
            val name = Routes.decode(entry.arguments?.getString("name").orEmpty())
            PlayerScreen(state = state, path = path, startName = name, nav = nav)
        }
        composable("trash") { TrashScreen(state = state, nav = nav) }
        composable("upload/{folder}") { entry ->
            val folder = Routes.decode(entry.arguments?.getString("folder").orEmpty())
            UploadScreen(state = state, initialFolder = folder, nav = nav)
        }
        composable("settings/server") {
            SetupScreen(
                state = state,
                onDone = { nav.popBackStack() },
                onCancel = { nav.popBackStack() },
            )
        }
        composable("ai/chat/{personaId}") { entry ->
            val personaId = entry.arguments?.getString("personaId").orEmpty()
            AiChatScreen(state = state, personaId = personaId, nav = nav)
        }
        composable(
            "ai/persona?personaId={personaId}",
            arguments = listOf(
                navArgument("personaId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            ),
        ) { entry ->
            AiPersonaScreen(
                state = state,
                personaId = entry.arguments?.getString("personaId"),
                nav = nav,
            )
        }
        composable("ai/settings") {
            AiSettingsScreen(state = state, nav = nav)
        }
    }
}

@Composable
private fun EntryGate(onConfirm: () -> Unit, onQuit: () -> Unit) {
    val palette = ComiiTheme.palette
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .statusBarsPadding()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Comii.", fontSize = 42.sp, fontWeight = FontWeight.Bold, color = palette.accent)
        Spacer(Modifier.height(28.dp))
        Text("私人收藏", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        Spacer(Modifier.height(10.dp))
        Text(
            "这里只保存你自己的收藏，内容仅对你可见。\n继续即表示你了解这一点，并会自行保管好设备与账号。",
            fontSize = 14.sp,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton(text = "进入 Comii", onClick = onConfirm)
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = {
            onQuit()
            (context as? Activity)?.finish()
        }) {
            Text("退出", color = palette.textSecondary)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "可在「设置 → 隐私」里关闭此提示",
            fontSize = 11.sp,
            color = palette.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}
