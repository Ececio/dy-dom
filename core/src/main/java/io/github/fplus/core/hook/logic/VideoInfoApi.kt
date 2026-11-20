package io.github.fplus.core.hook.logic

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal const val MCA_HOST = "https://dmfw.mca.gov.cn"
internal const val AREA_LIST_API = "$MCA_HOST/9095/xzqh/getList"
internal const val STNAME_API = "$MCA_HOST/9095/stname/listPub"
internal const val BOUND_API = "https://geo.datav.aliyun.com/areas_v3/bound/"

/** 设置页自检默认使用的行政区划代码（成都市武侯区） */
const val DEFAULT_VIDEO_INFO_CODE = "510100"

/**
 * 单个接口的测试结果。
 *
 * [detail] 成功时是接口返回的可读摘要，失败时是失败原因。
 */
data class VideoInfoApiResult(
    val name: String,
    val url: String,
    val ok: Boolean,
    val detail: String,
    val elapsedMs: Long,
)

/**
 * 视频信息用到的 4 个接口，既给长按「视频信息」取数据，也给设置页做连通性自检。
 */
object VideoInfoApi {
    suspend fun testArea(code: String): VideoInfoApiResult {
        val value = code.trim()
        val url = "$AREA_LIST_API?code=${encode(value)}&maxLevel=0"
        return runTest("区划接口", url) {
            if (value.isBlank()) error("区划代码为空")
            val json = jsonObjectOf(httpGet(url))
            val status = json.optInt("status", 0)
            if (status != 200) error("接口状态 $status")
            val data = json.optJSONObject("data") ?: error("返回内容没有 data 字段")
            val name = data.optText("name") ?: error("返回内容没有 name 字段")
            val type = data.optText("type")
            val level = data.optInt("level", 0).takeIf { it > 0 }
            listOfNotNull(name, type, level?.let { "level $it" }).joinToString(" · ")
        }
    }

    suspend fun testStname(code: String, keyword: String? = null): VideoInfoApiResult {
        val value = code.trim()
        val url = "$STNAME_API?page=1&size=1&code=${encode(value)}&stName=${encode(keyword.orEmpty())}"
        return runTest("地名接口", url) {
            if (value.isBlank()) error("区划代码为空")
            val json = jsonObjectOf(httpGet(url))
            val record = json.optJSONArray("records")?.optJSONObject(0)
                ?: error("没有查询到地名记录")
            val name = record.optText("standard_name")
                ?: error("记录缺少 standard_name")
            val type = record.optText("place_type")
            val region = listOf("province_name", "city_name", "area_name")
                .mapNotNull { key -> record.optText(key) }
                .distinct()
                .joinToString(" ")
            val spelling = record.optText("roman_alphabet_spelling")
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
        }
    }

    suspend fun testBound(code: String): VideoInfoApiResult {
        val adcode = code.trim().take(6)
        val url = "$BOUND_API$adcode.json"
        return runTest("边界接口", url) {
            if (adcode.isBlank()) error("区划代码为空")
            val text = try {
                httpGet(url, maxBytes = 512 * 1024)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 个别 adcode 只有 _full 版本
                httpGet("$BOUND_API${adcode}_full.json", maxBytes = 512 * 1024)
            }
            val props = extractJsonObject(text, "properties")?.let { JSONObject(it) }
                ?: error("返回内容没有 properties")
            val name = props.optText("name")
                ?: error("properties 缺少 name")
            val codeValue = props.optText("adcode")
            val center = props.optJSONArray("center")
                ?.takeIf { it.length() >= 2 }
                ?.let { String.format(Locale.ROOT, "%.4f,%.4f", it.optDouble(0), it.optDouble(1)) }
            val level = props.optText("level")
            val parent = props.optJSONObject("parent")?.optText("adcode")

            listOfNotNull(
                name,
                codeValue?.let { "adcode $it" },
                center?.let { "中心 $it" },
                level,
                parent?.let { "上级 $it" },
            ).joinToString(" · ")
        }
    }

    /**
     * 离线地区库查询；数据来自 [RegionLibrary] 落盘的文件。
     *
     * [names] 是作品 poi_info 里的省/市/区县名，用代码查不到时会退回按名称匹配；
     * [poi] 用于在数据集只到区县时补一个街道级。
     */
    suspend fun testOffline(
        context: Context,
        code: String? = null,
        names: List<String> = emptyList(),
        poi: String? = null,
    ): VideoInfoApiResult {
        val value = code?.trim()?.takeIf { it.isNotBlank() }
        val url = RegionLibrary.file(context).absolutePath
        return runTest("离线地区库", url) {
            if (value == null && names.isEmpty()) error("没有可查询的地区代码或地名")
            val root: JSONArray = RegionLibrary.load(context)
                ?: error("地区库不可用，请先更新地区库文件")

            val match = RegionLibrary.findByCode(root, value)
                ?: RegionLibrary.findByName(root, names)
            val path = (match?.path
                ?: RegionLibrary.findByNameDeep(root, names.lastOrNull())
                ?: error(
                    if (value != null) "地区库中找不到代码 $value"
                    else "地区库中找不到地名 ${names.joinToString("/")}"
                )).toMutableList()

            if (!poi.isNullOrBlank()) {
                RegionLibrary.findChild(match?.node?.optJSONArray("children"), poi)
                    ?.let { path += it.optString("name") }
            }

            path.joinToString(" / ")
        }
    }

    private suspend fun runTest(
        name: String,
        url: String,
        block: suspend () -> String,
    ): VideoInfoApiResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val detail = block()
            VideoInfoApiResult(
                name = name,
                url = url,
                ok = true,
                detail = detail,
                elapsedMs = System.currentTimeMillis() - start,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VideoInfoApiResult(
                name = name,
                url = url,
                ok = false,
                detail = e.message ?: e.javaClass.simpleName,
                elapsedMs = System.currentTimeMillis() - start,
            )
        }
    }

    private fun jsonObjectOf(text: String): JSONObject {
        return try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IllegalStateException("返回内容不是 JSON: ${snippet(text)}")
        }
    }

    /** optString 会把 JSONObject.NULL 变成字符串 "null"，这里统一当成空值 */
    private fun JSONObject.optText(key: String): String? {
        val value = opt(key) ?: return null
        if (value == JSONObject.NULL) return null
        return value.toString().trim().takeIf { it.isNotBlank() && it != "null" }
    }

    private fun snippet(text: String): String {
        val value = text.trim().replace(Regex("\\s+"), " ")
        return if (value.length > 60) value.take(60) + "..." else value
    }
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
