package io.github.fplus.core.hook.logic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * 下载前探测视频地址的真实编码。
 *
 * 抖音有一部分作品只下发 ByteVC1/ByteVC2 编码（mp4 里的 sample entry 是 `bvc1`/`bvc2`，
 * 配置盒是 `bv1C`/`bv2C`）。这是字节自己的编码格式，系统播放器、相册、剪辑软件都解不了，
 * 下载下来就是“有声音、没画面”。
 *
 * 做法：用 Range 请求只读地址的前 [SNIFF_BYTES] 字节，从 `moov/stbl/stsd` 里取出视频
 * sample entry 的 fourcc；优先挑 `avc1`/`hvc1` 这类通用编码，实在没有才用 ByteVC 流。
 * 只读一小段就断开，不会把整个视频下两遍。
 */
object VideoCodec {
    /** stsd 一般在文件最前面几 KB，读 256KB 足够覆盖抖音的 faststart 封装 */
    private const val SNIFF_BYTES = 256 * 1024

    /** 最多探测多少个候选地址，避免极端情况下请求过多 */
    private const val MAX_PROBE = 8

    /** 最多整包下载几个候选地址（探测没结论时的兜底），避免反复下载大文件 */
    private const val MAX_DOWNLOAD_TRY = 4

    /** 系统播放器/相册基本都能解的编码 */
    private val PLAYABLE = setOf("avc1", "avc3", "hvc1", "hev1", "av01", "vp09", "mp4v")

    /** ByteVC1 / ByteVC2：只有抖音自己的解码器能解 */
    private val BYTE_VC = setOf("bvc1", "bvc2")

    /** stsd 里的音频 sample entry，扫描时要跳过 */
    private val AUDIO = setOf(
        "mp4a", "ac-3", "ec-3", "Opus", "opus", "alac",
        "samr", "sawb", "twos", "sowt", "lpcm", "fLaC", "dfLa", "enca",
    )

    data class Picked(
        /** 重新排过序的候选地址，第一个就是准备下载的 */
        val urls: List<String>,
        /** 第一个地址的编码；null 表示没探测出来 */
        val codec: String?,
        /** 已经探测出是 ByteVC 的地址，下载时直接跳过，避免把同一个放不了的文件下好几遍 */
        val badUrls: Set<String> = emptySet(),
    )

    data class Downloaded(
        val file: File?,
        /** 实际保留下来的文件编码；null 表示没探测出来 */
        val codec: String?,
    )

    /** 选中 ByteVC 流：能下载，但部分播放器没有画面 */
    fun isByteVc(codec: String?): Boolean = codec != null && codec in BYTE_VC

    fun isPlayable(codec: String?): Boolean = codec == null || codec in PLAYABLE

    /**
     * 按清晰度顺序探测候选地址，把第一个能正常播放的地址提到最前面。
     *
     * - 探测失败的地址（超时/非 2xx/不是 mp4）按原顺序保留；
     * - 全是 ByteVC 时仍然返回原列表，只把编码带出去用于提示用户。
     */
    suspend fun pick(urls: List<String>): Picked {
        val first = urls.firstOrNull() ?: return Picked(urls, null)
        BatchDebug.codec("pick start, candidates=${urls.size}")

        var byteVcUrl: String? = null
        var byteVcCodec: String? = null
        var probed = 0
        val badUrls = mutableSetOf<String>()

        for (url in urls) {
            if (probed >= MAX_PROBE) break
            probed++
            val codec = sniff(url)
            BatchDebug.codec(
                "probe[$probed/${urls.size}] codec=${codec ?: "?"} host=${hostOf(url)} tail=${tailOf(url)}"
            )
            if (codec == null) {
                // 探测不出来就当作未知，继续看后面的地址
                continue
            }
            if (codec in PLAYABLE) {
                val reordered = if (url == first) urls else listOf(url) + urls.filter { it != url }
                BatchDebug.codec("pick codec=$codec host=${hostOf(url)}")
                return Picked(reordered, codec, badUrls)
            }
            badUrls += url
            if (byteVcUrl == null) {
                byteVcUrl = url
                byteVcCodec = codec
            }
        }

        if (byteVcUrl != null) {
            BatchDebug.codec("only bytevc available: codec=$byteVcCodec")
            val reordered = if (byteVcUrl == first) urls else listOf(byteVcUrl) + urls.filter { it != byteVcUrl }
            return Picked(reordered, byteVcCodec, badUrls)
        }

        BatchDebug.codec("no codec detected, keep original order")
        return Picked(urls, null, badUrls)
    }

