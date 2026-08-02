package io.hyper.freeform.xposed.shell

import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.reflect.Proxy
import io.hyper.freeform.xposed.model.FreeformTaskState
import io.hyper.freeform.xposed.model.WindowState
import io.hyper.freeform.xposed.policy.FreeformPolicy
import io.hyper.freeform.xposed.server.FreeformManagerService
import io.hyper.freeform.xposed.utils.FreeformHapticHelper
import io.hyper.freeform.xposed.utils.SystemServices
import io.hyper.freeform.xposed.utils.XLog
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot

/**
 * Shell-side gesture/overlay controller (Xiaomi SystemUI freeform shell role).
 * Caption: move / bottom close-fullscreen / corner resize / edge pin /
 * top-handle tap → MulWinSwitch-style window control menu
 * (fullscreen / split / freeform / close);
 * phone freeform drag top hot zone (~4.1% height) → fullscreen
 * (Xiaomi MultiTaskingHotAreaController type0).
 * Pin bubble: single-tap unpin restore; double-tap startPinToFullscreen;
 * freeform→pin animation is interruptible (Xiaomi handleInterruptPin).
 */
class FreeformShellController {
    private val handler = Handler(Looper.getMainLooper())
    private val captions = ConcurrentHashMap<Int, CaptionSession>()
    private val bubbles = ConcurrentHashMap<Int, BubbleSession>()
    private val launchSplashes = ConcurrentHashMap<String, LaunchSplashSession>()
    private val wm: WindowManager
        get() = SystemServices.windowManager

    fun start() {
        XLog.d("FreeformShellController started")
    }

    fun onTaskAdded(state: FreeformTaskState) {
        if (WindowState.isPinned(state.windowState) && !state.pinAnimating) {
            showBubble(state)
        } else {
            showCaption(state)
        }
    }

    fun onTaskRemoved(taskId: Int) {
        removeCaption(taskId)
        removeBubble(taskId)
    }

    fun onTaskFocused(taskId: Int) {
        captions[taskId]?.bringToFrontHint()
    }

    fun onStateChanged(state: FreeformTaskState) {
        if (WindowState.isPinned(state.windowState)) {
            if (state.pinAnimating) {
                // Interruptible pin shrink window: keep caption, no bubble yet.
                removeBubble(state.taskId)
                showCaption(state)
            } else {
                removeCaption(state.taskId)
                showBubble(state)
            }
        } else {
            removeBubble(state.taskId)
            showCaption(state)
        }
    }

    fun onPinned(state: FreeformTaskState) {
        removeCaption(state.taskId)
        showBubble(state)
    }

    /**
     * Xiaomi startPinAnimation / onPinAnimStarted:
     * keep freeform chrome visible so touch can handleInterruptPin before bubble.
     */
    fun onPinAnimating(state: FreeformTaskState) {
        removeBubble(state.taskId)
        showCaption(state)
        captions[state.taskId]?.setPinAnimating(true)
    }

    /**
     * Xiaomi setPinAnimInfo progress mirror on chrome overlay (0..1).
     * Task leash shrink is driven by FreeformManagerService/SystemServices.
     */
    fun onWindowAnimFrame(
        taskId: Int,
        frame: FreeformPolicy.WindowVisualFrame,
    ) {
        handler.post {
            captions[taskId]?.applyVisualFrame(frame)
        }
    }

    fun onLaunchAnimFrame(packageName: String, frame: FreeformPolicy.WindowVisualFrame) {
        handler.post { launchSplashes[packageName]?.applyVisualFrame(frame) }
    }

    fun onUnpinned(state: FreeformTaskState) {
        removeBubble(state.taskId)
        captions[state.taskId]?.setPinAnimating(false)
        showCaption(state)
    }

    fun showLaunchSplash(packageName: String, bounds: Rect) {
        val show = {
            launchSplashes.remove(packageName)?.detach()
            LaunchSplashSession(packageName, Rect(bounds)).also {
                launchSplashes[packageName] = it
                it.attach()
            }
        }
        // startFreeform runs on system_server's main looper as well.  Attach synchronously there
        // so the opaque mask is already present before startActivity can publish its starting
        // window; posting used to expose one unscaled frame and looked like a sideways flash.
        if (Looper.myLooper() == handler.looper) show() else handler.post { show() }
    }

    fun dismissLaunchSplash(packageName: String) {
        handler.post { launchSplashes.remove(packageName)?.animateOutAndDetach() }
    }

    private fun showCaption(state: FreeformTaskState) {
        val apply = {
            val existing = captions[state.taskId]
            if (existing != null) {
                existing.update(state)
            } else {
                val session = CaptionSession(state)
                captions[state.taskId] = session
                session.attach()
            }
        }
        // Apply immediately on main so resize->mini wins over in-flight gesture samples.
        if (Looper.myLooper() == handler.looper) apply()
        else handler.post { apply() }
    }

    private fun removeCaption(taskId: Int) {
        handler.post {
            captions.remove(taskId)?.detach()
        }
    }

    private fun showBubble(state: FreeformTaskState) {
        handler.post {
            val existing = bubbles[state.taskId]
            if (existing != null) {
                existing.update(state)
                return@post
            }
            val session = BubbleSession(state)
            bubbles[state.taskId] = session
            session.attach()
        }
    }

    private fun removeBubble(taskId: Int) {
        handler.post {
            bubbles.remove(taskId)?.detach()
        }
    }

