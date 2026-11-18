package io.github.fplus.core.hook.logic

import com.freegang.extension.pureFileName
import io.github.fplus.core.config.ConfigV1
import java.io.File

/**
 * 下载命名规则。
 *
 * 单个作品下载（[DownloadLogic]）和"下载所有作品"（[DownloadAllUserWorksLogic]）共用这里的实现，
 * 保证两条入口保存出来的目录、文件名完全一致，避免以后改一处忘另一处。
 */
object DownloadNaming {
    /**
     * 账号：uniqueId 为空时退回 shortId。
     */
    fun shortId(uniqueId: String?, shortId: String?): String {
        return if (uniqueId.isNullOrEmpty()) shortId.orEmpty() else uniqueId
    }

    /**
     * 按创作者单独建立文件夹：`昵称(账号)`。
     * 未开启"按创作者分文件夹"时返回空串。
     */
    fun ownerDir(nickname: String?, shortId: String): String {
        if (!ConfigV1.get().ownerDir) return ""
        return "${nickname.orEmpty().pureFileName}(${shortId})"
    }

    /**
     * 不含后缀的文件名：`昵称_账号_账号ID_文案`，文案为空时用时间戳。
     */
    fun fileName(nickname: String?, shortId: String, uid: String?, desc: String?): String {
        val pureNickname = nickname.orEmpty().pureFileName
        val tail = if (desc.isNullOrBlank()) {
            "${System.currentTimeMillis() / 1000}"
        } else {
            desc.pureFileName
        }
        return "${pureNickname}_${shortId}_${uid.orEmpty()}_${tail}"
    }

    /**
     * 同名文件去重：`a.mp4` 已存在时依次尝试 `a_2.mp4`、`a_3.mp4`……
     *
     * 批量下载时不同作品可能同名（文案一模一样，或文案为空落在同一秒），
     * 单个下载覆盖一个文件还能接受，批量覆盖就等同丢作品，所以这里加序号。
     */
    fun uniqueFile(parent: File, fileName: String): File {
        val file = File(parent, fileName)
        if (!file.exists()) return file

        val dot = fileName.lastIndexOf('.')
        val name = if (dot > 0) fileName.substring(0, dot) else fileName
        val suffix = if (dot > 0) fileName.substring(dot) else ""

        var index = 2
        while (index < 1000) {
            val candidate = File(parent, "${name}_${index}${suffix}")
            if (!candidate.exists()) return candidate
            index++
        }
        return file
    }
}
