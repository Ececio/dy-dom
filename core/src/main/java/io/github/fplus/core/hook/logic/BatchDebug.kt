package io.github.fplus.core.hook.logic

import android.content.Context
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

    /** 弹窗里最多展示的行数，避免内容过长 */
    private const val MAX_MEMORY_LINES = 40

    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var file: File? = null
    private val memory = ArrayDeque<String>()

    /** 每次点击「开始下载」时调用，清空上一轮的日志 */
    fun reset(context: Context) {
        synchronized(lock) {
            memory.clear()
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
}
