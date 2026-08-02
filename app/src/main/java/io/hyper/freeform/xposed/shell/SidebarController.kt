package io.hyper.freeform.xposed.shell

import android.content.Intent
import android.content.ComponentCallbacks
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.hyper.freeform.xposed.model.WindowState
import io.hyper.freeform.xposed.policy.FreeformPolicy
import io.hyper.freeform.xposed.server.FreeformManagerService
import io.hyper.freeform.xposed.utils.FreeformHapticHelper
import io.hyper.freeform.xposed.utils.SystemServices
import io.hyper.freeform.xposed.utils.XLog
import kotlin.math.roundToInt

/**
 * Xiaomi-style edge sidebar launcher for opening apps directly in a freeform window.
 *
 * A **thin vertical bar** on the chosen side (left/right, remembered via Settings.Global) is the
 * ONLY place an inward swipe opens the panel — a plain tap, or a swipe starting elsewhere on the
 * edge, does nothing. The bar is drawn small and unobtrusive (miuix / 小米官方侧边栏 look) so it
 * reads as a hint ("slide here"), not a chunk of UI.
 *
 * The panel itself follows the system dark/light theme (read from the system_context Resources
 * uiMode, since this runs in system_server with no Activity), lists the user-selected apps in one
 * vertical column, and tapping a tile opens it as a freeform small window. At most six apps are
 * visible at once; additional apps are reached by scrolling the column vertically.
 *
 * The bar registers [View.systemGestureExclusionRects] in **local** coordinates so the swipe never
 * triggers the back / full-screen edge gesture. No package names are hardcoded — the list comes
 * from the user's selection persisted in Settings.Global (comma-separated packages), falling back
 * to all launcher activities when empty.
 */
object SidebarController {
    /** 0 = left, 1 = right (default). Mirrored from the app UI into Settings.Global. */
    const val SETTING_SIDE = "hyper_freeform_sidebar_side"

    /**
     * Comma-separated launcher package names the user chose to show in the sidebar.
     * Empty/absent → show every launchable (non-blacklisted) app (the original behaviour).
     */
    const val SETTING_APPS = "hyper_freeform_sidebar_apps"
    const val SETTING_SHOW_APP_NAMES = "hyper_freeform_sidebar_show_app_names"
    /** Normalized top position (0..10000), shared across display orientations. */
    const val SETTING_EDGE_POSITION = "hyper_freeform_sidebar_edge_position"

    /** This module's own applicationId — excluded from the launcher list. */
    private const val SELF_PACKAGE = "io.hyper.freeform"

    private const val MAX_VISIBLE_APPS = 6
    private const val PANEL_COMPACT_WIDTH_DP = 64
    private const val PANEL_LABELED_WIDTH_DP = 84
    private const val PANEL_FOOTER_HEIGHT_DP = 36
    private const val APP_TILE_HEIGHT_DP = 52
    private const val APP_TILE_WITH_NAME_HEIGHT_DP = 72
    private const val PANEL_VERTICAL_PADDING_DP = 12
    private const val PANEL_EDGE_MARGIN_DP = 10
    private const val APP_ICON_SIZE_DP = 40
    private const val APP_ICON_WITH_NAME_SIZE_DP = 36

    private val handler = Handler(Looper.getMainLooper())
    private val wm get() = SystemServices.windowManager
    private val ctx get() = SystemServices.systemContext
    private val density get() = ctx.resources.displayMetrics.density

    private var edge: View? = null
    private var edgeLayoutParams: WindowManager.LayoutParams? = null
    private var edgeDisplayHeight = 0
    private var edgeHeight = 0
    private var edgeDragStartTop = 0
    private var edgeDragStartRawY = 0f
    private var panel: PanelView? = null
    private var started = false
    private var sideObserver: ContentObserver? = null
    private var appsObserver: ContentObserver? = null
    private var configObserver: ContentObserver? = null
    private var componentCallbacks: ComponentCallbacks? = null

