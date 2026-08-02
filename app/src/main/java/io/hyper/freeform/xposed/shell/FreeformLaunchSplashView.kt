package io.hyper.freeform.xposed.shell

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import io.hyper.freeform.xposed.policy.FreeformPolicy
import io.hyper.freeform.xposed.utils.SystemServices

/** Immediate icon-style starting surface shown until the app's real main window is drawn. */
class FreeformLaunchSplashView(
    context: Context,
    packageName: String,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val themed = resolveThemeSplash(packageName)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (SidebarController.isDark()) 0xFF202124.toInt() else 0xFFF7F7F7.toInt()
    }
    private val background = themed.first
    private val icon = themed.second ?: runCatching {
        SystemServices.packageManager.getApplicationIcon(packageName).mutate()
    }.getOrNull()
    private val clipPath = Path()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val radius = FreeformPolicy.freeformVisibleCornerRadiusPx(
            false,
            width,
            height,
            context,
        )
        clipPath.reset()
        clipPath.addRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            radius,
            radius,
            Path.Direction.CW,
        )
        val saveCount = canvas.save()
        try {
            canvas.clipPath(clipPath)
            // Always establish an opaque base. Some apps intentionally declare a transparent
            // StartingWindow background, which otherwise exposes the empty launch mask.
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
            background?.let { bg ->
                runCatching {
                    bg.bounds = Rect(0, 0, width, height)
                    bg.draw(canvas)
                }
            }

            icon?.let { drawable ->
                val maxSize = (72f * density).toInt()
                val size = minOf(maxSize, (width * 0.28f).toInt(), (height * 0.2f).toInt())
                    .coerceAtLeast((32f * density).toInt().coerceAtMost(minOf(width, height)))
                val left = (width - size) / 2
                val top = (height - size) / 2
                runCatching {
                    drawable.bounds = Rect(left, top, left + size, top + size)
                    drawable.draw(canvas)
                }
            }
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    private fun resolveThemeSplash(packageName: String): Pair<Drawable?, Drawable?> {
        return runCatching {
            val packageContext = context.createPackageContext(
                packageName,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            val component = SystemServices.packageManager
                .getLaunchIntentForPackage(packageName)?.component
            val activityInfo = component?.let {
                SystemServices.packageManager.getActivityInfo(it, 0)
            }
            val theme = packageContext.resources.newTheme().apply {
                val themeRes = activityInfo?.themeResource ?: 0
                if (themeRes != 0) applyStyle(themeRes, true)
            }

            fun themedDrawable(attribute: Int): Drawable? {
                val value = TypedValue()
                if (!theme.resolveAttribute(attribute, value, true)) return null
                if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                    return ColorDrawable(value.data)
                }
                if (value.resourceId == 0) return null
                return packageContext.resources.getDrawable(value.resourceId, theme).mutate()
            }

            themedDrawable(android.R.attr.windowSplashScreenBackground) to
                themedDrawable(android.R.attr.windowSplashScreenAnimatedIcon)
        }.getOrDefault(null to null)
    }
}