    /**
     * 依次下载候选地址，返回真正保留下来的文件和编码。
     *
     * 兜底逻辑：如果下回来的其实是 ByteVC（例如探测被 CDN 拒绝、没探出来），
     * 先把文件改名存一份，继续试后面的地址；全都不可播时再把 ByteVC 那份恢复成正式文件名。
     */
    suspend fun downloadBest(
        picked: Picked,
        download: suspend (String) -> File?,
    ): Downloaded {
        var fallback: File? = null
        var fallbackCodec: String? = picked.codec
        var attempts = 0

        for (url in picked.urls) {
            if (url in picked.badUrls) continue
            if (attempts >= MAX_DOWNLOAD_TRY) break
            attempts++

            val got = download(url) ?: continue
            // 探测结果优先；没探测出来就用文件头兜底
            val codec = codecOfFile(got) ?: picked.codec
            BatchDebug.codec("downloaded codec=${codec ?: "?"} size=${got.length()}")
            if (isPlayable(codec)) return Downloaded(got, codec)

            BatchDebug.codec("downloaded bytevc: codec=$codec, try next candidate")
            if (fallback == null) {
                val backup = File(got.parentFile, got.name + ".bvc")
                if (got.renameTo(backup)) {
                    fallback = backup
                    fallbackCodec = codec
                }
            } else {
                got.delete()
            }
        }

        fallback?.let { kept ->
            // 没有通用编码可用，把 ByteVC 文件恢复成正式文件名
            val restored = File(kept.parentFile, kept.name.removeSuffix(".bvc"))
            return if (kept.renameTo(restored)) Downloaded(restored, fallbackCodec) else Downloaded(kept, fallbackCodec)
        }

        // 探测结果就是全 ByteVC：为了防止重复下载，直接下第一个（也就是最清晰的那个）
        if (isByteVc(picked.codec)) {
            val url = picked.urls.firstOrNull() ?: return Downloaded(null, null)
            val got = download(url) ?: return Downloaded(null, null)
            return Downloaded(got, picked.codec)
        }

        return Downloaded(null, null)
    }

    /** 读取本地文件头，判断实际编码 */
    fun codecOfFile(file: File): String? = runCatching {
        val data = ByteArray(SNIFF_BYTES)
        var length = 0
        file.inputStream().use { input ->
            while (length < data.size) {
                val read = input.read(data, length, data.size - length)
                if (read <= 0) break
                length += read
            }
        }
        findVideoFourcc(data, length)
    }.getOrNull()

    /** 读取地址前 256KB，返回视频 sample entry 的四字符编码 */
    suspend fun sniff(url: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                useCaches = false
                doInput = true
                setRequestProperty("User-Agent", System.getProperty("http.agent") ?: "okhttp/3.12.13")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Range", "bytes=0-${SNIFF_BYTES - 1}")
                if (this is HttpsURLConnection) trustAll()
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                BatchDebug.codec("sniff http=$code host=${hostOf(url)}")
                return@withContext null
            }

            val data = ByteArray(SNIFF_BYTES)
            var length = 0
            connection.inputStream.use { input ->
                while (length < data.size) {
                    val read = input.read(data, length, data.size - length)
                    if (read <= 0) break
                    length += read
                }
            }
            findVideoFourcc(data, length)
        } catch (e: Exception) {
            BatchDebug.codec("sniff failed ${e.javaClass.simpleName} host=${hostOf(url)}")
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /**
     * 从 mp4 头部找 `stsd` 盒：`stsd` + version/flags(4) + entry_count(4) + entry_size(4) + fourcc(4)。
     * 音频轨通常排在视频轨前面，所以要把 `mp4a` 之类的音频条目跳过。
     */
    private fun findVideoFourcc(data: ByteArray, length: Int): String? {
        var index = 0
        val end = length - 20
        while (index < end) {
            if (data[index] != 's'.code.toByte() ||
                data[index + 1] != 't'.code.toByte() ||
                data[index + 2] != 's'.code.toByte() ||
                data[index + 3] != 'd'.code.toByte()
            ) {
                index++
                continue
            }

            val fourcc = String(data, index + 16, 4, Charsets.US_ASCII)
            if (fourcc.all { it.isLetterOrDigit() || it == ' ' } && fourcc !in AUDIO) {
                return fourcc
            }
            index += 4
        }
        return null
    }

    private fun hostOf(url: String): String = runCatching { URL(url).host }.getOrDefault("?")

    /** 只取路径最后一段，用来区分不同地址，又不把签名参数写进日志 */
    private fun tailOf(url: String): String = runCatching {
        URL(url).path.trimEnd('/').substringAfterLast('/').takeLast(48)
    }.getOrDefault("?")

    private fun HttpsURLConnection.trustAll() {
        runCatching {
            val manager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            sslSocketFactory = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(manager), null)
            }.socketFactory
            hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
    }
}
