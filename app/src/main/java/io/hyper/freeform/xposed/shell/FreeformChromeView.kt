package io.hyper.freeform.xposed.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.hyper.freeform.xposed.policy.FreeformPolicy

/**
 * Xiaomi freeform body chrome: stroke + bottom corner tips.
 * Drawn over the freeform overlay (not Z-Flow VirtualDisplay decoration).
 */
class FreeformChromeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = FreeformPolicy.STROKE_COLOR
    }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xCC808080.toInt() // freeform_corner_tip_color
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = RectF()
    private var captionHeight = 0f
    private var mini = false
    private var showTips = true
    private var active = false

    init {
        // Never intercept gestures; parent/handles own touch.
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setCaptionHeight(px: Int) {
        captionHeight = px.toFloat()
        invalidate()
    }

    fun setMini(value: Boolean) {
        mini = value
        invalidate()
    }

    fun setShowTips(value: Boolean) {
        showTips = value
        invalidate()
    }

    fun setActive(value: Boolean) {
        active = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val stroke = (if (mini) FreeformPolicy.MINI_STROKE_THICKNESS_DP else FreeformPolicy.STROKE_THICKNESS_DP) * d
        strokePaint.strokeWidth = stroke
        strokePaint.color = if (active) 0x993482FF.toInt() else FreeformPolicy.STROKE_COLOR

        // Full freeform frame (Xiaomi: stroke wraps the freeform window itself;
        // caption pill is drawn on top inside the same bounds, not above it).
        val inset = stroke / 2f
        val frameRadius = FreeformPolicy.freeformChromePathRadiusPx(
            mini,
            width,
            height,
            stroke,
            context,
        )
        body.set(
            inset,
            inset,
            width - inset,
            height - inset,
        )
        if (body.height() <= 0f || body.width() <= 0f) return
        canvas.drawRoundRect(body, frameRadius, frameRadius, strokePaint)

        // Xiaomi-style bottom center handle bar: a small pill indicating the bottom swipe zone
        // (up = close / down = fullscreen). Blends into the window like the top handle.
        if (!mini) {
            // Width tracks the bottom-center gesture zone (FreeformShellController.bottomBarTouchWidth)
            // so the visible 小白条 indicates exactly where the close/fullscreen swipe responds.
            val barW = 48f * d
            val barH = 4f * d
            val cx = width / 2f
            val cy = body.bottom - 10f * d
            val bar = RectF(cx - barW / 2f, cy - barH / 2f, cx + barW / 2f, cy + barH / 2f)
            barPaint.color = if (active) 0xCC3482FF.toInt() else 0x66000000
            canvas.drawRoundRect(bar, barH, barH, barPaint)
        }

        // Bottom corner tips (Xiaomi corner_tips_*): arc affordances for resize.
        if (showTips && !mini) {
            // The hint arc follows the exact window corner circle; no independent radius.
            val tipR = frameRadius.coerceAtMost(minOf(body.width(), body.height()) / 2f)
            val thick = FreeformPolicy.freeformCornerTipThicknessPx(context)
            tipPaint.strokeWidth = thick
            tipPaint.alpha = if (active) 220 else 160
            // Bottom-right arc
            val br = RectF(
                body.right - tipR * 2f,
                body.bottom - tipR * 2f,
                body.right,
                body.bottom,
            )
            canvas.drawArc(br, 0f, 90f, false, tipPaint)
            // Bottom-left arc
            val bl = RectF(
                body.left,
                body.bottom - tipR * 2f,
                body.left + tipR * 2f,
                body.bottom,
            )
            canvas.drawArc(bl, 90f, 90f, false, tipPaint)
        }
    }
}
