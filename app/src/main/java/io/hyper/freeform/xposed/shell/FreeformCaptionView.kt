package io.hyper.freeform.xposed.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Xiaomi freeform top chrome: three-dot drag handle drawn ON the freeform window top.
 * Not a detached bar above the task (no Z-Flow style external caption strip).
 */
class FreeformCaptionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var active = false
    private var mini = false
    private var bottomDrag = 0f
    private var flashUntil = 0L

    fun setActive(value: Boolean) {
        active = value
        invalidate()
    }

    fun setMini(value: Boolean) {
        mini = value
        invalidate()
    }

    fun setTitle(value: String) {
        // Kept as a no-op API for session binding; Xiaomi top chrome is pill-only.
        invalidate()
    }

    fun setBottomDrag(dy: Float) {
        bottomDrag = dy
        invalidate()
    }

    fun flash() {
        flashUntil = System.currentTimeMillis() + 220
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val flash = System.currentTimeMillis() < flashUntil
        val cy = height / 2f + (bottomDrag * 0.04f).coerceIn(-6f * d, 6f * d)
        val cx = width / 2f

        // Xiaomi-style three-dot drag handle (••• centered on the freeform window top).
        dotPaint.color = when {
            active || flash -> 0xFF3482FF.toInt()
            mini -> 0x99FFFFFF.toInt()
            else -> 0x99000000.toInt()
        }
        val dotR = (if (mini) 1.9f else 2.3f) * d
        val gap = (if (mini) 6f else 7f) * d // center-to-center spacing
        canvas.drawCircle(cx - gap, cy, dotR, dotPaint)
        canvas.drawCircle(cx, cy, dotR, dotPaint)
        canvas.drawCircle(cx + gap, cy, dotR, dotPaint)

        // No full-width title/plate: the top chrome is the three-dot handle and nothing else.
    }
}
