package io.github.fplus.core.hook

import android.content.Context
import android.view.View
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

class HHomeBottomTabServiceImpl : BaseHook() {
    private val config
        get() = ConfigV1.get()

    private var lastType: String? = "HOME"
    private var clickAgain: Boolean = false

    override fun setTargetClass(): Class<*> {
        // 40.6.0：包路径去掉 .business，类名保持不变
        return findClass("com.ss.android.ugc.aweme.homepage.tab.bottom.HomeBottomTabServiceImpl")
    }

    /**
     * 40.6.0 的 tab 点击入口：
     * `LJIIIIZZ(Context, BottomTab, String tabType)`（混淆名会变，这里按参数类型匹配）。
     */
    @OnBefore
    fun methodBefore(
        params: MethodParam,
        context: Context?,
        @Param("null") tab: Any?,
        type: String?,
    ) {
        hookBlockRunning(params) {
            if (!config.isPreventAccidentalTouch)
                return

            if (!clickAgain && lastType == "HOME" && type == "HOME") {
                val tabView = tab as? View
                showMessageDialog(
                    context = context ?: return@hookBlockRunning,
                    title = "温馨提示",
                    content = "你点击了首页，是否触发刷新？",
                    cancel = "取消",
                    confirm = "确定",
                    onConfirm = {
                        clickAgain = true
                        // 重新走一次点击，第二次会跳过弹窗直接刷新
                        tabView?.performClick()
                    }
                )

                setResultVoid()
            }

            lastType = type
            clickAgain = false
        }.onFailure {
            XplerLog.e(it)
        }
    }
}