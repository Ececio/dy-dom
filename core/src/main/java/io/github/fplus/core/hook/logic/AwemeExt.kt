package io.github.fplus.core.hook.logic

import android.content.Context
import com.freegang.extension.format
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.ugc.aweme.ImageUrlStruct
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Date
import java.util.Locale

private const val MCA_HOST = "https://dmfw.mca.gov.cn"
private const val AREA_LIST_API = "$MCA_HOST/9095/xzqh/getList"
private const val STNAME_API = "$MCA_HOST/9095/stname/listPub"
private const val BOUND_API = "https://geo.datav.aliyun.com/areas_v3/bound/"
private const val PCAS_API =
    "https://raw.githubusercontent.com/modood/Administrative-divisions-of-China/master/dist/pcas-code.json"
private const val PCAS_CACHE_NAME = "freedomplus_pcas_code.json"
private const val PCAS_CACHE_MAX_AGE = 30L * 24 * 60 * 60 * 1000
private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

@Volatile
private var pcasCache: JSONArray? = null

fun Aweme.createDate(): String {
    return Date(this.createTime * 1000).format()
}

/**
 * 视频信息：作者、作品 ID、属地、区划接口、发布时间、IP 属地、POI、时长、分辨率、音乐和描述。
 *
 * 区划/地名/边界/离线库 四个接口并发请求，任意一个失败只会隐藏对应字段，不影响其它内容。
 */
suspend fun Aweme.videoInfoText(context: Context): String = coroutineScope {
    val areaApi = async { areaApiInfo() }
    val stnameApi = async { stnameApiInfo() }
    val boundApi = async { boundApiInfo() }
    val offlineApi = async { offlineAreaInfo(context) }

    val lines = mutableListOf<String>()

    author?.let { user ->
        val name = user.nickname?.takeIf { it.isNotBlank() }
            ?: user.uniqueId?.takeIf { it.isNotBlank() }
            ?: user.shortId?.takeIf { it.isNotBlank() }
        val uid = user.uid?.takeIf { it.isNotBlank() }
        if (!name.isNullOrBlank()) {
            lines += if (uid != null) "作者: $name ($uid)" else "作者: $name"
        }
    }

    aid?.takeIf { it.isNotBlank() }?.let { lines += "作品ID: $it" }
    lines += "视频属地: ${cityInfo()}"

    areaApi.await()?.let { lines += "区划接口: $it" }
    stnameApi.await()?.let { lines += "地名接口: $it" }
    boundApi.await()?.let { lines += "边界接口: $it" }
    offlineApi.await()?.let { lines += "离线库: $it" }

    lines += "发布时间: ${createDate()}"

    ipAttribution?.takeIf { it.isNotBlank() }?.let { lines += "IP属地: $it" }

    poiInfo()?.let { info ->
        info.getTextValue("poiName", "poi_name", "name")?.let { lines += "POI: $it" }

        val addressInfo = info.getFieldValue("addressInfo", "address_info")
        val codes = listOfNotNull(
            addressInfo?.getTextValue("cityCode", "city_code")?.let { "城市码 $it" },
            addressInfo?.getTextValue("adCodeV2", "ad_code_v2", "adCode")?.let { "区县码 $it" },
        ).joinToString(" / ")
        if (codes.isNotBlank()) lines += "POI编码: $codes"
    }

    duration?.takeIf { it > 0 }?.let { lines += "时长: ${formatDuration(it)}" }

    video?.let {
        if (it.width > 0 && it.height > 0) {
            lines += "分辨率: ${it.width}×${it.height}"
        }
    }

    music?.let {
        val title = it.musicName?.takeIf { value -> value.isNotBlank() }
            ?: it.musicTitle?.takeIf { value -> value.isNotBlank() }
        val artist = it.authorName?.takeIf { value -> value.isNotBlank() }
            ?: it.ownerNickName?.takeIf { value -> value.isNotBlank() }
        if (!title.isNullOrBlank()) {
            lines += if (!artist.isNullOrBlank()) "音乐: $title - $artist" else "音乐: $title"
        }
    }

    desc?.takeIf { it.isNotBlank() }?.let {
        lines += "描述: ${it.take(120)}${if (it.length > 120) "..." else ""}"
    }

    lines.joinToString("\n")
}

/**
 * 读取作品自带的 poi_info 地址，优先级:
 * address_info(province/city/district) > poi_name > Aweme.city > ipAttribution。
 */
fun Aweme.cityInfo(): String {
    val info = poiInfo()
    val addressInfo = info?.getFieldValue("addressInfo", "address_info")

    addressInfo?.areaText()?.takeIf { it.isNotBlank() }?.let { return it }
    info?.getTextValue("poiName", "poi_name", "name")?.let { return it }
    getTextValue("city")?.let { return it }
    getTextValue("ipAttribution", "ip_attribution")?.let { return it }

    return "未知"
}

