package io.github.fplus.core.hook.logic

import android.content.Context
import com.freegang.extension.child
import io.github.fplus.core.config.ConfigV1
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 收藏作者的本地存储。
 *
 * 数据以 JSON 文件保存在 `Download/Freedom/favorites/authors.json`：
 * - 用户可以直接用文件管理器或 `adb pull` 备份、编辑、导入；
 * - 写入时先写 `.tmp` 再改名，同时保留 `.bak`，避免写一半把列表弄坏；
 * - 内存里缓存一份，文件修改时间变化时自动重新读取（手工改过文件也能生效）。
 */
object FavoriteAuthorStore {
    enum class Result { ADDED, UPDATED, FAILED }

    private const val DIR_NAME = "favorites"
    private const val FILE_NAME = "authors.json"
    private const val BACKUP_NAME = "authors.json.bak"
    private const val TMP_NAME = "authors.json.tmp"

    /** 上限只是防止误导入超大文件，正常收藏几百个完全够用 */
    private const val MAX_AUTHORS = 2000

    private val lock = Any()

    private var cache: List<FavoriteAuthor>? = null
    private var cachePath: String = ""
    private var cacheModified: Long = -1L

    fun dir(context: Context): File = ConfigV1.getFreedomDir(context).child(DIR_NAME)

    fun file(context: Context): File = File(dir(context), FILE_NAME)

    fun all(context: Context): List<FavoriteAuthor> = synchronized(lock) {
        load(context)
    }

    fun find(context: Context, key: String): FavoriteAuthor? = synchronized(lock) {
        load(context).firstOrNull { it.key == key }
    }

    /** 收藏或更新，返回是新增还是更新 */
    fun upsert(context: Context, author: FavoriteAuthor): Result = synchronized(lock) {
        if (author.key.isBlank()) return FavoriteAuthorStore.Result.FAILED

        val current = load(context).toMutableList()
        val index = current.indexOfFirst { it.key == author.key }
        val now = System.currentTimeMillis()

        if (index >= 0) {
            val old = current[index]
            // 合并而不是覆盖：新值为空时保留旧值（比如生日、签名之前拿到过）
            current[index] = old.copy(
                uid = author.uid.ifBlank { old.uid },
                secUid = author.secUid.ifBlank { old.secUid },
                uniqueId = author.uniqueId.ifBlank { old.uniqueId },
                shortId = author.shortId.ifBlank { old.shortId },
                nickname = author.nickname.ifBlank { old.nickname },
                signature = author.signature.ifBlank { old.signature },
                birthday = author.birthday.ifBlank { old.birthday },
                avatarUrl = author.avatarUrl.ifBlank { old.avatarUrl },
                remark = author.remark.ifBlank { old.remark },
                addedAt = old.addedAt.takeIf { it > 0L } ?: now,
                updatedAt = now,
            )
            if (!write(context, current)) return FavoriteAuthorStore.Result.FAILED
            return FavoriteAuthorStore.Result.UPDATED
        }

        if (current.size >= MAX_AUTHORS) return FavoriteAuthorStore.Result.FAILED
        current.add(
            0,
            author.copy(
                addedAt = author.addedAt.takeIf { it > 0L } ?: now,
                updatedAt = now,
            )
        )
        if (!write(context, current)) return FavoriteAuthorStore.Result.FAILED
        FavoriteAuthorStore.Result.ADDED
    }

    fun remove(context: Context, key: String): Boolean = synchronized(lock) {
        val current = load(context).toMutableList()
        val removed = current.removeAll { it.key == key }
        if (!removed) return false
        write(context, current)
    }

    /** 清空内存缓存，下一次读取会重新解析文件 */
    fun invalidate() = synchronized(lock) {
        cache = null
        cachePath = ""
        cacheModified = -1L
    }

    private fun load(context: Context): List<FavoriteAuthor> {
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

    private fun read(file: File?): List<FavoriteAuthor>? {
        if (file == null || !file.exists() || file.length() <= 0L) return null
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val array = root.optJSONArray("authors") ?: JSONArray()
            buildList {
                for (i in 0 until array.length()) {
                    val author = array.optJSONObject(i)?.let { FavoriteAuthor.fromJson(it) }
                    if (author != null) add(author)
                }
            }
        }.getOrNull()
    }

    private fun write(context: Context, list: List<FavoriteAuthor>): Boolean {
        val target = file(context)
        return runCatching {
            val parent = target.parentFile ?: return false
            if (!parent.exists() && !parent.mkdirs()) return false

            val json = JSONObject()
                .put("version", 1)
                .put("updatedAt", System.currentTimeMillis())
                .put("authors", JSONArray().apply {
                    list.forEach { put(it.toJson()) }
                })

            // 先写临时文件，落盘后再改名，避免中途失败留下半截 JSON
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
