package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.rounded.GridView
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
    var showOverview by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(path) {
        runCatching { state.repository.pdfInfo(path) }
            .onSuccess { info = it }
            .onFailure { error = it.message ?: "无法打开 PDF" }
    }

    val pages = info?.pages ?: 0
    val pager = rememberPagerState(pageCount = { pages })
    LaunchedEffect(pager.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

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
                    val zoomGestures = Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (pressed >= 2) {
                                    val next = (scale * zoomChange).coerceIn(1f, 5f)
                                    if (next > 1.01f) {
                                        scale = next
                                        offset += panChange
                                    } else {
                                        scale = 1f
                                        offset = Offset.Zero
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (scale > 1.01f) {
                                    offset += panChange
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
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
                                .then(zoomGestures),
                        )
                    }
                }
            }
        }

        if (showOverview) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.92f))
                    .pointerInput(Unit) { detectTapGestures { showOverview = false } }
            ) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("全部页面 · 共 $pages 页", color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("点击跳转", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(pages) { index ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF1A1A1C))
                                        .pointerInput(index) {
                                            detectTapGestures {
                                                showOverview = false
                                                scope.launch { pager.animateScrollToPage(index) }
                                            }
                                        }
                                ) {
                                    AsyncImage(
                                        model = state.repository.apiOrNull()?.pdfPageUrl(path, index, 240),
                                        contentDescription = "第 ${index + 1} 页",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxWidth().aspectRatio(0.72f),
                                    )
                                }
                                Text(
                                    "${index + 1}",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
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
                IconButton(onClick = { showOverview = true }) {
                    Icon(Icons.Rounded.GridView, contentDescription = "全部页面", tint = Color.White)
                }
            }
        }
    }
}
