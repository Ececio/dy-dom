package io.github.fplus.core.hook.logic

import android.content.Context
import com.freegang.extension.child
import io.github.fplus.core.config.ConfigV1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 离线地区库（省/市/区县/街道四级数据）。
 *
 * 文件放在 `Download/Freedom/region/pcas-code.json`，可以直接替换、备份或手工编辑；
 * 30 天没有更新时会在获取视频信息时自动重新下载，下载失败继续沿用旧文件。
 */
object RegionLibrary {
    /** modood 开源数据集 */
    const val API =
        "https://raw.githubusercontent.com/modood/Administrative-divisions-of-China/master/dist/pcas-code.json"

    const val RELATIVE_PATH = "region/pcas-code.json"

    private const val DIR_NAME = "region"
    private const val FILE_NAME = "pcas-code.json"
    private const val TEMP_NAME = "pcas-code.json.tmp"
    private const val BACKUP_NAME = "pcas-code.json.bak"
    private const val LEGACY_CACHE_NAME = "freedomplus_pcas_code.json"
    private const val MAX_AGE = 30L * 24 * 60 * 60 * 1000
    private const val MAX_BYTES = 8 * 1024 * 1024

    @Volatile
    private var cached: Pair<Long, JSONArray>? = null

    /** 地区库文件状态，供设置页展示 */
    data class Status(
        val path: String,
        val exists: Boolean,
        val bytes: Long,
        val lastModified: Long,
        val provinces: Int,
        val totalNodes: Int,
        val fresh: Boolean,
    )

    fun file(context: Context): File =
        ConfigV1.getFreedomDir(context).child(DIR_NAME).child(FILE_NAME)

    fun status(context: Context): Status {
        val file = file(context)
        val exists = file.exists() && file.length() > 0
        var provinces = 0
        var totalNodes = 0
        if (exists) {
            runCatching { JSONArray(file.readText()) }.onSuccess { root ->
                provinces = root.length()
                totalNodes = countNodes(root)
            }
        }
        return Status(
            path = file.absolutePath,
            exists = exists,
            bytes = if (file.exists()) file.length() else 0L,
            lastModified = if (exists) file.lastModified() else 0L,
            provinces = provinces,
            totalNodes = totalNodes,
            fresh = exists && System.currentTimeMillis() - file.lastModified() < MAX_AGE,
        )
    }

    /**
     * 读取地区库；文件不存在或过期时自动下载。
     * 下载失败时回退到已有的旧文件，完全不可用时返回 null。
     */
    suspend fun load(context: Context): JSONArray? = withContext(Dispatchers.IO) {
        val file = file(context)
        val modified = if (file.exists()) file.lastModified() else 0L
        cached?.takeIf { modified != 0L && it.first == modified }?.let { return@withContext it.second }

        migrateLegacyCache(context)

        var root = readArray(file)
        val fresh = root != null &&
            System.currentTimeMillis() - file.lastModified() < MAX_AGE

        if (!fresh) {
            // 过期或文件损坏时尝试更新，失败则继续使用旧数据
            runCatching { download(context) }
            root = readArray(file) ?: root
        }

        root?.also { cached = file.lastModified() to it }
    }

