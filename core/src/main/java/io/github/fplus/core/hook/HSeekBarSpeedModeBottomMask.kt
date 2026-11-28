package io.github.fplus.core.hook

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.freegang.extension.asOrNull
import com.freegang.extension.postRunning
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.CallMethods
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam
import io.github.xpler.core.proxy.MethodParam
import io.github.xpler.core.thisViewGroup

class HSeekBarSpeedModeBottomMask : BaseHook(), CallMethods {
    private val config: ConfigV1 get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        // 40.6.0：SeekBarSpeedModeBottomMask 已删除。
        // 沉浸式背景透明仍由 init 中对 getMBottomLayout() 的 hook 完成。
        return NoneHook::class.java
    }

    init {
        DexkitBuilder.seekBarSpeedModeBottomContainerClazz?.runCatching {
            lparam.hookClass(this)
                .method("getMBottomLayout") {
                    onAfter {
                        if (config.isImmersive) {
                            result?.asOrNull<View>()?.background = ColorDrawable(Color.TRANSPARENT)
                        }
                    }
                }
        }
    }

    override fun callOnBeforeMethods(params: MethodParam) {

    }

    override fun callOnAfterMethods(params: MethodParam) {
        hookBlockRunning(params) {
            if (config.isImmersive) {
                thisViewGroup.postRunning {
                    it.background = ColorDrawable(Color.TRANSPARENT)
                }
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }
}