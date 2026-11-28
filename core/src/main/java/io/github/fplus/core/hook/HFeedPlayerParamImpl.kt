package io.github.fplus.core.hook

import io.github.fplus.core.base.BaseHook
import io.github.xpler.core.entity.NoneHook

/**
 * 旧版播放状态参数（FeedPlayingParam / FeedPausePlayParam / FeedPlayProgressParam）。
 *
 * 40.6.0 已删除这三个类，播放状态改由 [HPlayerController] 直接 hook
 * `PMPlayListenerDispatcherV2`，这里不再挂载任何逻辑。
 */
class HFeedPlayerView : BaseHook() {
    override fun setTargetClass(): Class<*> {
        return NoneHook::class.java
    }
}