    private inner class LaunchSplashSession(
        private val packageName: String,
        private val bounds: Rect,
    ) {
        private val view = FreeformLaunchSplashView(SystemServices.systemContext, packageName)
        private var attached = false
        private val timeout = Runnable {
            if (launchSplashes.remove(packageName, this)) detach()
        }

        fun attach() {
            val lp = WindowManager.LayoutParams(
                bounds.width().coerceAtLeast(1),
                bounds.height().coerceAtLeast(1),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = bounds.left
                y = bounds.top
                title = "HyperFreeformLaunchSplash-${this@LaunchSplashSession.packageName}"
                runCatching { javaClass.getMethod("setTrustedOverlay").invoke(this) }
                    .recoverCatching {
                        val field = javaClass.getField("privateFlags")
                        field.setInt(this, field.getInt(this) or 0x20000000)
                    }
            }
            runCatching {
                wm.addView(view, lp)
                attached = true
                handler.postDelayed(timeout, 8_000L)
                XLog.d("launch splash shown pkg=$packageName bounds=$bounds")
            }.onFailure { XLog.e("launch splash attach failed pkg=$packageName", it) }
        }

        fun detach() {
            handler.removeCallbacks(timeout)
            if (!attached) return
            attached = false
            runCatching { wm.removeViewImmediate(view) }
            XLog.d("launch splash hidden pkg=$packageName")
        }

        fun applyVisualFrame(frame: FreeformPolicy.WindowVisualFrame) {
            val baseW = bounds.width().coerceAtLeast(1).toFloat()
            val baseH = bounds.height().coerceAtLeast(1).toFloat()
            view.pivotX = baseW / 2f
            view.pivotY = baseH / 2f
            view.scaleX = frame.width / baseW
            view.scaleY = frame.height / baseH
            view.translationX = frame.centerX - bounds.exactCenterX()
            view.translationY = frame.centerY - bounds.exactCenterY()
            view.alpha = frame.alpha
        }

        fun animateOutAndDetach() {
            handler.removeCallbacks(timeout)
            if (!attached) return
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .setDuration(100L)
                .withEndAction { detach() }
                .start()
        }
    }

    private inner class CaptionSession(private var state: FreeformTaskState) {
        private val density = SystemServices.systemContext.resources.displayMetrics.density
        private val captionHeight = (36 * density).toInt()
        private val pillTouchWidth = (64 * density).toInt()
        private val pillTouchHeight = (24 * density).toInt()
        private val edge = (22 * density).toInt()
        // Bottom 小白条 (close ↑ / fullscreen ↓) gesture strip — CENTER only, so the rest of the
        // bottom edge falls through to the app and normal taps there still work.
        private val bottomBarTouchWidth = (64 * density).toInt()
        private val bottomBarTouchHeight = (28 * density).toInt()
        // Resize corners — small squares hugging each bottom corner (at the app border), so they
        // don't eat normal taps near the bottom of the app (e.g. bilibili's fullscreen button).
        private val cornerTouch = (28 * density).toInt()
        private val root = FrameLayout(SystemServices.systemContext)
        private val chrome = FreeformChromeView(SystemServices.systemContext)
        private val caption = FreeformCaptionView(SystemServices.systemContext)
        private var lp = baseLayoutParams()
        private var attached = false
        /** SystemUI-style touchable region so freeform content receives app touches. */
        private var touchableInsetsInstalled = false
        private var touchableInsetsListener: Any? = null
        /** Visual-only live move/resize source (no real Task-bounds churn until settle). */
        private var liveResizeBase: Rect? = null

        // Xiaomi touch modes (MiuiFreeformModeGestureHandler)
        private val MODE_NONE = 0
        private val MODE_MOVE = 1          // top caption / mini body
        private val MODE_RESIZE_BR = 2
        private val MODE_RESIZE_BL = 3
        private val MODE_BOTTOM = 4

        // gesture state
        private var mode = MODE_NONE
        private var downX = 0f
        private var downY = 0f
        private var startBounds = Rect()
        private var velocityTracker: VelocityTracker? = null
        private var lastTapUpMs = 0L
        private var pendingMiniSingleTap: Runnable? = null
        /** One-shot haptics per gesture (Xiaomi lastTarget flags lite). */
        private var hapticMiniFired = false
        private var hapticBottomTarget: String? = null

        // Xiaomi MulWinSwitch handle menu (MiuiDecorationDot.createHandleMenu)
        private var handleMenu: FreeformHandleMenuView? = null
        private var handleMenuLp: WindowManager.LayoutParams? = null
        private var handleMenuAttached = false

        // Xiaomi MultiTaskingHotAreaController (phone freeform drag → top fullscreen strip)
        private var dragHotArea = FreeformPolicy.HOT_AREA_TYPE_FREEFORM
        private var hotIndicator: FreeformHotAreaIndicatorView? = null
        private var hotIndicatorLp: WindowManager.LayoutParams? = null
        private var hotIndicatorAttached = false

        fun attach() {
            if (attached) return
            chrome.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            chrome.setCaptionHeight(captionHeight)
            chrome.setMini(state.windowState == WindowState.MINI)
            chrome.setShowTips(
                state.windowState != WindowState.MINI && !state.landscape,
            )
            root.addView(chrome)
            caption.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                captionHeight,
                Gravity.TOP
            )
            root.addView(caption)
            // invisible edge handles — same listener as root so corner/bottom hits are reliable
            val br = edgeHandle(Gravity.BOTTOM or Gravity.END)
            val bl = edgeHandle(Gravity.BOTTOM or Gravity.START)
            val bottom = bottomHandle()
            root.addView(br)
            root.addView(bl)
            root.addView(bottom)
            applyBounds(state.bounds)
            caption.setMini(state.windowState == WindowState.MINI)
            caption.setTitle(state.packageName.substringAfterLast('.').ifEmpty { state.packageName })
            val touch = View.OnTouchListener { _, ev -> handleTouch(ev) }
            root.setOnTouchListener(touch)
            caption.setOnTouchListener(touch)
            br.setOnTouchListener(touch)
            bl.setOnTouchListener(touch)
            bottom.setOnTouchListener(touch)
            runCatching {
                wm.addView(root, lp)
                attached = true
                installTouchableRegion()
                root.requestApplyInsets()
            }.onFailure { XLog.e("caption attach failed", it) }
        }

