package io.github.fplus.core.hook

import androidx.core.view.children
import androidx.core.view.isVisible
import com.ss.android.ugc.aweme.kiwi.model.QModel
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam
import io.github.xpler.core.thisViewGroupOrNull

/**
 * 移除底部播放控制栏。
 *
 * 40.6.0：旧的 IPauseCtrlAction 控制栏已删除，暂停模式底部栏改由
 * FeedPauseModeBottomBarAnimationPresenter 持有（其 view 就是底部栏容器）。
 * 这里在 presenter 绑定时按开关隐藏/显示其子 View。
 */
class HBottomCtrlBar : BaseHook() {
    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        return DexkitBuilder.bottomCtrlBarClazz ?: NoneHook::class.java
    }

    @OnAfter("onBindSafe")
    fun onBindSafeAfter(params: MethodParam, qModel: QModel?) {
        hookBlockRunning(params) {
            val container = thisViewGroupOrNull ?: return@hookBlockRunning
            container.children.forEach { it.isVisible = !config.isRemoveBottomCtrlBar }
        }.onFailure {
            XplerLog.e(it)
        }
    }
}
