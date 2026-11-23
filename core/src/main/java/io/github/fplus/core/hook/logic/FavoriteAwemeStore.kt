package io.github.fplus.core.hook.logic

import android.content.Context
import com.freegang.extension.child
import io.github.fplus.core.config.ConfigV1
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 收藏作品的本地存储。
 *
 * 数据和收藏作者一样是可直接编辑的 JSON：`Download/Freedom/favorites/awemes.json`。
 * 写入时先写 `.tmp` 再改名，并保留 `.bak`，文件被手工修改后会自动重新读取。
 */
object FavoriteAwemeStore {
    enum class Result { ADDED, UPDATED, FAILED }

    private const val DIR_NAME = "favorites"
    private const val FILE_NAME = "awemes.json"
    private const val BACKUP_NAME = "awemes.json.bak"
    private const val TMP_NAME = "awemes.json.tmp"

    private const val MAX_AWEMES = 2000

    private val lock = Any()

    private var cache: List<FavoriteAweme>? = null
    private var cachePath: String = ""
    private var cacheModified: Long = -1L

    fun dir(context: Context): File = ConfigV1.getFreedomDir(context).child(DIR_NAME)

    fun file(context: Context): File = File(dir(context), FILE_NAME)

    fun all(context: Context): List<FavoriteAweme> = synchronized(lock) {
        load(context)
    }

    fun find(context: Context, key: String): FavoriteAweme? = synchronized(lock) {
        load(context).firstOrNull { it.key == key }
    }

    fun upsert(context: Context, aweme: FavoriteAweme): Result = synchronized(lock) {
        if (aweme.key.isBlank()) return FavoriteAwemeStore.Result.FAILED

        val current = load(context).toMutableList()
        val index = current.indexOfFirst { it.key == aweme.key }
        val now = System.currentTimeMillis()

        if (index >= 0) {
            val old = current[index]
            // 新值为空时保留旧值，避免宿主某次没下发描述/封面把已有数据覆盖成空
            current[index] = old.copy(
                desc = aweme.desc.ifBlank { old.desc },
                authorName = aweme.authorName.ifBlank { old.authorName },
                authorUid = aweme.authorUid.ifBlank { old.authorUid },
                coverUrl = aweme.coverUrl.ifBlank { old.coverUrl },
                videoUrl = aweme.videoUrl.ifBlank { old.videoUrl },
                // tags 为 null 表示这次不改标签；空列表表示用户主动清空
                tags = aweme.tags ?: old.tags,
                remark = aweme.remark.ifBlank { old.remark },
                addedAt = old.addedAt.takeIf { it > 0L } ?: now,
                updatedAt = now,
            )
            if (!write(context, current)) return FavoriteAwemeStore.Result.FAILED
            return FavoriteAwemeStore.Result.UPDATED
        }

        if (current.size >= MAX_AWEMES) return FavoriteAwemeStore.Result.FAILED
        current.add(
            0,
            aweme.copy(
                tags = aweme.tags ?: emptyList(),
                addedAt = aweme.addedAt.takeIf { it > 0L } ?: now,
                updatedAt = now,
            )
        )
        if (!write(context, current)) return FavoriteAwemeStore.Result.FAILED
        FavoriteAwemeStore.Result.ADDED
    }

    fun remove(context: Context, key: String): Boolean = synchronized(lock) {
        val current = load(context).toMutableList()
        val removed = current.removeAll { it.key == key }
        if (!removed) return false
        write(context, current)
    }

    fun invalidate() = synchronized(lock) {
        cache = null
        cachePath = ""
        cacheModified = -1L
    }

    private fun load(context: Context): List<FavoriteAweme> {
        val target = file(context)
        val cached = cache
        if (cached != null && cachePath == target.absolutePath && cacheModified == target.lastModified()) {
            return cached
        }

        val list = read(target)
            ?: read(File(target.parentFile, BACKUP_NAME))
            ?: emptyList()
        cache = list
        cachePath = target.absolutePath
        cacheModified = target.lastModified()
        return list
    }

    private fun read(file: File?): List<FavoriteAweme>? {
        if (file == null || !file.exists() || file.length() <= 0L) return null
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val array = root.optJSONArray("awemes") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val aweme = array.optJSONObject(i)?.let { FavoriteAweme.fromJson(it) }
                    if (aweme != null) add(aweme)
                }
            }
        }.getOrNull()
    }

    private fun write(context: Context, list: List<FavoriteAweme>): Boolean {
        val target = file(context)
        return runCatching {
            val parent = target.parentFile ?: return false
            if (!parent.exists() && !parent.mkdirs()) return false

            val json = JSONObject()
                .put("version", 1)
                .put("updatedAt", System.currentTimeMillis())
                .put("awemes", JSONArray().apply {
                    list.forEach { put(it.toJson()) }
                })

            val tmp = File(parent, TMP_NAME)
            tmp.writeText(json.toString(2), Charsets.UTF_8)

            if (target.exists()) {
                runCatching { target.copyTo(File(parent, BACKUP_NAME), overwrite = true) }
                target.delete()
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }

            cache = list
            cachePath = target.absolutePath
            cacheModified = target.lastModified()
            true
        }.getOrDefault(false)
    }
}

/**
 * 所有收藏项用过的标签，收藏作者/收藏作品共用一份，输入标签时直接点选。
 */
object FavoriteTags {
    fun all(context: Context): List<String> {
        val tags = mutableListOf<String>()
        runCatching {
            FavoriteAuthorStore.all(context).forEach { tags.addAll(it.tags.orEmpty()) }
        }
        runCatching {
            FavoriteAwemeStore.all(context).forEach { tags.addAll(it.tags.orEmpty()) }
        }
        return tags
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }
}
