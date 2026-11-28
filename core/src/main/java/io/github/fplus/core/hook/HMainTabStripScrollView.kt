package io.github.fplus.core.hook

import android.view.View
import com.freegang.extension.firstParentOrNull
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.CallMethods
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam
import io.github.xpler.core.thisViewGroup

class HMainTabStripScrollView : BaseHook(), CallMethods {
    private val config get() = ConfigV1.get()

    // 40.6.0 起顶栏容器迁移到 homepage.tab.top 包下
    override fun setTargetClass(): Class<*> {
        return findClass("com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.scroll.MainTabStripScrollView")
    }

    override fun callOnBeforeMethods(params: MethodParam) {}

    override fun callOnAfterMethods(params: MethodParam) {
        hookBlockRunning(params) {
            if (!config.isTranslucent)
                return

            // 不直接引用宿主 MainTitleBar 类，避免版本改名导致类加载失败
            thisViewGroup.firstParentOrNull(View::class.java) { parent ->
                parent.javaClass.name.endsWith(".MainTitleBar")
            }?.alpha = config.translucentValue[0] / 100f
        }.onFailure {
            XplerLog.e(it)
        }
    }
}
