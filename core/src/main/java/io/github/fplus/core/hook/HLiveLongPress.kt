package io.github.fplus.core.hook

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.freegang.extension.screenSize
import com.ss.android.ugc.aweme.live.LivePlayActivity
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.hook.logic.LiveRoomInfo
import io.github.fplus.core.ui.activity.FreedomSettingActivity
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam
import kotlin.math.abs

/**
 * 直播长按（参考长按视频）。
 *
 * LivePlayActivity 整条继承链都没有重写 dispatchTouchEvent，实际走 Activity 的实现，
 * 因此这里 hook android.app.Activity#dispatchTouchEvent，在 before 中观察事件，不消费事件。
 */
class HLiveLongPress : BaseHook() {
    companion object {
        @Volatile
        private var menuDownY = -1f

        @Volatile
        private var menuDownActivity: Activity? = null

        /**
         * 上一次按下是否落在直播间上半部分。
         * 用于屏蔽抖音自带的直播长按菜单，只显示模块自己的窗口。
         */
        fun shouldBlockNativeMenu(): Boolean {
            return ConfigV1.get().isLongPressLive &&
                    menuDownActivity is LivePlayActivity &&
                    menuDownY >= 0f && menuDownY < screenSize.height / 2f
        }
    }

    private val config get() = ConfigV1.get()

    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    // 抖音直播间自身也有长按菜单(底部弹窗), 稍晚一点弹出, 保证我们的窗口在其上方
    private val triggerDelay = longPressTimeout + 250L

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downActivity: Activity? = null
    private var longPressRunnable: Runnable? = null
    private var lastShowTime = 0L

    override fun setTargetClass(): Class<*> {
        return Activity::class.java
    }

    @OnBefore("dispatchTouchEvent")
    fun dispatchTouchEventBefore(params: MethodParam, event: MotionEvent) {
        hookBlockRunning(params) {
            val activity = thisObject as? Activity ?: return@hookBlockRunning

            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                cancelLongPress()
            }

            if (activity !is LivePlayActivity) return@hookBlockRunning
            if (!config.isLongPressLive) return@hookBlockRunning

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 记录按下位置, 用于屏蔽抖音自带的直播长按菜单
                    menuDownY = event.y
                    menuDownActivity = activity

                    // 只有上半部分生效
                    if (event.y > screenSize.height / 2f) return@hookBlockRunning

                    downX = event.x
                    downY = event.y
                    downTime = SystemClock.uptimeMillis()
                    downActivity = activity

                    val runnable = Runnable { onLongPress() }
                    longPressRunnable = runnable
                    handler.postDelayed(runnable, triggerDelay)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > 20f || abs(event.y - downY) > 20f) {
                        cancelLongPress()
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                }
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
        downActivity = null
    }

    private fun onLongPress() {
        longPressRunnable = null
        val activity = downActivity ?: return
        downActivity = null

        if (!config.isLongPressLive) return
        if (activity.isFinishing || activity.isDestroyed) return

        // 长按判定已被其它逻辑消费或耗时不足时忽略
        if (SystemClock.uptimeMillis() - downTime < triggerDelay - 50L) return

        // 弹窗本身不接收 Activity 的触摸，这里仅防止极短时间内重复弹出
        val now = SystemClock.uptimeMillis()
        if (now - lastShowTime < 500L) return
        lastShowTime = now

        showOptionsMenu(activity)
    }

    private fun showOptionsMenu(activity: Activity) {
        val items = mutableListOf("直播间信息")
        if (!config.isDisablePlugin) {
            items.add("模块设置")
        }

        showChoiceDialog(
            context = activity,
            title = "Freedom+",
            items = items.toTypedArray(),
            onChoice = { _, item, _ ->
                onChoiceSelected(activity, item)
            },
            // 直播间自身的长按菜单是 Dialog 窗口，PopupWindow 压不住它，这里改用 Dialog
            windowMode = true,
        )
    }

    private fun onChoiceSelected(activity: Activity, item: CharSequence) {
        when (item) {
            "直播间信息" -> {
                singleLaunchMain("liveRoomInfo") {
                    val msg = LiveRoomInfo.format(activity.classLoader)
                    showMessageDialog(
                        context = activity,
                        title = "直播间信息",
                        content = msg,
                        copyText = msg,
                        singleButton = true,
                        windowMode = true,
                    )
                }
            }

            "模块设置" -> {
                val intent = Intent().setClass(activity, FreedomSettingActivity::class.java)
                activity.startActivity(intent)
            }
        }
    }
}
