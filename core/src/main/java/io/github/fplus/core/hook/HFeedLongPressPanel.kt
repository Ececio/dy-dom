package io.github.fplus.core.hook

import androidx.fragment.app.DialogFragment
import io.github.fplus.core.base.BaseHook
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

/**
 * 关闭原生长按菜单。
 *
 * 40.6.0 的 Feed 长按菜单由 `LongPressPanelFragmentImpl`（DuxBasicPanelFragment，
 * 本质是 DialogFragment）展示。旧版通过拦截 `LongPressEvent#onLongPressAwemeSure`
 * 来阻止原生菜单，该事件类已删除。
 *
 * 现在由 [HLongPressLayout] 在按下时标记是否需要显示模块菜单，
 * 这里在原生面板 onStart 时直接关闭，保持“清爽模式下半屏只显示模块菜单”的效果。
 */
class HFeedLongPressPanel : BaseHook() {
    override fun setTargetClass(): Class<*> {
        return runCatching {
            findClass("com.ss.android.ugc.aweme.feed.long_press_panel.impl.LongPressPanelFragmentImpl")
        }.getOrElse { NoneHook::class.java }
    }

    @OnAfter("onStart")
    fun onStartAfter(params: MethodParam) {
        hookBlockRunning(params) {
            if (!HLongPressLayout.suppressNativeMenu) return@hookBlockRunning
            (thisObject as? DialogFragment)?.dismissAllowingStateLoss()
        }.onFailure {
            XplerLog.e(it)
        }
    }
}
