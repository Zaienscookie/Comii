package com.comii.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.comii.app.ui.AppState
import com.comii.app.ui.theme.ComiiTheme
import kotlinx.coroutines.launch

/** Small pill used for tags in the series page and the tag browser. */
@Composable
fun TagChip(
    text: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val palette = ComiiTheme.palette
    Row(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) palette.accent else palette.card)
            .then(
                if (onClick != null) Modifier.pointerInput(text) { detectTapGestures { onClick() } }
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "# $text",
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Color.White else palette.textSecondary,
        )
        if (onRemove != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                "×",
                color = if (selected) Color.White else palette.textTertiary,
                fontSize = 14.sp,
                modifier = Modifier.pointerInput(text) { detectTapGestures { onRemove() } },
            )
        }
    }
}

/**
 * Add / remove tags for one series. Existing tags are suggested so the library
 * keeps a small, reusable vocabulary instead of endless duplicates.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorDialog(
    state: AppState,
    path: String,
    seriesName: String,
    initial: List<String>,
    onDismiss: () -> Unit,
    onSaved: (List<String>) -> Unit,
) {
    val palette = ComiiTheme.palette
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(initial) }
    var all by remember { mutableStateOf<List<String>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        all = runCatching { state.repository.tags().tags.map { it.name } }.getOrDefault(emptyList())
    }

    fun add(raw: String) {
        val clean = raw.trim().replace("#", "").trim()
        if (clean.isEmpty()) return
        if (current.none { it.equals(clean, ignoreCase = true) }) {
            current = current + clean
        }
        input = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.card,
        title = { Text("标签 · $seriesName", color = palette.textPrimary, fontSize = 18.sp) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (current.isEmpty()) {
                    Text("还没有标签", color = palette.textSecondary, fontSize = 13.sp)
                } else {
                    FlowRow {
                        current.forEach { tag ->
                            TagChip(tag, onRemove = { current = current - tag })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text("输入新标签，例如 作者名 / 题材", color = palette.textTertiary, fontSize = 13.sp) },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add(input) }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = palette.background,
                            unfocusedContainerColor = palette.background,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { add(input) }, enabled = input.isNotBlank()) {
                        Text("添加", color = palette.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (all.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("历史标签", color = palette.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    FlowRow {
                        all.filter { name -> current.none { it.equals(name, ignoreCase = true) } }
                            .take(40)
                            .forEach { tag ->
                                TagChip(tag, onClick = { add(tag) })
                            }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        runCatching { state.repository.saveTags(listOf(path), current) }
                            .onSuccess {
                                state.showToast(if (current.isEmpty()) "已清除标签" else "标签已保存")
                                onSaved(it)
                            }
                            .onFailure { state.showToast("保存失败：${it.message}") }
                        saving = false
                        onDismiss()
                    }
                },
            ) { Text("保存", color = palette.accent, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = palette.textSecondary) } },
    )
}

/** Horizontal row of tags with an edit entry, used under the series title. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesTagRow(tags: List<String>, onEdit: () -> Unit) {
    val palette = ComiiTheme.palette
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        FlowRow(modifier = Modifier.weight(1f)) {
            tags.forEach { TagChip(it) }
        }
        if (tags.isEmpty()) {
            Text("还没有标签", color = palette.textTertiary, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
        }
        TextButton(onClick = onEdit) {
            Text(if (tags.isEmpty()) "+ 标签" else "编辑标签", color = palette.accent, fontSize = 12.5.sp)
        }
    }
}
