package io.github.fplus.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 标签背景色：浅色主题用深色字，深色主题用浅色字，保证两边都能看清 */
private val lightTagColors = listOf(
    Color(0xFFD32F2F),
    Color(0xFFEF6C00),
    Color(0xFF2E7D32),
    Color(0xFF1565C0),
    Color(0xFF6A1B9A),
    Color(0xFF00695C),
)

private val darkTagColors = listOf(
    Color(0xFFFF8A80),
    Color(0xFFFFB74D),
    Color(0xFF81C784),
    Color(0xFF64B5F6),
    Color(0xFFBA68C8),
    Color(0xFF4DB6AC),
)

@Composable
private fun tagAccent(tag: String): Color {
    if (tag.isBlank()) return MaterialTheme.colors.onSurface.copy(alpha = 0.65f)
    val colors = if (MaterialTheme.colors.isLight) lightTagColors else darkTagColors
    val index = (tag.hashCode() and Int.MAX_VALUE) % colors.size
    return colors[index]
}

@Composable
private fun tagBackground(tag: String): Color {
    val alpha = if (MaterialTheme.colors.isLight) 0.12f else 0.24f
    return tagAccent(tag).copy(alpha = alpha)
}

/**
 * 收藏列表的分组标题。
 *
 * 标签名放在带颜色的圆角背景里；整行可点击，用来展开/收起该分组。
 */
@Composable
fun FavoriteGroupHeader(
    tag: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    val accent = tagAccent(tag)
    val muted = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 24.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (collapsed) "▸" else "▾",
            style = MaterialTheme.typography.body2.copy(
                fontSize = 11.sp,
                color = accent,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .background(tagBackground(tag), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(
                text = if (tag.isBlank()) "未分组" else "#$tag",
                style = MaterialTheme.typography.body2.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$count",
            style = MaterialTheme.typography.body2.copy(
                fontSize = 12.sp,
                color = muted,
            ),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (collapsed) "展开" else "收起",
            style = MaterialTheme.typography.body2.copy(
                fontSize = 11.sp,
                color = muted,
            ),
        )
    }
}

/**
 * 一行显示多个标签，放不下时自动换行；每个标签都有独立的颜色背景。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavoriteTagChips(
    tags: List<String>,
    modifier: Modifier = Modifier,
) {
    val items = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (items.isEmpty()) return

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { tag ->
            val accent = tagAccent(tag)
            Box(
                modifier = Modifier
                    .background(tagBackground(tag), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "#$tag",
                    style = MaterialTheme.typography.body2.copy(
                        fontSize = 11.sp,
                        color = accent,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
