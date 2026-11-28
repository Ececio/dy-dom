package io.github.fplus.core.hook

import android.os.Bundle
import android.view.View
import com.freegang.extension.forEachWhereChild
import com.freegang.extension.removeInParent
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.HookEntity
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam

class HCommentListPageFragment : BaseHook() {
    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        return DexkitBuilder.commentListPageFragmentClazz ?: NoneHook::class.java
    }

    override fun onInit() {
        HCommentColorModeViewMode()
    }

    @OnAfter("onViewCreated")
    fun onViewCreatedAfter(
        params: MethodParam,
        view: View,
        savedInstanceState: Bundle?,
    ) {
        hookBlockRunning(params) {
            view.forEachWhereChild {
                if (it.contentDescription?.toString()?.startsWith("搜索，") == true) {
                    it.removeInParent()
                    return@forEachWhereChild true
                }

                false
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    // 评论颜色模式
    private inner class HCommentColorModeViewMode : HookEntity() {
        override fun setTargetClass(): Class<*> {
            return DexkitBuilder.commentColorModeViewModeClazz ?: NoneHook::class.java
        }

        // 40.6.0 中 CommentColorMode 已混淆为 LX/0dQI;，方法名也变成 tA，
        // 因此不再写死类型/方法名：只匹配该 ViewModel 唯一的一参方法，
        // 再用参数本身拿枚举常量，避免版本变化后类加载失败。
        @OnBefore
        fun setCommentColorModeBefore(
            params: MethodParam,
            @Param("null") mode: Any?,
        ) {
            hookBlockRunning(params) {
                if (!config.isCommentColorMode)
                    return

                val enumClass = args[0]?.javaClass ?: return
                val constants = enumClass.enumConstants ?: return
                if (constants.isEmpty())
                    return

                val index = when (config.commentColorMode) {
                    0 -> 0 // MODE_LIGHT
                    1 -> 1 // MODE_DARK
                    else -> 2 // MODE_LIGHT_OR_DARK
                }
                args[0] = constants.getOrNull(index) ?: return
            }.onFailure {
                XplerLog.e(it)
            }
        }
    }
}