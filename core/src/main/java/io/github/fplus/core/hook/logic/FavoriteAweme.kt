package io.github.fplus.core.hook.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ss.android.ugc.aweme.feed.model.Aweme
import org.json.JSONArray
import org.json.JSONObject

/**
 * 收藏的作品。
 *
 * 字段来自当前视频的 `Aweme`：
 * - awemeId 用 `aid`，是作品详情页的 ID；
 * - desc / author / 封面 / 分享链接 都是列表里展示或跳转需要的；
 * - 分享链接优先用宿主下发的 `shareUrl`，没有时用 `https://www.douyin.com/video/<aid>` 兜底。
 */
data class FavoriteAweme(
    val awemeId: String = "",
    val desc: String = "",
    val authorName: String = "",
    val authorUid: String = "",
    val coverUrl: String = "",
    val videoUrl: String = "",
    val tags: List<String>? = null,
    val remark: String = "",
    val addedAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 去重键：作品 ID */
    val key: String
        get() = awemeId

    /** 列表里显示的标题 */
    val displayName: String
        get() = desc.ifBlank { awemeId }

    /** 列表里显示的标签，例如 `#广西 #搞笑` */
    val tagsText: String
        get() = tags.orEmpty().joinToString(" ") { "#$it" }

    /** 可复制/浏览器打开的网页链接 */
    fun webLink(): String {
        return videoUrl.takeIf { it.startsWith("http") }
            ?: awemeId.takeIf { it.isNotBlank() }?.let { "https://www.douyin.com/video/$it" }
            ?: ""
    }

    /** 抖音里的详情页深链；失败时退回网页链接 */
    fun open(context: Context): Boolean {
        val deepLink = awemeId.takeIf { it.isNotBlank() }
            ?.let { "snssdk1128://aweme/detail/$it" }
        val fallback = webLink().takeIf { it.isNotBlank() }
        val url = deepLink ?: fallback ?: return false
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (opened || fallback == null || fallback == url) return opened
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(fallback))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("awemeId", awemeId)
        put("desc", desc)
        put("authorName", authorName)
        put("authorUid", authorUid)
        put("coverUrl", coverUrl)
        put("videoUrl", videoUrl)
        put("tags", JSONArray(tags.orEmpty()))
        put("remark", remark)
        put("addedAt", addedAt)
        put("updatedAt", updatedAt)
    }

    companion object {
        /** 从当前视频构造收藏项；没有作品 ID 时返回 null */
        fun fromAweme(aweme: Aweme?): FavoriteAweme? {
            if (aweme == null) return null
            val id = aweme.aid.orEmpty().trim()
            if (id.isBlank()) return null
            val user = aweme.author
            return FavoriteAweme(
                awemeId = id,
                desc = aweme.desc.orEmpty().trim(),
                authorName = user?.nickname.orEmpty(),
                authorUid = user?.uid.orEmpty(),
                coverUrl = runCatching {
                    aweme.video?.cover?.urlList?.firstOrNull()
                }.getOrNull().orEmpty(),
                videoUrl = aweme.videoLink(),
            )
        }

        fun fromJson(json: JSONObject): FavoriteAweme? {
            val aweme = FavoriteAweme(
                awemeId = json.optString("awemeId"),
                desc = json.optString("desc"),
                authorName = json.optString("authorName"),
                authorUid = json.optString("authorUid"),
                coverUrl = json.optString("coverUrl"),
                videoUrl = json.optString("videoUrl"),
                tags = json.optJSONArray("tags")?.let { array ->
                    buildList {
                        for (i in 0 until array.length()) {
                            array.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                    }
                } ?: emptyList(),
                remark = json.optString("remark"),
                addedAt = json.optLong("addedAt"),
                updatedAt = json.optLong("updatedAt"),
            )
            return aweme.takeIf { it.key.isNotBlank() }
        }
    }
}
