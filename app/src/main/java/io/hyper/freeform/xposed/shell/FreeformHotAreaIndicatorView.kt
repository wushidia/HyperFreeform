package io.hyper.freeform.xposed.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import io.hyper.freeform.xposed.policy.FreeformPolicy

/**
 * Xiaomi MulWinSwitch / MiuiFreeformModeVisualIndicator lite for freeform drag hot areas.
 * Phone freeform → fullscreen: translucent full-screen plate when top hot zone is armed.
 */
class FreeformHotAreaIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x553482FF
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC3482FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
    }
    private val tip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEEFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 14f * d
        isFakeBoldText = true
    }
    private val rect = RectF()
    private var mode: Int = FreeformPolicy.HOT_AREA_TYPE_FREEFORM

    fun setHotArea(type: Int) {
        if (mode == type) return
        mode = type
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (mode != FreeformPolicy.HOT_AREA_TYPE_FULLSCREEN &&
            mode != FreeformPolicy.HOT_AREA_TYPE_SPLIT_TOP &&
            mode != FreeformPolicy.HOT_AREA_TYPE_SPLIT_BOTTOM
        ) {
            return
        }
        val inset = 10f * d
        val radius = 18f * d
        when (mode) {
            FreeformPolicy.HOT_AREA_TYPE_FULLSCREEN -> {
                rect.set(inset, inset, width - inset, height - inset)
            }
            FreeformPolicy.HOT_AREA_TYPE_SPLIT_TOP -> {
                rect.set(inset, inset, width - inset, height / 2f - inset / 2f)
            }
            FreeformPolicy.HOT_AREA_TYPE_SPLIT_BOTTOM -> {
                rect.set(inset, height / 2f + inset / 2f, width - inset, height - inset)
            }
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, stroke)
        val label = when (mode) {
            FreeformPolicy.HOT_AREA_TYPE_FULLSCREEN -> "全屏"
            FreeformPolicy.HOT_AREA_TYPE_SPLIT_TOP -> "分屏 · 上"
            FreeformPolicy.HOT_AREA_TYPE_SPLIT_BOTTOM -> "分屏 · 下"
            else -> ""
        }
        if (label.isNotEmpty()) {
            canvas.drawText(label, rect.centerX(), rect.centerY() + tip.textSize / 3f, tip)
        }
    }
}
