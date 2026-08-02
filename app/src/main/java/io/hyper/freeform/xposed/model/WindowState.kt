package io.hyper.freeform.xposed.model

/**
 * Xiaomi freeform logical windowState on top of WINDOWING_MODE_FREEFORM=5.
 * Evidence: docs/小米HyperOS自由窗口实现与重写指南.md §4.2
 */
object WindowState {
    const val UNDEFINED = -1
    const val NORMAL = 0
    const val MINI = 1
    const val PIN_FROM_NORMAL = 2
    const val PIN_FROM_MINI = 3

    fun isPinned(state: Int): Boolean = state == PIN_FROM_NORMAL || state == PIN_FROM_MINI
    fun isMini(state: Int): Boolean = state == MINI
    fun isVisibleFreeform(state: Int): Boolean = state == NORMAL || state == MINI

    fun pinTarget(from: Int): Int = when (from) {
        MINI -> PIN_FROM_MINI
        else -> PIN_FROM_NORMAL
    }

    fun unpinRestore(from: Int): Int = when (from) {
        PIN_FROM_MINI -> MINI
        else -> NORMAL
    }

    fun label(state: Int): String = when (state) {
        UNDEFINED -> "undefined"
        NORMAL -> "normal"
        MINI -> "mini"
        PIN_FROM_NORMAL -> "pin_normal"
        PIN_FROM_MINI -> "pin_mini"
        else -> "unknown($state)"
    }
}

/** Xiaomi action codes subset (0-22) used by shell/server. */
object FreeformAction {
    const val OPEN = 0
    const val MOVE = 1
    const val RESIZE = 2
    const val TO_MINI = 3
    const val TO_NORMAL = 4
    const val PIN = 5
    const val UNPIN = 6
    const val CLOSE = 7
    const val FULLSCREEN = 8
    const val AVOID = 9
}