        /**
         * Only the center pill / corners / bottom strip are touchable.
         * Content region falls through to the freeform app — fixes dead touch inside freeform.
         * Pattern: SystemUI DragLayout / WindowDecoration TOUCHABLE_INSETS_REGION.
         */
        private fun installTouchableRegion() {
            if (touchableInsetsInstalled) return
            runCatching {
                val vto = root.viewTreeObserver
                val listenerClz = Class.forName(
                    "android.view.ViewTreeObserver\$OnComputeInternalInsetsListener",
                )
                val listener = Proxy.newProxyInstance(
                    listenerClz.classLoader,
                    arrayOf(listenerClz),
                ) { proxy, method, args ->
                    when (method.name) {
                        "onComputeInternalInsets" -> {
                            if (args != null && args.isNotEmpty()) {
                                applyTouchableInsets(args[0])
                            }
                            null
                        }
                        // ViewTreeObserver removes listeners through ArrayList.remove(),
                        // which invokes equals/hashCode on the dynamic proxy.
                        "equals" -> proxy === args?.getOrNull(0)
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> "HyperFreeformInsets-${state.taskId}"
                        else -> null
                    }
                }
                val add = vto.javaClass.methods.first {
                    it.name == "addOnComputeInternalInsetsListener" &&
                        it.parameterTypes.size == 1
                }
                add.isAccessible = true
                add.invoke(vto, listener)
                touchableInsetsListener = listener
                touchableInsetsInstalled = true
                XLog.d("caption touchable-region installed task=${state.taskId}")
            }.onFailure {
                XLog.e("caption touchable-region install failed task=${state.taskId}", it)
            }
        }

        private fun uninstallTouchableRegion() {
            val listener = touchableInsetsListener ?: run {
                touchableInsetsInstalled = false
                return
            }
            runCatching {
                val vto = root.viewTreeObserver
                val remove = vto.javaClass.methods.first {
                    it.name == "removeOnComputeInternalInsetsListener" &&
                        it.parameterTypes.size == 1
                }
                remove.isAccessible = true
                remove.invoke(vto, listener)
            }.onFailure {
                XLog.e("caption touchable-region remove failed task=${state.taskId}", it)
            }
            touchableInsetsListener = null
            touchableInsetsInstalled = false
        }

        private fun applyTouchableInsets(info: Any) {
            // AOSP InternalInsetsInfo (API 33+ / MuMu 15):
            //   FRAME=0 CONTENT=1 VISIBLE=2 REGION=3
            // SystemUI DragLayout / BubbleStackView use setTouchableInsets(3).
            // Using 2 (VISIBLE) makes the whole overlay steal freeform content touches.
            runCatching {
                info.javaClass.getMethod(
                    "setTouchableInsets",
                    Integer.TYPE,
                ).invoke(info, 3)
            }
            val region = runCatching {
                info.javaClass.getField("touchableRegion").get(info) as Region
            }.getOrNull() ?: return
            val w = root.width.coerceAtLeast(lp.width).coerceAtLeast(1)
            val h = root.height.coerceAtLeast(lp.height).coerceAtLeast(1)
            if (state.pinAnimating) {
                // Pin-anim needs a full interrupt surface only while shrinking.
                region.set(0, 0, w, h)
                return
            }
            val mini = state.windowState == WindowState.MINI
            if (mini) {
                // Xiaomi mini (windowState=1): the WHOLE content area is a shell move/tap
                // zone — single-tap restores to normal, drag moves. The app underneath must
                // NOT receive touches, otherwise tapping the mini hits the app instead of
                // expanding it (design doc §「mini 内容区 → 移动/点击」, 「单击恢复」).
                region.set(0, 0, w, h)
                return
            }
            // Sparse touchable region: ONLY the top pill, the bottom-center 小白条, and the two
            // bottom resize corners. Everything else falls through to the app so normal taps work.
            // A landscape video player owns its complete bottom edge (play/subtitle/speed/quality),
            // so only the top pill remains touchable while that state is active.
            val pillLeft = ((w - pillTouchWidth) / 2).coerceAtLeast(0)
            val pillRight = (pillLeft + pillTouchWidth).coerceAtMost(w)
            val pillTop = ((captionHeight - pillTouchHeight) / 2).coerceAtLeast(0)
            val pillBottom = (pillTop + pillTouchHeight).coerceAtMost(h)
            val barLeft = ((w - bottomBarTouchWidth) / 2).coerceAtLeast(0)
            val barRight = (barLeft + bottomBarTouchWidth).coerceAtMost(w)
            val local = Region()
            local.set(pillLeft, pillTop, pillRight, pillBottom)
            if (!state.landscape) {
                // Bottom-center 小白条 strip only.
                local.op(
                    barLeft,
                    (h - bottomBarTouchHeight).coerceAtLeast(0),
                    barRight,
                    h,
                    Region.Op.UNION,
                )
                // Bottom-left resize corner (at the border).
                local.op(
                    0,
                    (h - cornerTouch).coerceAtLeast(0),
                    cornerTouch.coerceAtMost(w),
                    h,
                    Region.Op.UNION,
                )
                // Bottom-right resize corner (at the border).
                local.op(
                    (w - cornerTouch).coerceAtLeast(0),
                    (h - cornerTouch).coerceAtLeast(0),
                    w,
                    h,
                    Region.Op.UNION,
                )
            }
            region.set(local)
        }

        private fun edgeHandle(gravity: Int): View {
            val v = View(SystemServices.systemContext)
            // Small corner square at the app border — grabbable for resize, not eating taps.
            v.layoutParams = FrameLayout.LayoutParams(cornerTouch, cornerTouch, gravity)
            v.isClickable = true
            v.isFocusable = false
            return v
        }

