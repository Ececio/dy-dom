package io.github.fplus.core.hook.logic

import android.content.Context
import android.os.Environment
import io.github.fplus.core.config.ConfigV1
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「下载所有作品」的调试日志。
 *
 * 为什么要单独写文件：抖音进程的 logcat 会被 VCStrategy 等日志刷屏，缓冲区很快滚掉，
 * XplerLog 基本抓不到。写到 `Download/Freedom/batch_debug.log` 后，不管成不成功都能
 * 用 `adb pull` 取回来，也能直接在弹窗里展示最后几行。
 */
object BatchDebug {
    private const val FILE_NAME = "batch_debug.log"

    /** App 自己发请求的抓包日志，和 batch_debug.log 分开，不会被「开始下载」清空 */
    private const val TAP_FILE_NAME = "app_net_tap.log"

    /** 抓包日志最多保留多大，超过就裁掉前半部分 */
    private const val MAX_TAP_BYTES = 400_000L

    /** 视频编码探测日志，和下载/抓包日志分开，方便排查「下载后没有画面」 */
    private const val CODEC_FILE_NAME = "video_codec.log"

    /** 编码日志最多保留多大，超过就裁掉前半部分 */
    private const val MAX_CODEC_BYTES = 200_000L

    /** 弹窗里最多展示的行数，避免内容过长 */
    private const val MAX_MEMORY_LINES = 40

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var file: File? = null
    private var tapFile: File? = null
    private var codecFile: File? = null
    private val memory = ArrayDeque<String>()

    /** 下载时顺手拿到的宿主 Context，用来在 hook 线程里定位下载目录 */
    private var appContext: Context? = null

    /** 下载入口调用一次即可；拿不到 ActivityThread 时也能正常写日志 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    /** 每次点击「开始下载」时调用，清空上一轮的日志 */
    fun reset(context: Context) {
        synchronized(lock) {
            memory.clear()
            // 未开启调试日志: 不建文件不写内存
            if (!ConfigV1.get().isBatchDebugLog) {
                file = null
                return
            }
            file = runCatching {
                val dir = ConfigV1.getFreedomDir(context)
                if (!dir.exists()) dir.mkdirs()
                File(dir, FILE_NAME).also {
                    val header = "===== " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(Date()) + " ====="
                    it.writeText(header + "\n")
                }
            }.onFailure {
                io.github.xpler.core.XplerLog.e("BatchDebug 初始化失败: ${it.message}")
            }.getOrNull()
        }
    }

    fun log(message: String) {
        if (!ConfigV1.get().isBatchDebugLog) return
        val line = timeFormat.format(Date()) + " " + message
        synchronized(lock) {
            memory.addLast(line)
            while (memory.size > MAX_MEMORY_LINES) memory.removeFirst()
            val target = file ?: return
            runCatching { target.appendText(line + "\n") }
        }
    }

    /** 最近若干行，用于弹窗展示 */
    fun tail(maxLines: Int = MAX_MEMORY_LINES): String {
        synchronized(lock) {
            return memory.toList().takeLast(maxLines).joinToString("\n")
        }
    }

    fun filePath(): String = file?.absolutePath.orEmpty()

    /**
     * App 自己发网络请求时调用的记录，写到 `Download/Freedom/app_net_tap.log`。
     *
     * 和 [log] 分开：这个文件不清空（否则一按「下载所有作品」就把刚抓到的证据洗掉了），
     * 超出 [MAX_TAP_BYTES] 时只裁掉前半部分，保证最新的调用还在。
     */
    fun tap(message: String) {
        // 未开启网络抓包日志: 不建文件不写内存
        if (!ConfigV1.get().isNetTapLog) return
        val line = timeFormat.format(Date()) + " " + message
        synchronized(lock) {
            val target = tapFile ?: openTapFile() ?: return
            runCatching {
                if (target.length() > MAX_TAP_BYTES) {
                    val text = target.readText()
                    target.writeText(text.substring(text.length / 2))
                }
                target.appendText(line + "\n")
            }
        }
    }

    fun tapPath(): String {
        if (!ConfigV1.get().isNetTapLog) return ""
        return tapFile?.absolutePath.orEmpty()
    }

    /**
     * 视频编码探测日志，写到 `Download/Freedom/video_codec.log`。
     *
     * 单独一个文件：单个下载也会写（[log] 只在下拉全部作品时初始化文件），
     * 方便用户反馈「某几个视频没有画面」时直接把日志发过来。
     */
    fun codec(message: String) {
        if (!ConfigV1.get().isBatchDebugLog) return
        val line = timeFormat.format(Date()) + " " + message
        synchronized(lock) {
            val target = codecFile ?: openCodecFile() ?: return
            runCatching {
                if (target.length() > MAX_CODEC_BYTES) {
                    val text = target.readText()
                    target.writeText(text.substring(text.length / 2))
                }
                target.appendText(line + "\n")
            }
        }
    }

    private fun openCodecFile(): File? {
        // 拿不到宿主 Context 时直接按公共下载目录兜底，避免单个下载完全没有日志
        val dir = currentApplication()?.let { ConfigV1.getFreedomDir(it) }
            ?: File(Environment.getExternalStorageDirectory(), "Download/Freedom")
        return runCatching {
            if (!dir.exists()) dir.mkdirs()
            File(dir, CODEC_FILE_NAME).also { codecFile = it }
        }.onFailure {
            io.github.xpler.core.XplerLog.e("BatchDebug 编码日志初始化失败: ${it.message}")
        }.getOrNull()
    }

    private fun openTapFile(): File? {
        val context = currentApplication() ?: return null
        return runCatching {
            val dir = ConfigV1.getFreedomDir(context)
            if (!dir.exists()) dir.mkdirs()
            File(dir, TAP_FILE_NAME).also { tapFile = it }
        }.onFailure {
            io.github.xpler.core.XplerLog.e("BatchDebug 抓包日志初始化失败: ${it.message}")
        }.getOrNull()
    }

    /** 宿主进程的 Application，用于拿下载目录；拿不到就放弃写文件 */
    private fun currentApplication(): Context? {
        appContext?.let { return it }
        return runCatching {
            val clazz = Class.forName("android.app.ActivityThread")
            val thread = clazz.getMethod("currentActivityThread").invoke(null)
            clazz.getMethod("getApplication").invoke(thread) as? Context
        }.getOrNull()
    }
}
