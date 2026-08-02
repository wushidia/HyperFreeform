package io.hyper.freeform.xposed.model

import android.graphics.Rect

/**
 * Server-authoritative freeform task state (Xiaomi FreeformTaskState model).
 */
data class FreeformTaskState(
    val taskId: Int,
    var windowState: Int = WindowState.NORMAL,
    var bounds: Rect = Rect(),
    var scale: Float = 1f,
    var restoreNormalBounds: Rect = Rect(),
    var restoreMiniBounds: Rect = Rect(),
    var pinPos: Int = 0, // 0 left, 1 right (Xiaomi pin edge)
    /**
     * Xiaomi pin floating window Y (bubble top). -1 = derive from freeform bounds.top
     * until first pin placement / drag updatePinFloatingWindowPos.
     */
    var pinY: Int = -1,
    /** Full bubble dock rect when known (Xiaomi mPinFloatingWindowPos lite). */
    var pinFloatingWindowPos: Rect = Rect(),
    var pinActiveTime: Long = 0L,
    /** True while freeform→pin shrink window is running (Xiaomi interruptible). */
    var pinAnimating: Boolean = false,
    /** Last user interaction / focus time; used for multi-window replace LRU. */
    var activeTime: Long = System.currentTimeMillis(),
    var foregroundPin: Boolean = false,
    /** True only while the app requested a landscape activity/player inside the freeform. */
    var landscape: Boolean = false,
    /** When app-landscape: the TASK render rect; the visible window ([bounds]) is 16:9. */
    var landscapeTaskBounds: Rect = Rect(),
    var imeAvoiding: Boolean = false,
    var preImeBounds: Rect = Rect(),
    var packageName: String = "",
    var userId: Int = 0,
    var component: String = "",
    var alwaysOnTop: Boolean = true,
    /** In-window DPI override (Xiaomi small-window DPI zoom). 0 = follow system density. */
    var freeformDpi: Int = 0,
) {
    fun copyBounds(): Rect = Rect(bounds)

    fun snapshot(): FreeformTaskState = copy(
        bounds = Rect(bounds),
        restoreNormalBounds = Rect(restoreNormalBounds),
        restoreMiniBounds = Rect(restoreMiniBounds),
        preImeBounds = Rect(preImeBounds),
        pinFloatingWindowPos = Rect(pinFloatingWindowPos),
        landscapeTaskBounds = Rect(landscapeTaskBounds),
    )
}