    fun side(): Int = runCatching {
        Settings.Global.getInt(ctx.contentResolver, SETTING_SIDE, 1)
    }.getOrDefault(1).coerceIn(0, 1)

    fun start() {
        if (started) return
        started = true
        handler.post { attachEdge() }
        runCatching {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    handler.post { reattach() }
                }
            }
            ctx.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(SETTING_SIDE),
                false,
                observer,
            )
            sideObserver = observer
        }
        runCatching {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    handler.post { refreshPanelApps() }
                }
            }
            ctx.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(SETTING_APPS),
                false,
                observer,
            )
            ctx.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(SETTING_SHOW_APP_NAMES),
                false,
                observer,
            )
            appsObserver = observer
        }
        // Re-theme the bar + panel when the system flips dark/light (mirrors miuix theming).
        runCatching {
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    handler.post {
                        (edge as? EdgeView)?.applyTheme()
                        panel?.let { it.refreshTheme() }
                    }
                }
            }
            ctx.contentResolver.registerContentObserver(
                Settings.Global.getUriFor("ui_night_mode"),
                false,
                observer,
            )
            // Secure variant some OEM builds use.
            runCatching {
                ctx.contentResolver.registerContentObserver(
                    Settings.Secure.getUriFor("ui_night_mode"),
                    false,
                    observer,
                )
            }
            configObserver = observer
        }
        runCatching {
            val callbacks = object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    // Effective night mode, density and display bounds can change without the
                    // ui_night_mode setting itself changing (automatic dark mode, rotation, etc.).
                    handler.post { reattach() }
                }

                override fun onLowMemory() = Unit
            }
            ctx.registerComponentCallbacks(callbacks)
            componentCallbacks = callbacks
        }.onFailure { XLog.e("SidebarController configuration callback failed", it) }
        XLog.i("SidebarController started (side=${side()})")
    }

    private fun reattach() {
        runCatching { edge?.let { wm.removeView(it) } }
        edge = null
        edgeLayoutParams = null
        hidePanel()
        attachEdge()
        XLog.i("SidebarController reattached side=${side()}")
    }

    /** Re-query the user's app selection into a visible panel without reopening it. */
    private fun refreshPanelApps() {
        val p = panel ?: return
        val showNames = showAppNames()
        val apps = loadApps()
        val (displayWidth, displayHeight) = displaySize()
        p.setShowAppNames(
            showNames,
            panelWidth(displayWidth, showNames),
            panelHeight(displayHeight, apps.size, showNames),
        )
        p.setApps(apps)
        XLog.d("SidebarController apps refreshed")
    }

    private fun showAppNames(): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, SETTING_SHOW_APP_NAMES, 0) != 0
    }.getOrDefault(false)

    private fun attachEdge() {
        runCatching {
            val (dw, dh) = displaySize()
            val right = side() == 1
            // Keep the same edge attachment, but double the visible strip size.
            val stripW = (10 * density).toInt().coerceAtLeast(16)
            val previousStripH = (dh * 0.34f).toInt()
                .coerceIn((140 * density).toInt(), (dh * 0.55f).toInt())
            val stripH = ((previousStripH * 2) / 5).coerceAtLeast(1)
            val v = EdgeView(
                right = right,
                onOpen = { handler.post { openPanel() } },
                onDragStart = { rawY -> beginEdgeDrag(rawY) },
                onDragMove = { rawY -> updateEdgeDrag(rawY) },
                onDragEnd = { finishEdgeDrag() },
            )
            val travel = (dh - stripH).coerceAtLeast(0)
            val storedPosition = runCatching {
                Settings.Global.getInt(ctx.contentResolver, SETTING_EDGE_POSITION, 5_000)
            }.getOrDefault(5_000).coerceIn(0, 10_000)
            val top = (travel * (storedPosition / 10_000f)).roundToInt()
            val lp = WindowManager.LayoutParams(
                stripW,
                stripH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
                x = 0
                y = top
                title = "HyperFreeformSidebarEdge"
                windowAnimations = 0
                markTrusted(this)
                runCatching {
                    val field = javaClass.getField("layoutInDisplayCutoutMode")
                    field.setInt(this, 1) // LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            wm.addView(v, lp)
            edge = v
            edgeLayoutParams = lp
            edgeDisplayHeight = dh
            edgeHeight = stripH
            // Exclusion rects MUST be in the view's local coordinates (0,0)-(w,h).
            v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                applyGestureExclusion(view)
            }
            v.post { applyGestureExclusion(v) }
            XLog.d(
                "sidebar edge attached side=${if (right) "right" else "left"} " +
                    "size=${stripW}x${stripH} display=${dw}x${dh} top=$top " +
                    "position=$storedPosition",
            )
        }.onFailure { XLog.e("sidebar attach edge failed", it) }
    }

    private fun beginEdgeDrag(rawY: Float) {
        val lp = edgeLayoutParams ?: return
        edgeDragStartTop = lp.y
        edgeDragStartRawY = rawY
        FreeformHapticHelper.hapticLight()
        XLog.d("sidebar edge drag start top=${lp.y} rawY=$rawY")
    }

    private fun updateEdgeDrag(rawY: Float) {
        val view = edge ?: return
        val lp = edgeLayoutParams ?: return
        val maxTop = (edgeDisplayHeight - edgeHeight).coerceAtLeast(0)
        val target = (edgeDragStartTop + rawY - edgeDragStartRawY)
            .roundToInt()
            .coerceIn(0, maxTop)
        if (target == lp.y) return
        lp.y = target
        runCatching { wm.updateViewLayout(view, lp) }
            .onFailure { XLog.e("sidebar edge drag update failed", it) }
    }

    private fun finishEdgeDrag() {
        val lp = edgeLayoutParams ?: return
        val maxTop = (edgeDisplayHeight - edgeHeight).coerceAtLeast(0)
        val normalized = if (maxTop == 0) 5_000 else {
            (lp.y.toFloat() / maxTop * 10_000f).roundToInt().coerceIn(0, 10_000)
        }
        runCatching {
            Settings.Global.putInt(ctx.contentResolver, SETTING_EDGE_POSITION, normalized)
        }.onFailure { XLog.e("sidebar edge position persist failed", it) }
        XLog.i("sidebar edge drag finish top=${lp.y} position=$normalized")
    }

    private fun applyGestureExclusion(view: View) {
        runCatching {
            val w = view.width.coerceAtLeast(1)
            val h = view.height.coerceAtLeast(1)
            val rect = Rect(0, 0, w, h)
            view.systemGestureExclusionRects = listOf(rect)
            XLog.d("sidebar gesture exclusion set ${rect.width()}x${rect.height()} side=${side()}")
        }.onFailure {
            XLog.e("sidebar gesture exclusion failed", it)
        }
    }

    /**
     * System dark/light — read from the system_context Resources (system_server has no Activity)
     * and a couple of Settings as fallback. Mirrors the miuix / 小米官方 侧边栏 which always tracks
     * the active system night-mode.
     */
    fun isDark(): Boolean {
        val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (night == Configuration.UI_MODE_NIGHT_YES) return true
        if (night == Configuration.UI_MODE_NIGHT_NO) return false
        return runCatching {
            Settings.Secure.getInt(ctx.contentResolver, "ui_night_mode", 0) == 2 ||
                Settings.Global.getInt(ctx.contentResolver, "ui_night_mode", 0) == 2
        }.getOrDefault(false)
    }

    /** Apply theme + look to the edge bar view (re-applied on theme flip). */
    private fun applyEdgeLook(view: EdgeView) {
        view.applyTheme()
    }


    /** Open only (never toggle-close from the edge). Edge swipe opens; scrim/back closes. */
    private fun openPanel() {
        if (panel != null) return
        showPanel()
    }

    private fun showPanel() {
        runCatching {
            if (panel != null) return
            val right = side() == 1
            val (dw, dh) = displaySize()
            val showNames = showAppNames()
            val panelW = panelWidth(dw, showNames)
            val apps = loadApps()
            val panelH = panelHeight(dh, apps.size, showNames)
            val p = PanelView(
                right = right,
                showAppNames = showNames,
                onDismiss = { hidePanel() },
                onAdd = { openSidebarAppPicker() },
            )
            p.setApps(apps)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "HyperFreeformSidebarPanel"
                markTrusted(this)
            }
            wm.addView(p, lp)
            panel = p
            p.animateIn(panelW, panelH)
            XLog.d("sidebar panel shown apps=${apps.size}")
        }.onFailure { XLog.e("sidebar show panel failed", it) }
    }

    private fun panelWidth(displayWidth: Int, showNames: Boolean): Int {
        val widthDp = if (showNames) PANEL_LABELED_WIDTH_DP else PANEL_COMPACT_WIDTH_DP
        val maxFraction = if (showNames) 0.36f else 0.28f
        return (widthDp * density).toInt()
            .coerceAtMost((displayWidth * maxFraction).toInt())
    }

    private fun panelHeight(displayHeight: Int, appCount: Int, showNames: Boolean): Int {
        val visibleApps = appCount.coerceIn(1, MAX_VISIBLE_APPS)
        val tileHeight = if (showNames) APP_TILE_WITH_NAME_HEIGHT_DP else APP_TILE_HEIGHT_DP
        val desiredHeight = (
            PANEL_FOOTER_HEIGHT_DP +
                PANEL_VERTICAL_PADDING_DP +
                visibleApps * tileHeight
            ) * density
        return desiredHeight.toInt().coerceAtMost((displayHeight * 0.82f).toInt())
    }

    private fun hidePanel() {
        val p = panel ?: return
        panel = null
        runCatching {
            p.animateOutAndRemove {
                runCatching { wm.removeView(p) }
            }
        }.onFailure {
            runCatching { wm.removeView(p) }
        }
    }

    /**
     * Build the sidebar app list. An absent/empty selection means every launchable app. Once the
     * user saved a non-empty selection, only valid packages from that selection are shown; if one
     * is uninstalled we do not unexpectedly replace it with every app on the device.
     */
    private fun loadApps(): List<AppItem> {
        return runCatching {
            val pm = SystemServices.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            // resolved: packageName -> ResolveInfo, so selection order can be honoured.
            val byPkg = pm.queryIntentActivities(intent, 0).asSequence()
                .filterNotNull()
                .filter { !it.activityInfo?.packageName.isNullOrEmpty() }
                .associateBy { it.activityInfo!!.packageName }
                .toMutableMap()

            // User-selected packages (comma-separated in Settings.Global), in their stored order.
            val raw = runCatching {
                Settings.Global.getString(ctx.contentResolver, SETTING_APPS)
            }.getOrNull()
            val selected = raw.orEmpty().split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != ctx.packageName && it != SELF_PACKAGE }

            val runningPackages = FreeformManagerService.allStates()
                .filter { WindowState.isVisibleFreeform(it.windowState) || WindowState.isPinned(it.windowState) }
                .map { it.packageName }
                .toSet()

            val pkgOrder: List<String> = if (selected.isNotEmpty()) {
                selected.filter { byPkg.containsKey(it) }
                    .sortedWith(
                        compareByDescending<String> { it in runningPackages }
                            .thenBy { selected.indexOf(it) }
                    )
            } else {
                byPkg.keys
                    .filter { it != ctx.packageName && it != SELF_PACKAGE }
                    .filterNot { FreeformPolicy.isBlacklisted(it) }
                    .sortedWith(
                        compareByDescending<String> { it in runningPackages }
                            .thenBy { (byPkg[it]?.loadLabel(pm)?.toString() ?: it).lowercase() }
                    )
            }

            pkgOrder.mapNotNull { pkg ->
                val ri = byPkg[pkg] ?: return@mapNotNull null
                if (FreeformPolicy.isBlacklisted(pkg)) return@mapNotNull null
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
                val icon = runCatching { ri.loadIcon(pm) }.getOrNull()
                AppItem(label, icon) { launch(pkg) }
            }
        }.onFailure {
            XLog.e("sidebar loadApps failed", it)
        }.getOrDefault(emptyList())
    }

    private fun launch(pkg: String) {
        hidePanel()
        // Package comes from PackageManager at runtime — never a hardcoded constant.
        runCatching {
            FreeformManagerService.startFreeformPackage(pkg, 0, WindowState.NORMAL)
        }.onFailure {
            XLog.e("sidebar launch failed pkg=$pkg", it)
        }
    }

    private fun openSidebarAppPicker() {
        hidePanel()
        runCatching {
            val intent = Intent().apply {
                setClassName(SELF_PACKAGE, "io.hyper.freeform.ui.SidebarAppsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            ctx.startActivity(intent)
            XLog.i("sidebar app picker requested")
        }.onFailure { directError ->
            // Compatibility fallback for ROMs that reject cross-package Activity starts even from
            // system_server. The exported receiver then starts the private picker in its own UID.
            runCatching {
                val fallback = Intent("io.hyper.freeform.OPEN_SIDEBAR_APPS").apply {
                    setClassName(SELF_PACKAGE, "io.hyper.freeform.receiver.StartFreeformReceiver")
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                ctx.sendBroadcastAsUser(fallback, android.os.Process.myUserHandle())
            }.onFailure { XLog.e("sidebar app picker launch failed", directError) }
        }
    }

    private fun displaySize(): Pair<Int, Int> = runCatching {
        val b = wm.currentWindowMetrics.bounds
        b.width() to b.height()
    }.getOrDefault(1080 to 1920)

    private fun markTrusted(lp: WindowManager.LayoutParams) {
        runCatching { lp.javaClass.getMethod("setTrustedOverlay").invoke(lp) }
            .recoverCatching {
                val f = lp.javaClass.getField("privateFlags")
                f.setInt(lp, f.getInt(lp) or 0x20000000) // PRIVATE_FLAG_TRUSTED_OVERLAY
            }
    }

    class AppItem(val label: String, val icon: Drawable?, val onClick: () -> Unit)

    /**
     * Thin vertical edge bar — the ONLY region an inward swipe opens the panel from.
     *
     * On a swipe starting here + travelling inward clearly, [onOpen] fires once. A plain tap, an
     * outward swipe, or a swipe that started elsewhere (the bar consumes its own touches) does
     * nothing — telling the user "only this strip opens the panel".
     */
    private class EdgeView(
        val right: Boolean,
        val onOpen: () -> Unit,
        val onDragStart: (Float) -> Unit,
        val onDragMove: (Float) -> Unit,
        val onDragEnd: () -> Unit,
    ) : View(SystemServices.systemContext) {
        private val d = resources.displayMetrics.density
        private var downX = 0f
        private var downY = 0f
        private var opened = false
        private var dragging = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private val longPress = Runnable {
            if (!opened && isPressed && isAttachedToWindow) {
                dragging = true
                onDragStart(downY)
            }
        }

        init {
            // Miuix: a slim translucent capsule on the edge — a discoverable but quiet hint.
            applyTheme()
            isClickable = true
            isLongClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        /** Re-tone for the active system dark/light theme. */
        fun applyTheme() {
            background = GradientDrawable().apply {
                // Slim pill on the edge — radius ≈ half its (small) width, capped at ~12dp.
                cornerRadius = (4f * d).coerceAtLeast(2f)
                // Subtle pill; slightly stronger on dark so it stays visible against busy content.
                setColor(if (controllerIsDark()) 0x33FFFFFF else 0x22000000)
                setStroke(
                    (1 * d).toInt().coerceAtLeast(1),
                    if (controllerIsDark()) 0x20FFFFFF else 0x18000000,
                )
            }
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    opened = false
                    dragging = false
                    isPressed = true
                    removeCallbacks(longPress)
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    return true // own this gesture so the system edge-back gesture can't start here
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        onDragMove(ev.rawY)
                        return true
                    }
                    if (opened) return true
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    val inward = if (right) -dx else dx
                    // A clearly horizontal INWARD swipe from this strip opens; anything else (tap,
                    // outward, mostly-vertical) is intentionally ignored.
                    if (inward > slop && inward > kotlin.math.abs(dy) * 1.1f) {
                        opened = true
                        isPressed = false
                        removeCallbacks(longPress)
                        onOpen()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    if (dragging) {
                        onDragMove(ev.rawY)
                        onDragEnd()
                    }
                    dragging = false
                    isPressed = false
                    // Tap without an inward swipe → no-op (the bar is a slide hint, not a button).
                    return true
                }
            }
            return false
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(longPress)
            dragging = false
            isPressed = false
            super.onDetachedFromWindow()
        }
    }

    private fun controllerIsDark(): Boolean = isDark()

    /** Xiaomi sidebar sp_app_add icon: a 24dp white tile with a gray rounded plus. */
    private class XiaomiSidebarAddView : View(SystemServices.systemContext) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val plusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = minOf(width, height) / 120f
            val offsetX = (width - 120f * scale) / 2f
            val offsetY = (height - 120f * scale) / 2f

            fillPaint.style = Paint.Style.FILL
            fillPaint.color = if (isPressed) 0xFFE0E0E0.toInt() else 0xFFF0F0F0.toInt()
            canvas.drawRoundRect(
                offsetX + 4f * scale,
                offsetY + 4f * scale,
                offsetX + 116f * scale,
                offsetY + 116f * scale,
                26f * scale,
                26f * scale,
                fillPaint,
            )

            plusPaint.color = 0xFFB1B1B1.toInt()
            plusPaint.strokeWidth = 6f * scale
            val centerX = offsetX + 60f * scale
            val centerY = offsetY + 60f * scale
            canvas.drawLine(centerX, offsetY + 43f * scale, centerX, offsetY + 77f * scale, plusPaint)
            canvas.drawLine(offsetX + 43f * scale, centerY, offsetX + 77f * scale, centerY, plusPaint)
        }
    }

    /** Xiaomi-style floating app rail. Extra apps remain available through vertical scrolling. */
    private class PanelView(
        val right: Boolean,
        private var showAppNames: Boolean,
        val onDismiss: () -> Unit,
        val onAdd: () -> Unit,
    ) : LinearLayout(SystemServices.systemContext) {
        private val d = resources.displayMetrics.density
        private val card = LinearLayout(context).apply { orientation = VERTICAL }
        private val list = LinearLayout(context).apply { orientation = VERTICAL }
        private val addButton = XiaomiSidebarAddView()
        private var currentApps: List<AppItem> = emptyList()
        private var cardW = 0
        private var dark = false

        init {
            orientation = HORIZONTAL
            dark = controllerIsDark()
            setBackgroundColor(Color.TRANSPARENT)
            gravity = Gravity.CENTER_VERTICAL or (if (right) Gravity.END else Gravity.START)

            card.addView(
                ScrollView(context).apply {
                    isFillViewport = false
                    isVerticalScrollBarEnabled = false
                    isSmoothScrollingEnabled = true
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    addView(
                        list,
                        android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
            )

            addButton.apply {
                contentDescription = "添加应用"
                isClickable = true
                isFocusable = true
                setOnClickListener { onAdd() }
            }
            val addSize = (24 * d).toInt()
            val footer = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                addView(addButton, LayoutParams(addSize, addSize))
            }
            card.addView(
                footer,
                LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    (PANEL_FOOTER_HEIGHT_DP * d).toInt(),
                ),
            )
            applyCardTheme()
            card.elevation = 10f * d
            card.clipToOutline = true

            setOnClickListener { onDismiss() }
            card.isClickable = true
            card.setOnClickListener { /* consume */ }
        }

        private fun controllerIsDark(): Boolean = SidebarController.isDark()

        private fun applyCardTheme() {
            card.background = GradientDrawable().apply {
                setColor(if (dark) 0xF2292929.toInt() else 0xF7FAFAFA.toInt())
                cornerRadius = 24f * d
                setStroke(
                    (0.5f * d).toInt().coerceAtLeast(1),
                    if (dark) 0x24FFFFFF else 0x16000000,
                )
            }
        }

        fun refreshTheme() {
            val nowDark = controllerIsDark()
            if (nowDark == dark) return
            dark = nowDark
            applyCardTheme()
            renderTiles(currentApps)
        }

        fun setShowAppNames(show: Boolean, width: Int, height: Int) {
            val modeChanged = showAppNames != show
            showAppNames = show
            cardW = width
            (card.layoutParams as? LayoutParams)?.let { params ->
                params.width = width
                params.height = height
                card.layoutParams = params
            }
            if (modeChanged) renderTiles(currentApps)
        }

        fun setApps(apps: List<AppItem>) {
            currentApps = apps
            renderTiles(apps)
        }

        private fun renderTiles(apps: List<AppItem>) {
            list.removeAllViews()
            if (apps.isEmpty()) {
                list.addView(
                    TextView(context).apply {
                        text = "没有可打开的应用"
                        setTextColor(if (dark) 0x99FFFFFF.toInt() else 0x99000000.toInt())
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setPadding(
                            (12 * d).toInt(),
                            (16 * d).toInt(),
                            (12 * d).toInt(),
                            (16 * d).toInt(),
                        )
                    },
                )
                return
            }
            val padHorizontal = ((if (showAppNames) 12 else 10) * d).toInt()
            val padVertical = (6 * d).toInt()
            val iconSizeDp = if (showAppNames) APP_ICON_WITH_NAME_SIZE_DP else APP_ICON_SIZE_DP
            val iconSize = (iconSizeDp * d).toInt()
            val tileHeightDp = if (showAppNames) {
                APP_TILE_WITH_NAME_HEIGHT_DP
            } else {
                APP_TILE_HEIGHT_DP
            }
            apps.forEach { app ->
                val tile = LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    contentDescription = app.label
                    background = null
                    setOnClickListener { app.onClick() }
                }
                val icon = ImageView(context).apply {
                    app.icon?.let { setImageDrawable(it) }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(
                                0,
                                0,
                                view.width,
                                view.height,
                                iconSize * 0.22f,
                            )
                        }
                    }
                }
                tile.addView(icon, LayoutParams(iconSize, iconSize))
                if (showAppNames) {
                    tile.addView(
                        TextView(context).apply {
                            text = app.label
                            textSize = 10.5f
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            includeFontPadding = false
                            gravity = Gravity.CENTER
                            textAlignment = View.TEXT_ALIGNMENT_CENTER
                            setTextColor(if (dark) 0xE6FFFFFF.toInt() else 0xE6000000.toInt())
                            setPadding((2 * d).toInt(), (3 * d).toInt(), (2 * d).toInt(), 0)
                        },
                        LayoutParams(
                            LayoutParams.MATCH_PARENT,
                            (27 * d).toInt(),
                        ),
                    )
                }
                list.addView(
                    tile,
                    LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        (tileHeightDp * d).toInt(),
                    ),
                )
            }
            list.setPadding(padHorizontal, padVertical, padHorizontal, padVertical)
        }

        private fun cardLayoutParams(width: Int, height: Int): LayoutParams {
            return LayoutParams(width, height).apply {
                val margin = (PANEL_EDGE_MARGIN_DP * d).toInt()
                if (right) rightMargin = margin else leftMargin = margin
            }
        }

        fun animateIn(width: Int, height: Int) {
            cardW = width
            if (card.parent == null) {
                addView(card, cardLayoutParams(width, height))
            }
            val travel = width + PANEL_EDGE_MARGIN_DP * d
            card.translationX = if (right) travel else -travel
            card.animate().translationX(0f).setDuration(220).start()
        }

        fun animateOutAndRemove(remove: () -> Unit) {
            val travel = cardW + PANEL_EDGE_MARGIN_DP * d
            val target = if (right) travel else -travel
            card.animate()
                .translationX(target)
                .setDuration(180)
                .withEndAction { remove() }
                .start()
        }
    }
}
