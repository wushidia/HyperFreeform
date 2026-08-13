package io.hyper.freeform.ui.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * Load a launcher icon for a package as a Bitmap (for Compose Image/asImageBitmap).
 * Adapts any Drawable (Vector / Adaptive) into a Bitmap of the given dp size so it can be drawn
 * by an [androidx.compose.foundation.Image]. Returns null when the icon is unavailable.
 */
object AppIconPainter {
    private val cache = object : LruCache<String, Bitmap>(64) {}

    fun load(ctx: Context, packageName: String, sizeDp: Int = 48): Bitmap? {
        val key = "$packageName@$sizeDp"
        cache.get(key)?.let { return it }
        return runCatching {
            val pm = ctx.packageManager
            val drawable: Drawable = pm.getApplicationIcon(packageName)
            val density = ctx.resources.displayMetrics.density
            val px = (sizeDp * density).toInt().coerceAtLeast(1)
            toBitmap(drawable, px).also { cache.put(key, it) }
        }.getOrNull()
    }

    private fun toBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val source = drawable.bitmap
            if (source.width == sizePx && source.height == sizePx) return source
            return Bitmap.createScaledBitmap(source, sizePx, sizePx, true)
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }
}
