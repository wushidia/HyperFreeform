package io.hyper.freeform.xposed.policy

import android.app.ActivityManager
import android.content.Context
import android.graphics.Rect
import android.view.WindowManager
import io.hyper.freeform.service.FreeformBridge
import io.hyper.freeform.xposed.shell.SidebarController
import io.hyper.freeform.xposed.utils.SystemServices
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Capability / layout policy modeled after Xiaomi MiuiMultiWindowUtils +
 * MiuiFreeformModeAvoidAlgorithm stack strategy.
 */
object FreeformPolicy {
    /** One authoritative screen-space frame shared by the task leash and module overlays. */
    data class WindowVisualFrame(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
        val alpha: Float,
    ) {
        val centerX: Float get() = left + width / 2f
        val centerY: Float get() = top + height / 2f
    }
    const val WINDOWING_MODE_FREEFORM = 5
    const val WINDOWING_MODE_FULLSCREEN = 1
    /** AOSP multi-window (split stages). Xiaomi freeform→split ends in this mode. */
    const val WINDOWING_MODE_MULTI_WINDOW = 6
    /** Side stage position: left/top (Xiaomi position 0). */
    const val SPLIT_POSITION_TOP_OR_LEFT = 0
    /** Side stage position: right/bottom (Xiaomi position 1). */
    const val SPLIT_POSITION_BOTTOM_OR_RIGHT = 1

    // Default portrait freeform aspect is user-configurable through Settings.Global.
    /** HyperOS landscape placement measured from the reference layout: tall, high and edge-docked. */
    private const val LANDSCAPE_NORMAL_HEIGHT_RATIO = 0.76f
    private const val LANDSCAPE_NORMAL_MAX_WIDTH_RATIO = 0.34f
    private const val LANDSCAPE_NORMAL_TOP_RATIO = 0.07f
    private const val LANDSCAPE_NORMAL_EDGE_MARGIN_RATIO = 0.025f
    private const val LANDSCAPE_NORMAL_EDGE_MARGIN_DP = 16
    private const val MINI_WIDTH_RATIO = 0.28f
    private const val MIN_SCALE = 0.45f
    private const val MAX_SCALE = 1.0f
    private const val MINI_SCALE_THRESHOLD = 0.55f

    /** Fallback only; runtime reads the active SystemUI freeform radius. */
    const val FREEFORM_CORNER_DP = 18f
    /** Floor so a square-corner ROM still gets a readable freeform silhouette. */
    const val MIN_FREEFORM_CORNER_DP = 12f
    /**
     * Display-panel radii above this are bezels, not window radii. Using them raw makes a small
     * window look like a pill.
     */
    private const val MAX_WINDOW_CORNER_DP = 28f
    const val MINI_CORNER_DP = 12f
    /** Xiaomi floating_window edge peek (only ~24dp remains on-screen) */
    const val BUBBLE_PEEK_DP = 24
    /** Fallback only; corner tips use the same runtime system corner radius. */
    const val CORNER_TIP_RADIUS_DP = 20f
    const val CORNER_TIP_THICKNESS_DP = 4f
    /** Xiaomi freeform_stroke_color / freeform_mini_stroke_color */
    val STROKE_COLOR: Int = 0x66C0C0C0.toInt()
    /** Visual stroke thickness for normal freeform chrome overlay */
    const val STROKE_THICKNESS_DP = 1.5f
    /** Mini stroke thickness ≈ MINI_FREEFORM_PADDING_STROKE visual edge */
    const val MINI_STROKE_THICKNESS_DP = 1.5f
    /** Xiaomi freeform_resize_corner hit visual */
    const val RESIZE_CORNER_DP = 44

    /** Xiaomi FREEFORM_RECT_OFFSET_X_ZIZHAN ≈ 78dp */
    private const val AVOID_OFFSET_X_DP = 78
    /** Xiaomi FREEFORM_RECT_OFFSET_Y_ZIZHAN ≈ 44dp */
    private const val AVOID_OFFSET_Y_DP = 44
    /** Xiaomi MIUI_FREEFORM_GAP = 6dp */
    private const val FREEFORM_GAP_DP = 6
    /** Xiaomi MiuiFreeformModeAvoidAlgorithm.IME_GAP = 20px */
    const val IME_GAP_PX = 20
    /** Xiaomi MIN_VISIBLE_AVOID_IME_FRAME_HEIGHT ≈ 200px when crop/shrink is required. */
    private const val MIN_VISIBLE_AVOID_IME_FRAME_HEIGHT = 200

    /** Settings.Global: force TOTAL_RAM GB for low-memory gate verification (0 = real). */
    const val SETTINGS_FORCE_RAM_GB = "hyper_freeform_force_ram_gb"
    /** Settings.Global: force enable/disable freeform regardless of RAM (-1 default, 0/1 force). */
    const val SETTINGS_FORCE_FREEFORM = "hyper_freeform_force_support"
    /** Settings.Secure: Xiaomi sidebar line bounds JSON (MiuiFreeformModeSettingsObserver). */
    const val SETTINGS_SIDEBAR_BOUNDS = "sidebar_bounds"
    /** Settings.Global: Xiaomi-like enable_foreground_pin (1 enable when RAM allows). */
    const val SETTINGS_ENABLE_FOREGROUND_PIN = "hyper_freeform_enable_foreground_pin"
    /** Debug: treat this package as payment/transfer protected (MuMu E2E without Alipay). */
    const val SETTINGS_TEST_PROTECT_PKG = "hyper_freeform_test_protect_pkg"
    /** Settings.Global: in-window DPI as percentage of system density (100 = follow system). */
    const val SETTINGS_DPI_PERCENT = FreeformBridge.SETTING_DPI_PERCENT
    const val SETTINGS_DPI_PERCENT_LEGACY = FreeformBridge.SETTING_DPI_PERCENT_LEGACY
    const val DEFAULT_DPI_PERCENT = FreeformBridge.DEFAULT_DPI_PERCENT
    const val SETTINGS_WINDOW_WIDTH_PERCENT = FreeformBridge.SETTING_WINDOW_WIDTH_PERCENT
    const val SETTINGS_WINDOW_HEIGHT_PERCENT = FreeformBridge.SETTING_WINDOW_HEIGHT_PERCENT
    const val DEFAULT_WINDOW_WIDTH_PERCENT = FreeformBridge.DEFAULT_WINDOW_WIDTH_PERCENT
    const val DEFAULT_WINDOW_HEIGHT_PERCENT = FreeformBridge.DEFAULT_WINDOW_HEIGHT_PERCENT
    /** Current boot in which the SystemUI caption-suppression hooks were installed. */
    const val SETTINGS_SYSTEMUI_HOOK_BOOT = "hyper_freeform_systemui_hook_boot"

