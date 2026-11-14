package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.comii.app.data.PdfInfo
import com.comii.app.ui.AppState

/** PDF 当小画册看：左右翻页、双指缩放、点一下收起顶栏。 */
@Composable
fun PdfScreen(state: AppState, path: String, nav: NavHostController) {
    var info by remember { mutableStateOf<PdfInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showBar by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(path) {
        runCatching { state.repository.pdfInfo(path) }
            .onSuccess { info = it }
            .onFailure { error = it.message ?: "无法打开 PDF" }
    }

    val pages = info?.pages ?: 0
    val pager = rememberPagerState(pageCount = { pages })

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            error != null -> Text(
                "打开失败：$error",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            info == null -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
            pages == 0 -> Text("这个 PDF 没有页面", color = Color.White, modifier = Modifier.align(Alignment.Center))
            else -> {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
                    val url = state.repository.apiOrNull()?.pdfPageUrl(path, index, 1440)
                    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                        val next = (scale * zoomChange).coerceIn(1f, 5f)
                        if (next <= 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = next
                            offset += panChange
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { showBar = !showBar }
                            }
                    ) {
                        AsyncImage(
                            model = url,
                            contentDescription = "第 ${index + 1} 页",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                                .transformable(transformState),
                        )
                    }
                }
            }
        }

        if (showBar) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "返回", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        info?.name ?: path.substringAfterLast('/').removeSuffix(".pdf"),
                        color = Color.White,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (pages > 0) "第 ${pager.currentPage + 1} / $pages 页" else "正在加载…",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
                Text(
                    "双指缩放",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
    }
}
