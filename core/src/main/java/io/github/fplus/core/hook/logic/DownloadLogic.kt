package io.github.fplus.core.hook.logic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.freegang.extension.child
import com.freegang.extension.need
import com.freegang.extension.pureFileName
import com.freegang.extension.pureName
import com.freegang.extension.secureFilename
import com.freegang.ktutils.app.IProgressNotification
import com.freegang.ktutils.app.KNotifiUtils
import com.freegang.ktutils.app.KToastUtils
import com.freegang.ktutils.media.KMediaUtils
import com.freegang.ktutils.net.KHttpUtils
import com.freegang.ktutils.text.KTextUtils
import com.ss.android.ugc.aweme.feed.model.Aweme
import de.robv.android.xposed.XposedBridge
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.webdav.WebDav
import io.github.xpler.core.XplerLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/// 下载(视频/图文/音乐)逻辑
class DownloadLogic(
    val hook: BaseHook,
    val context: Context,
    val aweme: Aweme?,
) {

    companion object {
        private val config get() = ConfigV1.get()
        private val webdav: WebDav get() = WebDav(config.webDavConfig)
        private var downloadNotifyId = 1
    }

    private lateinit var mShortId: String
    private lateinit var mOwnerDir: String

    private lateinit var mVideoParent: File
    private lateinit var mMusicParent: File
    private lateinit var mImageParent: File
    private lateinit var mPureFileName: String

    init {
        runCatching {
            if (aweme != null) {
                // 整理内容
                // 如果uniqueId为空, shortId为账号
                mShortId = DownloadNaming.shortId(aweme.author.uniqueId, aweme.author.shortId)

                // mOwnerDir: 如果需要按视频创作者单独创建文件夹: `/外置存储器/Download/Freedom/${video|music|picture}/昵称(账号)`
                mOwnerDir = DownloadNaming.ownerDir(aweme.author.nickname, mShortId)

                // 默认下载路径: `/外置存储器/Download/Freedom/video`
                mVideoParent = ConfigV1.getFreedomDir(context).child("video")

                // 默认下载路径: `/外置存储器/Download/Freedom/music`
                mMusicParent = ConfigV1.getFreedomDir(context).child("music")

                // 默认下载路径: `/外置存储器/Download/Freedom/picture`
                mImageParent = ConfigV1.getFreedomDir(context).child("picture")

                // 构建文件名
                mPureFileName = DownloadNaming.fileName(
                    nickname = aweme.author.nickname,
                    shortId = mShortId,
                    uid = aweme.author.uid,
                    desc = aweme.desc,
                    fallbackId = aweme.aid,
                )
                showChoiceDialog(aweme)
            } else {
                hook.showToast(context, "未获取到基本信息")
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 显示下载选择弹层
     * @param aweme
     */
    private fun showChoiceDialog(aweme: Aweme) {
        val urlList = aweme.getImageUrlList()
        val items = mutableListOf("文案", if (urlList.isEmpty()) "视频" else "图片", "背景音乐")
        if (config.isWebDav) {
            items.add(if (urlList.isEmpty()) "视频(WebDav)" else "图片(WebDav)")
            items.add("背景音乐(WebDav)")
        }
        hook.showInputChoiceDialog(
            context = context,
            title = "Freedom+",
            showInput1 = config.ownerDir,
            input1Hint = "创作者: $mOwnerDir",
            input1DefaultValue = mOwnerDir,
            input2Hint = "文件名: $mPureFileName",
            input2DefaultValue = mPureFileName,
            items = items.toTypedArray(),
            onChoice = { _, owner, filename, item, _ ->
                // 如果有重命名
                if (owner.isNotBlank()) mOwnerDir = owner
                mVideoParent = mVideoParent.child(mOwnerDir.pureFileName)
                mMusicParent = mMusicParent.child(mOwnerDir.pureFileName)
                mImageParent = mImageParent.child(mOwnerDir.pureFileName)
                if (filename.isNotBlank()) {
                    val tempFile = File(filename)
                    mVideoParent = mVideoParent.child(tempFile.parentFile?.path ?: "")
                    mMusicParent = mMusicParent.child(tempFile.parentFile?.path ?: "")
                    mImageParent = mImageParent.child(tempFile.parentFile?.path ?: "")
                    mPureFileName = tempFile.pureName
                }
                when (item) {
                    "文案" -> copyDesc(aweme)
                    "视频" -> downloadVideo(aweme)
                    "图片" -> downloadImages(aweme)
                    "背景音乐" -> downloadMusic(aweme)
                    "视频(WebDav)" -> downloadVideo(aweme, true)
                    "图片(WebDav)" -> downloadImages(aweme, true)
                    "背景音乐(WebDav)" -> downloadMusic(aweme, true)
                }
            }
        )
    }

    /**
     * 复制文案
     * @param aweme
     */
    private fun copyDesc(aweme: Aweme) {
        if (KTextUtils.isEmpty(aweme.desc)) {
            KToastUtils.show(context, "文案为空或获取失败")
            return
        }
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("视频文案", aweme.desc))
        KToastUtils.show(context, "文案复制成功")
    }

    /**
     * 下载视频
     * @param aweme
     */
    private fun downloadVideo(aweme: Aweme, isWebDav: Boolean = false) {
        BatchDebug.attach(context)
        val videoUrlList = aweme.getVideoUrlCandidates(config.videoCoding, config.isBestQuality)
        XplerLog.d(
            "Download ${aweme.videoQualityInfo(config.videoCoding, config.isBestQuality)}, " +
                    "candidates=${videoUrlList.size}"
        )

        if (videoUrlList.isEmpty()) {
            hook.showToast(context, "未获取到视频信息")
            return
        }
        // 构建视频文件名
        mPureFileName = mPureFileName.secureFilename(".mp4")
        if (config.notificationDownload) {
            showDownloadByNotification(videoUrlList, mVideoParent, mPureFileName, isWebDav)
        } else {
            showDownloadByDialog(videoUrlList, mVideoParent, mPureFileName, isWebDav)
        }
    }

    /**
     * 下载背景音乐
     * @param aweme
     */
    private fun downloadMusic(aweme: Aweme, isWebDav: Boolean = false) {
        val musicUrlList = aweme.getMusicUrlList()
        if (musicUrlList.isEmpty()) {
            hook.showToast(context, "未获取到背景音乐")
            return
        }
        // 构建视频文件名
        mPureFileName = mPureFileName.secureFilename(".mp3")
        if (config.notificationDownload) {
            showDownloadByNotification(musicUrlList, mMusicParent, mPureFileName, isWebDav)
        } else {
            showDownloadByDialog(musicUrlList, mMusicParent, mPureFileName, isWebDav)
        }
    }

    /**
     * 下载图片
     * @param aweme
     */
    private fun downloadImages(aweme: Aweme, isWebDav: Boolean = false) {
        val structList = aweme.getImageUrlList()
        if (structList.isEmpty()) {
            hook.showToast(context, "未获取到图片信息")
            return
        }

        if (config.notificationDownload) {
            // 发送通知
            hook.showDownloadNotification(
                context = context,
                notifyId = downloadNotifyId++,
                title = mPureFileName,
                listener = {
                    // 下载逻辑
                    hook.singleLaunchIO(mPureFileName) {
                        val imageFiles = mutableListOf<File>()
                        var downloadCount = 0 // 下载计数器
                        structList.forEachIndexed { index, urlStruct ->
                            val resultFile = downloadFile(
                                url = urlStruct.urlList.first(),
                                downloadFile = File(mImageParent.need(), mPureFileName.secureFilename("_${index + 1}.jpg")),
                                notify = it,
                                progressText = "$index/${aweme.images.size} %s%%",
                            )
                            if (resultFile != null) {
                                downloadCount += 1
                                imageFiles.add(resultFile)
                                KMediaUtils.notifyMediaUpdate(context, resultFile.absolutePath)
                            }
                        }

                        hook.refresh {
                            if (downloadCount == aweme.images.size) {
                                val message =
                                    if (isWebDav) "下载成功, 正在上传WebDav!" else "下载成功, 共${downloadCount}个文件!"
                                it.setFinishedText(message)
                                hook.showToast(context, message)
                            } else {
                                val failCount = aweme.images.size - downloadCount
                                it.setFinishedText("下载成功${downloadCount}, 失败${failCount}!")
                                hook.showToast(context, "下载成功${downloadCount}, 失败${failCount}!")
                                Toast.makeText(context, "正在上传WebDav!", Toast.LENGTH_SHORT).show()

                            }
                        }


                        // 上传WebDav
                        if (isWebDav) {
                            if (imageFiles.isEmpty()) {
                                hook.refresh {
                                    it.setFinishedText("上传WebDav失败, 无法找到已下载的内容!")
                                    hook.showToast(context, "上传WebDav失败, 无法找到已下载的内容!")
                                }
                                return@singleLaunchIO
                            }
                            var uploadCount = 0
                            for (image in imageFiles) {
                                val uploadStatus = uploadToWebDav(image)
                                if (uploadStatus) uploadCount += 1
                            }
                            hook.refresh {
                                if (uploadCount == imageFiles.size) {
                                    it.setFinishedText("上传WebDav成功!")
                                    hook.showToast(context, "上传WebDav成功!")
                                } else {
                                    it.setFinishedText("上传WebDav成功${uploadCount}, 失败${imageFiles.size - uploadCount}!")
                                    hook.showToast(
                                        context,
                                        "上传WebDav成功${uploadCount}, 失败${imageFiles.size - uploadCount}!"
                                    )
                                }
                            }
                        }
                    }
                }
            )
        } else {
            // 进度条Dialog
            hook.showProgressDialog(
                context = context,
                title = "Freedom+",
                listener = { dismiss, notify ->
                    // 下载逻辑
                    hook.singleLaunchIO(mPureFileName) {
                        var downloadCount = 0 // 下载计数器
                        structList.forEachIndexed { index, urlStruct ->
                            val resultFile = downloadFile(
                                url = urlStruct.urlList.first(),
                                downloadFile = File(mImageParent.need(), mPureFileName.secureFilename("_${index + 1}.jpg")),
                                notify = notify,
                                progressText = "$index/${aweme.images.size} %s%%",
                            )
                            if (resultFile != null) {
                                downloadCount += 1
                                KMediaUtils.notifyMediaUpdate(context, resultFile.absolutePath)
                            }
                        }

                        hook.refresh {
                            dismiss.invoke()
                            if (downloadCount == aweme.images.size) {
                                val message =
                                    if (isWebDav) "下载成功, 正在上传WebDav!" else "下载成功, 共${downloadCount}个文件!"
                                notify.setFinishedText(message)
                                hook.showToast(context, message)
                            } else {
                                val failCount = aweme.images.size - downloadCount
                                notify.setFinishedText("下载成功${downloadCount}, 失败${failCount}!")
                                hook.showToast(context, "下载成功${downloadCount}, 失败${failCount}!")
                                Toast.makeText(context, "正在上传WebDav!", Toast.LENGTH_SHORT).show()
                            }
                        }

                        // 上传WebDav
                        if (isWebDav) {
                            val images = (mImageParent.listFiles() ?: arrayOf<File>()).filter { it.isFile }
                            if (images.isEmpty()) {
                                hook.showToast(context, "上传WebDav失败, 无法找到已下载的内容!")
                                return@singleLaunchIO
                            }
                            var uploadCount = 0
                            for (image in images) {
                                val uploadStatus = uploadToWebDav(image)
                                if (uploadStatus) uploadCount += 1
                            }
                            if (uploadCount == images.size) {
                                hook.showToast(context, "上传WebDav成功!")
                            } else {
                                hook.showToast(
                                    context,
                                    "上传WebDav成功${uploadCount}, 失败${images.size - uploadCount}!"
                                )
                            }
                        }
                    }
                }
            )
        }
    }

    // 显示下载 Notification
    private fun showDownloadByNotification(
        urlList: List<String>,
        parentPath: File,
        pureFileName: String,
        isWebDav: Boolean = false,
    ) {
        // 发送通知
        hook.showDownloadNotification(
            context = context,
            notifyId = downloadNotifyId++,
            title = pureFileName,
            listener = {
                // 下载逻辑
                hook.singleLaunchIO(pureFileName) {
                    val file = File(parentPath.need(), pureFileName)
                    // 先下最清晰的那个，再读文件头判断编码；不是通用编码才去换候选
                    val done = VideoCodec.downloadBest(urlList) { url ->
                        downloadFile(
                            url = url,
                            downloadFile = file,
                            notify = it,
                            progressText = "下载中 %s%%",
                        ) ?: run {
                            file.deleteIfEmpty()
                            null
                        }
                    }
                    val resultFile = done.file
                    if (resultFile != null) {
                        val message = if (isWebDav) "下载成功, 正在上传WebDav!" else "下载成功!"
                        it.setFinishedText(message)
                        hook.showToast(context, message)
                        if (VideoCodec.isByteVc(done.codec)) {
                            hook.showToast(context, "注意: 该视频为 ByteVC 编码, 部分播放器可能没有画面")
                        }
                        KMediaUtils.notifyMediaUpdate(context, resultFile.absolutePath)

                        // 上传WebDav
                        if (isWebDav) {
                            val uploadStatus = uploadToWebDav(resultFile)
                            it.setFinishedText("上传WebDav${if (uploadStatus) "成功!" else "失败!"}")
                            hook.showToast(context, "上传WebDav${if (uploadStatus) "成功!" else "失败!"}")
                        }
                    } else {
                        it.setFinishedText("下载失败!")
                        hook.showToast(context, "下载失败!")
                    }
                }
            }
        )
    }

    // 显示下载 Dialog
    private fun showDownloadByDialog(
        urlList: List<String>,
        parentPath: File,
        pureFileName: String,
        isWebDav: Boolean = false,
    ) {
        // 进度条Dialog
        hook.showProgressDialog(
            context = context,
            title = "Freedom+",
            listener = { dismiss, notify ->
                // 下载逻辑
                hook.singleLaunchIO(pureFileName) {
                    val file = File(parentPath.need(), pureFileName)
                    // 先下最清晰的那个，再读文件头判断编码；不是通用编码才去换候选
                    val done = VideoCodec.downloadBest(urlList) { url ->
                        downloadFile(
                            url = url,
                            downloadFile = file,
                            notify = notify,
                            progressText = "%s%%",
                        ) ?: run {
                            file.deleteIfEmpty()
                            null
                        }
                    }
                    val resultFile = done.file
                    if (resultFile != null) {
                        hook.refresh { dismiss.invoke() }
                        val message = if (isWebDav) "下载成功, 正在上传WebDav!" else "下载成功!"
                        notify.setFinishedText(message)
                        hook.showToast(context, message)
                        if (VideoCodec.isByteVc(done.codec)) {
                            hook.showToast(context, "注意: 该视频为 ByteVC 编码, 部分播放器可能没有画面")
                        }
                        KMediaUtils.notifyMediaUpdate(context, resultFile.absolutePath)

                        // 上传WebDav
                        if (isWebDav) {
                            val uploadStatus = uploadToWebDav(resultFile)
                            hook.showToast(context, "上传WebDav${if (uploadStatus) "成功!" else "失败!"}")
                        }
                    } else {
                        hook.refresh { dismiss.invoke() }
                        notify.setFinishedText("下载失败!")
                        hook.showToast(context, "下载失败!")
                    }
                }
            }
        )
    }

    /**
     * 下载至本地
     */
    private suspend fun downloadFile(
        url: String,
        downloadFile: File,
        notify: IProgressNotification,
        progressText: String,
    ): File? {
        return withContext(Dispatchers.IO) {
            // 先下到隐藏的 .tmp 目录，成功后再改名到目标路径。
            // 这样既能避免半截文件被当成有效视频，也能绕开直接覆盖时偶发的 EEXIST。
            val tmpDir = File(downloadFile.parentFile, ".tmp")
            if (!tmpDir.exists()) tmpDir.mkdirs()
            val tmp = File(tmpDir, downloadFile.name)
            if (tmp.exists()) tmp.delete()

            val got = KHttpUtils.download(
                sourceUrl = url,
                file = tmp,
            ) { real, total, e ->
                if (e != null) {
                    XplerLog.e(e)
                    BatchDebug.codec("download error ${e.javaClass.simpleName}: ${e.message}")
                }

                if (total > 0) {
                    val step = (real * 100 / total).toInt()
                    if (notify is KNotifiUtils.ProgressNotification) { // 通知栏在ui线程中刷新会造成ui卡顿, 排查了一下午, 麻了
                        notify.notifyProgress(step, progressText)
                    } else {
                        hook.refresh { notify.notifyProgress(step, progressText) }
                    }
                }
            }

            if (got == null) {
                tmp.delete()
                return@withContext null
            }

            val target = if (got.extension.isEmpty()) {
                downloadFile
            } else {
                File(downloadFile.parentFile, downloadFile.nameWithoutExtension + "." + got.extension)
            }
            if (moveTo(got, target)) target else null
        }
    }

    /**
     * 上传至WebDav
     */
    private suspend fun uploadToWebDav(file: File): Boolean {
        return try {
            val directoryName = file.parentFile!!.absolutePath.substringAfter("Freedom")
            webdav.createDirectory(directoryName = directoryName, parentPath = "Freedom", true)
            webdav.put(file = file, "Freedom".plus(directoryName))
            true
        } catch (e: Exception) {
            XposedBridge.log(e)
            false
        }
    }
}

/** 下载失败可能在磁盘上留下 0 字节文件，清理掉再试下一个地址 */
private fun File.deleteIfEmpty() {
    if (exists() && length() <= 0L) delete()
}

/** 把临时文件移动到目标路径；先删同名旧文件，改名失败时退回复制 */
private fun moveTo(source: File, target: File): Boolean {
    if (source.absolutePath == target.absolutePath) return true
    if (target.exists()) target.delete()
    if (source.renameTo(target)) return true
    return runCatching {
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        source.delete()
        true
    }.getOrDefault(false)
}