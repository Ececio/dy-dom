package io.github.fplus.core.base

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.VIBRATOR_SERVICE
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Vibrator
import android.os.VibratorManager
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.Composable
import androidx.core.view.isVisible
import com.freegang.extension.isDarkMode
import com.freegang.ktutils.app.IProgressNotification
import com.freegang.ktutils.app.KNotifiUtils
import com.freegang.ktutils.app.KToastUtils
import io.github.fplus.core.R
import io.github.fplus.core.databinding.DialogChoiceLayoutBinding
import io.github.fplus.core.databinding.DialogInputChoiceLayoutBinding
import io.github.fplus.core.databinding.DialogInputLayoutBinding
import io.github.fplus.core.databinding.DialogMessageLayoutBinding
import io.github.fplus.core.databinding.DialogProgressLayoutBinding
import io.github.fplus.core.ui.ModuleTheme
import io.github.fplus.core.ui.dialog.XplerDialogWrapper
import io.github.fplus.core.view.FlowLayout
import io.github.fplus.core.view.PopupDialog
import io.github.fplus.core.view.adapter.DialogChoiceAdapter
import io.github.fplus.plugin.injectRes
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.HookEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

abstract class BaseHook : HookEntity() {
    protected val handler: Handler = Handler(Looper.getMainLooper())
    private val mainScope: CoroutineScope = CoroutineScope(Dispatchers.Main)
    private var singleMainJob: MutableMap<String, Job?> = mutableMapOf()
    private var singleIOJob: MutableMap<String, Job?> = mutableMapOf()

    private var popupDialog: PopupDialog? = null

    private fun launch(context: CoroutineContext, block: suspend CoroutineScope.() -> Unit): Job {
        val exceptionHandler = CoroutineExceptionHandler { coroutineContext, throwable ->
            if (throwable is CancellationException) {
                throwable.printStackTrace()
                return@CoroutineExceptionHandler
            }

            XplerLog.e(throwable)
            if (coroutineContext.isActive) {
                coroutineContext.cancel()
                coroutineContext.cancelChildren()
            }
        }
        val job = mainScope.launch(context + exceptionHandler) {
            try {
                block.invoke(this)
            } catch (e: CancellationException) {
                e.printStackTrace()
            } catch (e: Exception) {
                XplerLog.e(e)
            }
        }
        return job
    }

    fun launchMain(block: suspend CoroutineScope.() -> Unit): Job {
        return launch(Dispatchers.Main, block)
    }

    fun launchIO(block: suspend CoroutineScope.() -> Unit): Job {
        return launch(Dispatchers.IO, block)
    }

    fun singleLaunchMain(name: String = "singleMain", block: suspend CoroutineScope.() -> Unit) {
        if (singleMainJob.containsKey(name)) {
            singleMainJob[name]?.let { if (it.isActive) it.cancel() }
            singleMainJob.remove(name)
        }

        singleMainJob[name] = launch(Dispatchers.Main + CoroutineName(name), block)
    }

    fun singleLaunchIO(name: String = "singleIO", block: suspend CoroutineScope.() -> Unit) {
        if (singleIOJob.containsKey(name)) {
            singleIOJob[name]?.let { if (it.isActive) it.cancel() }
            singleIOJob.remove(name)
        }

        singleIOJob[name] = launch(Dispatchers.IO + CoroutineName(name), block)
    }

    fun refresh(block: () -> Unit) {
        handler.post(block)
    }

    fun showToast(context: Context, message: String) {
        refresh {
            KToastUtils.show(context, message)
        }
    }

    fun vibrate(context: Context, milliseconds: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            context.getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(milliseconds)
    }

    @Synchronized
    fun showDialog(
        view: View,
        onDismiss: () -> Unit = {},
    ) {
        popupDialog = if (popupDialog == null) PopupDialog() else popupDialog
        popupDialog!!.setView(view)
        popupDialog!!.show()
        popupDialog!!.setOnDismissListener { onDismiss.invoke() }
    }

