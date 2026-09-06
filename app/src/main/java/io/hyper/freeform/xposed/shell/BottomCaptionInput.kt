package io.hyper.freeform.xposed.shell

import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.input.InputManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import de.robv.android.xposed.XposedHelpers
import io.hyper.freeform.xposed.utils.SystemServices
import io.hyper.freeform.xposed.utils.XLog
import kotlin.math.abs

/**
 * Observe only the bottom handle, leaving the original touch stream with the app.
 * A vertical drag pilfers the stream (the app receives CANCEL); taps, long presses
 * and horizontal motion never leave the app. No synthetic click replay is needed.
 */
internal class BottomCaptionInput(
    private val taskId: Int,
    private val onDrag: (MotionEvent) -> Unit,
) {
    private val context = SystemServices.systemContext
    private val wm get() = SystemServices.windowManager
    private val view = View(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var down: MotionEvent? = null
    private var captured = false
    private var attached = false
    private var unavailable = false
    private val params = WindowManager.LayoutParams(
        1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        title = "HyperFreeformBottomInput-$taskId"
        SystemServices.applyOverlaySystemUiPassthrough(this)
    }

    init {
        view.setOnTouchListener { _, event ->
            handle(event)
            true // A SPY window observes; this does not consume the app's stream.
        }
    }

    fun update(bounds: Rect?) {
        if (bounds == null || bounds.isEmpty) {
            detach()
            return
        }
        if (unavailable) return
        params.x = bounds.left
        params.y = bounds.top
        params.width = bounds.width()
        params.height = bounds.height()
        runCatching {
            if (attached) {
                wm.updateViewLayout(view, params)
            } else {
                // Resolve the flag instead of guessing on older/vendor frameworks. If SPY
                // is unavailable, keep taps working and omit the optional bottom gesture.
                val clazz = WindowManager.LayoutParams::class.java
                val spy = XposedHelpers.getStaticIntField(clazz, "INPUT_FEATURE_SPY")
                XposedHelpers.setIntField(params, "inputFeatures", spy)
                XposedHelpers.callMethod(params, "setTrustedOverlay")
                wm.addView(view, params)
                attached = true
                XLog.d("bottom caption observes app touches task=$taskId")
            }
        }.onFailure {
            unavailable = true
            detach()
            XLog.e("bottom caption input unavailable; taps remain with app", it)
        }
    }

    private fun handle(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                down = MotionEvent.obtain(event)
            }
            MotionEvent.ACTION_MOVE -> {
                val start = down ?: return
                if (!captured) {
                    val dx = abs(event.rawX - start.rawX)
                    val dy = abs(event.rawY - start.rawY)
                    if (dx <= slop && dy <= slop) return
                    if (dx >= dy || event.eventTime - start.eventTime >=
                        ViewConfiguration.getLongPressTimeout()
                    ) {
                        reset()
                        return
                    }
                    val acquired = runCatching {
                        val root = XposedHelpers.callMethod(view, "getViewRootImpl")
                        val token = XposedHelpers.callMethod(root, "getInputToken")
                        val manager = context.getSystemService(InputManager::class.java)
                        XposedHelpers.callMethod(manager, "pilferPointers", token)
                    }.onFailure { XLog.e("bottom caption drag capture failed", it) }.isSuccess
                    if (!acquired) {
                        reset()
                        return
                    }
                    captured = true
                    onDrag(start)
                }
                onDrag(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancel(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasCaptured = captured
                reset()
                if (wasCaptured) onDrag(event)
            }
        }
    }

    private fun cancel(event: MotionEvent) {
        if (captured) {
            val cancel = MotionEvent.obtain(event)
            reset()
            try {
                cancel.action = MotionEvent.ACTION_CANCEL
                onDrag(cancel)
            } finally {
                cancel.recycle()
            }
        } else reset()
    }

    private fun reset() {
        down?.recycle()
        down = null
        captured = false
    }

    fun detach() {
        down?.let { cancel(it) }
        if (attached) runCatching { wm.removeView(view) }
        attached = false
    }
}
