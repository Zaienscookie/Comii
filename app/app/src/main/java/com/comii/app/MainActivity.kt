package com.comii.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.comii.app.data.ComiiRepository
import com.comii.app.ui.AppState
import com.comii.app.ui.Routes
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

    override fun onCreate() {
        super.onCreate()
        repository = ComiiRepository(this)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as ComiiApplication
            val state = remember { AppState(app.repository) }
            val dark = when (state.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            ComiiTheme(dark) {
                val view = LocalView.current
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as Activity).window
                        val controller = WindowCompat.getInsetsController(window, view)
                        controller.isAppearanceLightStatusBars = !dark
                        controller.isAppearanceLightNavigationBars = !dark
                    }
                }
                ComiiNavHost(state)
            }
        }
    }
}

@Composable
fun ComiiNavHost(state: AppState, nav: NavHostController = rememberNavController()) {
    val start = if (state.hasServer) "home" else "setup"
    NavHost(navController = nav, startDestination = start) {
        composable("setup") {
            SetupScreen(
                state = state,
                onDone = {
                    nav.navigate("home") {
                        popUpTo("setup") { inclusive = true }
                    }
                },
                onCancel = if (state.hasServer) ({ nav.popBackStack() }) else null,
            )
        }
        composable("home") {
            HomeScreen(state = state, nav = nav)
        }
        composable("series/{path}") { entry ->
            val path = Routes.decode(entry.arguments?.getString("path").orEmpty())
            SeriesScreen(state = state, path = path, nav = nav)
        }
        composable("reader/{path}") { entry ->
            val path = Routes.decode(entry.arguments?.getString("path").orEmpty())
            ReaderScreen(state = state, path = path, nav = nav)
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
    }
}