        private fun bottomHandle(): View {
            val v = View(SystemServices.systemContext)
            // Only the center 小白条 strip, not the full width.
            v.layoutParams = FrameLayout.LayoutParams(
                bottomBarTouchWidth,
                bottomBarTouchHeight,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
            v.isClickable = true
            v.isFocusable = false
            return v
        }

        fun setPinAnimating(animating: Boolean) {
            state.pinAnimating = animating
            if (!animating) {
                // Interrupt / unpin: restore chrome transform.
                root.animate().cancel()
                root.pivotX = root.width / 2f
                root.pivotY = root.height / 2f
                root.scaleX = 1f
                root.scaleY = 1f
                root.translationX = 0f
                root.translationY = 0f
                root.alpha = 1f
            }
        }

        fun applyVisualFrame(frame: FreeformPolicy.WindowVisualFrame) {
            val bounds = state.bounds
            val baseW = bounds.width().coerceAtLeast(1).toFloat()
            val baseH = bounds.height().coerceAtLeast(1).toFloat()
            root.pivotX = baseW / 2f
            root.pivotY = baseH / 2f
            root.scaleX = frame.width / baseW
            root.scaleY = frame.height / baseH
            root.translationX = frame.centerX - bounds.exactCenterX()
            root.translationY = frame.centerY - bounds.exactCenterY()
            root.alpha = frame.alpha
        }

        fun update(newState: FreeformTaskState) {
            state = newState
            if (!newState.pinAnimating) {
                root.scaleX = 1f
                root.scaleY = 1f
                root.translationX = 0f
                root.translationY = 0f
                root.alpha = 1f
            }
            val mini = newState.windowState == WindowState.MINI
            caption.setMini(mini)
            chrome.setMini(mini)
            chrome.setShowTips(!mini && !newState.landscape)
            applyBounds(newState.bounds)
            if (mini || WindowState.isPinned(newState.windowState)) {
                hideHandleMenu()
            } else if (handleMenuAttached) {
                // Keep menu anchored if freeform bounds changed under it.
                val (dw, dh) = FreeformPolicy.displaySize()
                handleMenu?.setLandscape(dw > dh)
                // Menu sits just under the in-window caption pill (Xiaomi WCHandlerMargin).
                val topMargin = (newState.bounds.top + captionHeight + (6 * density).toInt())
                    .coerceIn(0, (dh - (handleMenu?.preferredHeight() ?: 0) - 8).coerceAtLeast(0))
                handleMenu?.setCardTopMargin(topMargin)
                handleMenu?.invalidate()
            }
            if (attached) runCatching { wm.updateViewLayout(root, lp) }
        }

        fun bringToFrontHint() {
            caption.flash()
            chrome.setActive(true)
            root.postDelayed({ chrome.setActive(false) }, 220)
        }

        fun detach() {
            cancelPendingMiniTap()
            hideHandleMenu()
            hideHotIndicator()
            velocityTracker?.recycle()
            velocityTracker = null
            liveResizeBase = null
            uninstallTouchableRegion()
            if (!attached) return
            runCatching { wm.removeView(root) }
            attached = false
        }

        private fun applyBounds(bounds: Rect) {
            // Xiaomi: chrome is ON the freeform window, not a detached bar above it.
            lp.x = bounds.left
            lp.y = bounds.top
            lp.width = bounds.width().coerceAtLeast(1)
            lp.height = bounds.height().coerceAtLeast(1)
            caption.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                captionHeight,
                Gravity.TOP
            )
            // Recompute touchable holes after geometry change.
            root.invalidate()
        }

        private fun baseLayoutParams(): WindowManager.LayoutParams {
            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            return WindowManager.LayoutParams(
                0, 0, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "HyperFreeformCaption-${state.taskId}"
                markTrustedOverlay(this)
            }
        }

        private fun markTrustedOverlay(params: WindowManager.LayoutParams) {
            // System-owned WindowManager overlay. Trusted input prevents Android's
            // cross-UID obscured-touch policy from blocking third-party app content.
            runCatching {
                params.javaClass.getMethod("setTrustedOverlay").invoke(params)
            }.recoverCatching {
                val f = params.javaClass.getField("privateFlags")
                f.setInt(params, f.getInt(params) or 0x20000000)
            }.onFailure {
                XLog.e("caption trusted-overlay setup failed task=${state.taskId}", it)
            }
        }

