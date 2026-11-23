package io.github.fplus.core.view

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * 简单的流式布局：子 View 从左到右排列，一行放不下就自动换到下一行。
 *
 * 用来摆「已有标签」这类数量不定、宽度不一的胶囊按钮：
 * 以前是竖向列表，一行一个；现在一行可以放多个，超出 [maxItemsPerRow] 个也会换行。
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    /** 子 View 之间的水平间距（px） */
    var horizontalSpacing: Int = 0

    /** 行与行之间的垂直间距（px） */
    var verticalSpacing: Int = 0

    /** 一行最多放几个，默认不限制，只受宽度约束 */
    var maxItemsPerRow: Int = Int.MAX_VALUE
        set(value) {
            field = if (value <= 0) Int.MAX_VALUE else value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val available = (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)

        var rowWidth = 0
        var rowHeight = 0
        var rowCount = 0
        var totalHeight = 0
        var maxRowWidth = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue

            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight

            val needed = if (rowCount == 0) childWidth else rowWidth + horizontalSpacing + childWidth
            val overflow = rowCount > 0 && (needed > available || rowCount >= maxItemsPerRow)

            if (overflow) {
                totalHeight += rowHeight + verticalSpacing
                maxRowWidth = maxOf(maxRowWidth, rowWidth)
                rowWidth = childWidth
                rowHeight = childHeight
                rowCount = 1
            } else {
                rowWidth = needed
                rowHeight = maxOf(rowHeight, childHeight)
                rowCount++
            }
        }

        if (rowCount > 0) {
            totalHeight += rowHeight
            maxRowWidth = maxOf(maxRowWidth, rowWidth)
        }

        val width = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> (maxRowWidth + paddingLeft + paddingRight).coerceAtMost(widthSize)
            else -> maxRowWidth + paddingLeft + paddingRight
        }
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST ->
                (totalHeight + paddingTop + paddingBottom)
                    .coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
            else -> totalHeight + paddingTop + paddingBottom
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l - paddingLeft - paddingRight).coerceAtLeast(0)

        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        var rowCount = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue

            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val overflow = rowCount > 0 &&
                (x + childWidth > paddingLeft + available || rowCount >= maxItemsPerRow)

            if (overflow) {
                x = paddingLeft
                y += rowHeight + verticalSpacing
                rowHeight = 0
                rowCount = 0
            }

            child.layout(x, y, x + childWidth, y + childHeight)
            x += childWidth + horizontalSpacing
            rowHeight = maxOf(rowHeight, childHeight)
            rowCount++
        }
    }
}
