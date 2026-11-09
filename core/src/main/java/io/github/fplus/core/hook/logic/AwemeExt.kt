package io.github.fplus.core.hook.logic

import com.freegang.extension.format
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.ugc.aweme.ImageUrlStruct
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Date
import java.util.Locale

private const val AREA_LIST_API = "https://dmfw.mca.gov.cn/9095/xzqh/getList?code="
private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

fun Aweme.createDate(): String {
    return Date(this.createTime * 1000).format()
}

/**
 * 视频信息：作者、作品 ID、属地、发布时间、IP 属地、POI、时长、分辨率、音乐和描述。
 */
suspend fun Aweme.videoInfoText(): String {
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
    areaApiInfo()?.let { lines += "区划接口: $it" }
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

    return lines.joinToString("\n")
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
 * 用作品自带的行政区划代码查询民政部地名信息库，单独作为一个字段展示:
 * https://dmfw.mca.gov.cn/9095/xzqh/getList?code=510100
 */
suspend fun Aweme.areaApiInfo(): String? {
    val info = poiInfo()
    val addressInfo = info?.getFieldValue("addressInfo", "address_info")

    val code = addressInfo?.getTextValue("cityCode", "city_code").asAreaCode()
        ?: addressInfo?.getTextValue("adCodeV2", "ad_code_v2", "adCode").asAreaCode()
        ?: getTextValue("city").asAreaCode()

    return code?.let { queryAreaName(it) }
}

private fun Aweme.poiInfo(): Any? = getFieldValue("poiInfo", "poi_info")

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

private suspend fun queryAreaName(code: String): String? = withContext(Dispatchers.IO) {
    try {
        val url = AREA_LIST_API + URLEncoder.encode(code, "UTF-8")
        val json = JSONObject(httpGet(url))
        if (json.optInt("status", 0) != 200) {
            null
        } else {
            json.optJSONObject("data")
                ?.optString("name")
                ?.takeIf { it.isNotBlank() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

private fun httpGet(url: String, cookie: String? = null, allowRetry: Boolean = true): String {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 8000
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
                return httpGet(url, wafCookie, allowRetry = false)
            }
        }

        if (responseCode !in 200..299) {
            error("HTTP $responseCode")
        }

        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
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
    return "awemeType=${awemeType}, desc=$desc"
}