        private fun handleTouch(ev: MotionEvent): Boolean {
            // Listeners may sit on child handles; always hit-test in root coordinates.
            val rootLoc = IntArray(2)
            root.getLocationOnScreen(rootLoc)
            val localX = ev.rawX - rootLoc[0]
            val localY = ev.rawY - rootLoc[1]
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Xiaomi handleInterruptPin: any touch during freeform→pin anim restores.
                    if (state.pinAnimating) {
                        XLog.i("gesture interrupt pin anim task=${state.taskId}")
                        FreeformManagerService.interruptPinTask(state.taskId)
                        mode = MODE_NONE
                        return true
                    }
                    cancelPendingMiniTap()
                    hapticMiniFired = false
                    hapticBottomTarget = null
                    liveResizeBase = null
                    dragHotArea = FreeformPolicy.HOT_AREA_TYPE_FREEFORM
                    hideHotIndicator()
                    downX = ev.rawX
                    downY = ev.rawY
                    startBounds = Rect(state.bounds)
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain().also {
                        it.addMovement(ev)
                    }
                    mode = hitTest(localX, localY)
                    caption.setActive(mode != MODE_NONE)
                    chrome.setActive(mode != MODE_NONE)
                    // Xiaomi: hide IME + clear avoid on drag start so follow-hand does not jump.
                    // Also dismiss handle menu once a real chrome gesture begins.
                    if (mode != MODE_NONE) {
                        if (handleMenuAttached && mode != MODE_MOVE) {
                            hideHandleMenu()
                        }
                        FreeformManagerService.onGestureStart(state.taskId)
                    }
                    return mode != MODE_NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == MODE_NONE) return false
                    velocityTracker?.addMovement(ev)
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    when (mode) {
                        MODE_MOVE -> {
                            val b = Rect(startBounds)
                            b.offset(dx.toInt(), dy.toInt())
                            liveMove(b)
                            // Xiaomi phone freeform: live top hot-area indicator (type0 fullscreen).
                            if (state.windowState == WindowState.NORMAL) {
                                updateDragHotArea(ev.rawX, ev.rawY)
                            }
                        }
                        MODE_RESIZE_BR -> {
                            val b = Rect(startBounds)
                            b.right = (startBounds.right + dx).toInt()
                            b.bottom = (startBounds.bottom + dy).toInt()
                            // keep aspect (Xiaomi resizeAboutCtrlType)
                            val aspectBase = if (state.restoreNormalBounds.width() > 0 &&
                                state.restoreNormalBounds.height() > 0
                            ) state.restoreNormalBounds else startBounds
                            val aspect = aspectBase.width().toFloat() /
                                aspectBase.height().coerceAtLeast(1)
                            val newW = b.width().coerceAtLeast(1)
                            b.bottom = b.top + (newW / aspect).toInt()
                            liveResize(b)
                        }
                        MODE_RESIZE_BL -> {
                            val b = Rect(startBounds)
                            b.left = (startBounds.left + dx).toInt()
                            b.bottom = (startBounds.bottom + dy).toInt()
                            val aspectBase = if (state.restoreNormalBounds.width() > 0 &&
                                state.restoreNormalBounds.height() > 0
                            ) state.restoreNormalBounds else startBounds
                            val aspect = aspectBase.width().toFloat() /
                                aspectBase.height().coerceAtLeast(1)
                            val newW = b.width().coerceAtLeast(1)
                            b.bottom = b.top + (newW / aspect).toInt()
                            liveResize(b)
                        }
                        MODE_BOTTOM -> {
                            // rubber band visual only on caption
                            caption.setBottomDrag(dy)
                            // Xiaomi: haptic on first enter close/fullscreen target zone.
                            // Use directional fake velocity so dy threshold alone can arm target.
                            val fakeVy = when {
                                dy > 0f -> FreeformPolicy.BOTTOM_FULLSCREEN_VY + 1f
                                dy < 0f -> FreeformPolicy.BOTTOM_CLOSE_VY - 1f
                                else -> 0f
                            }
                            val target = FreeformPolicy.bottomCaptionAction(dy, fakeVy, state.scale)
                            if (target != null && target != hapticBottomTarget) {
                                hapticBottomTarget = target
                                FreeformHapticHelper.hapticLight()
                            } else if (target == null) {
                                hapticBottomTarget = null
                            }
                        }
                        MODE_NONE -> {}
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == MODE_NONE) return false
                    velocityTracker?.addMovement(ev)
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val vy = velocityTracker?.yVelocity ?: 0f
                    velocityTracker?.recycle()
                    velocityTracker = null
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    val cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL
                    if (!cancelled) {
                        settle(dx, dy, vx, vy)
                    } else {
                        // Keep the current leash transform until the single authoritative commit;
                        // resetting it first would flash the full-size source for one frame.
                        liveMove(Rect(startBounds), commit = true)
                    }
                    hideHotIndicator()
                    dragHotArea = FreeformPolicy.HOT_AREA_TYPE_FREEFORM
                    caption.setActive(false)
                    chrome.setActive(false)
                    caption.setBottomDrag(0f)
                    mode = MODE_NONE
                    return true
                }
            }
            return false
        }

        private fun hitTest(x: Float, y: Float): Int {
            val w = root.width.toFloat().coerceAtLeast(1f)
            val h = root.height.toFloat().coerceAtLeast(1f)
            val isMini = state.windowState == WindowState.MINI
            // Xiaomi mini: the whole content area is one move/tap zone (single-tap restores,
            // drag moves). Grab every touch so the mini expands instead of hitting the app.
            if (isMini) return MODE_MOVE
            // Only the visible center pill moves/opens menu; its sides belong to the app.
            val pillLeft = (w - pillTouchWidth) / 2f
            val pillRight = pillLeft + pillTouchWidth
            val pillTop = (captionHeight - pillTouchHeight) / 2f
            val pillBottom = pillTop + pillTouchHeight
            if (x in pillLeft..pillRight && y in pillTop..pillBottom) return MODE_MOVE
            // Fullscreen video controls occupy the entire bottom edge. Corner resize and the
            // bottom caption gesture must not intercept subtitle/speed/quality controls.
            if (state.landscape) return MODE_NONE
            // Corners scale — small zone hugging each bottom corner (at the app border).
            if (y >= h - cornerTouch && x <= cornerTouch) return MODE_RESIZE_BL
            if (y >= h - cornerTouch && x >= w - cornerTouch) return MODE_RESIZE_BR
            // Bottom-center 小白条 (close ↑ / fullscreen ↓) — center strip only.
            val barLeft = (w - bottomBarTouchWidth) / 2f
            val barRight = barLeft + bottomBarTouchWidth
            if (y >= h - bottomBarTouchHeight && x in barLeft..barRight) return MODE_BOTTOM
            // Transparent content: do not steal app touches
            return MODE_NONE
        }

        private fun liveMove(b: Rect, commit: Boolean = false) {
            val c = FreeformPolicy.clampBounds(b)
            state.bounds = c
            applyBounds(c)
            if (attached) runCatching { wm.updateViewLayout(root, lp) }
            if (!commit && state.windowState == WindowState.NORMAL) {
                // Moving a scaled NORMAL window by resizeTask on every MOVE starts a WM CHANGE
                // transition for every input sample.  The task surface then disappears/reappears
                // behind the already-moved chrome (visible flashing).  Keep the real task parked
                // and move only its leash; ACTION_UP commits one authoritative Task position.
                if (liveResizeBase == null) {
                    val source = if (!state.landscapeTaskBounds.isEmpty) {
                        state.landscapeTaskBounds
                    } else if (state.restoreNormalBounds.width() > 0 &&
                        state.restoreNormalBounds.height() > 0
                    ) {
                        state.restoreNormalBounds
                    } else {
                        startBounds
                    }
                    liveResizeBase = Rect(
                        startBounds.left,
                        startBounds.top,
                        startBounds.left + source.width().coerceAtLeast(1),
                        startBounds.top + source.height().coerceAtLeast(1),
                    )
                }
                FreeformManagerService.applyLiveResizeVisual(
                    state.taskId,
                    liveResizeBase!!,
                    c,
                )
            } else {
                liveResizeBase = null
                FreeformManagerService.moveTask(state.taskId, c)
            }
        }

        private fun liveResize(b: Rect) {
            // Once mini, stop corner-resize (Xiaomi mini is move/tap only).
            if (state.windowState == WindowState.MINI) return
            val c = FreeformPolicy.clampBounds(b)
            val base = if (state.restoreNormalBounds.width() > 0) {
                state.restoreNormalBounds
            } else {
                startBounds
            }
            val sc = FreeformPolicy.scaleFromBounds(c, base)
            // Commit threshold cross immediately; do not keep drawing intermediate shrink.
            if (FreeformPolicy.shouldEnterMini(sc)) {
                if (!hapticMiniFired) {
                    hapticMiniFired = true
                    FreeformHapticHelper.hapticLight()
                }
                // NO clearLiveResizeVisual here: resetting to identity flashes the content at
                // full base size for a frame. The service mini path stops the live ticker and
                // applyMiniFreeformVisual overwrites the transform directly — continuous scale.
                liveResizeBase = null
                FreeformManagerService.resizeTask(state.taskId, c, sc)
                return
            }
            // Live path: chrome + task leash matrix only (no resizeTask every MOVE).
            // Real bounds/config commit happens on ACTION_UP settle — kills resize flicker.
            // The leash scale is relative to the task's REAL (base) size — startBounds may be an
            // already-scaled visual rect (re-resizing a 缩放小窗), so use base dimensions.
            if (liveResizeBase == null) {
                liveResizeBase = Rect(
                    startBounds.left,
                    startBounds.top,
                    startBounds.left + base.width(),
                    startBounds.top + base.height(),
                )
            }
            state.bounds = c
            state.scale = sc
            applyBounds(c)
            if (attached) runCatching { wm.updateViewLayout(root, lp) }
            FreeformManagerService.applyLiveResizeVisual(state.taskId, liveResizeBase!!, c)
        }

        private fun settle(dx: Float, dy: Float, vx: Float, vy: Float) {
            val isMini = state.windowState == WindowState.MINI
            val slop = FreeformPolicy.tapSlopPx()
            when (mode) {
                MODE_BOTTOM -> {
                    // Xiaomi bottom caption: up close / down fullscreen (velocity-aware)
                    when (FreeformPolicy.bottomCaptionAction(dy, vy, state.scale)) {
                        "close" -> {
                            XLog.i("gesture bottom close task=${state.taskId} dy=$dy vy=$vy")
                            if (hapticBottomTarget != "close") FreeformHapticHelper.hapticLight()
                            FreeformManagerService.closeTask(state.taskId)
                        }
                        "fullscreen" -> {
                            XLog.i("gesture bottom fullscreen task=${state.taskId} dy=$dy vy=$vy")
                            if (hapticBottomTarget != "fullscreen") FreeformHapticHelper.hapticLight()
                            FreeformManagerService.fullscreenTask(state.taskId)
                        }
                        else -> {
                            // spring back visual only
                            caption.setBottomDrag(0f)
                        }
                    }
                }
                MODE_MOVE -> {
                    val moved = hypot(dx, dy)
                    // Mini: tap / double-tap (Xiaomi MiniStateHandler ~200ms)
                    if (isMini && moved < slop) {
                        handleMiniTap()
                        return
                    }
                    // Normal freeform: caption tap opens Xiaomi window-control menu
                    // (MiuiDecorationDot.handleTopCaptionClicked → createHandleMenu).
                    if (!isMini && moved < slop) {
                        if (liveResizeBase != null) {
                            liveMove(Rect(startBounds), commit = true)
                        }
                        toggleHandleMenu()
                        return
                    }
                    // Any real drag dismisses menu first.
                    hideHandleMenu()
                    // Mini upward fling close
                    if (isMini && FreeformPolicy.shouldMiniFlingClose(dy, vy)) {
                        XLog.i("gesture mini fling-close task=${state.taskId} dy=$dy vy=$vy")
                        FreeformManagerService.closeTask(state.taskId)
                        return
                    }
                    // Phone freeform drag top hot zone → fullscreen
                    // (Xiaomi MultiTaskingHotAreaController HOT_AREA_TYPE_FULLSCREEN).
                    // Commit before edge pin so top-strip release wins over side pin.
                    if (!isMini) {
                        val upX = downX + dx
                        val upY = downY + dy
                        val hot = FreeformPolicy.freeformDragHotArea(upX, upY)
                        if (hot == FreeformPolicy.HOT_AREA_TYPE_FULLSCREEN) {
                            XLog.i(
                                "gesture drag-hot fullscreen task=${state.taskId} " +
                                    "up=(${upX.toInt()},${upY.toInt()}) " +
                                    "area=${FreeformPolicy.hotAreaLabel(hot)}",
                            )
                            FreeformHapticHelper.hapticLight()
                            hideHotIndicator()
                            FreeformManagerService.fullscreenTask(state.taskId)
                            return
                        }
                    }
                    // Edge pin (Xiaomi isEnterPin + getPredictXY)
                    val current = Rect(startBounds).also { it.offset(dx.toInt(), dy.toInt()) }
                    if (FreeformPolicy.shouldEnterPin(current, vx, vy, isMini)) {
                        XLog.i(
                            "gesture pin task=${state.taskId} mini=$isMini " +
                                "vx=${vx.toInt()} vy=${vy.toInt()} bounds=$current",
                        )
                        FreeformHapticHelper.hapticLight()
                        // commit last drag position then pin
                        FreeformManagerService.moveTask(state.taskId, FreeformPolicy.clampBounds(current))
                        FreeformManagerService.pinTask(state.taskId, true)
                        return
                    }
                    // Inertia settle + mini side snap (Xiaomi MoveHandler getUpBounds)
                    val settled = FreeformPolicy.settleMoveBounds(
                        startBounds, dx, dy, vx, vy, isMini,
                    )
                    XLog.d(
                        "gesture move-settle task=${state.taskId} mini=$isMini " +
                            "vx=${vx.toInt()} vy=${vy.toInt()} -> $settled",
                    )
                    liveMove(settled, commit = true)
                }
                MODE_RESIZE_BL, MODE_RESIZE_BR -> {
                    // If shrink already entered mini, keep Xiaomi mini geometry from service.
                    if (state.windowState == WindowState.MINI) {
                        liveResizeBase = null
                        // Stop any stray live ticker; the service's mini-guard skips the
                        // identity reset so the mini transform stays intact.
                        FreeformManagerService.clearLiveResizeVisual(state.taskId, state.bounds)
                        XLog.d(
                            "gesture resize-settle already-mini task=${state.taskId} " +
                                "bounds=${state.bounds}",
                        )
                        return
                    }
                    val final = FreeformPolicy.clampBounds(state.bounds)
                    liveResizeBase = null
                    // Xiaomi visual-scale settle: the service keeps the task at BASE size and
                    // persists the uniform leash scale into `final` (等比缩放) — no clear here,
                    // the service resize path owns the transform (stops the live ticker itself).
                    FreeformManagerService.resizeTask(
                        state.taskId,
                        final,
                        FreeformPolicy.scaleFromBounds(final, state.restoreNormalBounds),
                    )
                    XLog.d(
                        "gesture resize-settle task=${state.taskId} " +
                            "scale=${state.scale} bounds=$final",
                    )
                }
                MODE_NONE -> {}
            }
        }

        private fun toggleHandleMenu() {
            if (handleMenuAttached) {
                hideHandleMenu()
            } else {
                showHandleMenu()
            }
        }

        private fun showHandleMenu() {
            if (state.windowState == WindowState.MINI) return
            if (handleMenuAttached) return
            val ctx = SystemServices.systemContext
            val (dw, dh) = FreeformPolicy.displaySize()
            val landscape = dw > dh
            val menu = FreeformHandleMenuView(ctx).also { handleMenu = it }
            menu.setLandscape(landscape)
            menu.setSelected(FreeformHandleMenuView.Action.FREEFORM)
            // Position card just under in-window top caption (Xiaomi WCHandlerMargin).
            val topMargin = (state.bounds.top + captionHeight + (6 * density).toInt())
                .coerceIn(0, (dh - menu.preferredHeight() - 8).coerceAtLeast(0))
            menu.setCardTopMargin(topMargin)
            menu.setListener { action ->
                onHandleMenuAction(action)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                title = "HyperFreeformHandleMenu-${state.taskId}"
            }
            handleMenuLp = lp
            runCatching {
                wm.addView(menu, lp)
                handleMenuAttached = true
                caption.flash()
                XLog.i("handle-menu show task=${state.taskId} top=$topMargin landscape=$landscape")
            }.onFailure {
                handleMenuAttached = false
                handleMenu = null
                handleMenuLp = null
                XLog.e("handle-menu show failed", it)
            }
        }

        private fun hideHandleMenu() {
            if (!handleMenuAttached && handleMenu == null) return
            val menu = handleMenu
            handleMenu = null
            handleMenuLp = null
            handleMenuAttached = false
            if (menu != null) {
                runCatching { wm.removeView(menu) }
                    .onFailure { XLog.e("handle-menu hide failed", it) }
            }
            XLog.d("handle-menu hide task=${state.taskId}")
        }

        private fun updateDragHotArea(rawX: Float, rawY: Float) {
            val hot = FreeformPolicy.freeformDragHotArea(rawX, rawY)
            if (hot == dragHotArea) return
            val prev = dragHotArea
            dragHotArea = hot
            XLog.d(
                "drag-hot area task=${state.taskId} " +
                    "${FreeformPolicy.hotAreaLabel(prev)} -> ${FreeformPolicy.hotAreaLabel(hot)} " +
                    "at (${rawX.toInt()},${rawY.toInt()})",
            )
            when (hot) {
                FreeformPolicy.HOT_AREA_TYPE_FULLSCREEN -> {
                    if (prev != FreeformPolicy.HOT_AREA_TYPE_FULLSCREEN) {
                        FreeformHapticHelper.hapticLight()
                    }
                    showHotIndicator(hot)
                }
                else -> hideHotIndicator()
            }
        }

        private fun showHotIndicator(type: Int) {
            val (dw, dh) = FreeformPolicy.displaySize()
            val existing = hotIndicator
            if (hotIndicatorAttached && existing != null) {
                existing.setHotArea(type)
                return
            }
            val view = FreeformHotAreaIndicatorView(SystemServices.systemContext).also {
                hotIndicator = it
                it.setHotArea(type)
            }
            val lp = WindowManager.LayoutParams(
                dw,
                dh,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                title = "HyperFreeformHotArea-${state.taskId}"
            }
            hotIndicatorLp = lp
            runCatching {
                wm.addView(view, lp)
                hotIndicatorAttached = true
                XLog.d(
                    "drag-hot indicator show task=${state.taskId} " +
                        "type=${FreeformPolicy.hotAreaLabel(type)}",
                )
            }.onFailure {
                hotIndicatorAttached = false
                hotIndicator = null
                hotIndicatorLp = null
                XLog.e("drag-hot indicator show failed", it)
            }
        }

        private fun hideHotIndicator() {
            if (!hotIndicatorAttached && hotIndicator == null) return
            val view = hotIndicator
            hotIndicator = null
            hotIndicatorLp = null
            hotIndicatorAttached = false
            if (view != null) {
                runCatching { wm.removeView(view) }
                    .onFailure { XLog.e("drag-hot indicator hide failed", it) }
            }
        }

        private fun onHandleMenuAction(action: FreeformHandleMenuView.Action) {
            val taskId = state.taskId
            // Always close first so chrome does not linger over transition.
            hideHandleMenu()
            when (action) {
                FreeformHandleMenuView.Action.FULLSCREEN -> {
                    XLog.i("handle-menu fullscreen task=$taskId")
                    FreeformManagerService.fullscreenTask(taskId)
                }
                FreeformHandleMenuView.Action.SPLIT_TOP_OR_LEFT -> {
                    XLog.i("handle-menu split top/left task=$taskId")
                    FreeformManagerService.splitTask(
                        taskId,
                        FreeformPolicy.SPLIT_POSITION_TOP_OR_LEFT,
                    )
                }
                FreeformHandleMenuView.Action.SPLIT_BOTTOM_OR_RIGHT -> {
                    XLog.i("handle-menu split bottom/right task=$taskId")
                    FreeformManagerService.splitTask(
                        taskId,
                        FreeformPolicy.SPLIT_POSITION_BOTTOM_OR_RIGHT,
                    )
                }
                FreeformHandleMenuView.Action.FREEFORM -> {
                    // Already freeform (selected). Dismiss only — Xiaomi keeps state.
                    XLog.d("handle-menu freeform no-op task=$taskId")
                }
                FreeformHandleMenuView.Action.CLOSE -> {
                    XLog.i("handle-menu close task=$taskId")
                    FreeformManagerService.closeTask(taskId)
                }
            }
        }

        private fun handleMiniTap() {
            val now = System.currentTimeMillis()
            val since = now - lastTapUpMs
            if (since in 1 until FreeformPolicy.MINI_DOUBLE_TAP_MS) {
                // second tap within window -> fullscreen
                cancelPendingMiniTap()
                lastTapUpMs = 0L
                XLog.i("gesture mini double-tap fullscreen task=${state.taskId}")
                FreeformManagerService.fullscreenTask(state.taskId)
                return
            }
            lastTapUpMs = now
            cancelPendingMiniTap()
            val taskId = state.taskId
            val r = Runnable {
                pendingMiniSingleTap = null
                XLog.i("gesture mini single-tap restore task=$taskId")
                FreeformManagerService.switchMini(taskId, false)
            }
            pendingMiniSingleTap = r
            handler.postDelayed(r, FreeformPolicy.MINI_DOUBLE_TAP_MS)
        }

        private fun cancelPendingMiniTap() {
            pendingMiniSingleTap?.let { handler.removeCallbacks(it) }
            pendingMiniSingleTap = null
        }


    }

    private inner class BubbleSession(private var state: FreeformTaskState) {
        private val density = SystemServices.systemContext.resources.displayMetrics.density
        private val size = (64 * density).toInt() // Xiaomi 64dp bubble
        private val view = FreeformBubbleView(SystemServices.systemContext)
        private var lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "HyperFreeformBubble-${state.taskId}"
        }
        private var attached = false
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        /** Xiaomi: bubble icon single-tap unPin; message click startPinToFullscreen.
         * Without message UI, double-tap maps to startPinToFullscreen. */
        private var lastTapUpMs = 0L
        private var pendingSingleTap: Runnable? = null

        fun attach() {
            if (attached) return
            position()
            view.bind(state.packageName)
            // Click path is driven from touch UP (double-tap aware); keep listener for a11y.
            view.setOnClickListener { handleBubbleTap() }
            view.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        startX = lp.x
                        startY = lp.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        lp.x = (startX + (ev.rawX - downX)).toInt()
                        lp.y = (startY + (ev.rawY - downY)).toInt()
                        runCatching { wm.updateViewLayout(view, lp) }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val moved = hypot(ev.rawX - downX, ev.rawY - downY) > 12f
                        if (!moved) {
                            handleBubbleTap()
                        } else {
                            cancelPendingSingleTap()
                            // Xiaomi bubble drag end: edge snap + updatePinFloatingWindowPos.
                            persistPinFloatingPosAfterDrag()
                        }
                        true
                    }
                    else -> false
                }
            }
            runCatching {
                wm.addView(view, lp)
                attached = true
            }.onFailure { XLog.e("bubble attach failed", it) }
        }

        private fun handleBubbleTap() {
            val now = System.currentTimeMillis()
            val since = now - lastTapUpMs
            if (since in 1 until FreeformPolicy.BUBBLE_DOUBLE_TAP_MS) {
                // Double-tap → Xiaomi startPinToFullscreen (maximize from pin).
                cancelPendingSingleTap()
                lastTapUpMs = 0L
                FreeformHapticHelper.hapticLight()
                XLog.i("bubble double-tap startPinToFullscreen task=${state.taskId}")
                FreeformManagerService.startPinToFullscreen(state.taskId)
                return
            }
            lastTapUpMs = now
            cancelPendingSingleTap()
            val taskId = state.taskId
            val r = Runnable {
                pendingSingleTap = null
                // Single-tap → unPinFloatingWindow restore freeform (page state keep).
                XLog.i("bubble single-tap unpin task=$taskId")
                FreeformManagerService.unpinTask(taskId)
            }
            pendingSingleTap = r
            handler.postDelayed(r, FreeformPolicy.BUBBLE_DOUBLE_TAP_MS)
        }

        private fun cancelPendingSingleTap() {
            pendingSingleTap?.let { handler.removeCallbacks(it) }
            pendingSingleTap = null
        }

        fun update(newState: FreeformTaskState) {
            state = newState
            position()
            if (attached) runCatching { wm.updateViewLayout(view, lp) }
        }

        /**
         * Xiaomi MiuiBubbleStackView onUp → updatePinFloatingWindowPos(finalBounds, taskId, true).
         * Snap to L/R peek, persist pinPos + Y into server (not restoreNormalBounds).
         */
        private fun persistPinFloatingPosAfterDrag() {
            val (dw, dh) = FreeformPolicy.displaySize()
            val peek = FreeformPolicy.bubblePeekPx()
            val pinPos = if (lp.x + size / 2 > dw / 2) 1 else 0
            lp.x = if (pinPos == 1) dw - peek else peek - size
            lp.y = lp.y.coerceIn(80, (dh - size - 80).coerceAtLeast(80))
            runCatching { wm.updateViewLayout(view, lp) }
            // Optimistic local state so re-layout before service round-trip keeps edge/Y.
            state.pinPos = pinPos
            state.pinY = lp.y
            FreeformManagerService.updatePinFloatingWindowPos(state.taskId, pinPos, lp.y)
            XLog.i(
                "bubble drag persist task=${state.taskId} pinPos=$pinPos pinY=${lp.y} " +
                    "lp=(${lp.x},${lp.y})",
            )
        }

        private fun position() {
            val (dw, dh) = FreeformPolicy.displaySize()
            val peek = FreeformPolicy.bubblePeekPx()
            // Prefer server pin floating pos / pinY; fall back to freeform top (not restoreN).
            val yBase = when {
                state.pinFloatingWindowPos.height() > 0 -> state.pinFloatingWindowPos.top
                state.pinY >= 0 -> state.pinY
                else -> state.bounds.top
            }
            val y = yBase.coerceIn(80, (dh - size - 80).coerceAtLeast(80))
            // Xiaomi: only ~24dp remains on-screen when docked to edge.
            lp.x = if (state.pinPos == 1) dw - peek else peek - size
            lp.y = y
        }

        fun detach() {
            cancelPendingSingleTap()
            if (!attached) return
            runCatching { wm.removeView(view) }
            attached = false
        }
    }
}
