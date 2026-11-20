package io.github.fplus.core.hook.logic

import com.freegang.extension.pureFileName
import io.github.fplus.core.config.ConfigV1

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
}
