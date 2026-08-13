package io.hyper.freeform.service

import kotlin.math.roundToInt

/**
 * Cross-process fallback used when a ROM's SELinux policy rejects a custom ServiceManager name.
 * The system_server receiver validates the real sending UID before executing any command.
 */
object FreeformBridge {
    const val ACTION = "io.hyper.freeform.action.SYSTEM_BRIDGE"

    const val EXTRA_OPERATION = "operation"
    const val EXTRA_COMPONENT = "component"
    const val EXTRA_PACKAGE = "package"
    const val EXTRA_BOUNDS = "bounds"
    const val EXTRA_TASK_ID = "task_id"
    const val EXTRA_WINDOW_STATE = "window_state"
    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_SIDE = "side"
    const val EXTRA_PACKAGES_CSV = "packages_csv"
    const val EXTRA_SHOW = "show"
    const val EXTRA_PERCENT = "percent"
    const val EXTRA_DPI = "dpi"
    const val EXTRA_WIDTH_PERCENT = "width_percent"
    const val EXTRA_HEIGHT_PERCENT = "height_percent"
    const val EXTRA_POSITION = "position"
    const val EXTRA_PIN_POSITION = "pin_position"
    const val EXTRA_Y = "y"
    const val EXTRA_HEIGHT = "height"

    const val OP_START_COMPONENT = "start_component"
    const val OP_START_PACKAGE = "start_package"
    const val OP_START_RECENT = "start_recent"
    const val OP_ADOPT_PENDING_INTENT_LAUNCH = "adopt_pending_intent_launch"
    const val OP_SET_ENABLED = "set_enabled"
    const val OP_CLOSE_TASK = "close_task"
    const val OP_PIN_TASK = "pin_task"
    const val OP_UNPIN_TASK = "unpin_task"
    const val OP_PIN_TO_FULLSCREEN = "pin_to_fullscreen"
    const val OP_UPDATE_PIN_POSITION = "update_pin_position"
    const val OP_SPLIT_TASK = "split_task"
    const val OP_SET_FREEFORM_DPI = "set_freeform_dpi"
    const val OP_SET_GLOBAL_DPI = "set_global_dpi"
    const val OP_SET_GLOBAL_WINDOW_SIZE = "set_global_window_size"
    const val OP_COLLAPSE_STATUS_BAR = "collapse_status_bar"
    const val OP_DEBUG_IME = "debug_ime"
    const val OP_SET_SIDEBAR_SIDE = "set_sidebar_side"
    const val OP_SET_SIDEBAR_APPS = "set_sidebar_apps"
    const val OP_SET_SIDEBAR_SHOW_NAMES = "set_sidebar_show_names"

    const val SETTING_READY_BOOT = "hyper_freeform_ready_boot"
    const val SETTING_ENABLED = "hyper_freeform_service_enabled"
    const val SETTING_SIDEBAR_SIDE = "hyper_freeform_sidebar_side"
    const val SETTING_SIDEBAR_APPS = "hyper_freeform_sidebar_apps"
    const val SETTING_SIDEBAR_SHOW_NAMES = "hyper_freeform_sidebar_show_app_names"
    /** Legacy v1 stored the hidden 50..200 density mapping, not the displayed slider value. */
    const val SETTING_DPI_PERCENT_LEGACY = "hyper_freeform_dpi_percent"
    /** v2 stores the real percentage shown in the UI: 60 means 60% of system density. */
    const val SETTING_DPI_PERCENT = "hyper_freeform_dpi_percent_v2"
    const val SETTING_WINDOW_WIDTH_PERCENT = "hyper_freeform_window_width_percent"
    const val SETTING_WINDOW_HEIGHT_PERCENT = "hyper_freeform_window_height_percent"
    /** Comma-separated task ids which were just maximized from freeform. */
    const val SETTING_SUPPRESS_FOREGROUND_MINI_TASKS =
        "hyper_freeform_suppress_foreground_mini_tasks"

    const val DEFAULT_DPI_PERCENT = 100
    const val MIN_DPI_PERCENT = 0
    const val MAX_DPI_PERCENT = 100
    const val DEFAULT_WINDOW_WIDTH_PERCENT = 62
    const val DEFAULT_WINDOW_HEIGHT_PERCENT = 58
    const val MIN_WINDOW_SIZE_PERCENT = 0
    const val MAX_WINDOW_SIZE_PERCENT = 100

    /**
     * Convert the old fake slider mapping to the v2 real percentage.
     *
     * v1 displayed level = (storedDensityPercent - 50) / 1.5. The useful legacy range requested
     * for v2 is 0..10, so level 6 becomes 60 and level 10 (or anything above it) becomes 100.
     */
    fun migrateLegacyDpiPercent(storedDensityPercent: Int): Int {
        val legacyLevel = ((storedDensityPercent.coerceIn(50, 200) - 50) / 1.5f)
            .roundToInt()
            .coerceIn(0, 10)
        return legacyLevel * 10
    }

    fun sanitizeDpiPercent(percent: Int): Int =
        percent.coerceIn(MIN_DPI_PERCENT, MAX_DPI_PERCENT)

    fun sanitizeWindowSizePercent(percent: Int): Int =
        percent.coerceIn(MIN_WINDOW_SIZE_PERCENT, MAX_WINDOW_SIZE_PERCENT)
}