/**
 * 【1】民政部 · 行政区划搜索:
 * https://dmfw.mca.gov.cn/9095/xzqh/getList?code=510100&maxLevel=0
 */
suspend fun Aweme.areaApiInfo(): String? {
    val code = areaCode() ?: return null
    return queryAreaDetail(code)
}

/**
 * 【2】民政部 · 地名搜索（标准名 / 拼音 / 坐标）:
 * https://dmfw.mca.gov.cn/9095/stname/listPub?page=1&size=1&code=510100&stName=成都
 */
private suspend fun Aweme.stnameApiInfo(): String? {
    val code = areaCode() ?: return null
    val addressInfo = poiInfo()?.getFieldValue("addressInfo", "address_info")
    val keyword = addressInfo?.getTextValue("district")
        ?: addressInfo?.getTextValue("city")
        ?: poiName()
        ?: return queryStname(code, null)

    queryStname(code, keyword)?.let { return it }
    return queryStname(code, null)
}

/**
 * 【3】阿里 DataV · 行政边界 GeoJSON，只读取首要素的 properties，不下载完整轮廓:
 * https://geo.datav.aliyun.com/areas_v3/bound/510100.json
 */
private suspend fun Aweme.boundApiInfo(): String? {
    val adcode = areaCode()?.take(6) ?: return null
    return queryBound(adcode)
}

/**
 * 【4】开源数据集 · 省市区街道四级（落盘缓存，30 天有效）:
 * https://raw.githubusercontent.com/modood/Administrative-divisions-of-China/master/dist/pcas-code.json
 */
private suspend fun Aweme.offlineAreaInfo(context: Context): String? {
    val code = areaCode()?.take(6)
    val names = addressInfoNames()
    if (code == null && names.isEmpty()) return null

    val root = loadPcas(context) ?: return null

    val match = findPcasNodeByCode(root, code, emptyList())
        ?: findPcasNodeByName(root, names)
    val path = (match?.path
        ?: findPcasByNameDeep(root, names.lastOrNull())
        ?: return null).toMutableList()

    // 数据集只到区县时，尝试用 POI 名称补一个街道级
    val poi = poiName()
    if (poi != null) {
        findPcasChild(match?.node?.optJSONArray("children"), poi)?.let { path += it.optString("name") }
    }

    return path.joinToString(" / ")
}

private fun Aweme.poiInfo(): Any? = getFieldValue("poiInfo", "poi_info")

private fun Aweme.poiName(): String? {
    return poiInfo()?.getTextValue("poiName", "poi_name", "name")
}

private fun Aweme.areaCode(): String? {
    val addressInfo = poiInfo()?.getFieldValue("addressInfo", "address_info")
    return addressInfo?.getTextValue("cityCode", "city_code").asAreaCode()
        ?: addressInfo?.getTextValue("adCodeV2", "ad_code_v2", "adCode").asAreaCode()
        ?: getTextValue("city").asAreaCode()
}

private fun Aweme.addressInfoNames(): List<String> {
    val addressInfo = poiInfo()?.getFieldValue("addressInfo", "address_info") ?: return emptyList()
    return listOf("province", "city", "district")
        .mapNotNull { addressInfo.getTextValue(it) }
        .distinct()
}

private fun Any.areaText(): String {
    return listOf("province", "city", "district")
        .mapNotNull { getTextValue(it) }
        .distinct()
        .joinToString(" ")
}

private fun String?.asAreaCode(): String? {
    val value = this?.trim() ?: return null
    return value.takeIf { it.length >= 6 && it.all(Char::isDigit) }
}

