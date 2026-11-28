package io.github.fplus.core.hook.logic

import android.content.Context
import com.freegang.extension.format
import com.ss.android.ugc.aweme.base.model.UrlModel
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.android.ugc.aweme.feed.model.BitRate
import com.ss.android.ugc.aweme.feed.model.Video
import com.ss.ugc.aweme.ImageUrlStruct
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.ByteArrayOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Date
import java.util.Locale

private const val BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

fun Aweme.createDate(): String {
    return Date(this.createTime * 1000).format()
}

/**
 * 作品网页链接：优先用宿主下发的 shareUrl，没有时用 `douyin.com/video/<aid>` 拼。
 * 两种链接都能在浏览器打开，也能被抖音识别为对应作品。
 */
fun Aweme.videoLink(): String {
    val share = getTextValue("shareUrl", "share_url")?.takeIf { it.startsWith("http") }
    if (!share.isNullOrBlank()) return share
    val id = aid?.takeIf { it.isNotBlank() } ?: return ""
    return "https://www.douyin.com/video/$id"
}

/**
 * 获取当前视频作者的全部字段。
 *
 * 使用运行时反射遍历真实的 User 类（不止 compileOnly 桩类里声明的字段），
 * 空值、空字符串和默认 Object.toString() 会被跳过。
 */
fun Aweme.authorInfoText(): String {
    val user = author ?: return "未知"
    val lines = mutableListOf<String>()
    val seen = mutableSetOf<String>()

    var type: Class<*>? = user.javaClass
    while (type != null && type != Any::class.java) {
        val current = type
        val fields = runCatching { current.declaredFields }.getOrNull()
        if (fields != null) {
            for (field in fields) {
                if (field.isSynthetic || Modifier.isStatic(field.modifiers)) continue
                if (!seen.add(field.name)) continue

                val value = runCatching {
                    field.isAccessible = true
                    field.get(user)
                }.getOrNull() ?: continue

                val text = value.toInfoText() ?: continue
                lines += "${field.name}: $text"
            }
        }
        type = current.superclass
    }

    return lines.joinToString("\n").ifBlank { "未知" }
}

private fun Any?.toInfoText(): String? {
    if (this == null) return null
    return when (this) {
        is CharSequence -> toString().trim().takeIf { it.isNotEmpty() }
        is Number, is Boolean -> toString()
        is Enum<*> -> name
        is Collection<*> -> asSequence()
            .mapNotNull { it.toInfoText() }
            .take(8)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        is Map<*, *> -> entries.asSequence()
            .mapNotNull { entry -> entry.value?.toInfoText()?.let { text -> "${entry.key}=$text" } }
            .take(8)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        is Array<*> -> asSequence()
            .mapNotNull { it?.toInfoText() }
            .take(8)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }

        else -> {
            if (javaClass.isArray) {
                (0 until minOf(java.lang.reflect.Array.getLength(this), 8))
                    .mapNotNull { index -> java.lang.reflect.Array.get(this, index)?.toInfoText() }
                    .joinToString(", ")
                    .takeIf { it.isNotBlank() }
            } else {
                extractNestedInfo()
            }
        }
    }
}

private val DEFAULT_TO_STRING = Regex("^[\\w.$]+@[0-9a-fA-F]{1,16}$")

private fun Any.extractNestedInfo(): String? {
    val urlList = runCatching {
        javaClass.getDeclaredField("urlList").apply { isAccessible = true }.get(this)
    }.getOrNull()

    if (urlList is Collection<*>) {
        urlList.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
    }

    for (name in listOf("url", "uri")) {
        val text = runCatching {
            javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this)?.toString()
        }.getOrNull()
        if (!text.isNullOrBlank()) return text
    }

    val text = toString()
    if (DEFAULT_TO_STRING.matches(text)) return null
    return text.take(300)
}