    /**
     * Resolve the user's global freeform DPI setting (percentage) into an absolute densityDpi.
     * Returns 0 when following the system (100%). Android cannot represent 0dpi, so the 0 end of
     * the UI is constrained to the platform-safe 120dpi floor while retaining a continuous slider.
     */
    fun freeformDpiFromSettings(context: Context = SystemServices.systemContext): Int {
        val percent = runCatching {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                SETTINGS_DPI_PERCENT,
                DEFAULT_DPI_PERCENT,
            )
        }.getOrDefault(DEFAULT_DPI_PERCENT)
        val normalized = FreeformBridge.sanitizeDpiPercent(percent)
        if (normalized == 100) return 0
        val sysDpi = context.resources.displayMetrics.densityDpi
        return (sysDpi * normalized / 100f).roundToInt()
            .coerceIn(120.coerceAtMost(sysDpi), sysDpi)
    }

    fun windowWidthPercentFromSettings(context: Context = SystemServices.systemContext): Int =
        runCatching {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                SETTINGS_WINDOW_WIDTH_PERCENT,
                DEFAULT_WINDOW_WIDTH_PERCENT,
            )
        }.getOrDefault(DEFAULT_WINDOW_WIDTH_PERCENT)
            .coerceIn(FreeformBridge.MIN_WINDOW_SIZE_PERCENT, FreeformBridge.MAX_WINDOW_SIZE_PERCENT)

    fun windowHeightPercentFromSettings(context: Context = SystemServices.systemContext): Int =
        runCatching {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                SETTINGS_WINDOW_HEIGHT_PERCENT,
                DEFAULT_WINDOW_HEIGHT_PERCENT,
            )
        }.getOrDefault(DEFAULT_WINDOW_HEIGHT_PERCENT)
            .coerceIn(FreeformBridge.MIN_WINDOW_SIZE_PERCENT, FreeformBridge.MAX_WINDOW_SIZE_PERCENT)

    fun totalRamMb(context: Context = SystemServices.systemContext): Int {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(1)
    }

    /**
     * Xiaomi Build.TOTAL_RAM / MiuiMultiWindowUtils.getTotalRam() style integer GB.
     * Honors [SETTINGS_FORCE_RAM_GB] so low-memory gates can be verified on high-RAM emulators.
     */
    fun totalRamGb(context: Context = SystemServices.systemContext): Int {
        val forced = runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, SETTINGS_FORCE_RAM_GB, 0)
        }.getOrDefault(0)
        if (forced > 0) return forced
        // Floor to match Xiaomi formatSizeWith1024 GB buckets (8GB device → 8, not 9).
        return (totalRamMb(context) / 1024).coerceAtLeast(1)
    }

    /**
     * Xiaomi supportFreeform / stack max gate.
     * Evidence: MiuiFreeFormStackDisplayStrategy + isNotSupportFreeformForCheckMemory (RAM < 3 → 0).
     */
    fun supportsFreeform(context: Context = SystemServices.systemContext): Boolean {
        val forced = runCatching {
            android.provider.Settings.Global.getInt(context.contentResolver, SETTINGS_FORCE_FREEFORM, -1)
        }.getOrDefault(-1)
        if (forced == 0) return false
        if (forced == 1) return true
        if (maxFreeformCount(context) <= 0) return false
        // AOSP freeform flag still respected when capability otherwise allows.
        val aosp = runCatching {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                "enable_freeform_support",
                1
            )
        }.getOrDefault(1)
        return aosp != 0
    }

    /** Xiaomi multiFreeFormSupported: TOTAL_RAM > 4. */
    fun supportsMultiFreeform(context: Context = SystemServices.systemContext): Boolean =
        totalRamGb(context) > 4

    /**
     * Xiaomi MiuiFreeFormStackDisplayStrategy.getMaxMiuiFreeFormStackCount (phone, non-desktop):
     * RAM < 3 → 0; RAM 3–4 / !multi → 1; else default 2.
     */
    fun maxFreeformCount(context: Context = SystemServices.systemContext): Int {
        val ramGb = totalRamGb(context)
        if (ramGb < 3) return 0
        if (!supportsMultiFreeform(context) || ramGb <= 4) return 1
        // mDefaultMaxFreeformCount = 2 (desktop mode 4 is out of phone scope)
        return 2
    }

    /**
     * Xiaomi supportForeGroundPin: TOTAL_RAM > 6 + enable_foreground_pin.
     * Regular edge-pin (bubble) remains available; this only gates foreground-priority pin.
     */
    fun supportsForegroundPin(context: Context = SystemServices.systemContext): Boolean {
        if (totalRamGb(context) <= 6) return false
        return runCatching {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                SETTINGS_ENABLE_FOREGROUND_PIN,
                1
            ) == 1
        }.getOrDefault(true)
    }

    fun capabilityDump(context: Context = SystemServices.systemContext): String {
        val ramGb = totalRamGb(context)
        val ramMb = totalRamMb(context)
        return "ramMb=$ramMb ramGb=$ramGb support=${supportsFreeform(context)} " +
            "multi=${supportsMultiFreeform(context)} max=${maxFreeformCount(context)} " +
            "fgPin=${supportsForegroundPin(context)}"
    }

    fun displaySize(context: Context = SystemServices.systemContext): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        val bounds = wm.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    fun movableRestriction(context: Context = SystemServices.systemContext): Rect {
        val (dw, dh) = displaySize(context)
        val pad = dp(context, 8)
        val top = dp(context, 48)
        val bottom = dp(context, 24)
        return Rect(pad, top, dw - pad, dh - bottom)
    }

    fun freeformGap(context: Context = SystemServices.systemContext): Int =
        dp(context, FREEFORM_GAP_DP)


    /**
     * Half-screen bounds for freeform→split (Xiaomi phone uses top/bottom).
     * @param position 0=top/left, 1=bottom/right
     */
    fun splitHalfBounds(
        position: Int,
        context: Context = SystemServices.systemContext,
    ): Rect {
        val dm = context.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val landscape = w > h
        return if (landscape) {
            val mid = w / 2
            if (position == SPLIT_POSITION_BOTTOM_OR_RIGHT) Rect(mid, 0, w, h) else Rect(0, 0, mid, h)
        } else {
            val mid = h / 2
            if (position == SPLIT_POSITION_BOTTOM_OR_RIGHT) Rect(0, mid, w, h) else Rect(0, 0, w, mid)
        }
    }

    fun defaultNormalBounds(context: Context = SystemServices.systemContext): Rect {
        val (dw, dh) = displaySize(context)
        if (dw > dh) {
            // Reconstruct the same default window we would create before rotation, then uniformly
            // fit it into the landscape movable area. Using the two portrait dimensions here is
            // what keeps the visual width/height ratio identical across orientation changes.
            val (portraitWidth, portraitHeight) = normalPortraitSize(dh, dw, context)
            return landscapeSideBounds(
                Rect(0, 0, portraitWidth, portraitHeight),
                context,
            )
        }
        val (w, h) = normalPortraitSize(dw, dh, context)
        val left = ((dw - w) / 2f).toInt()
        val top = ((dh - h) / 3f).toInt().coerceAtLeast(dp(context, 48))
        return Rect(left, top, left + w, top + h)
    }

    private fun normalPortraitSize(
        portraitDisplayWidth: Int,
        portraitDisplayHeight: Int,
        context: Context,
    ): Pair<Int, Int> {
        val widthPercent = windowWidthPercentFromSettings(context)
        val heightPercent = windowHeightPercentFromSettings(context)
        // Keep enough physical pixels for caption/resize gestures even at the 0 endpoint, and
        // leave screen margins at the 100 endpoint so the result remains a movable freeform.
        val minWidth = dp(context, 120).coerceAtMost(portraitDisplayWidth.coerceAtLeast(1))
        val minHeight = dp(context, 160).coerceAtMost(portraitDisplayHeight.coerceAtLeast(1))
        val maxWidth = (portraitDisplayWidth - dp(context, 16)).coerceAtLeast(minWidth)
        val maxHeight = (portraitDisplayHeight - dp(context, 72)).coerceAtLeast(minHeight)
        val width = (portraitDisplayWidth * widthPercent / 100f).roundToInt()
            .coerceIn(minWidth, maxWidth)
        val height = (portraitDisplayHeight * heightPercent / 100f).roundToInt()
            .coerceIn(minHeight, maxHeight)
        return width to height
    }

    /**
     * Uniformly scale a portrait normal-window rectangle into a landscape display and anchor it to
     * the same edge as the configured sidebar. This is shared by landscape launches and live
     * display rotation so both paths produce the same Xiaomi-style result.
     */
    fun landscapeSideBounds(
        portraitBounds: Rect,
        context: Context = SystemServices.systemContext,
    ): Rect {
        val (displayW, displayH) = displaySize(context)
        return landscapeSideBoundsForDisplay(portraitBounds, displayW, displayH, context)
    }

    private fun landscapeSideBoundsForDisplay(
        portraitBounds: Rect,
        displayW: Int,
        displayH: Int,
        context: Context,
    ): Rect {
        val sourceW = portraitBounds.width().coerceAtLeast(1)
        val sourceH = portraitBounds.height().coerceAtLeast(1)
        val aspect = sourceW.toFloat() / sourceH
        val maxH = (displayH * LANDSCAPE_NORMAL_HEIGHT_RATIO)
            .toInt()
            .coerceAtLeast(1)
        val maxW = (displayW * LANDSCAPE_NORMAL_MAX_WIDTH_RATIO)
            .toInt()
            .coerceAtLeast(1)

        var height = minOf(sourceH, maxH).coerceAtLeast(1)
        var width = (height * aspect).toInt().coerceAtLeast(1)
        if (width > maxW) {
            width = maxW
            height = (width / aspect).toInt().coerceAtLeast(1)
        }

        // Honour the visual gesture floor when it fits, scaling both dimensions together so the
        // aspect never changes. The WM task itself can remain larger and is leash-scaled by the
        // service, so this is a visual floor rather than an independent width/height clamp.
        val minW = dp(context, 120)
        val minH = dp(context, 160)
        val grow = max(
            minW.toFloat() / width.coerceAtLeast(1),
            minH.toFloat() / height.coerceAtLeast(1),
        ).coerceAtLeast(1f)
        if (width * grow <= maxW && height * grow <= maxH) {
            width = (width * grow).toInt()
            height = (height * grow).toInt()
        }

        val edgeMargin = max(
            dp(context, LANDSCAPE_NORMAL_EDGE_MARGIN_DP),
            (displayW * LANDSCAPE_NORMAL_EDGE_MARGIN_RATIO).toInt(),
        )
        val right = SidebarController.side() == 1
        val left = if (right) {
            displayW - edgeMargin - width
        } else {
            edgeMargin
        }.coerceIn(0, (displayW - width).coerceAtLeast(0))
        val requestedTop = (displayH * LANDSCAPE_NORMAL_TOP_RATIO).toInt()
        val top = requestedTop.coerceIn(0, (displayH - height).coerceAtLeast(0))
        return Rect(left, top, left + width, top + height)
    }

    /** Stable portrait-layout base behind a normal window launched while the display is wide. */
    fun normalBaseBoundsForVisual(
        visualBounds: Rect,
        context: Context = SystemServices.systemContext,
    ): Rect {
        val (displayW, displayH) = displaySize(context)
        if (displayW <= displayH) return Rect(visualBounds)
        // Reconstruct the natural portrait display, including its normal placement.  Keeping the
        // landscape visual's x coordinate here made a later portrait relayout clamp hard against
        // the right edge instead of returning to the ordinary portrait freeform position.
        val portraitW = displayH
        val portraitH = displayW
        val (width, height) = normalPortraitSize(displayH, displayW, context)
        val left = ((portraitW - width) / 2f).toInt()
        val top = ((portraitH - height) / 3f).toInt().coerceAtLeast(dp(context, 48))
        return Rect(
            left,
            top,
            left + width,
            top + height,
        )
    }

    /**
     * Restore a window opened on a landscape display to its portrait base.  Movement relative to
     * the default landscape dock is carried over proportionally, so a dragged window does not
     * suddenly snap to an unrelated edge after rotation.
     */
    fun relayoutLandscapeToPortrait(
        landscapeVisual: Rect,
        portraitBase: Rect,
        previousDisplayW: Int,
        previousDisplayH: Int,
        context: Context = SystemServices.systemContext,
    ): Rect {
        if (previousDisplayW <= previousDisplayH || portraitBase.isEmpty) {
            return relayoutAfterDisplayChange(portraitBase, context)
        }
        val defaultLandscape = landscapeSideBoundsForDisplay(
            portraitBase,
            previousDisplayW,
            previousDisplayH,
            context,
        )
        val (newW, newH) = displaySize(context)
        val oldTravelX = (previousDisplayW - defaultLandscape.width()).coerceAtLeast(1)
        val oldTravelY = (previousDisplayH - defaultLandscape.height()).coerceAtLeast(1)
        val newTravelX = (newW - portraitBase.width()).coerceAtLeast(0)
        val newTravelY = (newH - portraitBase.height()).coerceAtLeast(0)
        val dx = (
            (landscapeVisual.left - defaultLandscape.left).toFloat() / oldTravelX * newTravelX
        ).roundToInt()
        val dy = (
            (landscapeVisual.top - defaultLandscape.top).toFloat() / oldTravelY * newTravelY
        ).roundToInt()
        val restored = Rect(portraitBase)
        restored.offset(dx, dy)
        return relayoutAfterDisplayChange(restored, context)
    }

    /** Select an aspect-identical render source for [visualBounds]. */
    fun taskSourceBoundsForVisual(
        baseBounds: Rect,
        visualBounds: Rect,
        context: Context = SystemServices.systemContext,
    ): Rect {
        val (displayW, displayH) = displaySize(context)
        val visualW = visualBounds.width().coerceAtLeast(1)
        val visualH = visualBounds.height().coerceAtLeast(1)
        val baseW = baseBounds.width().coerceAtLeast(1)
        val baseH = baseBounds.height().coerceAtLeast(1)
        val exactSize = baseW == visualW && baseH == visualH
        // Cross multiplication avoids floating-point drift.  A few pixels of tolerance cover the
        // integer rounding produced by corner gestures, but never the hundreds-of-pixels mismatch
        // formerly caused by WM's independent minimum-width expansion.
        val aspectError = kotlin.math.abs(baseW.toLong() * visualH - baseH.toLong() * visualW)
        val aspectTolerance = maxOf(baseW, baseH, visualW, visualH).toLong() * 3L
        val stableSource = !exactSize &&
            baseW <= displayW && baseH <= displayH &&
            visualW <= baseW && visualH <= baseH &&
            aspectError <= aspectTolerance
        val width = if (stableSource) baseW else visualW
        val height = if (stableSource) baseH else visualH
        // Identity windows commit their actual Task position.  Only genuinely scaled windows park
        // a stable base and move the leash, preventing permanent scale=1 transforms and rotation
        // from consulting an obsolete source location.
        val preferredLeft = if (stableSource) baseBounds.left else visualBounds.left
        val preferredTop = if (stableSource) baseBounds.top else visualBounds.top
        val left = preferredLeft.coerceIn(0, (displayW - width).coerceAtLeast(0))
        val top = preferredTop.coerceIn(0, (displayH - height).coerceAtLeast(0))
        return Rect(left, top, left + width, top + height)
    }

    /** Xiaomi ActivityInfo landscape orientations (isFixedOrientationLandscape + sensor variants). */
    fun isLandscapeOrientation(orientation: Int): Boolean = when (orientation) {
        // SCREEN_ORIENTATION_LANDSCAPE / SENSOR_LANDSCAPE / REVERSE_LANDSCAPE / USER_LANDSCAPE
        0, 6, 8, 11 -> true
        else -> false
    }

    fun isPortraitOrientation(orientation: Int): Boolean = when (orientation) {
        // PORTRAIT / SENSOR_PORTRAIT / REVERSE_PORTRAIT / USER_PORTRAIT
        1, 7, 9, 12 -> true
        else -> false
    }

    /**
     * Xiaomi freeform landscape (MiuiFreeFormActivityStack.mIsLandcapeFreeform):
     * when the app inside requests landscape, the freeform window itself rotates to a
     * landscape rectangle using the reciprocal of its configured portrait aspect instead of
     * imposing 16:9 or the physical display ratio.
     * Keeps the visual center of [portrait] where possible, clamped on-screen.
     */
    fun landscapeBoundsFor(
        portrait: Rect,
        context: Context = SystemServices.systemContext,
    ): Rect {
        val (dw, dh) = displaySize(context)
        val landscapeAspect = portrait.height().coerceAtLeast(1).toFloat() /
            portrait.width().coerceAtLeast(1)
        val maxW = (dw - dp(context, 16)).coerceAtLeast(1)
        val maxH = (dh - dp(context, 80)).coerceAtLeast(1)
        var w = (minOf(dw, dh) * 0.86f).toInt().coerceAtMost(maxW)
        var h = (w / landscapeAspect).roundToInt().coerceAtLeast(1)
        if (h > maxH) {
            h = maxH
            w = (h * landscapeAspect).roundToInt().coerceAtMost(maxW)
        }
        val cx = if (portrait.width() > 0) portrait.exactCenterX() else dw / 2f
        val cy = if (portrait.height() > 0) portrait.exactCenterY() else dh / 2.4f
        val left = (cx - w / 2f).toInt()
        val top = (cy - h / 2f).toInt()
        return clampBounds(Rect(left, top, left + w, top + h), context)
    }

    /**
     * Large app-render source for landscape-player UI. It has exactly the same aspect as the
     * landscape visual frame, while its short edge uses the display's short edge for a full-size
     * resource/layout class. This avoids scaling a device-ratio source into a user-ratio mask.
     */
    fun landscapeSourceBoundsFor(
        portrait: Rect,
        context: Context = SystemServices.systemContext,
    ): Rect {
        val (dw, dh) = displaySize(context)
        val shortEdge = minOf(dw, dh).coerceAtLeast(1)
        val landscapeAspect = portrait.height().coerceAtLeast(1).toFloat() /
            portrait.width().coerceAtLeast(1)
        val width = (shortEdge * landscapeAspect).roundToInt().coerceAtLeast(1)
        return Rect(0, 0, width, shortEdge)
    }

    /**
     * Center [bounds] (keeping its size) on the screen's movable area. Used so that unpinning an
     * edge-pinned window returns it to the middle of the screen instead of the edge it was dragged
     * to before pinning.
     */
    fun centerBounds(bounds: Rect, context: Context = SystemServices.systemContext): Rect {
        val (dw, dh) = displaySize(context)
        val w = bounds.width().coerceAtLeast(dp(context, 120))
        val h = bounds.height().coerceAtLeast(dp(context, 160))
        val left = ((dw - w) / 2f).toInt()
        val top = ((dh - h) / 2f).toInt().coerceAtLeast(dp(context, 48))
        return clampBounds(Rect(left, top, left + w, top + h), context)
    }

    /** Resize to [targetSize] without making a live settings adjustment jump to another edge. */
    fun resizeKeepingCenter(
        current: Rect,
        targetSize: Rect,
        context: Context = SystemServices.systemContext,
    ): Rect {
        val (dw, dh) = displaySize(context)
        val width = targetSize.width().coerceIn(1, dw.coerceAtLeast(1))
        val height = targetSize.height().coerceIn(1, dh.coerceAtLeast(1))
        val centerX = if (current.width() > 0) current.exactCenterX() else dw / 2f
        val centerY = if (current.height() > 0) current.exactCenterY() else dh / 2f
        val left = (centerX - width / 2f).roundToInt()
            .coerceIn(0, (dw - width).coerceAtLeast(0))
        val top = (centerY - height / 2f).roundToInt()
            .coerceIn(0, (dh - height).coerceAtLeast(0))
        return Rect(left, top, left + width, top + height)
    }

    fun defaultMiniBounds(context: Context = SystemServices.systemContext, nearRight: Boolean = true): Rect {
        val (dw, dh) = displaySize(context)
        val w = (dw * MINI_WIDTH_RATIO).toInt().coerceAtLeast(dp(context, 120))
        // Xiaomi mini is a scaled preview of the normal task, not a new tiny layout.
        // Preserve normal aspect so the leash uses one uniform scale factor.
        val normal = defaultNormalBounds(context)
        val h = (w * normal.height().toFloat() / normal.width().coerceAtLeast(1))
            .toInt()
            .coerceAtLeast(dp(context, 160))
            .coerceAtMost((dh * 0.42f).toInt())
        val margin = dp(context, 12)
        val left = if (nearRight) dw - w - margin else margin
        val top = (dh * 0.2f).toInt()
        return Rect(left, top, left + w, top + h)
    }

    fun clampBounds(bounds: Rect, context: Context = SystemServices.systemContext): Rect {
        val (dw, dh) = displaySize(context)
        val minW = dp(context, 120)
        val minH = dp(context, 160)
        val r = Rect(bounds)
        if (r.width() < minW) r.right = r.left + minW
        if (r.height() < minH) r.bottom = r.top + minH
        // Keep at least 48dp visible
        val keep = dp(context, 48)
        if (r.right < keep) r.offset(keep - r.right, 0)
        if (r.left > dw - keep) r.offset(dw - keep - r.left, 0)
        if (r.bottom < keep) r.offset(0, keep - r.bottom)
        if (r.top > dh - keep) r.offset(0, dh - keep - r.top)
        if (r.top < 0) r.offset(0, -r.top)
        return r
    }

    /**
     * Bounds intentionally fully off-screen for pin hide fallback.
     * Unlike [clampBounds], this does NOT pull the window back into view.
     * @param pinPos 0=left edge, 1=right edge
     */
    fun offscreenPinBounds(
        bounds: Rect,
        pinPos: Int,
        context: Context = SystemServices.systemContext
    ): Rect {
        val (dw, dh) = displaySize(context)
        val r = Rect(bounds)
        if (r.width() <= 0 || r.height() <= 0) {
            val fallback = defaultMiniBounds(context, nearRight = pinPos == 1)
            r.set(fallback)
        }
        val top = r.top.coerceIn(0, (dh - r.height()).coerceAtLeast(0))
        if (pinPos == 1) {
            r.offsetTo(dw + dp(context, 48), top)
        } else {
            r.offsetTo(-r.width() - dp(context, 48), top)
        }
        return r
    }

    fun scaleFromBounds(bounds: Rect, base: Rect): Float {
        if (base.width() <= 0) return 1f
        return (bounds.width().toFloat() / base.width()).coerceIn(MIN_SCALE, MAX_SCALE)
    }

    fun shouldEnterMini(scale: Float): Boolean = scale < MINI_SCALE_THRESHOLD

    // --- Xiaomi gesture thresholds (MiuiFreeformModeMove/PinHandler) ---
    /** bottom caption close: dy <= -80 && vy < -1000, or scale < 0.35 */
    const val BOTTOM_CLOSE_DY = -80f
    const val BOTTOM_CLOSE_VY = -1000f
    /** bottom caption fullscreen: dy >= 80 && vy > 1000, or dy > 300 portrait */
    const val BOTTOM_FULLSCREEN_DY = 80f
    const val BOTTOM_FULLSCREEN_VY = 1000f
    const val BOTTOM_FULLSCREEN_LONG_DY = 300f
    /** mini upward fling exit */
    const val MINI_EXIT_DY = -100f
    const val MINI_EXIT_VY = -1500f
    /** pin friction for getPredictXY (Xiaomi FRICTION=1.2) */
    const val PIN_FRICTION = 1.2f
    /** treated as nearly stopped for pin enter (px/s) */
    const val PIN_STOP_SPEED = 220f
    /** Xiaomi freeform→pin interruptible animation window. */
    const val PIN_ANIM_MS = 320L

    // --- Xiaomi miuix Folme animation params (MultiTaskingEaseManager) ---
    // FolmeEase.spring(damping, response); larger response = slower, lower damping = more bounce.
    /** WINDOW_CLOSE_ALPHA_EASE = sinOut(300): close fade duration. */
    const val CLOSE_ANIM_MS = 300L
    /** TO_FREEFORM_POSITION_SIZE_EASE = spring(0.95, 0.4): unpin/expand to normal. */
    const val EXPAND_ANIM_MS = 360L
    const val SPRING_EXPAND_DAMPING = 0.95f
    const val SPRING_EXPAND_RESPONSE = 0.4f
    /** MINI_TO_FREEFORM_EASE = spring(0.95, 0.3): mini→normal expand (a touch snappier). */
    const val SPRING_MINI_EXPAND_DAMPING = 0.95f
    const val SPRING_MINI_EXPAND_RESPONSE = 0.3f
    /** Pin shrink uses a well-damped spring (PRE_CHANGE_DRAG_EASE = spring(0.9, 0.35)). */
    const val SPRING_PIN_DAMPING = 0.9f
    const val SPRING_PIN_RESPONSE = 0.35f

    // --- MIUI MultiTaskingEaseManager transition springs (systemui multitasking) ---
    /** DEFAULT_EASE = spring(0.95, 0.35): normal→mini shrink (applyFreeformToMiniAnimation). */
    const val SPRING_DEFAULT_DAMPING = 0.95f
    const val SPRING_DEFAULT_RESPONSE = 0.35f
    /** TO_FREEFORM_POSITION_SIZE_EASE = spring(0.95, 0.4): bubble/unpin restore. */
    const val SPRING_TO_FREEFORM_DAMPING = 0.95f
    const val SPRING_TO_FREEFORM_RESPONSE = 0.4f
    /**
     * FREEFORM_DRAG_TO_FULLSCREEN_*: maximize sweep.
     * Position spring(0.95, 0.4), size spring(0.95, 0.38) — MIUI animates them separately.
     */
    const val SPRING_MAXIMIZE_POS_DAMPING = 0.95f
    const val SPRING_MAXIMIZE_POS_RESPONSE = 0.4f
    const val SPRING_MAXIMIZE_SIZE_DAMPING = 0.95f
    const val SPRING_MAXIMIZE_SIZE_RESPONSE = 0.38f
    /** TO_FULLSCREEN_SPLIT_*: freeform→split. Position spring(0.85, 0.55), size spring(0.9, 0.48). */
    const val SPRING_SPLIT_POS_DAMPING = 0.85f
    const val SPRING_SPLIT_POS_RESPONSE = 0.55f
    const val SPRING_SPLIT_SIZE_DAMPING = 0.9f
    const val SPRING_SPLIT_SIZE_RESPONSE = 0.48f
    /** ROTATE_POSITION_Z_EASE = spring(0.95, 0.42): portrait/landscape orientation sweep. */
    const val SPRING_ROTATE_DAMPING = 0.95f
    const val SPRING_ROTATE_RESPONSE = 0.42f
    /** PIN_POSITION_EASE = spring(0.78, 0.6) / PIN_WIDTH_HEIGHT_EASE = spring(1.0, 0.35). */
    const val SPRING_PIN_POS_DAMPING = 0.78f
    const val SPRING_PIN_POS_RESPONSE = 0.6f
    const val SPRING_PIN_SIZE_DAMPING = 1.0f
    const val SPRING_PIN_SIZE_RESPONSE = 0.35f

    /** Fixed-window durations for the closed-form spring approximations (settle ≈ 2 periods). */
    const val MINI_ENTER_ANIM_MS = 330L
    const val UNPIN_ANIM_MS = 360L
    const val MAXIMIZE_ANIM_MS = 300L
    const val SPLIT_ANIM_MS = 380L
    const val ROTATE_ANIM_MS = 350L
    const val OPEN_ANIM_MS = 300L

    /**
     * Dual-spring transition frame (MIUI animates position and size with separate eases):
     * the frame CENTER follows [posP], WIDTH/HEIGHT/alpha follow [sizeP].
     */
    fun transitionVisualFrame(
        from: Rect,
        to: Rect,
        posP: Float,
        sizeP: Float,
        alphaFrom: Float = 1f,
        alphaTo: Float = 1f,
    ): WindowVisualFrame {
        val pp = posP.coerceIn(0f, 1.15f)
        val sp = sizeP.coerceIn(0f, 1.15f)
        val cx = from.exactCenterX() + (to.exactCenterX() - from.exactCenterX()) * pp
        val cy = from.exactCenterY() + (to.exactCenterY() - from.exactCenterY()) * pp
        val w = (from.width() + (to.width() - from.width()) * sp).coerceAtLeast(1f)
        val h = (from.height() + (to.height() - from.height()) * sp).coerceAtLeast(1f)
        return WindowVisualFrame(
            left = cx - w / 2f,
            top = cy - h / 2f,
            width = w,
            height = h,
            alpha = (alphaFrom + (alphaTo - alphaFrom) * sp).coerceIn(0f, 1f),
        )
    }

    /**
     * Fullscreen-leash settle frame used after WM has already committed fullscreen geometry.
     * Start from a centered, aspect-preserving cover of the old freeform card, then reveal the
     * complete fullscreen buffer while the spring lands. Content is never non-uniformly stretched.
     */
    fun maximizeFullscreenFrame(
        from: Rect,
        fullscreen: Rect,
        posProgress: Float,
        sizeProgress: Float,
    ): WindowVisualFrame {
        val pp = posProgress.coerceIn(0f, 1f)
        val sp = sizeProgress.coerceIn(0f, 1f)
        val centerX = from.exactCenterX() +
            (fullscreen.exactCenterX() - from.exactCenterX()) * pp
        val centerY = from.exactCenterY() +
            (fullscreen.exactCenterY() - from.exactCenterY()) * pp
        val width = from.width().coerceAtLeast(1) +
            (fullscreen.width() - from.width()) * sp
        val height = from.height().coerceAtLeast(1) +
            (fullscreen.height() - from.height()) * sp
        return WindowVisualFrame(
            left = centerX - width / 2f,
            top = centerY - height / 2f,
            width = width,
            height = height,
            alpha = 1f,
        )
    }

    fun openVisualFrame(bounds: Rect, progress: Float): WindowVisualFrame {
        val p = progress.coerceIn(0f, 1f)
        val scale = 0.86f + 0.14f * p
        val width = bounds.width().coerceAtLeast(1) * scale
        val height = bounds.height().coerceAtLeast(1) * scale
        return WindowVisualFrame(
            left = bounds.exactCenterX() - width / 2f,
            top = bounds.exactCenterY() - height / 2f,
            width = width,
            height = height,
            alpha = 0.35f + 0.65f * p,
        )
    }

    fun closeVisualFrame(bounds: Rect, progress: Float): WindowVisualFrame {
        val p = progress.coerceIn(0f, 1f)
        val scale = 1f - 0.12f * p
        val width = bounds.width().coerceAtLeast(1) * scale
        val height = bounds.height().coerceAtLeast(1) * scale
        return WindowVisualFrame(
            left = bounds.exactCenterX() - width / 2f,
            top = bounds.exactCenterY() - height / 2f,
            width = width,
            height = height,
            alpha = 1f - p,
        )
    }

    fun pinVisualFrame(
        sourceBounds: Rect,
        visualBounds: Rect,
        pinPos: Int,
        posProgress: Float,
        sizeProgress: Float = posProgress,
    ): WindowVisualFrame {
        // MIUI pin animates position (spring 0.78/0.6) and width/height (spring 1.0/0.35)
        // as separate properties, so the window settles at the edge before fully shrinking.
        val p = sizeProgress.coerceIn(0f, 1.15f)
        val pp = posProgress.coerceIn(0f, 1.15f)
        val srcW = sourceBounds.width().coerceAtLeast(1).toFloat()
        val srcH = sourceBounds.height().coerceAtLeast(1).toFloat()
        val startScale = (visualBounds.width().coerceAtLeast(1) / srcW).coerceIn(0.1f, 1.5f)
        val density = SystemServices.systemContext.resources.displayMetrics.density
        val bubble = 64f * density
        val endScale = (bubble / minOf(srcW, srcH)).coerceIn(0.08f, 0.45f)
        val scale = startScale + (endScale - startScale) * p
        val (displayW, displayH) = displaySize()
        val peek = bubblePeekPx().toFloat()
        val targetCenterX = if (pinPos == 1) {
            displayW - peek + bubble / 2f
        } else {
            peek - bubble / 2f
        }
        val targetCenterY = visualBounds.top.toFloat()
            .coerceIn(80f, (displayH - bubble - 80f).coerceAtLeast(80f)) + bubble / 2f
        val centerX = visualBounds.exactCenterX() +
            (targetCenterX - visualBounds.exactCenterX()) * pp
        val centerY = visualBounds.exactCenterY() +
            (targetCenterY - visualBounds.exactCenterY()) * pp
        val width = srcW * scale
        val height = srcH * scale
        return WindowVisualFrame(
            left = centerX - width / 2f,
            top = centerY - height / 2f,
            width = width,
            height = height,
            alpha = (1f - 0.82f * p.coerceIn(0f, 1f)).coerceIn(0.12f, 1f),
        )
    }

    /**
     * Closed-form miuix Folme spring position 0→1 for normalized progress [p] in [0,1].
     * Underdamped analytic solution x(t)=1-e^(-ζωt)[cos(ω_d t)+(ζω/ω_d)sin(ω_d t)], evaluated
     * over a settle window of ~2 natural periods and clamped so p=1 lands exactly on 1.
     * [damping] = ζ (0.85–0.99 here), [response] = natural period T (ω=2π/T).
     */
    fun folmeSpring(p: Float, damping: Float, response: Float): Float {
        val t = p.coerceIn(0f, 1f)
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val zeta = damping.coerceIn(0.1f, 0.9999f)
        val omega = (2.0 * Math.PI / response.coerceAtLeast(0.05f))
        // Physical settle window: ~2 periods is enough for these damping ratios.
        val settle = response * 2.0
        val time = t * settle
        val omegaD = omega * Math.sqrt(1.0 - (zeta * zeta).toDouble())
        val decay = Math.exp(-zeta.toDouble() * omega * time)
        val value = 1.0 - decay * (Math.cos(omegaD * time) +
            (zeta * omega / omegaD) * Math.sin(omegaD * time))
        return value.toFloat().coerceIn(0f, 1.15f)
    }
    /** double-tap window for mini (docs ~200ms) */
    const val MINI_DOUBLE_TAP_MS = 200L
    /** bubble double-tap → pinToFullscreen (slightly looser than mini) */
    const val BUBBLE_DOUBLE_TAP_MS = 280L

    // --- Xiaomi MultiTaskingHotAreaController (phone freeform drag) ---
    /** HOT_AREA_TYPE_FULLSCREEN */
    const val HOT_AREA_TYPE_FULLSCREEN = 0
    /** HOT_AREA_TYPE_SPLIT_LEFT_OR_TOP */
    const val HOT_AREA_TYPE_SPLIT_TOP = 1
    /** HOT_AREA_TYPE_SPLIT_RIGHT_OR_BOTTOM */
    const val HOT_AREA_TYPE_SPLIT_BOTTOM = 2
    /** HOT_AREA_TYPE_FREEFORM */
    const val HOT_AREA_TYPE_FREEFORM = 3
    /**
     * Phone portrait freeform drag: top strip height ratio
     * (initPhoneHotAreaList: dh * 0.041f).
     */
    const val PHONE_FREEFORM_FULLSCREEN_HOT_RATIO = 0.041f
    /** tap slop in px */
    fun tapSlopPx(context: Context = SystemServices.systemContext): Float =
        8f * context.resources.displayMetrics.density

    /**
     * Xiaomi MiuiFreeformModeUtils.calPredict / getPredictXY:
     * predict = pos + velocity / (friction * 4.2)
     */
    fun predictPosition(pos: Float, velocity: Float, friction: Float = PIN_FRICTION): Float {
        val f = if (friction == 0f) PIN_FRICTION else friction
        return pos + velocity / (f * 4.2f)
    }

    fun predictCenter(
        bounds: Rect,
        vx: Float,
        vy: Float,
        friction: Float = PIN_FRICTION,
    ): Pair<Float, Float> {
        return predictPosition(bounds.exactCenterX(), vx, friction) to
            predictPosition(bounds.exactCenterY(), vy, friction)
    }

    /**
     * Xiaomi isEnterPin lite.
     * - mini: out-of-screen predict with dominant horizontal velocity, or stopped at edge
     * - normal: not nearly-stopped; predict out OR left/right-angle toward side area
     */
    fun shouldEnterPin(
        bounds: Rect,
        vx: Float,
        vy: Float,
        isMini: Boolean,
        context: Context = SystemServices.systemContext,
    ): Boolean {
        val (dw, dh) = displaySize(context)
        val speed = hypot(vx, vy)
        val nearlyStopped = speed < PIN_STOP_SPEED
        val (px, py) = predictCenter(bounds, vx, vy)
        val edgeArea = dp(context, 48).toFloat()
        val sideArea = dp(context, 72).toFloat()

        fun outside(x: Float, y: Float, soft: Boolean): Boolean {
            val margin = if (soft) edgeArea else 0f
            return x < -margin || x > dw + margin || y < -margin || y > dh + margin
        }

        fun outerX(x: Float, area: Float): Boolean = x < area || x > dw - area

        if (isMini) {
            val out = outside(px, py, soft = true) && abs(vx) > abs(vy)
            val stopAtEdge = nearlyStopped && outerX(bounds.exactCenterX(), edgeArea)
            return out || stopAtEdge
        }

        // Normal freeform: almost-stopped never pins (Xiaomi isStop true -> false)
        if (nearlyStopped) return false
        if (outside(px, py, soft = false)) return true

        // Angle toward left/right edge more than top/bottom
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        if (vx == 0f) return false
        val upVelTan = abs(vy / vx)
        val upAngelTan = when {
            vx >= 0f && vy >= 0f -> (dh - cy) / (dw - cx).coerceAtLeast(1f)
            vx >= 0f && vy < 0f -> cy / (dw - cx).coerceAtLeast(1f)
            vx < 0f && vy >= 0f -> (dh - cy) / cx.coerceAtLeast(1f)
            else -> cy / cx.coerceAtLeast(1f)
        }
        val leftRightAngle = upAngelTan >= upVelTan
        return leftRightAngle && outerX(px, sideArea)
    }

    /**
     * Apply fling inertia then clamp; mini freeforms snap to left/right edge.
     */
    fun settleMoveBounds(
        start: Rect,
        dx: Float,
        dy: Float,
        vx: Float,
        vy: Float,
        isMini: Boolean,
        context: Context = SystemServices.systemContext,
    ): Rect {
        // Xiaomi: drag offset + short fling predict (getPredictXY with FRICTION=1.2)
        val predicted = Rect(start).also {
            it.offset(
                (dx + vx / (PIN_FRICTION * 4.2f)).toInt(),
                (dy + vy / (PIN_FRICTION * 4.2f)).toInt(),
            )
        }
        val movable = clampedToMovable(predicted, context)
        val snapped = if (isMini) snapMiniToEdge(movable, context) else movable
        return clampBounds(snapped, context)
    }

    /**
     * A pinned mini may have crossed the edge before the pin gesture completed. Restore it to the
     * normal mini resting position: fully inside the movable area and snapped to the nearest side.
     */
    fun visibleMiniRestingBounds(
        bounds: Rect,
        context: Context = SystemServices.systemContext,
    ): Rect {
        val movable = clampedToMovable(bounds, context)
        return clampBounds(snapMiniToEdge(movable, context), context)
    }

    private fun clampedToMovable(bounds: Rect, context: Context): Rect {
        val restriction = movableRestriction(context)
        val r = Rect(bounds)
        if (r.left < restriction.left) r.offset(restriction.left - r.left, 0)
        if (r.top < restriction.top) r.offset(0, restriction.top - r.top)
        if (r.right > restriction.right) r.offset(restriction.right - r.right, 0)
        if (r.bottom > restriction.bottom) r.offset(0, restriction.bottom - r.bottom)
        return r
    }

    /** Mini freeforms strongly adsorb to left/right screen edge (Xiaomi mini side snap). */
    fun snapMiniToEdge(
        bounds: Rect,
        context: Context = SystemServices.systemContext,
    ): Rect {
        val (dw, _) = displaySize(context)
        val margin = dp(context, 12)
        val r = Rect(bounds)
        val nearRight = r.centerX() >= dw / 2
        val left = if (nearRight) dw - r.width() - margin else margin
        r.offsetTo(left, r.top)
        return r
    }

    /**
     * Bottom caption settle decision.
     * @return "close" | "fullscreen" | null (spring back)
     */
    fun bottomCaptionAction(dy: Float, vy: Float, scale: Float): String? {
        val close =
            (dy <= BOTTOM_CLOSE_DY && vy < BOTTOM_CLOSE_VY) || scale < 0.35f
        if (close) return "close"
        val fullscreen =
            (dy >= BOTTOM_FULLSCREEN_DY && vy > BOTTOM_FULLSCREEN_VY) ||
                dy > BOTTOM_FULLSCREEN_LONG_DY
        if (fullscreen) return "fullscreen"
        return null
    }

    fun shouldMiniFlingClose(dy: Float, vy: Float): Boolean {
        // Xiaomi: velocityY < -1500 with upward travel; also accept long up-drag when
        // VelocityTracker end-sample is flaky on synthetic/input injection.
        return (dy <= MINI_EXIT_DY && vy < MINI_EXIT_VY) || dy <= -280f
    }

    /**
     * Xiaomi phone freeform drag hot area (mPhoneFreeFormHotAreaList).
     * Portrait: type0 fullscreen = Rect(0,0,dw, 0.041*dh); type3 freeform = rest.
     * Uses touch position like MulWinSwitchGestureHandler.getHotAreaForDrag.
     * @return HOT_AREA_TYPE_*
     */
    fun freeformDragHotArea(
        rawX: Float,
        rawY: Float,
        context: Context = SystemServices.systemContext,
    ): Int {
        val (dw, dh) = displaySize(context)
        val x = rawX.coerceIn(0f, dw.toFloat())
        val y = rawY.coerceIn(0f, dh.toFloat())
        val topH = (dh * PHONE_FREEFORM_FULLSCREEN_HOT_RATIO + 0.5f).toInt().coerceAtLeast(1)
        // Phone freeform list order: first fullscreen strip, then freeform fallback.
        if (y <= topH && x >= 0f && x <= dw) {
            return HOT_AREA_TYPE_FULLSCREEN
        }
        return HOT_AREA_TYPE_FREEFORM
    }

    fun freeformFullscreenHotRect(context: Context = SystemServices.systemContext): Rect {
        val (dw, dh) = displaySize(context)
        val topH = (dh * PHONE_FREEFORM_FULLSCREEN_HOT_RATIO + 0.5f).toInt().coerceAtLeast(1)
        return Rect(0, 0, dw, topH)
    }

    fun hotAreaLabel(type: Int): String = when (type) {
        HOT_AREA_TYPE_FULLSCREEN -> "fullscreen"
        HOT_AREA_TYPE_SPLIT_TOP -> "split_top"
        HOT_AREA_TYPE_SPLIT_BOTTOM -> "split_bottom"
        HOT_AREA_TYPE_FREEFORM -> "freeform"
        else -> "unknown($type)"
    }

    fun pinEdge(bounds: Rect, context: Context = SystemServices.systemContext): Int {
        val (dw, _) = displaySize(context)
        val cx = bounds.centerX()
        return if (cx >= dw / 2) 1 else 0
    }

    // --- Payment / transfer protect (MiuiMultiWindowAdapter) ---
    // HIDE_SELF_IF_NEW_FREEFORM_TASK_WHITE_LIST_ACTIVITY class names (system array + known pay).
    private val HIDE_SELF_IF_NEW_FREEFORM_CLASS = setOf(
        "com.alipay.mobile.quinox.SchemeLauncherActivity",
        "com.alipay.mobile.quinox.LauncherActivity.alias",
        "com.alipay.mobile.nebulax.integration.mpaas.activity.NebulaActivity\$Main",
        "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity",
        "com.miui.securityscan.MainEntryActivity",
        "com.miui.securitymain.SCMainEntryActivity",
        "com.miui.securitymain.MainEntryActivity",
        "com.tencent.mm.plugin.base.stub.WXPayEntryActivity",
        "com.tencent.mm.plugin.base.stub.WXEntryActivity",
        "com.tencent.mm.plugin.base.stub.WXCustomSchemeEntryActivity",
        "com.tencent.mm.plugin.wallet_index.ui.OrderHandlerUI",
        "com.tencent.mm.plugin.base.stub.UIEntryStub",
        "com.tencent.mm.plugin.webview.ui.tools.SDKOAuthUI",
    )

    // SHOW_HIDDEN_TASK_IF_FINISHED_WHITE_LIST_ACTIVITY — finishing these reveals hidden pay task.
    private val SHOW_HIDDEN_IF_FINISHED_CLASS = setOf(
        "com.alipay.mobile.quinox.LauncherActivity.alias",
        "com.alipay.mobile.nebulax.integration.mpaas.activity.NebulaActivity\$Main",
        "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity",
        "com.miui.securitymain.SCMainEntryActivity",
        "com.tencent.mm.plugin.base.stub.WXPayEntryActivity",
        "com.tencent.mm.ui.LauncherUI",
    )

    /**
     * sNotExitFreeFormWhenAddOtherFreeFormTask:
     * victim shortComponent → adding shortComponent that must NOT close the freeform.
     */
    private val NOT_EXIT_WHEN_ADD_OTHER: Map<String, String> = mapOf(
        "com.eg.android.AlipayGphone/com.alipay.mobile.quinox.SchemeLauncherActivity"
            to "com.eg.android.AlipayGphone/com.alipay.mobile.quinox.LauncherActivity.alias",
        "com.miui.securitycenter/com.miui.securityscan.MainEntryActivity"
            to "com.miui.securitymanager/com.miui.securitymain.SCMainEntryActivity",
    )

    /** Packages treated as payment/transfer hosts — never LRU-killed while top is transfer. */
    private val PROTECTED_PAY_PACKAGES = setOf(
        "com.eg.android.AlipayGphone",
        "com.tencent.mm",
        "com.miui.securitycenter",
        "com.miui.securitymanager",
        "com.tencent.mobileqq",
        "com.unionpay",
    )

    // Foreground-pin allow packages (audio/nav/game style; Xiaomi cloud lists).
    private val FOREGROUND_PIN_WHITELIST = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.android.deskclock",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.systemui", // dialer shade edge cases never freeform, listed for completeness
        "com.miui.weather2",
        "com.maps.app",
        "com.google.android.apps.maps",
        "com.autonavi.minimap",
        "com.baidu.BaiduMap",
        "com.netease.cloudmusic",
        "com.tencent.qqmusic",
        "com.kugou.android",
        "com.spotify.music",
    )

    private val FOREGROUND_PIN_BLACKLIST = setOf(
        "com.android.settings",
        "com.android.vending",
        "com.android.chrome",
    )

    private fun classNameOf(component: String?): String {
        if (component.isNullOrBlank()) return ""
        val slash = component.indexOf('/')
        return if (slash >= 0) component.substring(slash + 1) else component
    }

    fun isHideSelfIfNewFreeformActivity(componentOrClass: String?): Boolean {
        if (componentOrClass.isNullOrBlank()) return false
        val cls = classNameOf(componentOrClass)
        if (cls in HIDE_SELF_IF_NEW_FREEFORM_CLASS) return true
        // Also match bare class tail
        return HIDE_SELF_IF_NEW_FREEFORM_CLASS.any { componentOrClass.endsWith(it) || cls.endsWith(it) }
    }

    fun isShowHiddenIfFinishedActivity(componentOrClass: String?): Boolean {
        if (componentOrClass.isNullOrBlank()) return false
        val cls = classNameOf(componentOrClass)
        if (cls in SHOW_HIDDEN_IF_FINISHED_CLASS) return true
        return SHOW_HIDDEN_IF_FINISHED_CLASS.any { componentOrClass.endsWith(it) || cls.endsWith(it) }
    }

    fun isProtectedPayPackage(packageName: String?): Boolean =
        !packageName.isNullOrBlank() && packageName in PROTECTED_PAY_PACKAGES

    /**
     * Xiaomi onMiuiFreeFormStasckAdded: skip startExitApplication for payment/transfer pairs.
     * @param victimComponent flattenToString or short "pkg/class" of existing freeform
     * @param addingComponent component being opened (nullable when unknown)
     */
    fun shouldSkipReplaceFreeform(
        victimComponent: String?,
        victimPackage: String?,
        addingComponent: String? = null,
        context: Context = SystemServices.systemContext,
    ): Boolean {
        // Debug/MuMu: force-protect a package to verify replace-skip without Alipay installed.
        val testPkg = runCatching {
            android.provider.Settings.Global.getString(
                context.contentResolver,
                SETTINGS_TEST_PROTECT_PKG
            )
        }.getOrNull()
        if (!testPkg.isNullOrBlank() && testPkg == victimPackage) {
            return true
        }
        val victimShort = normalizeShortComponent(victimComponent, victimPackage)
        val addingShort = normalizeShortComponent(addingComponent, null)
        // sNotExitFreeFormWhenAddOtherFreeFormTask pair
        val mapped = NOT_EXIT_WHEN_ADD_OTHER[victimShort]
        if (mapped != null && (addingShort.isEmpty() || mapped == addingShort ||
                (addingComponent != null && addingComponent.contains(mapped.substringAfter('/'))))
        ) {
            return true
        }
        // HIDE_SELF victim + SHOW_HIDDEN adding → hide-self flow, do not kill
        if (isHideSelfIfNewFreeformActivity(victimComponent) &&
            (addingComponent == null || isShowHiddenIfFinishedActivity(addingComponent))
        ) {
            return true
        }
        // Standalone: payment/transfer top activity must not be LRU-killed
        if (isHideSelfIfNewFreeformActivity(victimComponent)) return true
        return false
    }

    private fun normalizeShortComponent(component: String?, packageName: String?): String {
        if (component.isNullOrBlank()) {
            return ""
        }
        if (component.contains('/')) {
            // flattenToString is pkg/class; short is same for non-inner in Xiaomi shortComponentName
            val pkg = component.substringBefore('/')
            val cls = component.substringAfter('/')
            return "$pkg/$cls"
        }
        if (!packageName.isNullOrBlank()) return "$packageName/$component"
        return component
    }

    /** Whether package may use foreground-priority pin (not regular edge pin). */
    fun allowsForegroundPin(packageName: String?, context: Context = SystemServices.systemContext): Boolean {
        if (!supportsForegroundPin(context)) return false
        if (packageName.isNullOrBlank()) return false
        if (packageName in FOREGROUND_PIN_BLACKLIST) return false
        // Xiaomi: audio list OR white list OR top game. We use white list as primary.
        return packageName in FOREGROUND_PIN_WHITELIST
    }

    /**
     * Parse Settings.Secure sidebar_bounds JSON array:
     * [{"l":..,"t":..,"r":..,"b":..}, ...]
     */
    fun parseSidebarBounds(raw: String?): List<Rect> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = ArrayList<Rect>()
        try {
            // Minimal JSON array parse without org.json dependency surprises in system_server.
            val trimmed = raw.trim()
            if (!trimmed.startsWith("[")) return emptyList()
            val objRegex = Regex("""\{([^{}]*)\}""")
            for (m in objRegex.findAll(trimmed)) {
                val body = m.groupValues[1]
                fun num(key: String): Int {
                    val km = Regex(""""$key"\s*:\s*(-?\d+)""").find(body) ?: return -1
                    return km.groupValues[1].toInt()
                }
                val l = num("l"); val t = num("t"); val r = num("r"); val b = num("b")
                if (l >= 0 && t >= 0 && r > l && b > t) {
                    out.add(Rect(l, t, r, b))
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return out
    }

    fun sidebarBounds(context: Context = SystemServices.systemContext): List<Rect> {
        val raw = runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                SETTINGS_SIDEBAR_BOUNDS
            )
        }.getOrNull()
        return parseSidebarBounds(raw)
    }

    /**
     * Xiaomi MiuiFreeformModeAvoidAlgorithm.adjustBoundsForSidebarIfNeed:
     * if mini intersects expanded sidebar line, shift vertically within movable area.
     */
    fun adjustBoundsForSidebarIfNeed(
        bounds: Rect,
        movable: Rect = movableRestriction(),
        sidebars: List<Rect> = sidebarBounds(),
        context: Context = SystemServices.systemContext,
    ): Rect {
        if (sidebars.isEmpty()) return Rect(bounds)
        val result = Rect(bounds)
        val expandX = dp(context, 16)
        val expandY = dp(context, 6)
        for (raw in sidebars) {
            if (raw.isEmpty) continue
            val bar = Rect(raw).also { it.inset(-expandX, -expandY) }
            if (!Rect.intersects(bar, result)) continue
            if (bar.centerY() <= result.centerY()) {
                if (movable.bottom >= result.height() + bar.bottom) {
                    result.offsetTo(result.left, bar.bottom)
                } else if (movable.top <= bar.top - result.height()) {
                    result.offsetTo(result.left, bar.top - result.height())
                }
            } else {
                if (movable.top <= bar.top - result.height()) {
                    result.offsetTo(result.left, bar.top - result.height())
                } else if (movable.bottom >= result.height() + bar.bottom) {
                    result.offsetTo(result.left, bar.bottom)
                }
            }
        }
        return clampBounds(result, context)
    }

    fun isBlacklisted(packageName: String): Boolean {
        // Safety + Xiaomi-style system shell blacklist (not VirtualDisplay path).
        // Keep Settings / third-party launchable apps openable as freeform.
        val blocked = setOf(
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.launcher",
            "com.android.launcher3",
            "com.miui.home",
            "com.android.keyguard",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.vpndialogs",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "io.hyper.freeform",
        )
        return packageName in blocked
    }

    /**
     * Single freeform corner radius for ALL four corners (top+bottom unified).
     * Prefer the active SystemUI / window radius. Physical display bezels are only used when they
     * look like window metrics; square-corner devices still get [MIN_FREEFORM_CORNER_DP].
     * [isMini] no longer forces a different radius — top/bottom stay identical in every state.
     */
    fun freeformCornerRadiusPx(
        isMini: Boolean,
        context: Context = SystemServices.systemContext,
    ): Float {
        val d = context.resources.displayMetrics.density
        val minPx = MIN_FREEFORM_CORNER_DP * d
        val maxWindowPx = MAX_WINDOW_CORNER_DP * d
        fun floor(value: Float): Float = value.coerceAtLeast(minPx)
        // 1) SystemUI freeform radius (same value for task crop and overlay frame).
        val dimen = SystemServices.systemUiDimensionPx(
            listOf(
                "freeform_round_radius",
                "freeform_round_corner",
                "miui_freeform_round_corner",
                "desktop_windowing_freeform_rounded_corner_radius",
            ),
        )
        if (dimen != null && dimen > 0) return floor(dimen.toFloat())
        // 2) Framework dialog/config corner — the OS window language, not the panel bezel.
        val framework = runCatching {
            val res = context.resources
            for (name in listOf(
                "config_bottomDialogCornerRadius",
                "config_dialogCornerRadius",
                "config_roundedCornerRadius",
            )) {
                val id = res.getIdentifier(name, "dimen", "android")
                if (id != 0) {
                    val v = res.getDimensionPixelSize(id)
                    if (v > 0) return@runCatching v.toFloat()
                }
            }
            0f
        }.getOrDefault(0f)
        if (framework > 0f) return floor(framework)
        // 3) Device rounded corner only when it is in the window-radius range. Panel bezels are
        // commonly 40–80dp and would turn a small window into a pill.
        val sys = systemCornerRadiusPx(context)
        if (sys in minPx..maxWindowPx) return sys
        // 4) Hard dp fallback (same for mini so top/bottom never diverge).
        // isMini kept in the signature for call-site compatibility.
        @Suppress("UNUSED_EXPRESSION")
        isMini
        return floor(FREEFORM_CORNER_DP * d)
    }

    /**
     * Authoritative radius of the visible task/mask outer contour. Cap it against the current
     * visual frame so every state (normal, scaled normal and mini) remains a valid rounded rect.
     */
    fun freeformVisibleCornerRadiusPx(
        isMini: Boolean,
        visualWidth: Int,
        visualHeight: Int,
        context: Context = SystemServices.systemContext,
    ): Float {
        val raw = freeformCornerRadiusPx(isMini, context)
        val half = minOf(visualWidth, visualHeight).coerceAtLeast(1) / 2f
        val minPx = MIN_FREEFORM_CORNER_DP * context.resources.displayMetrics.density
        return raw.coerceAtMost(half).coerceAtLeast(minPx.coerceAtMost(half))
    }

    /** Radius submitted to a task leash whose local coordinates are transformed by [scale]. */
    fun freeformLeashCornerRadiusPx(
        isMini: Boolean,
        visualWidth: Int,
        visualHeight: Int,
        scale: Float,
        context: Context = SystemServices.systemContext,
    ): Float = freeformVisibleCornerRadiusPx(
        isMini,
        visualWidth,
        visualHeight,
        context,
    ) / scale.coerceAtLeast(0.01f)

    /**
     * Radius of the inset chrome stroke path. Its outer antialiased contour then has exactly the
     * same center and radius as [freeformVisibleCornerRadiusPx], rather than merely using a similar
     * independent dp value.
     */
    fun freeformChromePathRadiusPx(
        isMini: Boolean,
        visualWidth: Int,
        visualHeight: Int,
        strokeWidth: Float,
        context: Context = SystemServices.systemContext,
    ): Float = (
        freeformVisibleCornerRadiusPx(isMini, visualWidth, visualHeight, context) -
            strokeWidth / 2f
        ).coerceAtLeast(0f)

    /**
     * A bottom-corner inward resize settles at the opposite top corner. Shrinking the left edge
     * moves the visual center right (bottom-left -> top-right); shrinking the right edge moves it
     * left (bottom-right -> top-left).
     */
    fun miniNearRightAfterResize(
        baseBounds: Rect,
        resizedBounds: Rect,
        context: Context = SystemServices.systemContext,
    ): Boolean {
        val delta = resizedBounds.exactCenterX() - baseBounds.exactCenterX()
        val threshold = dp(context, 1).toFloat()
        return when {
            delta > threshold -> true
            delta < -threshold -> false
            else -> pinEdge(baseBounds, context) == 1
        }
    }

    /**
     * Device system screen rounded-corner radius.
     * Averages top and bottom when both exist so freeform top+bottom stay unified and
     * still match the system look. Returns 0 when the screen has square corners.
     */
    private fun systemCornerRadiusPx(context: Context): Float {
        val res = context.resources
        // Prefer explicit top/bottom pair → average (unifies any system asymmetry into one radius).
        val top = runCatching {
            val id = res.getIdentifier("rounded_corner_radius_top", "dimen", "android")
            if (id != 0) res.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
        val bottom = runCatching {
            val id = res.getIdentifier("rounded_corner_radius_bottom", "dimen", "android")
            if (id != 0) res.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
        if (top > 0 && bottom > 0) return (top + bottom) / 2f
        if (top > 0) return top.toFloat()
        if (bottom > 0) return bottom.toFloat()
        val single = runCatching {
            val id = res.getIdentifier("rounded_corner_radius", "dimen", "android")
            if (id != 0) res.getDimensionPixelSize(id) else 0
        }.getOrDefault(0)
        if (single > 0) return single.toFloat()

        // Display.getRoundedCorner(pos) (API 31+): average the non-zero corners.
        return runCatching {
            val display = context.display ?: return@runCatching 0f
            val getRC = display.javaClass.getMethod("getRoundedCorner", Integer.TYPE)
            var sum = 0
            var count = 0
            for (pos in 0..3) {
                val rc = getRC.invoke(display, pos) ?: continue
                val r = rc.javaClass.getMethod("getRadius").invoke(rc) as? Int ?: 0
                if (r > 0) {
                    sum += r
                    count++
                }
            }
            if (count > 0) sum.toFloat() / count else 0f
        }.getOrDefault(0f)
    }

    fun freeformCornerTipThicknessPx(
        context: Context = SystemServices.systemContext,
    ): Float {
        val d = context.resources.displayMetrics.density
        return SystemServices.systemUiDimensionPx(
            listOf("corner_tips_thickness"),
        )?.toFloat() ?: CORNER_TIP_THICKNESS_DP * d
    }

    fun bubblePeekPx(context: Context = SystemServices.systemContext): Int =
        dp(context, BUBBLE_PEEK_DP)

    /** Xiaomi MiuiBubble ~64dp container. */
    fun bubbleSizePx(context: Context = SystemServices.systemContext): Int =
        dp(context, 64)

    /**
     * Xiaomi pin floating window dock rect (edge peek + Y).
     * pinPos 0=left, 1=right; y = bubble top.
     */
    fun pinFloatingWindowPos(
        pinPos: Int,
        y: Int,
        context: Context = SystemServices.systemContext,
    ): Rect {
        val (dw, dh) = displaySize(context)
        val size = bubbleSizePx(context)
        val peek = bubblePeekPx(context)
        val top = y.coerceIn(80, (dh - size - 80).coerceAtLeast(80))
        val left = if (pinPos == 1) dw - peek else peek - size
        return Rect(left, top, left + size, top + size)
    }

    fun freeformShadowRadiusPx(
        isMini: Boolean,
        context: Context = SystemServices.systemContext,
    ): Float {
        // Xiaomi shadow is HWUI-custom; SurfaceControl shadowRadius is a lite stand-in.
        val d = context.resources.displayMetrics.density
        return (if (isMini) 28f else 36f) * d
    }

    /**
     * Xiaomi MiuiMultiWindowUtils.avoidIfNeeded port.
     * Moves [mobile] so it no longer fully covers [fixed], staying inside [restriction].
     */
    fun avoidIfNeeded(mobile: Rect, fixed: Rect, restriction: Rect, context: Context = SystemServices.systemContext) {
        val ox = dp(context, AVOID_OFFSET_X_DP)
        val oy = dp(context, AVOID_OFFSET_Y_DP)
        val intersect = Rect()
        if (!intersect.setIntersect(mobile, fixed)) return

        // Horizontal avoidance when heavily overlapping on X
        if (mobile.left - fixed.left < ox && fixed.right - mobile.right < ox) {
            val targetLeft: Int
            val leftCandidate = (fixed.right - ox) - mobile.width()
            val rightCandidate = fixed.left + ox
            if (leftCandidate < restriction.left && rightCandidate + mobile.width() > restriction.right) {
                targetLeft = if (mobile.left >= fixed.left || mobile.right > fixed.right) {
                    restriction.right - mobile.width()
                } else {
                    restriction.left
                }
            } else if (leftCandidate >= restriction.left && rightCandidate + mobile.width() <= restriction.right) {
                targetLeft = if (mobile.left < fixed.left && mobile.right <= fixed.right) {
                    leftCandidate
                } else {
                    rightCandidate
                }
            } else if (leftCandidate >= restriction.left) {
                targetLeft = leftCandidate
            } else {
                targetLeft = rightCandidate
            }
            mobile.offsetTo(targetLeft, mobile.top)
        }

        // Vertical avoidance when heavily overlapping on Y
        if (mobile.top - fixed.top < oy && fixed.bottom - mobile.bottom < oy) {
            val targetTop: Int
            val upCandidate = (fixed.bottom - oy) - mobile.height()
            val downCandidate = fixed.top + oy
            if (upCandidate < restriction.top && downCandidate + mobile.height() > restriction.bottom) {
                targetTop = if ((mobile.bottom <= fixed.bottom || mobile.top < fixed.top) && !mobile.contains(fixed)) {
                    restriction.top
                } else {
                    restriction.bottom - mobile.height()
                }
            } else if (upCandidate >= restriction.top && downCandidate + mobile.height() <= restriction.bottom) {
                targetTop = if ((mobile.bottom > fixed.bottom && mobile.top >= fixed.top) || mobile.contains(fixed)) {
                    downCandidate
                } else {
                    upCandidate
                }
            } else if (upCandidate >= restriction.top) {
                targetTop = upCandidate
            } else {
                targetTop = downCandidate
            }
            mobile.offsetTo(mobile.left, targetTop)
        }
    }

    /**
     * Place a new freeform so it does not fully cover existing freeforms
     * (Xiaomi avoidOtherFreeformTaskIfNeed + cascade stack).
     */
    fun placeNewFreeformBounds(
        desired: Rect,
        existing: List<Rect>,
        context: Context = SystemServices.systemContext
    ): Rect {
        if (existing.isEmpty()) return clampBounds(desired, context)

        val restriction = movableRestriction(context)
        val candidate = Rect(desired)
        val cascadeX = dp(context, 28)
        val cascadeY = dp(context, 36)
        val gap = freeformGap(context)

        // First pass: Xiaomi avoidIfNeeded against each existing window.
        for (other in existing) {
            val inflated = Rect(other).also {
                it.inset(-gap, -gap)
            }
            if (Rect.intersects(candidate, inflated)) {
                avoidIfNeeded(candidate, other, restriction, context)
            }
        }

        // Second pass: if still heavily covered, cascade diagonally (stack strategy).
        var guard = 0
        while (guard < existing.size + 3) {
            val heavy = existing.any { coversHeavily(candidate, it, context) }
            if (!heavy) break
            candidate.offset(cascadeX, cascadeY)
            if (candidate.right > restriction.right) {
                candidate.offsetTo(restriction.left, candidate.top + cascadeY)
            }
            if (candidate.bottom > restriction.bottom) {
                candidate.offsetTo(
                    candidate.left.coerceIn(restriction.left, restriction.right - candidate.width()),
                    restriction.top
                )
            }
            // Re-apply avoid against all after cascade step.
            for (other in existing) {
                if (Rect.intersects(candidate, other)) {
                    avoidIfNeeded(candidate, other, restriction, context)
                }
            }
            guard++
        }

        // Keep inside movable area.
        if (candidate.left < restriction.left) candidate.offset(restriction.left - candidate.left, 0)
        if (candidate.top < restriction.top) candidate.offset(0, restriction.top - candidate.top)
        if (candidate.right > restriction.right) candidate.offset(restriction.right - candidate.right, 0)
        if (candidate.bottom > restriction.bottom) candidate.offset(0, restriction.bottom - candidate.bottom)

        return clampBounds(candidate, context)
    }

    /**
     * Push [moving] away from siblings after user move/resize settle
     * (simplified avoidOtherFreeformTaskIfNeed for the active window).
     */
    fun avoidSiblings(
        moving: Rect,
        siblings: List<Rect>,
        context: Context = SystemServices.systemContext
    ): Rect {
        if (siblings.isEmpty()) return clampBounds(moving, context)
        val restriction = movableRestriction(context)
        val result = Rect(moving)
        for (other in siblings) {
            if (Rect.intersects(result, other)) {
                avoidIfNeeded(result, other, restriction, context)
            }
        }
        return clampBounds(result, context)
    }

    /**
     * Stack mini freeforms on the same edge vertically (autoOrderingAvoidMiniTask lite).
     */
    fun orderMiniBounds(
        active: Rect,
        otherMinis: List<Rect>,
        context: Context = SystemServices.systemContext
    ): List<Pair<Int, Rect>> {
        // Caller maps index externally; here we only compute vertical offsets for others.
        if (otherMinis.isEmpty()) return emptyList()
        val gap = freeformGap(context)
        val restriction = movableRestriction(context)
        val result = ArrayList<Pair<Int, Rect>>(otherMinis.size)
        val sorted = otherMinis.mapIndexed { idx, r -> idx to Rect(r) }
            .sortedBy { it.second.top }
        var cursor = active.bottom + gap
        for ((idx, r) in sorted) {
            if (!sameSide(active, r) || !Rect.intersects(
                    Rect(active).also { it.inset(-gap * 2, -gap * 4) },
                    r
                )
            ) {
                continue
            }
            val placed = Rect(r)
            placed.offsetTo(placed.left, cursor)
            if (placed.bottom > restriction.bottom) {
                // Flip above active
                placed.offsetTo(placed.left, (active.top - gap - placed.height()).coerceAtLeast(restriction.top))
            }
            result.add(idx to clampBounds(placed, context))
            cursor = placed.bottom + gap
        }
        return result
    }


    /**
     * Xiaomi imeTopPos: movable.bottom - imeHeight - IME_GAP.
     * Returns Int.MAX_VALUE when IME is not effectively showing.
     */
    fun imeTopPos(
        imeHeight: Int,
        context: Context = SystemServices.systemContext
    ): Int {
        if (imeHeight <= 0) return Int.MAX_VALUE
        val restriction = movableRestriction(context)
        return (restriction.bottom - imeHeight - IME_GAP_PX).coerceAtLeast(restriction.top)
    }

    /**
     * Offset/shrink freeform bounds so content sits above IME (Xiaomi onImeVisibilityChanged).
     * Returns null when no change is needed.
     */
    fun applyImeAvoid(
        bounds: Rect,
        imeHeight: Int,
        mini: Boolean = false,
        context: Context = SystemServices.systemContext
    ): Rect? {
        if (imeHeight <= 0) return null
        val restriction = movableRestriction(context)
        var topLimit = imeTopPos(imeHeight, context)
        if (mini) {
            // Mini freeforms keep a small stroke/padding budget like Xiaomi.
            topLimit -= dp(context, 2)
        }
        if (bounds.bottom <= topLimit) return null

        val result = Rect(bounds)
        val minH = if (mini) dp(context, 120) else MIN_VISIBLE_AVOID_IME_FRAME_HEIGHT
        topLimit = topLimit.coerceIn(restriction.top, restriction.bottom)
        val availableHeight = topLimit - restriction.top
        // A bogus/full-display IME measurement must never collapse the task to zero/one pixel.
        // Keeping the original window partially overlapped is safer than making it disappear.
        if (availableHeight < minH) return null
        if (result.height() <= availableHeight) {
            result.offsetTo(result.left, topLimit - result.height())
        } else {
            result.top = restriction.top
            result.bottom = topLimit
        }
        // Keep horizontally inside restriction.
        if (result.left < restriction.left) result.offset(restriction.left - result.left, 0)
        if (result.right > restriction.right) result.offset(restriction.right - result.right, 0)
        return if (result == bounds) null else result
    }

    /**
     * After rotation/display change: keep freeform inside new movable area without
     * destroying size when possible (Xiaomi onDisplayChange relayout).
     */
    fun relayoutAfterDisplayChange(
        bounds: Rect,
        context: Context = SystemServices.systemContext
    ): Rect {
        val restriction = movableRestriction(context)
        val r = Rect(bounds)
        val minW = dp(context, 120)
        val minH = dp(context, 160)
        if (r.width() < minW) r.right = r.left + minW
        if (r.height() < minH) r.bottom = r.top + minH
        // Prefer preserving size; only shrink when larger than display movable area.
        if (r.width() > restriction.width()) {
            r.left = restriction.left
            r.right = restriction.right
        }
        if (r.height() > restriction.height()) {
            r.top = restriction.top
            r.bottom = restriction.bottom
        }
        if (r.left < restriction.left) r.offset(restriction.left - r.left, 0)
        if (r.top < restriction.top) r.offset(0, restriction.top - r.top)
        if (r.right > restriction.right) r.offset(restriction.right - r.right, 0)
        if (r.bottom > restriction.bottom) r.offset(0, restriction.bottom - r.bottom)
        return clampBounds(r, context)
    }

    private fun sameSide(a: Rect, b: Rect, context: Context = SystemServices.systemContext): Boolean {
        return pinEdge(a, context) == pinEdge(b, context)
    }

    private fun coversHeavily(a: Rect, b: Rect, context: Context): Boolean {
        val inter = Rect()
        if (!inter.setIntersect(a, b)) return false
        val area = inter.width().toLong() * inter.height()
        val minArea = (b.width().toLong() * b.height() * 0.45f).toLong()
        val ox = dp(context, AVOID_OFFSET_X_DP)
        val oy = dp(context, AVOID_OFFSET_Y_DP)
        val nearAligned =
            kotlin.math.abs(a.left - b.left) < ox && kotlin.math.abs(a.top - b.top) < oy
        return area >= minArea || nearAligned
    }

    private fun dp(context: Context, value: Int): Int {
        val density = context.resources.displayMetrics.density
        return (value * density).toInt()
    }

    /** WM config_defaultMinimalSizeResizableTask (220dp) in px at the system density. */
    fun minResizableTaskPx(context: Context = SystemServices.systemContext): Int = dp(context, 220)
}
