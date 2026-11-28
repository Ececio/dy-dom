package io.github.fplus.core.hook

import io.github.fplus.core.base.BaseHook
import io.github.xpler.core.entity.NoneHook

/**
 * 表情面板长按保存弹窗。
 *
 * 抖音 40.6.0 的表情面板弹窗已改为 Compose 实现，原
 * `RemoteImageView + DmtTextView` 的 PopupWindow 不再存在，
 * 无法再通过 TextView 文本替换实现“长按保存”。
 *
 * 表情保存仍可通过 [HEmojiDetailDialogNew]、[HGifEmojiDetailActivity]
 * 和 [HDetailPageFragment] 中的入口使用。
 */
class HEmojiPopupWindow : BaseHook() {
    override fun setTargetClass(): Class<*> {
        return NoneHook::class.java
    }
}
