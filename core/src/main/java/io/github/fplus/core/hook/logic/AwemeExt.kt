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
 * poi_info.address_info > poi_info > poi_info.poi_name > Aweme.city > Aweme.ipAttribution
 *
 * address_info 依次输出:
 * province city district city_code ad_code_v2 country country_code
 */
suspend fun Aweme.cityInfo(): String {
    val poiInfo = getFieldValue("poiInfo", "poi_info")
    val addressInfo = poiInfo?.getFieldValue("addressInfo", "address_info")

    addressInfo?.addressText()?.takeIf { it.isNotBlank() }?.let { return it }
    poiInfo?.addressText()?.takeIf { it.isNotBlank() }?.let { return it }

    poiInfo?.getTextValue("poiName", "poi_name", "name")?.let { return it }

    getTextValue("city")?.let { return it }
    getTextValue("ipAttribution", "ip_attribution")?.let { return it }

    return "未知"
}

private val ADDRESS_FIELDS = listOf(
    listOf("province"),
    listOf("city"),
    listOf("district"),
    listOf("cityCode", "city_code"),
    listOf("adCodeV2", "ad_code_v2"),
    listOf("country"),
    listOf("countryCode", "country_code"),
)

private fun Any.addressText(): String {
    return ADDRESS_FIELDS
        .mapNotNull { names -> getTextValue(*names.toTypedArray()) }
        .joinToString(" ")
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