    /**
     * 使用真正的 Dialog 窗口显示内容。
     *
     * PopupWindow 是附加在 Activity 窗口上的子窗口，层级低于系统/App 的 Dialog；
     * 某些场景（例如直播间长按，抖音自己会弹一个 Dialog 菜单）需要跑到它上面时，
     * 只能使用 Dialog 窗口。
     */
    private fun createWindowDialog(context: Context, view: View): Dialog {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            setGravity(Gravity.CENTER)
        }
        return dialog
    }

    @Synchronized
    fun showComposeDialog(
        context: Context,
        onDismiss: () -> Unit = {},
        content: @Composable (closeHandler: () -> Unit) -> Unit
    ) {
        injectRes(context.resources)
        XplerDialogWrapper(context).apply {
            setWrapperContent {
                ModuleTheme {
                    content.invoke(this::dismiss)
                }
            }
            setOnDismissListener {
                onDismiss.invoke()
            }
        }.show()
    }

    fun showMessageDialog(
        context: Context,
        title: CharSequence,
        content: CharSequence,
        cancel: CharSequence = "取消",
        confirm: CharSequence = "确定",
        copyText: CharSequence? = null, // 不为空时显示"复制"按钮
        actions: List<Pair<CharSequence, () -> Unit>> = emptyList(), // 额外按钮，纵向排列在内容下方
        singleButton: Boolean = false, // 只会响应 onConfirm 方法
        onConfirm: () -> Unit = {},
        onCancel: () -> Unit = {},
        onDismiss: () -> Unit = {},
        windowMode: Boolean = false,
    ) {
        injectRes(context.resources)
        val isDarkMode = context.isDarkMode
        val binding = DialogMessageLayoutBinding.inflate(LayoutInflater.from(context))

        binding.messageDialogContainer.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_background_night,
            R.drawable.dialog_background,
        )
        val showCopy = copyText != null
        binding.messageDialogCopy.isVisible = showCopy
        if (showCopy) {
            binding.messageDialogCopy.background = getDrawable(
                context,
                isDarkMode,
                R.drawable.dialog_cancel_button_background_night,
                R.drawable.dialog_cancel_button_background,
            )
        }
        if (singleButton) {
            binding.messageDialogCancel.visibility = View.GONE
            binding.messageDialogConfirm.background = if (showCopy) {
                getDrawable(
                    context,
                    isDarkMode,
                    R.drawable.dialog_confirm_button_background_night,
                    R.drawable.dialog_confirm_button_background,
                )
            } else {
                getDrawable(
                    context,
                    isDarkMode,
                    R.drawable.dialog_single_button_background_night,
                    R.drawable.dialog_single_button_background,
                )
            }
        } else {
            binding.messageDialogCancel.background = getDrawable(
                context,
                isDarkMode,
                R.drawable.dialog_cancel_button_background_night,
                R.drawable.dialog_cancel_button_background,
            )
            binding.messageDialogConfirm.background = getDrawable(
                context,
                isDarkMode,
                R.drawable.dialog_confirm_button_background_night,
                R.drawable.dialog_confirm_button_background,
            )
        }
        binding.messageDialogTitle.text = title
        binding.messageDialogTitle.setTextColor(getTextColor(isDarkMode))
        binding.messageDialogContent.text = content
        binding.messageDialogContent.setTextColor(getTextColor(isDarkMode))
        binding.messageDialogCancel.text = cancel
        binding.messageDialogCancel.setTextColor(getTextColor(isDarkMode))
        binding.messageDialogConfirm.text = confirm
        copyText?.let { text ->
            binding.messageDialogCopy.setTextColor(getTextColor(isDarkMode))
            binding.messageDialogCopy.setOnClickListener {
                copyToClipboard(context, title, text)
                showToast(context, "已复制")
            }
        }

        binding.messageDialogActions.isVisible = actions.isNotEmpty()
        if (actions.isNotEmpty()) {
            val density = context.resources.displayMetrics.density
            val dp: (Int) -> Int = { (it * density).toInt() }
            // 信息弹窗字段多，缩小四周留白，能多显示几行内容
            (binding.messageDialogContainer.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.setMargins(dp(20), dp(32), dp(20), dp(32))
                binding.messageDialogContainer.layoutParams = params
            }
            binding.messageDialogActions.removeAllViews()
            // 一行最多放两个按钮，避免一行一个太占空间
            val columns = minOf(actions.size, 2)
            actions.chunked(columns).forEachIndexed { rowIndex, rowActions ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (rowIndex > 0) topMargin = dp(4)
                    }
                }

                rowActions.forEachIndexed { index, (label, action) ->
                    val button = TextView(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        ).apply {
                            if (index > 0) leftMargin = dp(4)
                            if (index < rowActions.lastIndex) rightMargin = dp(4)
                        }
                        gravity = Gravity.CENTER
                        setPadding(0, dp(10), 0, dp(10))
                        text = label
                        textSize = 14f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(Color.parseColor("#FFEDA664"))
                        background = getDrawable(
                            context,
                            isDarkMode,
                            R.drawable.dialog_action_background_night,
                            R.drawable.dialog_action_background,
                        )
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { action.invoke() }
                    }
                    row.addView(button)
                }

                // 最后一行不满时补占位，保证按钮宽度一致
                repeat(columns - rowActions.size) {
                    row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
                }
                binding.messageDialogActions.addView(row)
            }
        }

        // 先按内容测量一次：内容超高时固定弹窗高度，把剩余空间全部给滚动区，
        // 这样底部操作按钮不会被内容挤出屏幕。
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        val horizontalMargin = ((if (actions.isNotEmpty()) 20 else 48) * density).toInt()
        val verticalMargin = ((if (actions.isNotEmpty()) 32 else 48) * density).toInt()
        val maxHeight = if (actions.isEmpty()) {
            (metrics.heightPixels * 0.55f).toInt()
        } else {
            metrics.heightPixels - verticalMargin * 2
        }
        binding.messageDialogContainer.measure(
            View.MeasureSpec.makeMeasureSpec(
                metrics.widthPixels - horizontalMargin * 2,
                View.MeasureSpec.EXACTLY,
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        if (binding.messageDialogContainer.measuredHeight > maxHeight) {
            (binding.messageDialogContainer.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
                params.height = maxHeight
                binding.messageDialogContainer.layoutParams = params
            }
            binding.messageDialogScroll.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val windowDialog = if (windowMode) createWindowDialog(context, binding.root) else null

        binding.messageDialogCancel.setOnClickListener {
            windowDialog?.dismiss() ?: popupDialog!!.dismiss()
            onCancel.invoke()
        }
        binding.messageDialogConfirm.setOnClickListener {
            windowDialog?.dismiss() ?: popupDialog!!.dismiss()
            onConfirm.invoke()
        }

        if (windowDialog != null) {
            windowDialog.setOnDismissListener { onDismiss.invoke() }
            windowDialog.show()
        } else {
            showDialog(
                view = binding.root,
                onDismiss = onDismiss,
            )
        }
        // 字段很多时（如作者/直播间信息）限制内容高度，按钮才不会被挤出屏幕；
        // 高度已在上方按实际测量结果固定，这里不再需要二次布局修正。
    }

    fun copyToClipboard(context: Context, label: CharSequence, text: CharSequence) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /**
     * 单行文本输入弹窗。
     *
     * 用于收藏作者时填写标签这类场景：默认值会预填并全选，留空表示不设置/清空标签。
     */
    fun showInputDialog(
        context: Context,
        title: CharSequence,
        message: CharSequence = "",
        hint: CharSequence = "",
        defaultValue: CharSequence = "",
        cancel: CharSequence = "取消",
        confirm: CharSequence = "确定",
        onConfirm: (input: String) -> Unit,
        onCancel: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        injectRes(context.resources)
        val isDarkMode = context.isDarkMode
        val binding = DialogInputLayoutBinding.inflate(LayoutInflater.from(context))

        binding.inputDialogContainer.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_background_night,
            R.drawable.dialog_background,
        )

        binding.inputDialogTitle.text = title
        binding.inputDialogTitle.setTextColor(getTextColor(isDarkMode))

        binding.inputDialogMessage.isVisible = message.isNotBlank()
        binding.inputDialogMessage.text = message
        binding.inputDialogMessage.setTextColor(getHintTextColor(isDarkMode))

        binding.inputDialogEdit.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_input_background_night,
            R.drawable.dialog_input_background,
        )
        binding.inputDialogEdit.hint = hint
        binding.inputDialogEdit.setHintTextColor(getHintTextColor(isDarkMode))
        binding.inputDialogEdit.setText(defaultValue)
        binding.inputDialogEdit.setTextColor(getTextColor(isDarkMode))
        binding.inputDialogEdit.setSelection(binding.inputDialogEdit.text?.length ?: 0)

        binding.inputDialogCancel.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_cancel_button_background_night,
            R.drawable.dialog_cancel_button_background,
        )
        binding.inputDialogConfirm.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_confirm_button_background_night,
            R.drawable.dialog_confirm_button_background,
        )
        binding.inputDialogCancel.text = cancel
        binding.inputDialogCancel.setTextColor(getTextColor(isDarkMode))
        binding.inputDialogConfirm.text = confirm
        binding.inputDialogConfirm.setTextColor(getTextColor(isDarkMode))

        binding.inputDialogCancel.setOnClickListener {
            popupDialog?.dismiss()
            onCancel.invoke()
        }
        binding.inputDialogConfirm.setOnClickListener {
            val input = binding.inputDialogEdit.text?.toString().orEmpty()
            popupDialog?.dismiss()
            onConfirm.invoke(input)
        }

        showDialog(
            view = binding.root,
            onDismiss = onDismiss,
        )

        // 窗口附加到屏幕后再请求焦点，否则软键盘不会弹出
        binding.inputDialogEdit.requestFocus()
        binding.inputDialogEdit.postDelayed({
            if (!binding.inputDialogEdit.isAttachedToWindow) return@postDelayed
            binding.inputDialogEdit.requestFocus()
            val manager =
                context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showSoftInput(binding.inputDialogEdit, InputMethodManager.SHOW_IMPLICIT)
        }, 300L)
    }

    /**
     * 标签输入弹窗：上方是已用过的标签，点一下选中/取消，下方输入框也能自己填新标签。
     *
     * 输入框是唯一数据源，点选标签只是把标签拼进输入框，所以「点选」和「手输」可以混用；
     * 返回值仍然是输入框里的文本，由调用方用同一套规则解析成标签列表。
     */
    fun showTagInputDialog(
        context: Context,
        title: CharSequence,
        message: CharSequence = "",
        hint: CharSequence = "输入新标签，多个用空格或逗号分隔",
        allTags: List<String> = emptyList(),
        defaultTags: List<String> = emptyList(),
        maxTags: Int = 8,
        maxTagLength: Int = 16,
        cancel: CharSequence = "取消",
        confirm: CharSequence = "保存",
        onConfirm: (input: String) -> Unit,
        onCancel: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        injectRes(context.resources)
        val isDarkMode = context.isDarkMode
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()
        val textColor = getTextColor(isDarkMode)
        val hintColor = getHintTextColor(isDarkMode)

        fun parseTags(text: String): List<String> {
            return text.split(Regex("[\\s\\u3000,，;；#]+"))
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.take(maxTagLength) }
                .distinct()
                .take(maxTags)
                .toList()
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(
                context,
                isDarkMode,
                R.drawable.dialog_background_night,
                R.drawable.dialog_background,
            )
        }

        container.addView(TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(textColor)
            setPadding(dp(24), dp(16), dp(24), dp(8))
        })

        if (message.isNotBlank()) {
            container.addView(TextView(context).apply {
                text = message
                textSize = 12f
                setTextColor(hintColor)
                setLineSpacing(0f, 1.2f)
                setPadding(dp(24), 0, dp(24), dp(8))
            })
        }

        val editText = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 14f
            setTextColor(textColor)
            this.hint = hint
            setHintTextColor(hintColor)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = getDrawable(
                context,
                isDarkMode,
                R.drawable.dialog_input_background_night,
                R.drawable.dialog_input_background,
            )
            setText(defaultTags.joinToString(" "))
            setSelection(text?.length ?: 0)
        }
        container.addView(editText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            topMargin = dp(4)
        })

        val chips = linkedMapOf<String, TextView>()

        fun renderChips() {
            val selected = parseTags(editText.text?.toString().orEmpty())
            chips.forEach { (tag, view) ->
                val isSelected = selected.contains(tag)
                view.text = if (isSelected) "✓ $tag" else "#$tag"
                view.setTextColor(if (isSelected) Color.parseColor("#FFEDA664") else textColor)
                view.background = if (isSelected) {
                    getDrawable(
                        context,
                        isDarkMode,
                        R.drawable.dialog_input_background_night,
                        R.drawable.dialog_input_background,
                    )
                } else {
                    getDrawable(
                        context,
                        isDarkMode,
                        R.drawable.dialog_action_background_night,
                        R.drawable.dialog_action_background,
                    )
                }
            }
        }

        if (allTags.isNotEmpty()) {
            container.addView(TextView(context).apply {
                text = "已有标签（点击选择/取消）"
                textSize = 12f
                setTextColor(hintColor)
                setPadding(dp(24), dp(12), dp(24), dp(4))
            })

            // 已有标签用流式布局，一行最多 4 个，放不下自动换行
            val chipsLayout = FlowLayout(context).apply {
                horizontalSpacing = dp(6)
                verticalSpacing = dp(6)
                maxItemsPerRow = 4
                setPadding(dp(24), dp(2), dp(24), dp(4))
            }
            allTags.distinct().forEach { tag ->
                val chip = TextView(context).apply {
                    text = "#$tag"
                    textSize = 13f
                    setTextColor(textColor)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        val selected = parseTags(editText.text?.toString().orEmpty()).toMutableList()
                        if (!selected.remove(tag)) {
                            if (selected.size >= maxTags) {
                                showToast(context, "最多 $maxTags 个标签")
                                return@setOnClickListener
                            }
                            selected.add(tag)
                        }
                        editText.setText(selected.joinToString(" "))
                        editText.setSelection(editText.text?.length ?: 0)
                    }
                }
                chips[tag] = chip
                chipsLayout.addView(
                    chip,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }

            val scroll = ScrollView(context).apply {
                isFillViewport = false
            }
            scroll.addView(chipsLayout, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
            // 一行 4 个，超过两行才限高滚动，避免一两个标签也占一大片
            val chipRows = (allTags.distinct().size + 3) / 4
            container.addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                if (chipRows > 2) dp(168) else LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        container.addView(TextView(context).apply {
            text = "也可以直接输入新标签，最多 $maxTags 个"
            textSize = 12f
            setTextColor(hintColor)
            setPadding(dp(24), dp(6), dp(24), dp(6))
        })

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cancelView = TextView(context).apply {
            text = cancel
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = true
            isFocusable = true
            background = getDrawable(
                context,
                isDarkMode,
                R.drawable.dialog_cancel_button_background_night,
                R.drawable.dialog_cancel_button_background,
            )
            setOnClickListener {
                popupDialog?.dismiss()
                onCancel.invoke()
            }
        }
        val confirmView = TextView(context).apply {
            text = confirm
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFEDA664"))
            setPadding(dp(24), dp(16), dp(24), dp(16))
            isClickable = true
            isFocusable = true
            background = getDrawable(
                context,
                isDarkMode,
                R.drawable.dialog_confirm_button_background_night,
                R.drawable.dialog_confirm_button_background,
            )
            setOnClickListener {
                val input = editText.text?.toString().orEmpty()
                popupDialog?.dismiss()
                onConfirm.invoke(input)
            }
        }
        buttons.addView(cancelView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(confirmView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                renderChips()
            }
        })
        renderChips()

        val root = FrameLayout(context)
        root.addView(container, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ).apply {
            leftMargin = dp(32)
            rightMargin = dp(32)
        })

        showDialog(view = root, onDismiss = onDismiss)

        // 不主动弹软键盘：先把已有标签完整露出来，用户点输入框时再输入新标签。
        editText.requestFocus()
    }

    fun showProgressDialog(
        context: Context,
        title: CharSequence,
        progress: Int = 0,
        listener: (dismiss: (() -> Unit), progress: ProgressDialogNotification) -> Unit,
        onDismiss: () -> Unit = {},
    ) {
        injectRes(context.resources)
        val isDarkMode = context.isDarkMode
        val binding = DialogProgressLayoutBinding.inflate(LayoutInflater.from(context))

        binding.progressDialogContainer.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_background_night,
            R.drawable.dialog_background,
        )
        binding.progressDialogBar.progress = progress
        binding.progressDialogTitle.text = title
        binding.progressDialogTitle.setTextColor(getTextColor(isDarkMode))
        binding.progressDialogText.setTextColor(getTextColor(isDarkMode))
        listener.invoke(
            popupDialog!!::dismiss,
            ProgressDialogNotification(
                binding.progressDialogBar,
                binding.progressDialogText
            )
        )

        showDialog(
            view = binding.root,
            onDismiss = onDismiss
        )
    }

    fun showInputChoiceDialog(
        context: Context,
        title: CharSequence,
        showInput1: Boolean,
        input1Hint: CharSequence,
        input1DefaultValue: CharSequence,
        input2Hint: CharSequence,
        input2DefaultValue: CharSequence,
        items: Array<String>,
        cancel: CharSequence = "取消",
        onChoice: (view: View, input1: String, input2: String, item: CharSequence, position: Int) -> Unit,
        onCancel: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        injectRes(context.resources)

        val isDarkMode = context.isDarkMode
        val binding = DialogInputChoiceLayoutBinding.inflate(LayoutInflater.from(context))

        binding.choiceDialogContainer.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_background_night,
            R.drawable.dialog_background,
        )

        binding.choiceDialogCancel.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_single_button_background_night,
            R.drawable.dialog_single_button_background,
        )

        binding.choiceDialogTitle.text = title
        binding.choiceDialogTitle.setTextColor(getTextColor(isDarkMode))

        binding.choiceDialogInput1.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_input_background_night,
            R.drawable.dialog_input_background,
        )
        binding.choiceDialogInput1.hint = input1Hint
        binding.choiceDialogInput1.setHintTextColor(getHintTextColor(isDarkMode))
        binding.choiceDialogInput1.setText(input1DefaultValue)
        binding.choiceDialogInput1.setTextColor(getTextColor(isDarkMode))
        if (!showInput1) {
            binding.choiceDialogInput1.setText("")
            binding.choiceDialogInput1.isVisible = false
        }

        binding.choiceDialogInput2.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_input_background_night,
            R.drawable.dialog_input_background,
        )
        binding.choiceDialogInput2.hint = input2Hint
        binding.choiceDialogInput2.setHintTextColor(getHintTextColor(isDarkMode))
        binding.choiceDialogInput2.setText(input2DefaultValue)
        binding.choiceDialogInput2.setTextColor(getTextColor(isDarkMode))

        binding.choiceDialogCancel.text = cancel
        binding.choiceDialogCancel.setOnClickListener {
            popupDialog!!.dismiss()
            onCancel.invoke()
        }

        binding.choiceDialogList.adapter = DialogChoiceAdapter(context, items, getTextColor(isDarkMode))
        binding.choiceDialogList.divider = ColorDrawable(Color.TRANSPARENT)
        binding.choiceDialogList.selector = getDrawable(
            context,
            isDarkMode,
            R.drawable.item_selector_background_night,
            R.drawable.item_selector_background,
        )
        binding.choiceDialogList.setOnItemClickListener { _, view, position, _ ->
            popupDialog!!.dismiss()
            onChoice.invoke(
                view,
                "${binding.choiceDialogInput1.text}",
                "${binding.choiceDialogInput2.text}",
                items[position],
                position
            )
        }

        showDialog(
            view = binding.root,
            onDismiss = onDismiss
        )
    }

    fun showChoiceDialog(
        context: Context,
        title: CharSequence,
        items: Array<String>,
        cancel: CharSequence = "取消",
        onChoice: (view: View, item: CharSequence, position: Int) -> Unit,
        onCancel: () -> Unit = {},
        windowMode: Boolean = false,
    ) {
        injectRes(context.resources)
        val isDarkMode = context.isDarkMode
        val binding = DialogChoiceLayoutBinding.inflate(LayoutInflater.from(context))

        binding.choiceDialogContainer.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_background_night,
            R.drawable.dialog_background,
        )

        binding.choiceDialogCancel.background = getDrawable(
            context,
            isDarkMode,
            R.drawable.dialog_single_button_background_night,
            R.drawable.dialog_single_button_background,
        )

        binding.choiceDialogTitle.text = title
        binding.choiceDialogTitle.setTextColor(getTextColor(isDarkMode))

        val windowDialog = if (windowMode) createWindowDialog(context, binding.root) else null

        binding.choiceDialogCancel.text = cancel
        binding.choiceDialogCancel.setOnClickListener {
            windowDialog?.dismiss() ?: popupDialog!!.dismiss()
            onCancel.invoke()
        }

        binding.choiceDialogList.adapter = DialogChoiceAdapter(context, items, getTextColor(isDarkMode))
        binding.choiceDialogList.divider = ColorDrawable(Color.TRANSPARENT)
        binding.choiceDialogList.selector = getDrawable(
            context,
            isDarkMode,
            R.drawable.item_selector_background_night,
            R.drawable.item_selector_background,
        )
        binding.choiceDialogList.setOnItemClickListener { _, view, position, _ ->
            windowDialog?.dismiss() ?: popupDialog!!.dismiss()
            onChoice.invoke(
                view,
                items[position],
                position
            )
        }

        if (windowDialog != null) {
            windowDialog.show()
        } else {
            showDialog(binding.root)
        }
    }

    private fun getHintTextColor(isDarkMode: Boolean): Int {
        return if (isDarkMode) {
            Color.parseColor("#FFAAAAAA")
        } else {
            Color.parseColor("#FF666666")
        }
    }

    private fun getTextColor(isDarkMode: Boolean): Int {
        return if (isDarkMode) {
            Color.parseColor("#FFFFFFFF")
        } else {
            Color.parseColor("#FF2C2F39")
        }
    }

    private fun getDrawable(
        context: Context,
        isDarkMode: Boolean,
        @DrawableRes darkId: Int,
        @DrawableRes lightId: Int,
    ): Drawable? {
        return if (isDarkMode) {
            AppCompatResources.getDrawable(context, darkId)
        } else {
            AppCompatResources.getDrawable(context, lightId)
        }
    }

    fun showNotification(
        context: Context,
        notifyId: Int,
        title: String,
        text: String,
    ) {
        val channelId = "Freedom+"
        val channelName = "Freedom+ Message"
        KNotifiUtils.showNotification(
            context = context,
            notifyId = notifyId,
            channelId = channelId,
            channelName = channelName,
            title = title,
            text = text,
        )
    }

    fun showDownloadNotification(
        context: Context,
        notifyId: Int,
        title: String,
        inProgressText: String = "%d%%",
        finishedText: String = "下载完成!",
        listener: (notify: KNotifiUtils.ProgressNotification) -> Unit,
    ) {
        val channelId = "Freedom+"
        val channelName = "Freedom+ Message"
        KNotifiUtils.showProgressNotification(
            context = context,
            notifyId = notifyId,
            channelId = channelId,
            channelName = channelName,
            title = title,
            inProgressText = inProgressText,
            finishedText = finishedText,
            listener = listener,
        )
    }

    class ProgressDialogNotification(
        private val progressBar: ProgressBar,
        private val progressText: TextView,
    ) : IProgressNotification {
        override fun setFinishedText(finishedText: String) {
            progressBar.progress = 100
            progressText.text = finishedText
        }

        override fun setFailedText(failedText: String) {
            progressBar.setProgress(0, true)
            progressText.text = failedText
        }

        override fun notifyProgress(step: Int, inProgressText: String) {
            progressBar.progress = step
            progressText.text = String.format(inProgressText, step)
        }
    }
}