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
 * 处理抖音的 ByteVC1/ByteVC2 编码。
 *
 * 有一部分作品只下发 ByteVC1/ByteVC2 编码（mp4 里的 sample entry 是 `bvc1`/`bvc2`，
 * 配置盒是 `bv1C`/`bv2C`）。这是字节自己的编码格式，系统播放器、相册、剪辑软件都解不了，
 * 下载下来就是“有声音、没画面”。
 *
 * 做法：先照常下载第一个（也是最清晰的）候选地址，然后读本地文件头里的
 * `moov/stbl/stsd` 判断真实编码：
 *
 *  - 是 avc1/hvc1 这类通用编码：直接完成，正常作品仍然只发一次下载请求；
 *  - 是 ByteVC：把文件改名成 `.bvc` 备份，再探测剩下的候选地址，优先挑通用编码下载；
 *    成功后删掉备份，全部候选都不可播时把备份恢复成正式文件名，并提示用户。
 *
 * 为什么要“先下载再判断”：抖音的 `api-play.../play` 这类地址，用 Range 请求读头部
 * 和整包下载返回的编码可能不一样（实测探测报 bvc2、下载下来却是 hvc1），
 * 只靠探测会误判。
 */
object VideoCodec {
    /** stsd 一般在文件最前面几 KB，读 256KB 足够覆盖抖音的 faststart 封装 */
    private const val SNIFF_BYTES = 256 * 1024

    /** 最多探测多少个候选地址，避免极端情况下请求过多 */
    private const val MAX_PROBE = 8

    /** 备份文件之外的候选地址最多再整包下载几次，避免反复下载大文件 */
    private const val MAX_EXTRA_DOWNLOAD = 3

    /** ByteVC 文件的临时备份后缀，找到可播版本后会删掉 */
    private const val BACKUP_SUFFIX = ".bvc"

    /** 系统播放器/相册基本都能解的编码 */
    private val PLAYABLE = setOf("avc1", "avc3", "hvc1", "hev1", "av01", "vp09", "mp4v")

    /** ByteVC1 / ByteVC2：只有抖音自己的解码器能解 */
    private val BYTE_VC = setOf("bvc1", "bvc2")

    /** stsd 里的音频 sample entry，扫描时要跳过 */
    private val AUDIO = setOf(
        "mp4a", "ac-3", "ec-3", "Opus", "opus", "alac",
        "samr", "sawb", "twos", "sowt", "lpcm", "fLaC", "dfLa", "enca",
    )

    data class Downloaded(
        /** 保留下来的文件；null 表示一个地址都没下载成功 */
        val file: File?,
        /** 实际保留下来的文件编码；null 表示没探测出来 */
        val codec: String?,
    )

    /** 选中 ByteVC 流：能下载，但部分播放器没有画面 */
    fun isByteVc(codec: String?): Boolean = codec != null && codec in BYTE_VC

    fun isPlayable(codec: String?): Boolean = codec == null || codec in PLAYABLE

    /**
     * 依次尝试候选地址，返回真正保留下来的文件和编码。
     *
     * 第一个候选直接整包下载：正常作品只发一次请求，下完读文件头就结束。
     * 只有第一个下回来是 ByteVC 时，才探测剩下的候选，尽量换一个能播的。
     */
    suspend fun downloadBest(
        urls: List<String>,
        download: suspend (String) -> File?,
    ): Downloaded {
        if (urls.isEmpty()) return Downloaded(null, null)
        BatchDebug.codec("download start, candidates=${urls.size}")

        var fallback: File? = null
        var fallbackCodec: String? = null

        // 第一次直接下载最清晰的那个地址，不做任何探测
        val first = urls.first()
        BatchDebug.codec("download[1/${urls.size}] host=${hostOf(first)} tail=${tailOf(first)}")
        val firstFile = download(first)
        if (firstFile != null) {
            val codec = codecOfFile(firstFile)
            BatchDebug.codec(
                "downloaded[1/${urls.size}] codec=${codec ?: "?"} size=${firstFile.length()} host=${hostOf(first)}"
            )
            if (isPlayable(codec)) return Downloaded(firstFile, codec)
            // 不可播：先留着当兜底，再去后面找通用编码
            fallback = backup(firstFile)
            fallbackCodec = codec
        } else {
            BatchDebug.codec("download[1/${urls.size}] failed host=${hostOf(first)}")
        }

        val rest = urls.drop(1)
        var probed = 0
        var extraDownload = 0
        for (url in rest) {
            if (extraDownload >= MAX_EXTRA_DOWNLOAD) break

            // 剩下的大多是同一作品的其他 CDN / 档位，先用小段请求探一下，能跳过的就不整包下载
            val sniffed = if (probed < MAX_PROBE) {
                probed++
                sniff(url)
            } else {
                null
            }
            if (sniffed != null) {
                BatchDebug.codec(
                    "probe[$probed/${rest.size}] codec=$sniffed host=${hostOf(url)} tail=${tailOf(url)}"
                )
                if (!isPlayable(sniffed)) continue
            }

            // 探测不出编码（例如 CDN 拒绝 Range 请求）时才真的整包下回来验证
            extraDownload++
            BatchDebug.codec("download[extra$extraDownload] host=${hostOf(url)} tail=${tailOf(url)}")
            val got = download(url)
            if (got == null) {
                BatchDebug.codec("download[extra$extraDownload] failed host=${hostOf(url)}")
                continue
            }
            val codec = codecOfFile(got) ?: sniffed
            BatchDebug.codec(
                "downloaded codec=${codec ?: "?"} size=${got.length()} host=${hostOf(url)}"
            )
            if (isPlayable(codec)) {
                fallback?.delete()
                return Downloaded(got, codec)
            }
            if (fallback == null) {
                fallback = backup(got)
                fallbackCodec = codec
            } else {
                got.delete()
            }
        }

        val kept = fallback ?: return Downloaded(null, null)
        BatchDebug.codec("only bytevc available: codec=$fallbackCodec, keep it")
        // 没有通用编码可用，把 ByteVC 文件恢复成正式文件名
        val restored = File(kept.parentFile, kept.name.removeSuffix(BACKUP_SUFFIX))
        return if (kept.renameTo(restored)) Downloaded(restored, fallbackCodec) else Downloaded(kept, fallbackCodec)
    }

    /** 把不可播的文件改名备份，返回备份文件（改名失败返回 null） */
    private fun backup(file: File): File? {
        val target = File(file.parentFile, file.name + BACKUP_SUFFIX)
        return if (file.renameTo(target)) target else null
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