/**
 * 视频信息：作者、作品 ID、属地、区划接口、发布时间、IP 属地、POI、时长、分辨率、音乐、描述，
 * 视频链接放在最后，方便直接选中复制。
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

    video?.videoUrlModels()?.firstOrNull { it.width > 0 && it.height > 0 }?.let {
        lines += "分辨率: ${it.width}×${it.height}"
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

    videoLink().takeIf { it.isNotBlank() }?.let { lines += "视频链接: $it" }

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
    return VideoInfoApi.testArea(code).takeIf { it.ok }?.detail
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

    val withKeyword = VideoInfoApi.testStname(code, keyword)
    if (withKeyword.ok) return withKeyword.detail
    if (keyword == null) return null
    return VideoInfoApi.testStname(code, null).takeIf { it.ok }?.detail
}

/**
 * 【3】阿里 DataV · 行政边界 GeoJSON，只读取首要素的 properties，不下载完整轮廓:
 * https://geo.datav.aliyun.com/areas_v3/bound/510100.json
 */
private suspend fun Aweme.boundApiInfo(): String? {
    val adcode = areaCode()?.take(6) ?: return null
    return VideoInfoApi.testBound(adcode).takeIf { it.ok }?.detail
}

/**
 * 【4】开源数据集 · 省市区街道四级（落盘在 Download/Freedom/region，30 天有效）:
 * https://raw.githubusercontent.com/modood/Administrative-divisions-of-China/master/dist/pcas-code.json
 */
