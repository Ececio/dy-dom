package io.github.fplus.core.hook.logic

import android.content.Context
import com.freegang.extension.child
import com.freegang.extension.need
import com.freegang.extension.pureFileName
import com.freegang.extension.secureFilename
import com.freegang.ktutils.app.IProgressNotification
import com.freegang.ktutils.app.KNotifiUtils
import com.freegang.ktutils.media.KMediaUtils
import com.freegang.ktutils.net.KHttpUtils
import com.ss.android.ugc.aweme.feed.model.Aweme
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 下载某个作者的全部作品。
 *
 * 命名规则和单个作品下载完全一致（见 [DownloadNaming]）：
 * 目录 `Freedom/video|picture/昵称(账号)`，文件名 `昵称_账号_账号ID_文案.mp4`（图文为 `..._1.jpg`）。
 */
class DownloadAllUserWorksLogic(
    private val hook: BaseHook,
    private val context: Context,
    private val aweme: Aweme?,
) {
    companion object {
        private const val JOB_NAME = "DownloadAllWorks"
        private const val NOTIFY_TITLE = "下载所有作品"
        private const val NOTIFY_ID = 1001

        /** 每页数量，和抖音主页一致 */
        private const val PAGE_SIZE = 20

        /** 翻页安全上限，接口异常（hasMore 一直为 true）时不至于无限拉取 */
        private const val MAX_PAGES = 200
    }

    private val config get() = ConfigV1.get()

    private var nickname: String = ""
    private var shortId: String = ""
    private var secUid: String? = null
    private var uid: String = ""
    private var awemeCount: Int = 0

    private lateinit var videoDir: File
    private lateinit var imageDir: File

    fun start() {
        val user = aweme?.author
        if (user == null) {
            hook.showToast(context, "未获取到作者信息")
            return
        }

        nickname = user.nickname.orEmpty()
        shortId = DownloadNaming.shortId(user.uniqueId, user.shortId)
        secUid = user.readText("secUid", "sec_uid")
        uid = user.uid.orEmpty()
        awemeCount = user.readInt("awemeCount", "aweme_count") ?: 0

        if (secUid.isNullOrBlank() && uid.isBlank()) {
            hook.showToast(context, "未获取到作者 ID，无法下载")
            return
        }

        val ownerDir = DownloadNaming.ownerDir(nickname, shortId)
        videoDir = ConfigV1.getFreedomDir(context).child("video").child(ownerDir.pureFileName)
        imageDir = ConfigV1.getFreedomDir(context).child("picture").child(ownerDir.pureFileName)

        val content = buildString {
            append("作者: ").append(nickname.ifBlank { shortId })
            if (shortId.isNotBlank()) append(" (").append(shortId).append(")")
            append("\n作品数: ").append(if (awemeCount > 0) awemeCount else "未知")
            append("\n保存目录: ").append(ConfigV1.getFreedomDir(context).path)
            append("\n\n视频保存为 mp4，图文按图片逐张保存；文件名与单个作品下载一致。")
            if (config.isWebDav) append("\n本功能不会自动上传 WebDav。")
        }

        hook.showMessageDialog(
            context = context,
            title = NOTIFY_TITLE,
            content = content,
            confirm = "开始下载",
            onConfirm = { startDownload() },
        )
    }

    private fun startDownload() {
        if (!UserPostApi.isAvailable()) {
            hook.showToast(context, "当前抖音版本未找到作品列表接口，暂时无法使用")
            return
        }

        if (config.notificationDownload) {
            hook.showDownloadNotification(
                context = context,
                notifyId = NOTIFY_ID,
                title = NOTIFY_TITLE,
                inProgressText = "下载中 %d%%",
                listener = { notify ->
                    hook.singleLaunchIO(JOB_NAME) { runBatch(notify, null) }
                },
            )
        } else {
            hook.showProgressDialog(
                context = context,
                title = NOTIFY_TITLE,
                listener = { dismiss, notify ->
                    hook.singleLaunchIO(JOB_NAME) {
                        runBatch(notify) { hook.refresh { dismiss.invoke() } }
                    }
                },
            )
        }
    }

    private suspend fun runBatch(notify: IProgressNotification, finish: (() -> Unit)?) {
        var failed = false
        val summary = try {
            downloadAll(notify)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            XplerLog.e(e)
            failed = true
            "下载中断: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
        }

        notifyFinish(notify, summary, finish, failed)
        hook.showToast(context, summary)
    }

    private suspend fun downloadAll(notify: IProgressNotification): String {
        var cursor = 0L
        var page = 0
        var index = 0
        var success = 0
        var failed = 0
        var error: String? = null
        val seen = HashSet<String>()

        while (page < MAX_PAGES) {
            val result = UserPostApi.fetchPage(secUid, uid, cursor, PAGE_SIZE)
            if (result.error != null) {
                error = result.error
                break
            }
            if (result.awemes.isEmpty()) break

            for (item in result.awemes) {
                val id = item.aid.orEmpty()
                if (id.isNotBlank() && !seen.add(id)) continue

                index++
                if (downloadAweme(item, notify, index)) success++ else failed++
            }

            page++
            if (!result.hasMore) break
            // 游标不前进说明接口已到底，避免死循环重复下载
            if (result.maxCursor == cursor) break
            cursor = result.maxCursor
        }

        return buildString {
            append("完成: 成功 ").append(success).append(" 个")
            if (failed > 0) append(", 失败 ").append(failed).append(" 个")
            if (error != null) append("\n").append(error)
        }
    }

    private suspend fun downloadAweme(item: Aweme, notify: IProgressNotification, index: Int): Boolean {
        val baseName = DownloadNaming.fileName(nickname, shortId, uid, item.desc)

        val images = item.getImageUrlList()
        if (images.isNotEmpty()) {
            var downloaded = 0
            val dir = imageDir.need()
            images.forEachIndexed { imageIndex, struct ->
                val url = struct.urlList.firstOrNull { it.isNotBlank() } ?: return@forEachIndexed
                val file = DownloadNaming.uniqueFile(
                    dir,
                    baseName.secureFilename("_${imageIndex + 1}.jpg"),
                )
                val result = downloadFile(url, file, notify, "第${index}个 ${imageIndex}/${images.size} %s%%")
                if (result == null) {
                    file.deleteIfEmpty()
                    return@forEachIndexed
                }

                // 抖音部分图文是 VVIC/HEIC，相册和电脑都打不开：用抖音内置解码器就地转成 JPEG
                HeifTranscoder.transcodeInPlace(result)
                KMediaUtils.notifyMediaUpdate(context, result.absolutePath)
                downloaded++
            }
            return downloaded > 0
        }

        val urlList = when (config.videoCoding) {
            "H265" -> item.getH265VideoUrlList()
            "H264" -> item.getH264VideoUrlList()
            "Auto" -> item.getAutoVideoUrlList()
            else -> emptyList()
        }
        val url = urlList.firstOrNull { it.isNotBlank() } ?: return false

        val file = DownloadNaming.uniqueFile(videoDir.need(), baseName.secureFilename(".mp4"))
        val result = downloadFile(url, file, notify, "第${index}个 %s%%")
        if (result == null) {
            file.deleteIfEmpty()
            return false
        }

        KMediaUtils.notifyMediaUpdate(context, result.absolutePath)
        return true
    }

    private suspend fun downloadFile(
        url: String,
        file: File,
        notify: IProgressNotification,
        progressText: String,
    ): File? = withContext(Dispatchers.IO) {
        KHttpUtils.download(
            sourceUrl = url,
            file = file,
        ) { real, total, e ->
            if (e != null) XplerLog.e(e)
            if (total > 0) {
                val step = (real * 100 / total).toInt().coerceIn(0, 100)
                if (notify is KNotifiUtils.ProgressNotification) {
                    notify.notifyProgress(step, progressText)
                } else {
                    hook.refresh { notify.notifyProgress(step, progressText) }
                }
            }
        }
    }

    /**
     * 下载失败时删掉 KHttpUtils 建出来的空文件，避免它挡住同名作品的重试（否则会一路加 _2/_3）。
     */
    private fun File.deleteIfEmpty() {
        runCatching { if (exists() && length() == 0L) delete() }
    }

    private fun notifyFinish(
        notify: IProgressNotification,
        message: String,
        finish: (() -> Unit)?,
        failed: Boolean = false,
    ) {
        if (notify is KNotifiUtils.ProgressNotification) {
            if (failed) notify.setFailedText(message) else notify.setFinishedText(message)
            finish?.invoke()
        } else {
            hook.refresh {
                if (failed) notify.setFailedText(message) else notify.setFinishedText(message)
                finish?.invoke()
            }
        }
    }

    private fun Any.readText(vararg names: String): String? {
        return readValue(*names)?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun Any.readInt(vararg names: String): Int? {
        return (readValue(*names) as? Number)?.toInt()
    }

    private fun Any.readValue(vararg names: String): Any? {
        var type: Class<*>? = javaClass
        while (type != null && type != Any::class.java) {
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
}
