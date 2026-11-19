package io.github.fplus.core.hook

import io.github.fplus.core.base.BaseHook
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

/**
 * 屏蔽抖音自带的直播长按菜单。
 *
 * 直播间的长按菜单最终都会走 LivePlayFragment#showDouyinHotsoonDislikeMenu,
 * 新版走 IHostBusiness.showLongPressPanel, 旧版走 showOldLongPressPanel(DislikeOptionsBottomDialog)，
 * 在这里拦截后，上半部分长按只会弹出模块自己的窗口。
 */
class HLiveLongPressMenu : BaseHook() {
    override fun setTargetClass(): Class<*> {
        return findClass("com.bytedance.android.livesdk.chatroom.LivePlayFragment")
    }

    @OnBefore("showDouyinHotsoonDislikeMenu")
    fun showDouyinHotsoonDislikeMenuBefore(params: MethodParam) {
        hookBlockRunning(params) {
            if (!HLiveLongPress.shouldBlockNativeMenu()) return@hookBlockRunning
            setResultVoid()
        }.onFailure {
            XplerLog.e(it)
        }
    }
}