private suspend fun Aweme.offlineAreaInfo(context: Context): String? {
    val code = areaCode()?.take(6)
    val names = addressInfoNames()
    if (code == null && names.isEmpty()) return null

    return VideoInfoApi.testOffline(
        context = context,
        code = code,
        names = names,
        poi = poiName(),
    ).takeIf { it.ok }?.detail
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


internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

internal fun httpGet(
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
        // 民政部接口有 WAF，需要来源页；其它域名带上它会 403（如阿里 DataV 的边界接口）
        if (url.contains("mca.gov.cn")) {
            setRequestProperty("Referer", "https://dmfw.mca.gov.cn/")
        }
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

/*
 * 40.6.0 起 Video 的播放地址/码率字段全部被混淆（playAddr -> a、playAddrH265 -> b、
 * bitRate -> y 之类），直接按字段名访问会抛 NoSuchFieldError。
 *
 * 这里改成运行时反射：先按类型找出所有 UrlModel 字段，再按 urlKey 里的 h264/bytevc1
 * 标记区分编码；码率档位则找出元素类型为 BitRate 的 List 字段。字段名再变也不影响。
 */
private val videoUrlModelFields: List<Field> by lazy {
    Video::class.java.declaredFields
        .filter { UrlModel::class.java.isAssignableFrom(it.type) }
        .onEach { runCatching { it.isAccessible = true } }
}

private val videoListFields: List<Field> by lazy {
    Video::class.java.declaredFields
        .filter { List::class.java.isAssignableFrom(it.type) }
        .onEach { runCatching { it.isAccessible = true } }
}

private fun Video.videoUrlModels(): List<UrlModel> {
    val all = videoUrlModelFields.mapNotNull { field ->
        runCatching { field.get(this) as? UrlModel }.getOrNull()
    }.filter { model -> model.urlList.orEmpty().any { it.isNotBlank() } }

    val playable = all.filter { model ->
        model.urlList.orEmpty().any { url ->
            url.contains("/video/tos/") || url.contains("douyinvod")
        }
    }
    // 兜底：排除封面图（douyinpic）后剩下的地址
    return playable.ifEmpty {
        all.filter { model -> model.urlList.orEmpty().none { it.contains("douyinpic") } }
    }
}

private fun Video.videoUrlModelsByKey(vararg markers: String): List<UrlModel> {
    return videoUrlModels().filter { model ->
        val key = model.urlKey.orEmpty()
        markers.any { key.contains(it, ignoreCase = true) }
    }
}

private fun List<UrlModel>.flatUrls(): List<String> {
    return flatMap { it.urlList.orEmpty() }.filter { it.isNotBlank() }
}

/** 视频的码率档位列表（BitRate.playAddr / playAddr265 字段名在 40.6.0 未被混淆） */
fun Video.gears(): List<BitRate> {
    for (field in videoListFields) {
        val list = runCatching { field.get(this) as? List<*> }.getOrNull() ?: continue
        if (list.firstOrNull() is BitRate) return list.filterIsInstance<BitRate>()
    }
    return emptyList()
}

fun Video.h265UrlList(): List<String> {
    return videoUrlModelsByKey("bytevc1", "h265").flatUrls()
}

fun Video.h264UrlList(): List<String> {
    return videoUrlModelsByKey("h264").flatUrls()
}

fun Video.autoUrlList(): List<String> {
    val models = videoUrlModels()
    val preferred = models.firstOrNull { it.urlKey.orEmpty().contains("h264", true) }
        ?: models.firstOrNull()
    return listOfNotNull(preferred).flatUrls()
}

fun Aweme.getH265VideoUrlList(): List<String> = video?.h265UrlList().orEmpty()

fun Aweme.getH264VideoUrlList(): List<String> = video?.h264UrlList().orEmpty()

fun Aweme.getAutoVideoUrlList(): List<String> = video?.autoUrlList().orEmpty()

/** 按编码类型取播放地址(不含清晰度排序) */
fun Aweme.getVideoUrlList(coding: String): List<String> {
    return when (coding) {
        "H265" -> getH265VideoUrlList()
        "H264" -> getH264VideoUrlList()
        else -> getAutoVideoUrlList()
    }
}

/**
 * 视频候选地址，按清晰度从高到低排序。
 *
 * 抖音把不同清晰度档位放在 `video.bitRate[]`（gearName/bitRate/playAddr/playAddr265）里，
 * 播放器使用的 `video.playAddr` 只是其中一个档位，不一定是最高档。
 *
 * 排序不能只看码率：实测同一个作品里 `540_2_2` 的 bitRate 可能比 `720_2_2` 还高，
 * 所以先按 gearName 里的分辨率数字排序，再用码率兜底。
 *
 * best=true 时先按清晰度从高到低取各档位地址，末尾再补上默认地址兜底，
 * 这样某个档位地址失效或缺少 H265 时不会直接下载失败。
 */
fun Aweme.getVideoUrlCandidates(coding: String, best: Boolean): List<String> {
    val video = video ?: return emptyList()
    val result = LinkedHashSet<String>()

    if (best) {
        // 档位按分辨率从高到低；同一档位里优先用所选编码，缺地址时再用另一编码
        // （高分辨率档位的另一种编码，仍然比低分辨率档位清晰）
        val gears = video.gears().sortedWith(gearComparator)
        for (gear in gears) {
            val ordered = when (coding) {
                "H265" -> listOfNotNull(gear.playAddr265, gear.playAddr)
                "H264" -> listOfNotNull(gear.playAddr, gear.playAddr265)
                else -> listOfNotNull(gear.playAddr, gear.playAddr265)
            }
            ordered.forEach { urlModel ->
                urlModel.urlList.orEmpty().filterTo(result) { it.isNotBlank() }
            }
        }
    }

    // 兜底: 先默认档, 再其他编码, 保证有地址可用
    result.addAll(getVideoUrlList(coding).filter { it.isNotBlank() })
    result.addAll(getAutoVideoUrlList().filter { it.isNotBlank() })
    result.addAll(getH265VideoUrlList().filter { it.isNotBlank() })
    result.addAll(getH264VideoUrlList().filter { it.isNotBlank() })
    return result.toList()
}

/** 清晰度信息，用于日志确认是否拿到了最高档位 */
fun Aweme.videoQualityInfo(coding: String, best: Boolean): String {
    val video = video ?: return "video=null"
    val gears = video.gears()
    val top = gears.sortedWith(gearComparator).firstOrNull()
    val size = video.videoUrlModels().firstOrNull { it.width > 0 && it.height > 0 }
    return buildString {
        append("coding=").append(coding)
        append(", best=").append(best)
        append(", size=").append(size?.width ?: 0).append("x").append(size?.height ?: 0)
        append(", gears=").append(gears.size)
        if (top != null) {
            append(", topGear=").append(top.gearName)
            append("(").append(top.bitRate).append("bps")
            append(", type=").append(top.qualityType)
            append(", h265=").append(top.isH265).append(")")
        }
    }
}

/** 从 gearName（如 540_2_2 / normal_720_0）里取分辨率数字，用于清晰度排序 */
private fun BitRate.gearPixels(): Int {
    val name = gearName ?: return 0
    return Regex("(\\d{3,4})").find(name)?.value?.toIntOrNull() ?: 0
}

/** 分辨率高的档位优先；分辨率相同再比码率 */
private val gearComparator: Comparator<BitRate> =
    compareByDescending<BitRate> { gear -> gear.gearPixels() }
        .thenByDescending { gear -> gear.bitRate }

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
