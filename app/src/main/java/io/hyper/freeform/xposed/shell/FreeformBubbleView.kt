package io.hyper.freeform.xposed.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import io.hyper.freeform.xposed.utils.SystemServices

/**
 * Xiaomi-like 64dp pin bubble (edge floating icon).
 * Container ~64dp, icon ~56dp; edge peek handled by BubbleSession position.
 */
class FreeformBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        setShadowLayer(10f * d, 0f, 3f * d, 0x44000000)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = 0x553482FF
    }
    private var icon: Drawable? = null
    private var label: String = "?"
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3482FF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 18f * d
        isFakeBoldText = true
    }

    init {
        // Shadow layer requires software for reliable soft shadow on overlays.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun bind(packageName: String) {
        label = packageName.substringAfterLast('.').take(1).uppercase().ifEmpty { "?" }
        icon = runCatching {
            SystemServices.packageManager.getApplicationIcon(packageName)
        }.getOrNull()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // Xiaomi floating_window_corner_radius ~18.18dp on square → near-circle.
        val radius = width.coerceAtMost(height) / 2f - 3f * d
        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        val ic = icon
        if (ic != null) {
            // Icon ~56dp inside 64dp container.
            val s = (radius * 1.55f).toInt().coerceAtMost((56f * d).toInt())
            val left = (cx - s / 2f).toInt()
            val top = (cy - s / 2f).toInt()
            ic.setBounds(left, top, left + s, top + s)
            ic.draw(canvas)
        } else {
            canvas.drawText(label, cx, cy + textPaint.textSize / 3f, textPaint)
        }
    }
}
