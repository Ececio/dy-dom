package io.github.fplus.core.hook.logic

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.ss.android.ugc.aweme.feed.model.Aweme
import org.json.JSONObject

/**
 * 收藏的作者。
 *
 * 字段来源是视频里的 `aweme.author`：
 * - nickname / uid / uniqueId / shortId 可以直接取；
 * - secUid / signature / birthday 在宿主里没有稳定的公开字段，用反射按名字取，取不到就留空；
 * - birthday 属于隐私信息，抖音经常不对他人下发，留空是正常情况。
 */
data class FavoriteAuthor(
    val uid: String = "",
    val secUid: String = "",
    val uniqueId: String = "",
    val shortId: String = "",
    val nickname: String = "",
    val signature: String = "",
    val birthday: String = "",
    val avatarUrl: String = "",
    val remark: String = "",
    val addedAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 去重键：uid 优先，其次 secUid / 抖音号 / shortId */
    val key: String
        get() = uid.ifBlank { secUid }.ifBlank { uniqueId }.ifBlank { shortId }

    /** 列表里显示的名字 */
    val displayName: String
        get() = nickname.ifBlank { uniqueId }.ifBlank { shortId }.ifBlank { uid }

    /** 抖音号（uniqueId 和 shortId 通常相同，优先显示 uniqueId） */
    val douyinId: String
        get() = uniqueId.ifBlank { shortId }

    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("secUid", secUid)
        put("uniqueId", uniqueId)
        put("shortId", shortId)
        put("nickname", nickname)
        put("signature", signature)
        put("birthday", birthday)
        put("avatarUrl", avatarUrl)
        put("remark", remark)
        put("addedAt", addedAt)
        put("updatedAt", updatedAt)
    }

    /**
     * 打开作者主页。
     *
     * `snssdk1128://user/profile/{uid}` 已在抖音 31.5.0 实测可直达 UserProfileActivity；
     * 没有 uid 时退回网页链接（secUid 是网页主页使用的 ID）。
     */
    fun openProfile(context: Context): Boolean {
        val url = when {
            uid.isNotBlank() -> "snssdk1128://user/profile/$uid"
            secUid.isNotBlank() -> "https://www.douyin.com/user/$secUid"
            else -> return false
        }
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    companion object {
        /** 从视频作者信息构造收藏项；没有任何可用 ID 时返回 null */
        fun fromAweme(aweme: Aweme?): FavoriteAuthor? {
            val user = aweme?.author ?: return null
            val uid = user.uid.orEmpty()
            val secUid = user.readText("secUid", "sec_uid")
            val uniqueId = user.uniqueId.orEmpty()
            val shortId = user.shortId.orEmpty()
            if (uid.isBlank() && secUid.isBlank() && uniqueId.isBlank() && shortId.isBlank()) {
                return null
            }
            return FavoriteAuthor(
                uid = uid,
                secUid = secUid,
                uniqueId = uniqueId,
                shortId = shortId,
                nickname = user.nickname.orEmpty(),
                signature = user.readText("signature"),
                birthday = user.readText("birthday"),
                avatarUrl = runCatching {
                    user.avatarThumb?.urlList?.firstOrNull()
                        ?: user.avatarMedium?.urlList?.firstOrNull()
                }.getOrNull().orEmpty(),
            )
        }

        fun fromJson(json: JSONObject): FavoriteAuthor? {
            val author = FavoriteAuthor(
                uid = json.optString("uid"),
                secUid = json.optString("secUid"),
                uniqueId = json.optString("uniqueId"),
                shortId = json.optString("shortId"),
                nickname = json.optString("nickname"),
                signature = json.optString("signature"),
                birthday = json.optString("birthday"),
                avatarUrl = json.optString("avatarUrl"),
                remark = json.optString("remark"),
                addedAt = json.optLong("addedAt"),
                updatedAt = json.optLong("updatedAt"),
            )
            return author.takeIf { it.key.isNotBlank() }
        }

        /** 宿主的 User 类没有稳定的公开字段，按名字反射读取 */
        private fun Any.readText(vararg names: String): String {
            var type: Class<*>? = javaClass
            while (type != null && type != Any::class.java) {
                val current = type
                for (name in names) {
                    val field = runCatching { current.getDeclaredField(name) }.getOrNull() ?: continue
                    val value = runCatching {
                        field.isAccessible = true
                        field.get(this)
                    }.getOrNull() ?: continue
                    val text = when (value) {
                        is String -> value
                        is Number, is Boolean -> value.toString()
                        else -> null
                    }
                    if (!text.isNullOrBlank()) return text
                }
                type = current.superclass
            }
            return ""
        }
    }
}
