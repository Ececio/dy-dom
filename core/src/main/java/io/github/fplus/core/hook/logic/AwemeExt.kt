package io.github.fplus.core.hook.logic

import com.freegang.extension.format
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.ugc.aweme.ImageUrlStruct
import java.util.Date

fun Aweme.createDate(): String {
    return Date(this.createTime * 1000).format()
}

/**
 * 读取作品自带的 poi_info，地址优先级:
 * poi_info.address_info(province/city/district) > poi_info.poi_name > Aweme.city > Aweme.ipAttribution
 */
suspend fun Aweme.cityInfo(): String {
    val poiInfo = getFieldValue("poiInfo", "poi_info")
    val addressInfo = poiInfo?.getFieldValue("addressInfo", "address_info")

    if (addressInfo != null) {
        val area = listOf("province", "city", "district")
            .mapNotNull { addressInfo.getTextValue(it) }
            .distinct()
            .joinToString(" ")
        if (area.isNotBlank()) return area
    }

    poiInfo?.getTextValue("poiName", "poi_name", "name")?.let { return it }

    getTextValue("city")?.let { return it }
    getTextValue("ipAttribution", "ip_attribution")?.let { return it }

    return "未知"
}

private fun Any.getFieldValue(vararg names: String): Any? {
    var type: Class<*>? = javaClass
    while (type != null) {
        val current = type
        for (name in names) {
            val field = runCatching { current.getDeclaredField(name) }.getOrNull() ?: continue
            return runCatching {
                field.isAccessible = true
                field.get(this)
            }.getOrNull()
        }
        type = current.superclass
    }
    return null
}

private fun Any.getTextValue(vararg names: String): String? {
    return (getFieldValue(*names) as? String)?.takeIf { it.isNotBlank() }
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