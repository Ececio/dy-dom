package io.github.fplus.core.hook.logic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 当前直播间信息。
 *
 * livesdkapi 相关类不在模块编译期 classpath 内，因此全部通过反射读取。
 * 取数链路：
 *   LiveOuterService.LIZIZ(false) / MonsterSingleTon.getServiceFromMap(ILiveOuterService, false)
 *     -> ILiveOuterService.getLive()
 *     -> ILiveService.roomService()
 *     -> ILiveRoomService.getCurrentRoom()
 */
object LiveRoomInfo {
    private const val CLS_MONSTER = "com.ss.android.ugc.MonsterSingleTon"
    private const val CLS_LIVE_OUTER = "com.ss.android.ugc.aweme.live.LiveOuterService"
    private const val CLS_ILIVE_OUTER = "com.ss.android.ugc.aweme.live.ILiveOuterService"

    private const val UNKNOWN = "未知"

    fun format(loader: ClassLoader): String {
        return try {
            val room = currentRoom(loader)
            if (room == null) {
                "未获取到直播间信息\n\n可能还没进入直播间，或直播间数据尚未加载完成。"
            } else {
                roomText(room)
            }
        } catch (e: Throwable) {
            "获取直播间信息失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 用已经拿到的 Room 对象格式化，供信息流直播预览使用（不需要先进入直播间）。
     */
    fun formatRoom(room: Any?): String {
        if (room == null) {
            return "未获取到直播间信息\n\n直播间数据尚未加载完成，可以稍后再试。"
        }
        return try {
            roomText(room)
        } catch (e: Throwable) {
            "获取直播间信息失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 从信息流 Aweme 里取直播间数据。
     *
     * Aweme#getLiveRoom() 返回的可能是 FlexModel 包装对象，
     * 真实的 Room 需要再调用一次 getFlexInstance() 才能拿到。
     */
    fun formatFromAweme(aweme: Any?): String {
        return formatRoom(roomFromAweme(aweme))
    }

    private fun roomFromAweme(aweme: Any?): Any? {
        if (aweme == null) return null

        val raw = invokeNoArg(aweme, "getLiveRoom")
            ?: field(aweme, "liveRoom")
            ?: field(aweme, "mRoomFeedCellStruct")?.let { field(it, "rawRoom") }
            ?: return null

        return unwrapFlex(raw)
    }

    private fun unwrapFlex(value: Any?): Any? {
        var current = value ?: return null
        repeat(4) {
            val next = invokeNoArg(current, "getFlexInstance") ?: return current
            if (next === current) return current
            current = next
        }
        return current
    }

    private fun currentRoom(loader: ClassLoader): Any? {
        val outer = liveOuterService(loader) ?: return null
        val live = invokeNoArg(outer, "getLive") ?: invokeNoArg(outer, "LJIIL") ?: return null
        val roomService = invokeNoArg(live, "roomService") ?: return null
        return invokeNoArg(roomService, "getCurrentRoom")
            ?: invokeNoArg(roomService, "getCurrentRoomFromRoomContext")
            ?: invokeNoArg(roomService, "getStreamerRoom")
    }

    private fun liveOuterService(loader: ClassLoader): Any? {
        // 优先走服务容器，避免依赖被混淆的 LiveOuterService.LIZIZ(Z)
        try {
            val service = Class.forName(CLS_ILIVE_OUTER, false, loader)
            val monster = Class.forName(CLS_MONSTER, false, loader)
            val method = monster.declaredMethods.firstOrNull {
                it.name == "getServiceFromMap" &&
                        it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == Class::class.java &&
                        it.parameterTypes[1] == java.lang.Boolean.TYPE
            }
            if (method != null) {
                method.isAccessible = true
                (method.invoke(null, service, false))?.let { return it }
            }
        } catch (_: Throwable) {
        }

        // 兜底：LiveOuterService.LIZIZ(Z) 或 LLIILII()
        try {
            val clazz = Class.forName(CLS_LIVE_OUTER, false, loader)
            for (name in arrayOf("LIZIZ", "LLIILII")) {
                for (method in clazz.declaredMethods) {
                    if (method.name != name) continue
                    if (!java.lang.reflect.Modifier.isStatic(method.modifiers)) continue
                    method.isAccessible = true
                    val result = when (method.parameterTypes.size) {
                        0 -> method.invoke(null)
                        1 -> if (method.parameterTypes[0] == java.lang.Boolean.TYPE) {
                            method.invoke(null, false)
                        } else {
                            null
                        }

                        else -> null
                    }
                    if (result != null) return result
                }
            }
        } catch (_: Throwable) {
        }

        return null
    }

    private fun invokeNoArg(obj: Any, name: String): Any? {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            for (method in clazz.declaredMethods) {
                if (method.name != name || method.parameterTypes.isNotEmpty()) continue
                try {
                    method.isAccessible = true
                    return method.invoke(obj)
                } catch (_: Throwable) {
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun field(obj: Any?, name: String): Any? {
        if (obj == null) return null
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(name)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
            } catch (_: Throwable) {
                return null
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun roomText(room: Any): String {
        val id = field(room, "id") as? Long ?: 0L
        val idStr = (field(room, "idStr") as? String)?.takeIf { it.isNotBlank() } ?: id.toString()
        val title = (field(room, "title") as? String)?.takeIf { it.isNotBlank() } ?: "(无)"
        val status = field(room, "status") as? Int ?: -1
        val ownerUserId = field(room, "ownerUserId") as? Long ?: 0L
        val owner = field(room, "owner")
        val stats = field(room, "stats")
        val userCount = field(room, "userCount") as? Int ?: 0
        val diggCount = field(room, "diggCount") as? Int ?: 0
        val shareUrl = (field(room, "shareUrl") as? String).orEmpty()
        val requestId = (field(room, "mRequestId") as? String).orEmpty()
        val createTime = field(room, "createTime") as? Long ?: 0L
        val startTime = field(room, "startTime") as? Long ?: 0L
        val finishTime = field(room, "finishTime") as? Long ?: 0L
        val audio = field(room, "liveTypeAudio") as? Boolean ?: false

        val nickName = (field(owner, "nickName") as? String).orEmpty()
        val uid = field(owner, "id") as? Long ?: ownerUserId
        val secUid = (field(owner, "secUid") as? String).orEmpty()
        val shortId = field(owner, "shortId") as? Long ?: 0L
        val displayId = (field(owner, "displayId") as? String).orEmpty()
        val webcastId = (field(owner, "webcastId") as? String).orEmpty()
        // 账号: 优先抖音号(displayId), 退回 webcastId / shortId
        val account = when {
            displayId.isNotBlank() -> displayId
            webcastId.isNotBlank() -> webcastId
            shortId > 0L -> shortId.toString()
            else -> "-"
        }

        val totalUser = field(stats, "totalUser") as? Int ?: 0
        val totalUserDesp = (field(stats, "totalUserDesp") as? String).orEmpty()
        val totalUserStr = (field(stats, "totalUserStr") as? String).orEmpty()
        val commentCount = field(stats, "commentCount") as? Int ?: 0
        val followCount = field(stats, "followCount") as? Int ?: 0
        val enterCount = field(stats, "enterCount") as? Int ?: 0
        val giftUvCount = field(stats, "giftUVCount") as? Int ?: 0

        return buildString {
            appendLine("房间ID: $idStr")
            appendLine("标题: $title")
            appendLine("状态: ${statusText(status)}")
            appendLine("主播昵称: ${nickName.ifBlank { UNKNOWN }}")
            appendLine("账号: $account")
            appendLine("UID: ${uid.takeIf { it > 0L } ?: "-"}")
            appendLine("短ID: ${shortId.takeIf { it > 0L } ?: "-"}")
            if (secUid.isNotBlank()) appendLine("sec_uid: $secUid")
            if (webcastId.isNotBlank()) appendLine("webcastId: $webcastId")
            appendLine(
                "在线人数: ${
                    if (totalUserDesp.isNotBlank()) "$totalUserDesp ($totalUser)" else totalUser.toString()
                }"
            )
            if (totalUserStr.isNotBlank()) appendLine("观看人数: $totalUserStr")
            appendLine("进入人数: $enterCount")
            appendLine("房间人数: $userCount")
            appendLine("点赞数: $diggCount")
            appendLine("评论数: $commentCount")
            appendLine("关注数: $followCount")
            appendLine("送礼人数: $giftUvCount")
            appendLine("开播时间: ${timeText(startTime)}")
            if (status != 2 && finishTime > 0L) appendLine("结束时间: ${timeText(finishTime)}")
            appendLine("创建时间: ${timeText(createTime)}")
            appendLine("直播类型: ${if (audio) "语音直播" else "视频直播"}")
            if (shareUrl.isNotBlank()) appendLine("分享链接: $shareUrl")
            if (requestId.isNotBlank()) appendLine("请求ID: $requestId")
        }.trim()
    }

    private fun statusText(status: Int): String {
        return when (status) {
            2 -> "直播中"
            3 -> "暂停"
            4 -> "已结束"
            else -> "$UNKNOWN($status)"
        }
    }

    private fun timeText(value: Long): String {
        if (value <= 0L) return "-"
        val millis = if (value < 100000000000L) value * 1000L else value
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }
}
