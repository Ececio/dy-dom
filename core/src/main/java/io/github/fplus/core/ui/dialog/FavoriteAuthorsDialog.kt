package io.github.fplus.core.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fplus.core.hook.logic.FavoriteAuthor
import io.github.fplus.core.ui.component.FDialog
import io.github.fplus.core.ui.component.FavoriteGroupHeader
import io.github.fplus.core.ui.component.FavoriteTagChips

/** 一个标签分组；tag 为空表示「未分组」 */
private data class AuthorGroup(
    val tag: String,
    val authors: List<FavoriteAuthor>,
)

/**
 * 按标签分组：每个标签一个分组，一个作者有多个标签时会在多个分组里各出现一次；
 * 没有标签的作者放进「未分组」，排在最后。
 */
private fun groupByTag(authors: List<FavoriteAuthor>): List<AuthorGroup> {
    val tagged = linkedMapOf<String, MutableList<FavoriteAuthor>>()
    val untagged = mutableListOf<FavoriteAuthor>()

    authors.forEach { author ->
        val tags = author.tags.orEmpty().distinct()
        if (tags.isEmpty()) {
            untagged.add(author)
        } else {
            tags.forEach { tag ->
                tagged.getOrPut(tag) { mutableListOf() }.add(author)
            }
        }
    }

    val groups = tagged.entries
        .sortedBy { it.key.lowercase() }
        .map { AuthorGroup(it.key, it.value) }
        .toMutableList()
    if (untagged.isNotEmpty()) groups.add(AuthorGroup("", untagged))
    return groups
}

/**
 * 收藏作者列表弹窗。
 *
 * 按标签分组显示；点击一行跳转作者主页，点右侧「删除」移除。数据来源是
 * `Download/Freedom/favorites/authors.json`，可以直接备份或手工编辑。
 */
@Composable
fun FavoriteAuthorsDialog(
    authors: List<FavoriteAuthor>,
    onDismiss: () -> Unit,
    onOpen: (FavoriteAuthor) -> Unit,
    onDelete: (FavoriteAuthor) -> Unit,
) {
    FDialog(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "收藏作者 (${authors.size})",
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 16.dp),
                    style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "关闭",
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.body1.copy(
                        color = MaterialTheme.colors.primary,
                    ),
                )
            }
            Divider()

            if (authors.isEmpty()) {
                Text(
                    text = "还没有收藏作者。\n在视频上长按，选「收藏作者」即可加入列表，可顺便添加标签。",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                    style = MaterialTheme.typography.body1.copy(
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    ),
                )
            } else {
                val groups = remember(authors) { groupByTag(authors) }
                var collapsedTags by remember(authors) { mutableStateOf(emptySet<String>()) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                ) {
                    groups.forEach { group ->
                        val collapsed = group.tag in collapsedTags
                        item(key = "group:${group.tag}") {
                            FavoriteGroupHeader(
                                tag = group.tag,
                                count = group.authors.size,
                                collapsed = collapsed,
                                onToggle = {
                                    collapsedTags = if (collapsed) {
                                        collapsedTags - group.tag
                                    } else {
                                        collapsedTags + group.tag
                                    }
                                },
                            )
                        }
                        if (!collapsed) {
                            items(
                                items = group.authors,
                                key = { author -> "${group.tag}:${author.key}" },
                            ) { author ->
                                FavoriteAuthorRow(
                                    author = author,
                                    onOpen = { onOpen(author) },
                                    onDelete = { onDelete(author) },
                                )
                                Divider()
                            }
                        }
                    }
                }
            }

            Text(
                text = "文件: Download/Freedom/favorites/authors.json",
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.body2.copy(
                    fontSize = 11.sp,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                ),
            )
        }
    }
}

@Composable
private fun FavoriteAuthorRow(
    author: FavoriteAuthor,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val muted = MaterialTheme.colors.onSurface.copy(alpha = 0.55f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = author.displayName,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "删除",
                modifier = Modifier
                    .clickable { onDelete() }
                    .padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                style = MaterialTheme.typography.body2.copy(
                    fontSize = 12.sp,
                    color = Color(0xFFE53935),
                ),
            )
        }

        val ids = buildString {
            if (author.douyinId.isNotBlank()) append("抖音号 ").append(author.douyinId)
            if (author.uid.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append("UID ").append(author.uid)
            }
        }
        if (ids.isNotBlank()) {
            Text(
                text = ids,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.body2.copy(fontSize = 12.sp, color = muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (author.tags.orEmpty().isNotEmpty()) {
            FavoriteTagChips(
                tags = author.tags.orEmpty(),
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (author.birthday.isNotBlank()) {
            Text(
                text = "生日 ${author.birthday}",
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.body2.copy(fontSize = 12.sp, color = muted),
            )
        }

        if (author.signature.isNotBlank()) {
            Text(
                text = author.signature,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.body2.copy(fontSize = 12.sp, color = muted),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