    /** 手动更新地区库，供设置页按钮调用 */
    suspend fun update(context: Context): Result<Status> = withContext(Dispatchers.IO) {
        try {
            download(context)
            Result.success(status(context))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun download(context: Context) {
        val target = file(context)
        val text = httpGet(
            url = API,
            maxBytes = MAX_BYTES,
            connectTimeout = 8000,
            readTimeout = 20000,
        )
        if (text.isBlank()) error("下载内容为空")
        val root = JSONArray(text)
        if (root.length() == 0) error("地区库没有省级节点")

        val parent = target.parentFile ?: error("地区库目录不可用")
        if (!parent.exists() && !parent.mkdirs()) error("无法创建地区库目录")

        val temp = File(parent, TEMP_NAME)
        try {
            temp.writeText(text)
            if (temp.length() <= 0) error("地区库写入失败")

            if (target.exists()) {
                val backup = File(parent, BACKUP_NAME)
                if (backup.exists()) backup.delete()
                target.renameTo(backup)
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                error("地区库替换失败")
            }
            File(parent, BACKUP_NAME).takeIf { it.exists() }?.delete()
        } finally {
            if (temp.exists()) temp.delete()
        }
        cached = null
    }

    private fun readArray(file: File): JSONArray? {
        if (!file.exists() || file.length() <= 0) return null
        return runCatching { JSONArray(file.readText()) }.getOrNull()
            ?.takeIf { it.length() > 0 }
    }

    /** 旧版本把地区库放在宿主 cacheDir 里，首次使用时搬到可见目录 */
    private fun migrateLegacyCache(context: Context) {
        val target = file(context)
        if (target.exists() && target.length() > 0) return

        val legacy = File(context.cacheDir, LEGACY_CACHE_NAME)
        if (!legacy.exists() || legacy.length() <= 0) return

        runCatching {
            target.parentFile?.let { if (!it.exists()) it.mkdirs() }
            legacy.copyTo(target, overwrite = true)
        }
    }

    private fun countNodes(nodes: JSONArray): Int {
        var count = 0
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            count += 1 + countNodes(node.optJSONArray("children") ?: JSONArray())
        }
        return count
    }

    // ---------------------------------------------------------------- 查询

    data class PcasMatch(val node: JSONObject, val path: List<String>)

    /**
     * modood 数据集的层级编码为 2 位省 / 4 位市 / 6 位区县 / 9 位街道，
     * 这里把作品的 6 位（或 12 位）行政区划代码换算成对应层级的前缀。
     */
    fun codeCandidates(code: String): Set<String> {
        val value = code.trim()
        if (value.length < 6) return setOf(value)

        val result = mutableSetOf(value.take(6))
        if (value.take(6).endsWith("00")) {
            val city = value.take(4)
            result += city
            if (city.endsWith("00")) result += city.take(2)
        }
        return result
    }

    fun findByCode(nodes: JSONArray?, code: String?, path: List<String> = emptyList()): PcasMatch? {
        if (nodes == null || code.isNullOrBlank()) return null
        // 先匹配更长的代码：城市代码 510100 应该命中 5101（成都市），而不是更短的省份 51
        val candidates = codeCandidates(code).sortedByDescending { it.length }

        for (candidate in candidates) {
            findExactCode(nodes, candidate, path)?.let { return it }
        }
        return null
    }

    private fun findExactCode(
        nodes: JSONArray,
        code: String,
        path: List<String>,
    ): PcasMatch? {
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            val nodePath = path + node.optString("name")
            if (node.optString("code") == code) {
                return PcasMatch(node, nodePath)
            }
            val children = node.optJSONArray("children") ?: continue
            findExactCode(children, code, nodePath)?.let { return it }
        }
        return null
    }

    fun findByName(nodes: JSONArray?, names: List<String>): PcasMatch? {
        if (nodes == null || names.isEmpty()) return null

        var current: JSONArray? = nodes
        var last: PcasMatch? = null
        val path = mutableListOf<String>()
        for (name in names) {
            val node = findChild(current, name) ?: break
            path += node.optString("name")
            last = PcasMatch(node, path.toList())
            current = node.optJSONArray("children")
        }
        return last
    }

    fun findByNameDeep(
        nodes: JSONArray?,
        name: String?,
        path: List<String> = emptyList(),
    ): List<String>? {
        if (nodes == null || name.isNullOrBlank()) return null
        val target = normalizeName(name)

        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            val nodePath = path + node.optString("name")
            if (normalizeName(node.optString("name")) == target) return nodePath
            findByNameDeep(node.optJSONArray("children"), name, nodePath)?.let { return it }
        }
        return null
    }

    fun findChild(nodes: JSONArray?, name: String?): JSONObject? {
        if (nodes == null || name.isNullOrBlank()) return null
        val target = normalizeName(name)
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            if (normalizeName(node.optString("name")) == target) return node
        }
        return null
    }

    private fun normalizeName(name: String): String {
        var value = name.trim()
        for (suffix in listOf("特别行政区", "自治区", "自治州", "自治县", "街道", "省", "市", "区", "县", "旗")) {
            if (value.length > suffix.length && value.endsWith(suffix)) {
                value = value.removeSuffix(suffix)
                break
            }
        }
        return value
    }
}