private suspend fun queryAreaDetail(code: String): String? = withContext(Dispatchers.IO) {
    try {
        val url = "$AREA_LIST_API?code=${encode(code)}&maxLevel=0"
        val json = JSONObject(httpGet(url))
        if (json.optInt("status", 0) != 200) {
            null
        } else {
            json.optJSONObject("data")?.let { data ->
                val name = data.optString("name").takeIf { it.isNotBlank() } ?: return@let null
                val type = data.optString("type").takeIf { it.isNotBlank() }
                val level = data.optInt("level", 0).takeIf { it > 0 }
                listOfNotNull(name, type, level?.let { "level $it" }).joinToString(" · ")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private suspend fun queryStname(code: String, keyword: String?): String? = withContext(Dispatchers.IO) {
    try {
        val url = "$STNAME_API?page=1&size=1&code=${encode(code)}&stName=${encode(keyword.orEmpty())}"
        val json = JSONObject(httpGet(url))
        val record = json.optJSONArray("records")?.optJSONObject(0) ?: return@withContext null
        val name = record.optString("standard_name").takeIf { it.isNotBlank() } ?: return@withContext null
        val type = record.optString("place_type").takeIf { it.isNotBlank() }
        val region = listOf("province_name", "city_name", "area_name")
            .mapNotNull { key -> record.optString(key).takeIf { it.isNotBlank() } }
            .distinct()
            .joinToString(" ")
        val spelling = record.optString("roman_alphabet_spelling").trim().takeIf { it.isNotBlank() }
        val center = record.optJSONObject("gdm")
            ?.optJSONArray("coordinates")
            ?.optJSONArray(0)
            ?.takeIf { it.length() >= 2 }
            ?.let { String.format(Locale.ROOT, "%.4f,%.4f", it.optDouble(0), it.optDouble(1)) }

        listOfNotNull(
            name,
            type,
            region.takeIf { it.isNotBlank() },
            spelling,
            center?.let { "坐标 $it" },
        ).joinToString(" · ")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private suspend fun queryBound(adcode: String): String? = withContext(Dispatchers.IO) {
    try {
        val text = try {
            httpGet("$BOUND_API$adcode.json", maxBytes = 512 * 1024)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 个别 adcode 只有 _full 版本
            httpGet("$BOUND_API${adcode}_full.json", maxBytes = 512 * 1024)
        }
        val props = extractJsonObject(text, "properties")?.let { JSONObject(it) } ?: return@withContext null

        val name = props.optString("name").takeIf { it.isNotBlank() } ?: return@withContext null
        val code = props.opt("adcode")?.toString()?.takeIf { it.isNotBlank() }
        val center = props.optJSONArray("center")
            ?.takeIf { it.length() >= 2 }
            ?.let { String.format(Locale.ROOT, "%.4f,%.4f", it.optDouble(0), it.optDouble(1)) }
        val level = props.optString("level").takeIf { it.isNotBlank() }
        val parent = props.optJSONObject("parent")?.opt("adcode")?.toString()?.takeIf { it.isNotBlank() }

        listOfNotNull(
            name,
            code?.let { "adcode $it" },
            center?.let { "中心 $it" },
            level,
            parent?.let { "上级 $it" },
        ).joinToString(" · ")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private suspend fun loadPcas(context: Context): JSONArray? = withContext(Dispatchers.IO) {
    pcasCache?.let { return@withContext it }

    val cacheFile = File(context.cacheDir, PCAS_CACHE_NAME)
    try {
        val fresh = cacheFile.exists() && cacheFile.length() > 0 &&
            System.currentTimeMillis() - cacheFile.lastModified() < PCAS_CACHE_MAX_AGE

        val text = if (fresh) {
            cacheFile.readText()
        } else {
            httpGet(
                url = PCAS_API,
                maxBytes = 8 * 1024 * 1024,
                connectTimeout = 5000,
                readTimeout = 10000,
            ).also { data -> runCatching { cacheFile.writeText(data) } }
        }

        if (text.isBlank()) return@withContext null
        JSONArray(text).also { pcasCache = it }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 下载失败时尽量读取过期缓存，保证离线可用
        try {
            if (cacheFile.exists() && cacheFile.length() > 0) {
                JSONArray(cacheFile.readText()).also { pcasCache = it }
            } else {
                null
            }
        } catch (ignored: Exception) {
            null
        }
    }
}

private data class PcasMatch(val node: JSONObject, val path: List<String>)

/**
 * modood 数据集的层级编码为 2 位省 / 4 位市 / 6 位区县 / 9 位街道，
 * 这里把作品的 6 位（或 12 位）行政区划代码换算成对应层级的前缀。
 */
private fun pcasCodeCandidates(code: String): Set<String> {
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

private fun findPcasNodeByCode(
    nodes: JSONArray?,
    code: String?,
    path: List<String>,
): PcasMatch? {
    if (nodes == null || code.isNullOrBlank()) return null
    val candidates = pcasCodeCandidates(code)

    for (i in 0 until nodes.length()) {
        val node = nodes.optJSONObject(i) ?: continue
        val nodePath = path + node.optString("name")
        if (node.optString("code") in candidates) {
            return PcasMatch(node, nodePath)
        }
        findPcasNodeByCode(node.optJSONArray("children"), code, nodePath)?.let { return it }
    }
    return null
}

private fun findPcasNodeByName(nodes: JSONArray?, names: List<String>): PcasMatch? {
    if (nodes == null || names.isEmpty()) return null

    var current: JSONArray? = nodes
    var last: PcasMatch? = null
    val path = mutableListOf<String>()
    for (name in names) {
        val node = findPcasChild(current, name) ?: break
        path += node.optString("name")
        last = PcasMatch(node, path.toList())
        current = node.optJSONArray("children")
    }
    return last
}

private fun findPcasByNameDeep(
    nodes: JSONArray?,
    name: String?,
    path: List<String> = emptyList(),
): List<String>? {
    if (nodes == null || name.isNullOrBlank()) return null
    val target = normalizeAreaName(name)

    for (i in 0 until nodes.length()) {
        val node = nodes.optJSONObject(i) ?: continue
        val nodePath = path + node.optString("name")
        if (normalizeAreaName(node.optString("name")) == target) return nodePath
        findPcasByNameDeep(node.optJSONArray("children"), name, nodePath)?.let { return it }
    }
    return null
}

private fun findPcasChild(nodes: JSONArray?, name: String): JSONObject? {
    if (nodes == null) return null
    val target = normalizeAreaName(name)
    for (i in 0 until nodes.length()) {
        val node = nodes.optJSONObject(i) ?: continue
        if (normalizeAreaName(node.optString("name")) == target) return node
    }
    return null
}

private fun normalizeAreaName(name: String): String {
    var value = name.trim()
    for (suffix in listOf("特别行政区", "自治区", "自治州", "自治县", "街道", "省", "市", "区", "县", "旗")) {
        if (value.length > suffix.length && value.endsWith(suffix)) {
            value = value.removeSuffix(suffix)
            break
        }
    }
    return value
}

/**
 * 从 JSON 文本中截取指定 key 对应的对象，允许文本被截断（边界接口只取前半段）。
 */
private fun extractJsonObject(text: String, key: String): String? {
    val keyIndex = text.indexOf("\"$key\"")
    if (keyIndex < 0) return null

    val start = text.indexOf('{', keyIndex)
    if (start < 0) return null

    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val char = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
        } else {
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
    }
    return null
}

private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun httpGet(
    url: String,
    cookie: String? = null,
    allowRetry: Boolean = true,
    maxBytes: Int = 256 * 1024,
    connectTimeout: Int = 8000,
    readTimeout: Int = 8000,
): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        this.connectTimeout = connectTimeout
        this.readTimeout = readTimeout
        setRequestProperty("User-Agent", BROWSER_UA)
        setRequestProperty("Accept", "application/json, text/plain, */*")
        setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        setRequestProperty("Referer", "https://dmfw.mca.gov.cn/")
        if (!cookie.isNullOrBlank()) {
            setRequestProperty("Cookie", cookie)
        }
    }

    return try {
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_FORBIDDEN && allowRetry) {
            val wafCookie = connection.headerFields
                .entries
                .firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                ?.value
                ?.firstOrNull { it.startsWith("https_waf_cookie=") }
                ?.substringBefore(';')

            connection.disconnect()
            if (!wafCookie.isNullOrBlank()) {
                return httpGet(url, wafCookie, false, maxBytes, connectTimeout, readTimeout)
            }
        }

        if (responseCode !in 200..299) {
            error("HTTP $responseCode")
        }

        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        connection.inputStream.use { stream ->
            while (output.size() < maxBytes) {
                val read = stream.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, minOf(read, maxBytes - output.size()))
            }
        }
        String(output.toByteArray(), Charsets.UTF_8)
    } finally {
        connection.disconnect()
    }
}

private fun formatDuration(milliseconds: Int): String {
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

private fun Any.getFieldValue(vararg names: String): Any? {
    var type: Class<*>? = javaClass
    while (type != null) {
        val current = type
        for (name in names) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull() ?: continue
            val value = runCatching {
                field.isAccessible = true
                field.get(this)
            }.getOrNull()
            if (value != null) return value
        }
        type = current.superclass
    }
    return null
}

private fun Any.getTextValue(vararg names: String): String? {
    return getFieldValue(*names)?.toString()?.takeIf { it.isNotBlank() }
}

fun Aweme.getH265VideoUrlList(): List<String> {
    val video = video ?: return emptyList()
    return video.playAddrH265?.urlList ?: emptyList()
}

fun Aweme.getH264VideoUrlList(): List<String> {
    val video = video ?: return emptyList()
    return video.h264PlayAddr?.urlList ?: emptyList()
}

fun Aweme.getAutoVideoUrlList(): List<String> {
    val video = video ?: return emptyList()
    return video.playAddr?.urlList ?: emptyList()
}

fun Aweme.getImageUrlList(): List<ImageUrlStruct> {
    return images ?: return emptyList()
}

fun Aweme.getMusicUrlList(): List<String> {
    val music = music ?: return emptyList()
    return music.playUrl?.urlList ?: emptyList()
}

fun Aweme.sortString(): String {
    val desc = "$desc".replace(Regex("\\s"), "")
    return "awemeType=$awemeType, desc=$desc"
}
