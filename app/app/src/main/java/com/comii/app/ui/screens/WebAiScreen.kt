package com.comii.app.ui.screens

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import com.comii.app.ui.AppState
import com.comii.app.ui.components.EmptyState
import com.comii.app.ui.components.PrimaryButton
import com.comii.app.ui.theme.ComiiTheme

/**
 * AI 页面改为内嵌服务器上的网页（web/static/ai.html）。
 * 改 WebUI 源码并部署后，App 不用重新打包即可生效。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebAiScreen(state: AppState, nav: NavHostController) {
    val palette = ComiiTheme.palette
    if (!state.hasServer) {
        EmptyState(
            icon = Icons.Rounded.AutoAwesome,
            title = "先连接服务器",
            subtitle = "AI 页面由服务器提供，填好地址后就能在这里打开",
            action = {
                PrimaryButton(
                    text = "去设置服务器",
                    onClick = { nav.navigate("settings/server") },
                    modifier = Modifier.fillMaxSize(0.6f),
                )
            },
        )
        return
    }

    val dark = when (state.themeMode) {
        "light" -> false
        "dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }
    val pageUrl = remember(state.serverUrl, dark) {
        val base = state.serverUrl.trim().trimEnd('/')
        "$base/ai.html?theme=${if (dark) "dark" else "light"}"
    }
    val background = palette.background.toArgb()

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(background)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.textZoom = 100
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                webViewClient = WebViewClient()
                loadUrl(pageUrl)
            }
        },
        update = { webView ->
            if (webView.url != pageUrl) webView.loadUrl(pageUrl)
        },
    )
}
