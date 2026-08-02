package io.hyper.freeform.ui.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Load a launcher icon for a package as a Bitmap (for Compose Image/asImageBitmap).
 * Adapts any Drawable (Vector / Adaptive) into a Bitmap of the given dp size so it can be drawn
 * by an [androidx.compose.foundation.Image]. Returns null when the icon is unavailable.
 */
object AppIconPainter {
    fun load(ctx: Context, packageName: String, sizeDp: Int = 48): Bitmap? {
        return runCatching {
            val pm = ctx.packageManager
            val drawable: Drawable = pm.getApplicationIcon(packageName)
            val density = ctx.resources.displayMetrics.density
            val px = (sizeDp * density).toInt().coerceAtLeast(1)
            toBitmap(drawable, px)
        }.getOrNull()
    }

    private fun toBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }
}

/** Compose helper: returns a Bitmap for [packageName]'s launcher icon, or null. */
@androidx.compose.runtime.Composable
fun AppIconPainter(packageName: String, sizeDp: Int = 48): Bitmap? {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(packageName, sizeDp) {
        io.hyper.freeform.ui.utils.AppIconPainter.load(ctx, packageName, sizeDp)
    }
}
