package io.github.fplus.core.hook

import android.app.ActivityOptions
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import com.freegang.extension.activeActivity
import com.freegang.extension.asOrNull
import com.freegang.extension.findFieldGetValue
import com.freegang.extension.findFieldSetValue
import com.freegang.extension.findMethodInvoke
import com.freegang.extension.firstParentOrNull
import com.freegang.extension.forEachChild
import com.freegang.extension.isDarkMode
import com.freegang.extension.screenSize
import com.freegang.ktutils.other.KAutomationUtils
import com.freegang.ktutils.view.KViewUtils
import com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.android.ugc.aweme.feed.ui.PenetrateTouchRelativeLayout
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.core.hook.logic.DownloadAllUserWorksLogic
import io.github.fplus.core.hook.logic.DownloadLogic
import io.github.fplus.core.hook.logic.FavoriteAuthor
import io.github.fplus.core.hook.logic.FavoriteAuthorStore
import io.github.fplus.core.hook.logic.LiveRoomInfo
import io.github.fplus.core.hook.logic.authorInfoText
import io.github.fplus.core.hook.logic.videoInfoText
import io.github.fplus.core.ui.activity.FreedomSettingActivity
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam
import io.github.xpler.core.proxy.MethodParam
import io.github.xpler.core.thisView
import java.util.WeakHashMap


class HLongPressLayout : BaseHook() {
    companion object {
        /**
         * 信息流直播预览卡片：LongPressLayout -> 当前卡片数据。
         * 用弱引用 key，卡片回收后自动清理。
         */
        private val livePreviewData = WeakHashMap<View, LivePreviewData>()

        private data class LivePreviewData(
            val room: Any?,
            val aweme: Any?,
        )

        private const val VERTICAL_VIEW_PAGER =
            "com.ss.android.ugc.aweme.common.widget.VerticalViewPager"
        private const val LIVE_PLAYER =
            "com.bytedance.android.livesdkapi.roomplayer.LivePlayerView"

        /**
         * 直播预览卡片独有、普通视频页不会出现的视图。
         * 「直播」Tab 的部分预览卡没有可见的 LivePlayerView，只能靠这些引导视图判定。
         */
        private val LIVE_PREVIEW_VIEWS = arrayOf(
            LIVE_PLAYER,
            "com.ss.android.ugc.aweme.feed.widget.entertip.view.LiveGuideProgressBgView",
            "com.ss.android.ugc.aweme.feed.widget.entertip.view.LiveGuideAutoEnterCancelView",
            "com.ss.android.ugc.aweme.feed.widget.entertip.view.LiveGuideCountDownBgView",
        )
        private const val MAX_SCAN_VIEWS = 600
    }

    private val config
        get() = ConfigV1.get()

    private var lastTouchDown: Long = 0
    private var numberOfTaps: Int = 0
    private var lastTapTimeMs: Long = 0
    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f

    private val LONG_PRESS_TIME = ViewConfiguration.getLongPressTimeout()
    private val DOUBLE_TAP_TIME = ViewConfiguration.getDoubleTapTimeout()

    private var longPressRunnable: Runnable? = null

    /**
     * 直播预览卡片长按弹出菜单后，松手会被原生点击事件当成「进入直播间」。
     * 这里记一个标记，在下一次 ACTION_UP/ACTION_CANCEL 时消费掉事件。
     */
    private var suppressClickUp = false

    /**
     * 长按直播预览卡片时先记下要弹的菜单，等松手后再弹。
     * 按住中途弹窗会被同一次手势的抬起事件点到菜单项上。
     */
    private var pendingLiveMenu: Pair<View, LivePreviewData>? = null

    private var aweme: Aweme? = null

    override fun setTargetClass(): Class<*> {
        return findClass("com.ss.android.ugc.aweme.feed.ui.LongPressLayout")
    }

