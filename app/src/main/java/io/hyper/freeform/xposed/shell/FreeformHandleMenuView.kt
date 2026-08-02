package io.hyper.freeform.xposed.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.hyper.freeform.xposed.policy.FreeformPolicy

/**
 * Xiaomi MulWinSwitch-style freeform window-control menu (handle menu).
 *
 * Freeform entry shows: fullscreen / split top-left / split bottom-right /
 * freeform(current) / close. Visual language matches MIUI caption buttons
 * (rounded card + circular icon cells), not Z-Flow VirtualDisplay chrome.
 */
class FreeformHandleMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    enum class Action {
        FULLSCREEN,
        SPLIT_TOP_OR_LEFT,
        SPLIT_BOTTOM_OR_RIGHT,
        FREEFORM,
        CLOSE,
    }

    fun interface Listener {
        fun onAction(action: Action)
    }

    private val d = resources.displayMetrics.density
    private val buttonSize = (44f * d).toInt()
    private val buttonGap = (6f * d).toInt()
    private val padH = (10f * d).toInt()
    private val padV = (8f * d).toInt()
    private val cardRadius = 18f * d

    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1A000000
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
        style = Paint.Style.FILL
    }

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(padH, padV, padH, padV)
    }

    private var listener: Listener? = null
    private var selected = Action.FREEFORM
    private var landscape = false

    init {
        // Transparent full-screen host so outside taps dismiss.
        setBackgroundColor(0x00000000)
        isClickable = true
        isFocusable = false
        clipToPadding = false
        clipChildren = false
        addView(
            row,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )
        rebuildButtons()
        setOnClickListener {
            // Outside the card: dismiss via FREEFORM no-op path (controller closes).
            listener?.onAction(Action.FREEFORM)
        }
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    fun setSelected(action: Action) {
        if (selected == action) return
        selected = action
        rebuildButtons()
    }

    fun setLandscape(value: Boolean) {
        if (landscape == value) return
        landscape = value
        rebuildButtons()
    }

    fun setCardTopMargin(px: Int) {
        val lp = row.layoutParams as LayoutParams
        if (lp.topMargin != px) {
            lp.topMargin = px
            row.layoutParams = lp
        }
    }

    fun preferredWidth(): Int = padH * 2 + buttonSize * 5 + buttonGap * 4

    fun preferredHeight(): Int = padV * 2 + buttonSize

    private fun rebuildButtons() {
        row.removeAllViews()
        addBtn(Action.FULLSCREEN, "fullscreen")
        addBtn(Action.SPLIT_TOP_OR_LEFT, if (landscape) "split-left" else "split-top")
        addBtn(Action.SPLIT_BOTTOM_OR_RIGHT, if (landscape) "split-right" else "split-bottom")
        addBtn(Action.FREEFORM, "freeform")
        addBtn(Action.CLOSE, "close")
    }

    private fun addBtn(action: Action, desc: String) {
        val v = IconButton(context, action, action == selected, landscape)
        v.contentDescription = desc
        val lp = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
            if (row.childCount > 0) marginStart = buttonGap
        }
        v.setOnClickListener {
            listener?.onAction(action)
        }
        // Prevent host outside-click from eating button presses.
        v.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                // consume down so parent FrameLayout click does not fire
            }
            false
        }
        row.addView(v, lp)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Draw card behind the button row.
        val l = row.left.toFloat()
        val t = row.top.toFloat()
        val r = row.right.toFloat()
        val b = row.bottom.toFloat()
        if (r > l && b > t) {
            val rect = RectF(l, t, r, b)
            // Soft drop shadow
            val shadow = RectF(rect)
            shadow.offset(0f, 1.5f * d)
            canvas.drawRoundRect(shadow, cardRadius, cardRadius, shadowPaint)
            canvas.drawRoundRect(rect, cardRadius, cardRadius, cardPaint)
            canvas.drawRoundRect(rect, cardRadius, cardRadius, cardStroke)
        }
        super.dispatchDraw(canvas)
    }

    private class IconButton(
        context: Context,
        private val action: Action,
        private val selected: Boolean,
        private val landscape: Boolean,
    ) : View(context) {
        private val d = resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.8f * d
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private var pressed = false

        init {
            isClickable = true
            isFocusable = false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    invalidate()
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = width.coerceAtMost(height) / 2f - 1f * d

            bg.color = when {
                selected -> 0x1A3482FF
                pressed -> 0x14000000
                else -> 0x00000000
            }
            canvas.drawCircle(cx, cy, r, bg)

            val accent = if (selected) 0xFF3482FF.toInt() else 0xFF333333.toInt()
            icon.color = accent
            fill.color = accent

            val s = r * 0.46f
            when (action) {
                Action.FULLSCREEN -> drawFullscreen(canvas, cx, cy, s)
                Action.SPLIT_TOP_OR_LEFT -> drawSplit(canvas, cx, cy, s, topOrLeft = true)
                Action.SPLIT_BOTTOM_OR_RIGHT -> drawSplit(canvas, cx, cy, s, topOrLeft = false)
                Action.FREEFORM -> drawFreeform(canvas, cx, cy, s)
                Action.CLOSE -> drawClose(canvas, cx, cy, s * 0.85f)
            }
        }

        private fun drawFullscreen(canvas: Canvas, cx: Float, cy: Float, s: Float) {
            val rect = RectF(cx - s, cy - s * 0.85f, cx + s, cy + s * 0.85f)
            canvas.drawRoundRect(rect, 2.2f * d, 2.2f * d, icon)
            // corner ticks to read as "expand"
            val t = s * 0.28f
            canvas.drawLine(rect.left + t, rect.top, rect.left, rect.top, icon)
            canvas.drawLine(rect.left, rect.top + t, rect.left, rect.top, icon)
            canvas.drawLine(rect.right - t, rect.bottom, rect.right, rect.bottom, icon)
            canvas.drawLine(rect.right, rect.bottom - t, rect.right, rect.bottom, icon)
        }

        private fun drawSplit(canvas: Canvas, cx: Float, cy: Float, s: Float, topOrLeft: Boolean) {
            val rect = RectF(cx - s, cy - s * 0.9f, cx + s, cy + s * 0.9f)
            canvas.drawRoundRect(rect, 2f * d, 2f * d, icon)
            fill.alpha = 160
            if (landscape) {
                val mid = cx
                if (topOrLeft) {
                    canvas.drawRect(rect.left + d, rect.top + d, mid - 0.5f * d, rect.bottom - d, fill)
                } else {
                    canvas.drawRect(mid + 0.5f * d, rect.top + d, rect.right - d, rect.bottom - d, fill)
                }
                canvas.drawLine(mid, rect.top + d, mid, rect.bottom - d, icon)
            } else {
                val mid = cy
                if (topOrLeft) {
                    canvas.drawRect(rect.left + d, rect.top + d, rect.right - d, mid - 0.5f * d, fill)
                } else {
                    canvas.drawRect(rect.left + d, mid + 0.5f * d, rect.right - d, rect.bottom - d, fill)
                }
                canvas.drawLine(rect.left + d, mid, rect.right - d, mid, icon)
            }
            fill.alpha = 255
        }

        private fun drawFreeform(canvas: Canvas, cx: Float, cy: Float, s: Float) {
            // Xiaomi freeform glyph: small rounded window slightly offset.
            val rect = RectF(cx - s * 0.85f, cy - s * 0.55f, cx + s * 0.55f, cy + s * 0.85f)
            canvas.drawRoundRect(rect, FreeformPolicy.FREEFORM_CORNER_DP * 0.25f * d, FreeformPolicy.FREEFORM_CORNER_DP * 0.25f * d, icon)
            // caption pill inside
            val pillH = 1.6f * d
            canvas.drawRoundRect(
                RectF(rect.left + s * 0.25f, rect.top + s * 0.22f, rect.right - s * 0.25f, rect.top + s * 0.22f + pillH),
                pillH,
                pillH,
                fill,
            )
        }

        private fun drawClose(canvas: Canvas, cx: Float, cy: Float, s: Float) {
            canvas.drawLine(cx - s, cy - s, cx + s, cy + s, icon)
            canvas.drawLine(cx + s, cy - s, cx - s, cy + s, icon)
        }
    }
}
