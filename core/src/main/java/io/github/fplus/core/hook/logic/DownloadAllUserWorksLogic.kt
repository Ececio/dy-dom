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
    private var lastTotal: Int = 0

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

        XplerLog.d(
            "DownloadAllWorks 作者: nickname=$nickname, uniqueId=${user.uniqueId}, shortId=${user.shortId}, " +
                    "secUid=$secUid, uid=$uid, awemeCount=$awemeCount"
        )

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
            append("\n作品数: ").append(if (awemeCount > 0) awemeCount else "未知（开始后自动统计）")
            append("\n视频保存到: ").append(videoDir.path)
            append("\n图片保存到: ").append(imageDir.path)
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
        BatchDebug.reset(context)
        BatchDebug.log("开始: nickname=$nickname shortId=$shortId secUid=$secUid uid=$uid awemeCount=$awemeCount")

        if (!UserPostApi.isAvailable()) {
            val reason = UserPostApi.lastError()
            hook.showMessageDialog(
                context = context,
                title = NOTIFY_TITLE,
                content = buildString {
                    append("当前抖音版本未找到作品列表接口，暂时无法使用。")
                    if (!reason.isNullOrBlank()) {
                        append("\n\n原因: ").append(reason)
                    }
                    append("\n\n可把这段提示反馈给开发者，便于适配新版本。")
                },
                singleButton = true,
                confirm = "知道了",
            )
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
            BatchDebug.log("中断: ${e.javaClass.name}: ${e.message}")
            lastTotal = 0
            failed = true
            "下载中断: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
        }

        notifyFinish(notify, summary, finish, failed)

        if (lastTotal == 0) {
            // 一个作品都没拿到：直接弹窗展示调试信息，并带复制按钮，方便反馈
            hook.refresh {
                hook.showMessageDialog(
                    context = context,
                    title = NOTIFY_TITLE,
                    content = summary,
                    singleButton = true,
                    confirm = "知道了",
                    copyText = summary,
                )
            }
        } else {
            hook.showToast(context, summary)
        }
    }

    private suspend fun downloadAll(notify: IProgressNotification): String {
        lastTotal = 0
        var cursor = 0L
        var page = 0
        var index = 0
        var success = 0
        var failed = 0
        var total = 0
        var error: String? = null
        val seen = HashSet<String>()

        while (page < MAX_PAGES) {
            val result = UserPostApi.fetchPage(secUid, uid, cursor, PAGE_SIZE)
            XplerLog.d(
                "DownloadAllWorks 第${page + 1}页: 作品=${result.awemes.size}, " +
                        "hasMore=${result.hasMore}, maxCursor=${result.maxCursor}, error=${result.error}"
            )
            if (result.error != null) {
                error = result.error
                break
            }
            page++
            if (result.awemes.isEmpty()) break

            var added = 0
            for (item in result.awemes) {
                val id = item.aid.orEmpty()
                if (id.isNotBlank() && !seen.add(id)) continue

                added++
                total++
                index++
                if (downloadAweme(item, notify, index)) success++ else failed++
            }

            // 整页都是重复作品说明游标没前进，直接结束，避免空转
            if (added == 0) break
            if (!result.hasMore) break
            // 游标不前进说明接口已到底，避免死循环重复下载
            if (result.maxCursor == cursor) break
            cursor = result.maxCursor
        }

        XplerLog.d("DownloadAllWorks 完成: 页数=$page, 作品=$total, 成功=$success, 失败=$failed, error=$error")

        val summary = buildString {
            append("完成: 成功 ").append(success).append(" 个")
            if (failed > 0) append(", 失败 ").append(failed).append(" 个")
            append("\n拉取 ").append(page).append(" 页, 共 ").append(total).append(" 个作品")
            if (error != null) append("\n").append(error)
        }

        BatchDebug.log("结果: $summary")
        lastTotal = total

        // 一个都没拿到时把调试信息一起弹出来，省得再抓 logcat
        return if (total == 0) {
            buildString {
                append(summary)
                append("\n\n--- 调试信息 ---\n")
                append(BatchDebug.tail())
            }
        } else {
            summary
        }
    }

    private suspend fun downloadAweme(item: Aweme, notify: IProgressNotification, index: Int): Boolean {
        val baseName = DownloadNaming.fileName(nickname, shortId, uid, item.desc)

        val images = item.getImageUrlList()
        XplerLog.d(
            "DownloadAllWorks [$index] aid=${item.aid}, desc=${item.desc?.take(40)}, " +
                    "images=${images.size}, videoUrls=" +
                    "${item.getAutoVideoUrlList().size}"
        )
        if (images.isNotEmpty()) {
            var downloaded = 0
            val dir = imageDir.need()
            images.forEachIndexed { imageIndex, struct ->
                // urlList 可能是 null（部分图文只给 downloadUrlList / maskUrlList），也可能是多条镜像
                val candidates = (struct.urlList.orEmpty() + struct.downloadUrlList.orEmpty())
                    .filter { it.isNotBlank() }
                    .distinct()
                if (candidates.isEmpty()) {
                    XplerLog.d("DownloadAllWorks [$index] 第${imageIndex + 1}张图没有地址, uri=${struct.uri}")
                    BatchDebug.log("第${imageIndex + 1}张图没有地址 uri=${struct.uri}")
                    return@forEachIndexed
                }
                val file = DownloadNaming.uniqueFile(
                    dir,
                    baseName.secureFilename("_${imageIndex + 1}.jpg"),
                )
                var result: File? = null
                for (url in candidates) {
                    val got = downloadFile(url, file, notify, "第${index}个 ${imageIndex}/${images.size} %s%%")
                    if (got != null) {
                        result = got
                        break
                    }
                    file.deleteIfEmpty()
                }
                val saved = result ?: return@forEachIndexed

                // 抖音部分图文是 VVIC/HEIC，相册和电脑都打不开：用抖音内置解码器就地转成 JPEG
                HeifTranscoder.transcodeInPlace(saved)
                KMediaUtils.notifyMediaUpdate(context, saved.absolutePath)
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
        val candidates = urlList.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) {
            XplerLog.d("DownloadAllWorks [$index] 没有可用视频地址, videoCoding=${config.videoCoding}, urls=${urlList.size}")
            return false
        }

        val file = DownloadNaming.uniqueFile(videoDir.need(), baseName.secureFilename(".mp4"))
        var result: File? = null
        for (url in candidates) {
            val got = downloadFile(url, file, notify, "第${index}个 %s%%")
            if (got != null) {
                result = got
                break
            }
            file.deleteIfEmpty()
        }
        val saved = result ?: return false
        XplerLog.d("DownloadAllWorks [$index] 保存成功: ${saved.absolutePath} (${saved.length()} bytes)")
        KMediaUtils.notifyMediaUpdate(context, saved.absolutePath)
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
