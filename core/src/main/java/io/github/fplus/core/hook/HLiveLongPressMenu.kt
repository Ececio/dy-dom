package io.github.fplus.core.hook

import io.github.fplus.core.base.BaseHook
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

/**
 * 屏蔽抖音自带的直播长按菜单。
 *
 * 40.6.0 中 LivePlayFragmentV2 已没有 showDouyinHotsoonDislikeMenu，
 * 长按菜单统一由 `com.bytedance.android.livesdk.dislike.DislikeOptionsBottomDialog`
 * （DialogFragment）展示，新旧两种面板样式都走这个弹窗。
 *
 * 这里 hook DialogFragment#onStart，只拦截该类，并在 onStart 中直接返回，
 * 使弹窗永远不会显示；其它 DialogFragment 不受影响。
 */
class HLiveLongPressMenu : BaseHook() {
    companion object {
        private const val DISLIKE_DIALOG = "com.bytedance.android.livesdk.dislike.DislikeOptionsBottomDialog"
    }

    override fun setTargetClass(): Class<*> {
        return findClass("androidx.fragment.app.DialogFragment")
    }

    @OnBefore("onStart")
    fun onStartBefore(params: MethodParam) {
        hookBlockRunning(params) {
            val className = thisObject?.javaClass?.name ?: return@hookBlockRunning
            if (className != DISLIKE_DIALOG) return@hookBlockRunning
            if (!HLiveLongPress.shouldBlockNativeMenu()) return@hookBlockRunning

            // 跳过 DialogFragment.onStart -> mDialog.show()
            setResultVoid()
        }.onFailure {
            XplerLog.e(it)
        }
    }
}
