package io.github.fplus.core.hook

import io.github.fplus.core.base.BaseHook
import io.github.xpler.core.entity.NoneHook

/**
 * 旧版表情详情弹窗（com.ss.android.ugc.aweme.emoji.views.EmojiDetailDialog）。
 *
 * 抖音 40.6.0 已删除该类，只保留 [HEmojiDetailDialogNew] 对应的
 * `EmojiDetailDialogNew`，因此这里不再挂载任何逻辑。
 */
class HEmojiDetailDialog : BaseHook() {
    override fun setTargetClass(): Class<*> {
        return NoneHook::class.java
    }
}
