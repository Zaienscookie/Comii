@file:OptIn(ExperimentalFoundationApi::class)

package com.comii.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.comii.app.data.NodeKind
import com.comii.app.ui.theme.ComiiTheme

@Composable
fun LargeTitle(text: String, subtitle: String? = null, color: Color? = null) {
    val palette = ComiiTheme.palette
    Column {
        Text(text, style = androidx.compose.material3.MaterialTheme.typography.displaySmall, color = color ?: palette.textPrimary)
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
        }
    }
}

@Composable
fun ComiiTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val palette = ComiiTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBackIosNew, contentDescription = "返回", tint = palette.accent, modifier = Modifier.size(20.dp))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                color = palette.textPrimary,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "搜索",
    onSubmit: (() -> Unit)? = null,
) {
    val palette = ComiiTheme.palette
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = palette.textTertiary) },
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(20.dp))
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            onSubmit?.invoke()
        }),
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = palette.card,
            unfocusedContainerColor = palette.card,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = palette.textPrimary,
            unfocusedTextColor = palette.textPrimary,
            cursorColor = palette.accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun SegmentControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = ComiiTheme.palette
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.separator.copy(alpha = if (palette.isDark) 0.45f else 0.55f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) palette.card else Color.Transparent)
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(index) },
                    )
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) palette.textPrimary else palette.textSecondary,
                )
            }
        }
    }
}

@Composable
fun CoverImage(
    model: Any?,
    kind: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val palette = ComiiTheme.palette
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (palette.isDark) Color(0xFF2C2C2E) else Color(0xFFE9E9EE)),
        contentAlignment = Alignment.Center,
    ) {
        if (model != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(model).crossfade(180).build(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                when (kind) {
                    NodeKind.VIDEO -> "▶"
                    NodeKind.FOLDER -> "▤"
                    else -> "◫"
                },
                fontSize = 26.sp,
                color = palette.textTertiary,
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val palette = ComiiTheme.palette
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = palette.textTertiary, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, color = palette.textPrimary)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = palette.textSecondary,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(18.dp))
            action()
        }
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val palette = ComiiTheme.palette
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (danger) palette.danger else palette.accent,
            contentColor = Color.White,
            disabledContainerColor = palette.separator,
            disabledContentColor = palette.textSecondary,
        ),
        modifier = modifier.fillMaxWidth().height(50.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    dismissText: String = "取消",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = ComiiTheme.palette
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = palette.textPrimary) },
        text = { Text(message, color = palette.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (destructive) palette.danger else palette.accent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissText, color = palette.textSecondary) }
        },
        containerColor = palette.card,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ComiiTheme.palette.elevated,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 5.dp)
                    .clip(CircleShape)
                    .background(ComiiTheme.palette.textTertiary.copy(alpha = 0.5f))
            )
        },
    ) {
        content()
    }
}

@Composable
fun SettingsGroup(title: String? = null, content: @Composable () -> Unit) {
    val palette = ComiiTheme.palette
    Column(modifier = Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title.uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = palette.textSecondary,
                modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
            )
        }
        Surface(shape = RoundedCornerShape(16.dp), color = palette.card) {
            Column { content() }
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
) {
    val palette = ComiiTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.bodyLarge, color = palette.textPrimary)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = palette.textSecondary,
                )
            }
        }
        if (trailing != null) trailing()
        if (showChevron) {
            Text("›", fontSize = 20.sp, color = palette.textTertiary, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
fun Separator(modifier: Modifier = Modifier, inset: Int = 16) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = inset.dp)
            .height(0.7.dp)
            .background(ComiiTheme.palette.separator)
    )
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${value.toInt()} ${units[index]}" else String.format("%.1f %s", value, units[index])
}

fun formatRemaining(seconds: Long): String {
    if (seconds <= 0) return "即将清理"
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    return when {
        days > 0 -> "${days}天${hours}小时后清理"
        hours > 0 -> "${hours}小时后清理"
        else -> "${seconds / 60}分钟后清理"
    }
}