    /**
     * 直播预览页的原生长按监听器会直接进入直播间，注册时就不给它设置。
     */
    @OnBefore("setListener")
    fun setListenerBefore(params: MethodParam) {
        hookBlockRunning(params) {
            val view = thisObject as? View ?: return@hookBlockRunning
            if (!config.isLongPressLive) return@hookBlockRunning

            // listener 就是直播预览 widget：它自己持有当前卡片的 Aweme
            val listener = args.getOrNull(0)
            val listenerAweme = listener?.findFieldGetValue<Aweme> { type(Aweme::class.java) }
            if (listenerAweme != null && listenerAweme.isLive) {
                val room = listener.findFieldGetValue<Any> { name("LJIIL") }
                synchronized(livePreviewData) {
                    livePreviewData[view] = LivePreviewData(room, listenerAweme)
                }
                setArgs(arrayOf(null))
                return@hookBlockRunning
            }

            // 注册过直播预览的布局直接放行（部分预览卡没有 LivePlayerView）
            val registered = synchronized(livePreviewData) { livePreviewData.containsKey(view) }
            if (!registered && !isLivePreviewPage(view)) return@hookBlockRunning
            setArgs(arrayOf(null))
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnBefore("onTouchEvent")
    fun onTouchEventBefore(params: MethodParam, event: MotionEvent) {
        hookBlockRunning(params) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    suppressClickUp = false
                    pendingLiveMenu = null
                    // 原生 runnable 会在 500ms 左右直接弹菜单/进直播间，按下时就得摘掉
                    val layout = thisObject as? View
                    if (layout != null && config.isLongPressLive) {
                        // 原生监听器本身持有直播 Aweme 时，直接登记为直播预览卡
                        val listener = layout.findFieldGetValue<Any> { name("LJ") }
                        val listenerAweme = listener?.findFieldGetValue<Aweme> {
                            type(Aweme::class.java)
                        }
                        if (listenerAweme != null && listenerAweme.isLive) {
                            val room = listener.findFieldGetValue<Any> { name("LJIIL") }
                            synchronized(livePreviewData) {
                                livePreviewData[layout] = LivePreviewData(room, listenerAweme)
                            }
                        }
                        if (findLivePreview(layout) != null) {
                            detachNativeLongPress(layout)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!suppressClickUp) return@hookBlockRunning
                    suppressClickUp = false
                    val view = thisObject as? View
                    // 原生 onTouchEvent 不再执行，手动复位按压态
                    view?.isPressed = false
                    // 直接返回 true，跳过原生的点击/上滑处理，避免进入直播间
                    setResult(true)
                    // 松手后才弹菜单
                    val pending = pendingLiveMenu
                    pendingLiveMenu = null
                    if (pending != null && event.actionMasked == MotionEvent.ACTION_UP) {
                        val (target, data) = pending
                        handler.post { showLiveOptionsMenu(target, data) }
                    }
                }
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("onTouchEvent")
    fun onTouchEventAfter(params: MethodParam, event: MotionEvent) {
        hookBlockRunning(params) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 每次按下都重新建 runnable，避免沿用上一次手势的 View/坐标
                    val view = thisView
                    val downEvent = MotionEvent.obtain(event)
                    longPressRunnable = Runnable { onLongPress(view, downEvent) }
                    longPressRunnable?.let { handler.postDelayed(it, LONG_PRESS_TIME.toLong()) }
                    touchDownX = event.x
                    touchDownY = event.y
                    lastTouchDown = System.currentTimeMillis()
                }

                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let {
                        handler.removeCallbacks(it)
                        longPressRunnable = null
                    }

                    if (System.currentTimeMillis() - lastTouchDown < LONG_PRESS_TIME) {
                        // onClick(thisView,event)
                        if (System.currentTimeMillis() - lastTapTimeMs < DOUBLE_TAP_TIME) {
                            if (numberOfTaps == 1) {
                                onDoubleClick(thisView, event)
                                numberOfTaps = 0
                            }
                        } else {
                            numberOfTaps = 1
                        }
                        lastTapTimeMs = System.currentTimeMillis()
                    }
                }

                else -> {
                    if (isMoved(event) || event.action == MotionEvent.ACTION_CANCEL) {
                        longPressRunnable?.let {
                            handler.removeCallbacks(it)
                            longPressRunnable = null
                        }
                    }
                }
            }

        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 摘掉 LongPressLayout 的原生长按监听器，并取消已经排队的长按 runnable。
     * 原生 runnable 会调用 LJ.onLongPressAwemeSure()，直播预览卡片上等于「进入直播间」。
     */
    private fun detachNativeLongPress(layout: View) {
        runCatching {
            layout.findFieldSetValue(null) { name("LJ") }
            layout.findMethodInvoke<Any>(block = {
                name("LIZIZ")
                parameterTypes(emptyList())
            })
        }.onFailure {
            XplerLog.e(it)
        }
    }

    private fun isMoved(event: MotionEvent): Boolean {
        return Math.abs(touchDownX - event.x) > 10 || Math.abs(touchDownY - event.y) > 10
    }

    private fun onClick(view: View, event: MotionEvent) {

    }

    private fun onDoubleClick(view: View, event: MotionEvent) {
        if (!config.isDoubleClickType)
            return

        val holderRootView = view.firstParentOrNull(VideoViewHolderRootView::class.java) ?: return
        when (config.doubleClickType) {
            1 -> onClickView(holderRootView, targetContent = Regex("评论(.*?)，按钮"))// 打开评论区
            2 -> {} // 点赞
        }
    }

    private fun onLongPress(view: View, event: MotionEvent) {
        // 信息流里的直播预览卡片没有 VideoViewHolderRootView，单独处理
        val live = findLivePreview(view)
        if (live != null) {
            if (!config.isLongPressLive) return
            // 屏蔽原生长按菜单（直播预览的原生监听器在 LongPressLayout.LJ 字段）
            detachNativeLongPress(view)
            // 松手会进入直播间，先吃掉抬起事件；菜单等松手后再弹
            suppressClickUp = true
            pendingLiveMenu = view to live
            return
        }

        // 预留出长按快进
        if (event.x < screenSize.width / 8 || event.x > screenSize.width - screenSize.width / 8)
            return

        if (!config.isNeatMode)
            return

        if (!config.longPressMode) {
            if (event.y < screenSize.height / 2)
                return
        } else {
            if (event.y > screenSize.height / 2)
                return
        }

        val holderRootView = view.firstParentOrNull(VideoViewHolderRootView::class.java) ?: return
        showOptionsMenu(holderRootView)
    }

    private fun registerLivePreview(widget: Any) {
        runCatching {
            val layout = widget.findFieldGetValue<View> { name("LJIIJJI") }
                ?: return
            val room = widget.findFieldGetValue<Any> { name("LJIIL") }
            val aweme = widget.findFieldGetValue<Any> { name("LJIILIIL") }
            synchronized(livePreviewData) {
                livePreviewData[layout] = LivePreviewData(room, aweme)
            }
            if (config.isLongPressLive) {
                // 原生长按监听器在 LongPressLayout.LJ 字段
                layout.findFieldSetValue(null) { name("LJ") }
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    /**
     * 判断当前长按的 LongPressLayout 是否属于直播预览卡片。
     *
     * 优先用 LivePreviewGestureWidget 注册的数据（能精确对应到卡片）；
     * 否则看这一页里有没有直播预览专属视图。
     *
     * 注意不要依赖 HVerticalViewPager.currentAweme：「直播」Tab 的 adapter
     * 取不到 Aweme，按下瞬间经常是 null 或上一页的普通视频，会导致漏判。
     */
    private fun findLivePreview(view: View): LivePreviewData? {
        synchronized(livePreviewData) {
            livePreviewData[view]?.let { return it }
        }

        if (!isLivePreviewPage(view)) return null
        val aweme = HVerticalViewPager.currentAweme?.takeIf { it.isLive }
        return LivePreviewData(room = null, aweme = aweme)
    }

    /**
     * 长按所在的一整页（VerticalViewPager 的一页）里是否有直播预览专属视图。
     */
    private fun isLivePreviewPage(view: View): Boolean {
        var page: View = view
        while (true) {
            val parent = page.parent as? View ?: return false
            if (parent.javaClass.name == VERTICAL_VIEW_PAGER) break
            page = parent
        }

        val stack = ArrayDeque<View>()
        stack.addLast(page)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_SCAN_VIEWS) {
            val current = stack.removeLast()
            visited++
            if (current.visibility == View.VISIBLE &&
                LIVE_PREVIEW_VIEWS.contains(current.javaClass.name)
            ) {
                return true
            }
            if (current is ViewGroup) {
                // 倒序压栈，先扫靠前的子 View（直播播放器通常在第一个容器里）
                for (i in current.childCount - 1 downTo 0) {
                    stack.addLast(current.getChildAt(i))
                }
            }
        }
        return false
    }

    private fun showLiveOptionsMenu(view: View, data: LivePreviewData) {
        val items = mutableListOf("直播间信息")
        if (!config.isDisablePlugin) {
            items.add("模块设置")
        }

        showChoiceDialog(
            context = view.context,
            title = "Freedom+",
            items = items.toTypedArray(),
            onChoice = { _, item, _ ->
                when (item) {
                    "直播间信息" -> {
                        singleLaunchMain("feedLiveRoomInfo") {
                            val room = data.room
                            val msg = if (room != null) {
                                LiveRoomInfo.formatRoom(room)
                            } else {
                                LiveRoomInfo.formatFromAweme(data.aweme ?: HVerticalViewPager.currentAweme)
                            }
                            showMessageDialog(
                                context = view.context,
                                title = "直播间信息",
                                content = msg,
                                copyText = msg,
                                singleButton = true,
                            )
                        }
                    }

                    "模块设置" -> {
                        val intent = Intent().setClass(view.context, FreedomSettingActivity::class.java)
                        intent.putExtra("isDark", view.context.isDarkMode)
                        val options = ActivityOptions.makeCustomAnimation(
                            activeActivity,
                            android.R.anim.slide_in_left,
                            android.R.anim.slide_out_right
                        )
                        view.context.startActivity(intent, options.toBundle())
                    }
                }
            },
        )
    }

    private fun showOptionsMenu(view: ViewGroup) {
        if (HDetailPageFragment.isComment) return

        val items = getChoiceItems(view).toTypedArray()
        showChoiceDialog(
            context = view.context,
            title = "Freedom+",
            items = items,
            onChoice = { _, item, _ ->
                onChoiceSelected(view, item)
            }
        )
    }

    private fun getChoiceItems(view: View): List<String> {
        val items = mutableListOf("评论", "收藏", "分享")

        if (config.isDownload) {
            items.add("下载")
            items.add("下载所有作品")
        }

        items.add("收藏作者")
        items.add("获取作者信息")

        if (config.isNeatMode) {
            items.add(0, if (!config.neatModeState) "清爽模式" else "普通模式")
        }

        // if (config.isVideoFilter) {
        //     items.add("过滤统计")
        // }

        items.add("视频信息")

        if (!config.isDisablePlugin) {
            items.add("模块设置")
        }
        return items
    }

    private fun onChoiceSelected(view: View, item: CharSequence) {
        when (item) {
            "清爽模式", "普通模式" -> {
                config.neatModeState = !config.neatModeState
                toggleView(view, !config.neatModeState)
                showToast(view.context, if (config.neatModeState) "清爽模式" else "普通模式")
            }

            "评论" -> {
                onClickView(view, targetContent = Regex("评论(.*?)，按钮"))
            }

            "收藏" -> {
                onClickView(view, targetContent = Regex("收藏(.*?)，按钮"))
            }

            "分享" -> {
                onClickView(view, targetContent = Regex("分享(.*?)，按钮"))
            }

            "下载" -> {
                DownloadLogic(
                    hook = this@HLongPressLayout,
                    context = view.context,
                    aweme = aweme,
                )
            }

            "下载所有作品" -> {
                DownloadAllUserWorksLogic(
                    hook = this@HLongPressLayout,
                    context = view.context,
                    aweme = aweme,
                ).start()
            }

            "收藏作者" -> {
                val author = FavoriteAuthor.fromAweme(aweme)
                if (author == null) {
                    showToast(view.context, "未获取到作者信息")
                    return
                }
                val context = view.context
                // 先读已有的标签，弹窗里预填，方便直接修改或清空
                singleLaunchIO("findFavoriteAuthor") {
                    val existing = runCatching {
                        FavoriteAuthorStore.find(context, author.key)
                    }.getOrNull()
                    singleLaunchMain("favoriteAuthor") {
                        showInputDialog(
                            context = context,
                            title = if (existing == null) "收藏作者" else "更新收藏作者",
                            message = "给「${author.displayName}」添加标签，可留空。" +
                                "多个标签用空格或逗号分隔，最多 8 个；清空后保存会删除原有标签。",
                            hint = "例如：广西 搞笑",
                            defaultValue = existing?.tags.orEmpty().joinToString(" "),
                            confirm = "保存",
                            onConfirm = { text ->
                                val tagged = author.copy(tags = FavoriteAuthor.parseTags(text))
                                singleLaunchIO("favoriteAuthor") {
                                    val tagSuffix = tagged.tags.orEmpty()
                                        .takeIf { it.isNotEmpty() }
                                        ?.joinToString("", prefix = "，标签：") { "#$it" }
                                        .orEmpty()
                                    when (FavoriteAuthorStore.upsert(context, tagged)) {
                                        FavoriteAuthorStore.Result.ADDED ->
                                            showToast(context, "已收藏作者：${tagged.displayName}$tagSuffix")

                                        FavoriteAuthorStore.Result.UPDATED ->
                                            showToast(context, "已更新收藏：${tagged.displayName}$tagSuffix")

                                        FavoriteAuthorStore.Result.FAILED ->
                                            showToast(context, "收藏失败，请检查存储权限")
                                    }
                                }
                            },
                        )
                    }
                }
            }

            "获取作者信息" -> {
                singleLaunchMain("$item") {
                    val target = aweme
                    val msg = target?.authorInfoText() ?: "未知"
                    val author = FavoriteAuthor.fromAweme(target)
                    val uid = author?.uid.orEmpty()
                    val secUid = author?.secUid.orEmpty()
                    val context = view.context
                    val homeUrl = "https://www.douyin.com/user/$secUid"

                    showMessageDialog(
                        context = context,
                        title = "作者信息",
                        content = msg,
                        singleButton = true,
                        actions = listOf(
                            "复制主要信息" to {
                                copyToClipboard(context, "作者信息", msg)
                                showToast(context, "已复制作者信息")
                            },
                            "复制 UID" to {
                                if (uid.isBlank()) {
                                    showToast(context, "没有获取到 UID")
                                } else {
                                    copyToClipboard(context, "UID", uid)
                                    showToast(context, "已复制 UID：$uid")
                                }
                            },
                            "复制主页链接" to {
                                if (secUid.isBlank()) {
                                    showToast(context, "作者信息里没有 secUid")
                                } else {
                                    copyToClipboard(context, "主页链接", homeUrl)
                                    showToast(context, "已复制主页链接")
                                }
                            },
                        ),
                    )
                }
            }

            "视频信息" -> {
                singleLaunchMain("$item") {
                    val msg = aweme?.videoInfoText(view.context) ?: "未知"
                    showMessageDialog(
                        context = view.context,
                        title = "视频信息",
                        content = msg,
                        copyText = msg,
                        singleButton = true,
                    )
                }
            }

            "过滤统计" -> {
                val builder = StringBuilder()
                if (HVerticalViewPager.filterLiveCount > 0) {
                    builder.append("直播过滤: ")
                        .append(HVerticalViewPager.filterLiveCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterImageCount > 0) {
                    builder.append("图文过滤: ")
                        .append(HVerticalViewPager.filterImageCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterAdCount > 0) {
                    builder.append("广告过滤: ")
                        .append(HVerticalViewPager.filterAdCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterLongVideoCount > 0) {
                    builder.append("长视频过滤: ")
                        .append(HVerticalViewPager.filterLongVideoCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterRecommendedCardsCount > 0) {
                    builder.append("推荐卡片过滤: ")
                        .append(HVerticalViewPager.filterRecommendedCardsCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterRecommendedMerchantsCount > 0) {
                    builder.append("推荐商家过滤: ")
                        .append(HVerticalViewPager.filterRecommendedMerchantsCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterEmptyDescCount > 0) {
                    builder.append("空文案过滤: ")
                        .append(HVerticalViewPager.filterEmptyDescCount)
                        .append("\n")
                }
                if (HVerticalViewPager.filterOtherCount > 0) {
                    builder.append("关键字过滤: ").append(HVerticalViewPager.filterOtherCount)
                }
                val msg = builder.toString().trim()
                if (msg.isEmpty()) {
                    showToast(view.context, "未过滤视频")
                } else {
                    showToast(view.context, msg)
                }
            }

            "模块设置" -> {
                val intent = Intent().setClass(view.context, FreedomSettingActivity::class.java)
                intent.putExtra("isDark", view.context.isDarkMode)
                val options = ActivityOptions.makeCustomAnimation(
                    activeActivity,
                    android.R.anim.slide_in_left,
                    android.R.anim.slide_out_right
                )
                view.context.startActivity(intent, options.toBundle())
            }
        }
    }

    private fun toggleView(view: View, visible: Boolean) {
        val viewHolderRootView = view as VideoViewHolderRootView
        val monitorScrollFrameLayout = viewHolderRootView.children.lastOrNull {
            it.javaClass.name.contains("MonitorScrollFrameLayout")
        }?.asOrNull<ViewGroup>()

        // 清爽模式
        monitorScrollFrameLayout?.children?.forEach {
            if (it is PenetrateTouchRelativeLayout) {
                it.isVisible = visible
            }
        }

        HMainActivity.toggleView(visible)
    }

    private fun onClickView(
        parent: View,
        targetView: Class<out View>? = null,
        targetText: Regex = Regex(""),
        targetHint: Regex = Regex(""),
        targetContent: Regex = Regex(""),
    ) {
        parent.forEachChild {
            var needClick = false
            if (it is TextView) {
                needClick = "${it.text}".containsNotEmpty(targetText)
                needClick = needClick || "${it.hint}".containsNotEmpty(targetHint)
            }
            needClick = needClick || "${it.contentDescription}".containsNotEmpty(targetContent)
            needClick = needClick || (targetView?.isInstance(it) ?: false)
            if (!needClick) return@forEachChild
            // KLogCat.d("找到: \n${this}")

            // 是否具有点击事件
            val onClickListener = KViewUtils.getOnClickListener(it)
            if (onClickListener != null) {
                if (!KViewUtils.isFastClick(200L)) {
                    onClickListener.onClick(it)
                }
                return@forEachChild
            }
            // KLogCat.d("没有点击事件")

            // 模拟手势
            val location = IntArray(2) { 0 }
            it.getLocationOnScreen(location)
            if (location[1] > 0 && location[1] < screenSize.height) { // 是否在屏幕内
                location[0] = location[0] + it.right / 2
                location[1] = location[1] + it.bottom / 2
                if (!KViewUtils.isFastClick(200)) {
                    KAutomationUtils.simulateClickByView(it, location[0].toFloat(), location[1].toFloat())
                }
                return@forEachChild
            }
        }
    }

    private fun CharSequence.containsNotEmpty(regex: Regex): Boolean {
        if (regex.pattern.isEmpty()) return false
        return contains(regex)
    }

    override fun onInit() {

        // 信息流直播预览：记录卡片对应的 Room/Aweme，并屏蔽原生「不感兴趣」长按菜单
        runCatching {
            val clazz = findClass("com.ss.android.ugc.aweme.feed.widget.LivePreviewGestureWidget")

            // widget 创建时先拿到 LongPressLayout，并清掉原生监听器
            lparam.hookClass(clazz)
                .method("LJIJJLI") {
                    onAfter {
                        thisObject?.let { registerLivePreview(it) }
                    }
                }

            // 每次绑定 Aweme 时刷新 Room/Aweme，保证长按时拿到的是当前卡片
            lparam.hookClass(clazz)
                .method("LIZ", Aweme::class.java) {
                    onAfter {
                        thisObject?.let { registerLivePreview(it) }
                    }
                }
        }.onFailure {
            XplerLog.e(it)
        }

        // 长按菜单事件拦截
        DexkitBuilder.longPressEventClazz?.also {
            lparam.hookClass(it)
                .method(
                    "onLongPressAwemeSure",
                    Float::class.java,
                    Float::class.java,
                ) {
                    onBefore {
                        val x = args[0] as Float
                        val y = args[1] as Float

                        aweme = thisObject?.findFieldGetValue { type(Aweme::class.java) }
                            ?: HVideoViewHolder.aweme

                        // 直播预览卡片：原生这套长按会直接进直播间，一律拦掉。
                        // 部分「直播」Tab 卡片拿不到直播 Aweme，这里再用页面视图兜底判定。
                        if (config.isLongPressLive) {
                            val layout = thisObject?.findFieldGetValue<View> { name("LJIIJJI") }
                                ?: (thisObject as? View)
                            if (aweme?.isLive == true || (layout != null && isLivePreviewPage(layout))) {
                                setResultVoid()
                                return@onBefore
                            }
                        }

                        if (!config.isNeatMode)
                            return@onBefore

                        if (config.longPressMode) {
                            if (y < screenSize.height / 2)
                                setResultVoid()
                        } else {
                            if (y > screenSize.height / 2)
                                setResultVoid()
                        }
                    }
                }
        }

        // 双击点赞事件拦截
        DexkitBuilder.doubleClickEventClazz?.also {
            lparam.hookClass(it)
                .methodAllByParamTypes(
                    View::class.java,
                    MotionEvent::class.java,
                    MotionEvent::class.java,
                    MotionEvent::class.java,
                ) {
                    onBefore {
                        if (!config.isDoubleClickType)
                            return@onBefore

                        if (config.doubleClickType == 1)
                            setResultVoid()
                    }
                }
        }
    }
}