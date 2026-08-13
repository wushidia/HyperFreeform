package io.hyper.freeform.xposed.hook

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.hyper.freeform.service.FreeformBridge
import io.hyper.freeform.service.FreeformManagerClient
import io.hyper.freeform.xposed.utils.XLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Port of Xiaomi MiuiHome [SmallWindowCrop] recents entry onto Lawnchair / AOSP Quickstep.
 *
 * Xiaomi path (docs §7.1):
 *   Recent task card drag to top hotzone
 *     → Shell startSmallFreeformFromRecent(taskInfo, cornerPosition)
 *     → mini freeform
 *
 * On MuMu there is no MiuiHome; launcher is Lawnchair (`app.lawnchair`) with Quickstep.
 * The entry point is the foreground app swipe-up gesture: pull up far enough while
 * sending the current app to background, then release to convert that running task
 * via FreeformManagerClient.startFromRecent(mini=true). Once Overview/Recents is
 * already open, dragging cards must not trigger freeform.
 *
 * Native freeform only — never Z-Flow VirtualDisplay.
 */
object HookLauncher {
    private const val TAG = "HookLauncher"
    private const val SETTINGS_RECENTS_FREEFORM = "hyper_freeform_recents"

    /** Visual pill height. */
    private const val PILL_HEIGHT_DP = 48f
    /** Z-Flow/Quickstep mCurrentShift threshold for foreground swipe-up launch. */
    private const val ZFLOW_SWIPE_PROGRESS_THRESHOLD = 3f
    /**
     * MuMu's Lawnchair reports mCurrentShift as a normalized 0..1-ish value.
     * Treat this as valid only together with the bottom-edge -> upper-screen pull below.
     */
    private const val NORMALIZED_SWIPE_PROGRESS_THRESHOLD = 0.20f
    private const val FOREGROUND_EDGE_START_FRACTION = 0.88f
    private const val FOREGROUND_TOP_PULL_FRACTION = 0.35f
    private const val FOREGROUND_MIN_DISTANCE_FRACTION = 0.50f
    /** Let Quickstep release its input consumer before changing the running task's mode. */
    private const val FOREGROUND_CONVERSION_SETTLE_MS = 180L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hooked = AtomicBoolean(false)

    @Volatile private var launcherPackageName = ""
    @Volatile private var lastTriggerUptime = 0L
    @Volatile private var lastSwipeProgressLogUptime = 0L
    @Volatile private var dragInHotzone = false
    @Volatile private var foregroundSwipeStartY = Float.NaN
    @Volatile private var foregroundSwipeMinY = Float.POSITIVE_INFINITY
    @Volatile private var foregroundSwipeHeight = 0
    @Volatile private var foregroundSwipeMaxProgress = 0f
    /** ACTION_UP/CANCEL or the end-target callback has sealed the current gesture. */
    @Volatile private var foregroundSwipeTerminal = false
    @Volatile private var hotzoneViewRef: WeakReference<View>? = null
    @Volatile private var hotzoneHostRef: WeakReference<ViewGroup>? = null
    @Volatile private var hotzoneLayoutListener: View.OnLayoutChangeListener? = null
    /** Invalidates an attach/remove posted by an older Quickstep callback. */
    @Volatile private var hotzoneGeneration = 0L

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!hooked.compareAndSet(false, true)) return
        launcherPackageName = lpparam.packageName
        XLog.d("$TAG init for ${lpparam.packageName}")
        val cl = lpparam.classLoader
        val swipeInstalled = hookForegroundSwipeUpHandler(cl)
        val homeNavigationInstalled = hookHomeNavigationBehindFreeform(cl)
        hookFreeformSystemShortcut(cl)
        if (swipeInstalled) {
            XLog.i("$TAG recents→mini freeform entry armed")
        } else {
            XLog.e("$TAG: no foreground swipe hooks installed")
        }
        if (homeNavigationInstalled) {
            XLog.i("$TAG home navigation behind freeform armed")
        }
    }

    // region Foreground swipe-up gesture (only app -> background, not existing Recents)

    private fun hookForegroundSwipeUpHandler(cl: ClassLoader): Boolean {
        val handlerClazz = runCatching {
            XposedHelpers.findClass("com.android.quickstep.AbsSwipeUpHandler", cl)
        }.getOrNull()
        val gestureStateClazz = runCatching {
            XposedHelpers.findClass("com.android.quickstep.GestureState", cl)
        }.getOrNull()

        var absSwipeInstalled = false
        if (handlerClazz != null && gestureStateClazz != null) {
            runCatching {
                XposedBridge.hookAllMethods(
                    handlerClazz,
                    "initStateCallbacks",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            dragInHotzone = false
                            foregroundSwipeMaxProgress = 0f
                            foregroundSwipeTerminal = false
                            if (foregroundSwipeStartY.isNaN()) {
                                resetForegroundSwipeMotion()
                            }
                            removeHotzone()
                            val handler = param.thisObject ?: return
                            XLog.d("$TAG foreground initStateCallbacks")
                            val gestureState = getFieldUp(handler, "mGestureState")
                            if (gestureState == null) {
                                XLog.e("$TAG foreground gestureState missing")
                                return
                            }
                            val endTargetState = runCatching {
                                XposedHelpers.getStaticIntField(
                                    gestureStateClazz,
                                    "STATE_END_TARGET_SET",
                                )
                            }.getOrNull()
                            if (endTargetState == null) {
                                XLog.e("$TAG STATE_END_TARGET_SET missing")
                                return
                            }
                            runCatching {
                                XposedHelpers.callMethod(
                                    gestureState,
                                    "runOnceAtState",
                                    endTargetState,
                                    Runnable {
                                        runCatching { handleForegroundSwipeEnd(handler) }
                                            .onFailure {
                                                XLog.e("$TAG foreground swipe end failed", it)
                                            }
                                    },
                                )
                            }.onFailure {
                                XLog.d("$TAG runOnceAtState unavailable: ${it.message}")
                            }
                        }
                    }
                )
            }.onSuccess { absSwipeInstalled = it.isNotEmpty() || absSwipeInstalled }
                .onFailure { XLog.e("$TAG hook AbsSwipeUpHandler.initStateCallbacks failed", it) }
        } else {
            XLog.d("$TAG AbsSwipeUpHandler state callback unavailable")
        }

        if (handlerClazz != null) {
            runCatching {
                XposedBridge.hookAllMethods(
                    handlerClazz,
                    "updateSysUiFlags",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val handler = param.thisObject ?: return
                            // Quickstep emits final flag updates while settling after ACTION_UP.
                            // A stale high progress must not re-arm the hotzone/conversion.
                            if (foregroundSwipeTerminal) {
                                dragInHotzone = false
                                setHotzoneActive(false)
                                removeHotzone()
                                return
                            }
                            val progress = readForegroundSwipeProgress(handler)
                            val now = SystemClock.uptimeMillis()
                            if (progress > 0.2f && now - lastSwipeProgressLogUptime > 350L) {
                                lastSwipeProgressLogUptime = now
                                XLog.d(
                                    "$TAG foreground progress=$progress " +
                                        "armed=${isForegroundSwipeArmed(progress)}",
                                )
                            }
                            foregroundSwipeMaxProgress =
                                maxOf(foregroundSwipeMaxProgress, progress)
                            val active = isForegroundSwipeArmed(progress)
                            if (active && !dragInHotzone) {
                                dragInHotzone = true
                                getFieldUp(handler, "mRecentsView")?.let { recents ->
                                    if (recents is ViewGroup) attachHotzone(recents)
                                }
                                setHotzoneActive(true)
                            } else if (!active && dragInHotzone) {
                                dragInHotzone = false
                                setHotzoneActive(false)
                            }
                        }
                    }
                )
            }.onSuccess { absSwipeInstalled = it.isNotEmpty() || absSwipeInstalled }
                .onFailure { XLog.d("$TAG updateSysUiFlags hook skipped: ${it.message}") }
        }

        val otherActivityInstalled = hookOtherActivityInputConsumer(cl)
        val touchServiceInstalled = hookTouchInteractionService(cl)
        val installed = absSwipeInstalled || otherActivityInstalled || touchServiceInstalled

        if (installed) {
            XLog.i(
                "$TAG foreground swipe hooks installed " +
                    "(abs=$absSwipeInstalled other=$otherActivityInstalled " +
                    "tis=$touchServiceInstalled)",
            )
        }
        return installed
    }

    private fun hookTouchInteractionService(cl: ClassLoader): Boolean {
        val clazz = runCatching {
            XposedHelpers.findClass("com.android.quickstep.TouchInteractionService", cl)
        }.getOrNull() ?: return false
        var installed = false
        for (name in listOf("onInputEvent", "onInputConsumerMotionEvent", "onMotionEvent")) {
            runCatching {
                XposedBridge.hookAllMethods(clazz, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val ev = param.args.firstOrNull { it is MotionEvent } as? MotionEvent ?: return
                        val service = param.thisObject ?: return
                        val consumer = getFieldUp(service, "mUncheckedConsumer")
                            ?: getFieldUp(service, "mInputConsumer")
                            ?: return
                        handleOtherActivityConsumerMotion(consumer, ev, "TIS.$name")
                    }
                })
            }.onSuccess { installed = it.isNotEmpty() || installed }
                .onFailure { XLog.d("$TAG TouchInteractionService.$name skipped: ${it.message}") }
        }
        if (installed) {
            XLog.i("$TAG hooked TouchInteractionService motion path")
        }
        return installed
    }

    private fun hookOtherActivityInputConsumer(cl: ClassLoader): Boolean {
        val clazz = runCatching {
            XposedHelpers.findClass("com.android.quickstep.inputconsumers.OtherActivityInputConsumer", cl)
        }.getOrNull() ?: return false
        var installed = false
        runCatching {
            XposedBridge.hookAllMethods(clazz, "onMotionEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val ev = param.args.firstOrNull { it is MotionEvent } as? MotionEvent ?: return
                    handleOtherActivityConsumerMotion(param.thisObject ?: return, ev, "onMotionEvent")
                }
            })
        }.onSuccess { installed = it.isNotEmpty() || installed }
            .onFailure {
                XLog.d("$TAG OtherActivityInputConsumer.onMotionEvent skipped: ${it.message}")
            }

        runCatching {
            XposedBridge.hookAllMethods(clazz, "finishTouchTracking", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    handleOtherActivityConsumerEnd(param.thisObject ?: return, "finishTouchTracking")
                }
            })
        }.onSuccess { installed = it.isNotEmpty() || installed }
            .onFailure {
                XLog.d("$TAG OtherActivityInputConsumer.finishTouchTracking skipped: ${it.message}")
            }
        if (installed) {
            XLog.i("$TAG hooked OtherActivityInputConsumer foreground path")
        }
        return installed
    }

    /**
     * AOSP Quickstep assumes that the focused/top-resumed task is also the full-screen task whose
     * navigation gesture should be animated.  A native always-on-top freeform breaks that
     * assumption: after a landscape app goes HOME, both HOME and the freeform remain visible but
     * TopTaskTracker still reports the freeform first, while the visible launcher cannot own
     * window focus.  Depending on transition timing Quickstep therefore chooses either
     * OtherActivityInputConsumer or LauncherWithoutFocusInputConsumer; the latter explicitly
     * starts HOME on release.  Every subsequent overview gesture then appears to do nothing.
     *
     * LauncherInputConsumer is Quickstep's own path for proxying the nav gesture into the visible
     * launcher DragLayer.  Select it only when a visible freeform exists and Launcher is already
     * resumed (the WithoutFocus selection proves that directly).  For the OtherActivity fallback,
     * additionally require a visible HOME task.  Task order is deliberately ignored: rotation
     * callbacks can place either task first while the freeform still owns the real window focus.
     * Before HOME is visible (for example the original landscape app + sidebar freeform), the
     * stock OtherActivityInputConsumer is kept so the first HOME gesture continues to work.
     */
    private fun hookHomeNavigationBehindFreeform(cl: ClassLoader): Boolean {
        val utils = runCatching {
            XposedHelpers.findClass("com.android.quickstep.InputConsumerUtils", cl)
        }.getOrNull() ?: return false
        val launcherInputConsumer = runCatching {
            XposedHelpers.findClass(
                "com.android.quickstep.inputconsumers.LauncherInputConsumer",
                cl,
            )
        }.getOrNull() ?: return false

        return runCatching {
            val hooks = XposedBridge.hookAllMethods(
                utils,
                "newBaseConsumer",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val selected = param.result ?: return
                        val selectedName = selected.javaClass.name
                        val isOtherActivity =
                            selectedName.endsWith(".OtherActivityInputConsumer")
                        val isLauncherWithoutFocus =
                            selectedName.endsWith(".LauncherWithoutFocusInputConsumer")
                        if (!isOtherActivity && !isLauncherWithoutFocus) return

                        // newBaseConsumer receives the previous and current GestureState.  The
                        // latter is last and contains TopTaskTracker's fresh CachedTaskInfo.
                        val gestureState = param.args.lastOrNull {
                            it?.javaClass?.name == "com.android.quickstep.GestureState"
                        } ?: return
                        val cachedTask = runCatching {
                            XposedHelpers.callMethod(gestureState, "getRunningTask")
                        }.getOrNull() ?: return
                        val freeformTask = cachedTaskVisibleFreeform(cachedTask) ?: return
                        if (isOtherActivity && !cachedTaskHasVisibleHome(cachedTask)) return

                        val containerInterface = runCatching {
                            XposedHelpers.callMethod(gestureState, "getContainerInterface")
                        }.getOrNull() ?: return
                        val container = createdLauncherContainer(containerInterface) ?: return
                        val freeformTaskId = taskInfoId(freeformTask)
                        if (!isForegroundMiniConversionAllowed(container, freeformTaskId)) {
                            // Launcher can hold a stale freeform TaskInfo for one or two frames
                            // after the system-server mode switch. Do not replace Quickstep's
                            // normal consumer in that window: the maximized task must navigate
                            // to HOME/Recents as a regular fullscreen task.
                            XLog.d(
                                "$TAG keep stock navigation consumer for maximized task=" +
                                    freeformTaskId,
                            )
                            return
                        }
                        val inputMonitor = param.args.firstOrNull {
                            it?.javaClass?.name ==
                                "com.android.systemui.shared.system.InputMonitorCompat"
                        } ?: return

                        val replacement = runCatching {
                            XposedHelpers.newInstance(
                                launcherInputConsumer,
                                gestureState,
                                container,
                                inputMonitor,
                                false,
                            )
                        }.onFailure {
                            XLog.e("$TAG launcher navigation consumer creation failed", it)
                        }.getOrNull() ?: return

                        param.result = replacement
                        XLog.i(
                            "$TAG routed overview gesture to visible HOME behind freeform " +
                                "task=$freeformTaskId",
                        )
                    }
                },
            )
            hooks.isNotEmpty()
        }.onFailure {
            XLog.e("$TAG home navigation behind freeform hook failed", it)
        }.getOrDefault(false)
    }

    private fun createdLauncherContainer(containerInterface: Any): Any? {
        runCatching {
            XposedHelpers.callMethod(containerInterface, "getCreatedContainer")
        }.getOrNull()?.let { return it }
        // A few Quickstep builds bridge/rename the covariant getter.  Resolve it by return type
        // instead of depending on an R8-generated method name.
        var c: Class<*>? = containerInterface.javaClass
        while (c != null && c != Any::class.java) {
            c.declaredMethods.firstOrNull {
                it.parameterCount == 0 &&
                    it.returnType.name.contains("quickstep.views.RecentsViewContainer")
            }?.let { method ->
                return runCatching {
                    method.isAccessible = true
                    method.invoke(containerInterface)
                }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    private fun cachedTaskHasVisibleHome(cachedTask: Any): Boolean {
        val tasks = getFieldUp(cachedTask, "mAllCachedTasks") as? Iterable<*> ?: return false
        return tasks.any { task ->
            task != null && taskInfoActivityType(task) == 2 && taskInfoIsVisible(task)
        }
    }

    private fun cachedTaskVisibleFreeform(cachedTask: Any): Any? {
        val tasks = getFieldUp(cachedTask, "mAllCachedTasks") as? Iterable<*> ?: return null
        return tasks.firstOrNull { task ->
            task != null && taskInfoWindowingMode(task) == 5 && taskInfoIsVisible(task)
        }
    }

    private fun taskInfoId(task: Any): Int =
        (getFieldUp(task, "taskId") as? Number)?.toInt()
            ?: runCatching {
                (XposedHelpers.callMethod(task, "getTaskId") as? Number)?.toInt()
            }.getOrNull()
            ?: -1

    private fun taskInfoWindowingMode(task: Any): Int = runCatching {
        (XposedHelpers.callMethod(task, "getWindowingMode") as? Number)?.toInt()
    }.getOrNull() ?: 0

    private fun taskInfoActivityType(task: Any): Int = runCatching {
        (XposedHelpers.callMethod(task, "getActivityType") as? Number)?.toInt()
    }.getOrNull() ?: 0

    private fun taskInfoIsVisible(task: Any): Boolean =
        (getFieldUp(task, "isVisible") as? Boolean) == true ||
            (getFieldUp(task, "isVisibleRequested") as? Boolean) == true

    private fun handleOtherActivityConsumerMotion(consumer: Any, ev: MotionEvent, source: String) {
        primeForegroundSwipeMotion(ev)
        val handler = getFieldUp(consumer, "mInteractionHandler") ?: run {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                XLog.d("$TAG otherActivity $source no interaction handler")
            }
            return
        }
        updateForegroundSwipeMotion(handler, ev)
        if (ev.actionMasked != MotionEvent.ACTION_UP &&
            ev.actionMasked != MotionEvent.ACTION_CANCEL
        ) return
        handleOtherActivityConsumerEnd(consumer, source)
    }

    private fun primeForegroundSwipeMotion(ev: MotionEvent) {
        val y = runCatching { ev.rawY }.getOrDefault(ev.y)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                foregroundSwipeTerminal = false
                foregroundSwipeStartY = y
                foregroundSwipeMinY = y
                foregroundSwipeHeight = 0
                foregroundSwipeMaxProgress = 0f
                dragInHotzone = false
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (foregroundSwipeStartY.isNaN()) foregroundSwipeStartY = y
                foregroundSwipeMinY = minOf(foregroundSwipeMinY, y)
            }
        }
    }

    private fun handleOtherActivityConsumerEnd(consumer: Any, source: String) {
        if (foregroundSwipeTerminal) return
        val handler = getFieldUp(consumer, "mInteractionHandler") ?: run {
            XLog.d("$TAG otherActivity $source no interaction handler")
            foregroundSwipeTerminal = true
            resetForegroundSwipeMotion()
            return
        }
        val passedSlop = getFieldUp(consumer, "mPassedWindowMoveSlop") as? Boolean ?: false
        val progress = maxOf(readForegroundSwipeProgress(handler), foregroundSwipeMaxProgress)
        val armed = isForegroundSwipeArmed(progress)
        XLog.d("$TAG otherActivity $source passedSlop=$passedSlop progress=$progress armed=$armed")
        foregroundSwipeTerminal = true
        dragInHotzone = false
        setHotzoneActive(false)
        mainHandler.postDelayed({ removeHotzone() }, 60L)
        if (!passedSlop || !armed) {
            resetForegroundSwipeMotion()
            return
        }
        if (!isRecentsFreeformEnabled(handler)) {
            resetForegroundSwipeMotion()
            return
        }
        val taskId = resolveRunningTaskIdFromSwipeHandler(handler)
        if (taskId <= 0) {
            XLog.e("$TAG otherActivity swipe no task id progress=$progress")
            resetForegroundSwipeMotion()
            return
        }
        XLog.i("$TAG otherActivity foreground swipe task=$taskId progress=$progress")
        triggerFromRecent(
            taskId,
            "FOREGROUND_SWIPE",
            getFieldUp(handler, "mRecentsView") ?: handler,
        )
        resetForegroundSwipeMotion()
    }

    private fun handleForegroundSwipeEnd(handler: Any) {
        if (foregroundSwipeTerminal) {
            dragInHotzone = false
            setHotzoneActive(false)
            mainHandler.postDelayed({ removeHotzone() }, 60L)
            return
        }
        val progress = maxOf(readForegroundSwipeProgress(handler), foregroundSwipeMaxProgress)
        XLog.d("$TAG foreground end progress=$progress armed=$dragInHotzone")
        val wasArmed = isForegroundSwipeArmed(progress) || dragInHotzone
        foregroundSwipeTerminal = true
        dragInHotzone = false
        setHotzoneActive(false)
        mainHandler.postDelayed({ removeHotzone() }, 180L)
        if (!wasArmed) {
            resetForegroundSwipeMotion()
            return
        }
        if (!isRecentsFreeformEnabled(handler)) {
            resetForegroundSwipeMotion()
            return
        }
        val taskId = resolveRunningTaskIdFromSwipeHandler(handler)
        if (taskId <= 0) {
            XLog.e("$TAG foreground swipe armed but no running task id progress=$progress")
            resetForegroundSwipeMotion()
            return
        }
        XLog.i("$TAG foreground swipe task=$taskId progress=$progress")
        triggerFromRecent(
            taskId,
            "FOREGROUND_SWIPE",
            getFieldUp(handler, "mRecentsView") ?: handler,
        )
        resetForegroundSwipeMotion()
    }

    private fun updateForegroundSwipeMotion(handler: Any, ev: MotionEvent) {
        val y = runCatching { ev.rawY }.getOrDefault(ev.y)
        val height = resolveSwipeHeight(handler)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                foregroundSwipeTerminal = false
                foregroundSwipeStartY = y
                foregroundSwipeMinY = y
                foregroundSwipeHeight = height
                foregroundSwipeMaxProgress = 0f
                dragInHotzone = false
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (foregroundSwipeStartY.isNaN()) foregroundSwipeStartY = y
                foregroundSwipeMinY = minOf(foregroundSwipeMinY, y)
                if (height > 0) foregroundSwipeHeight = height
                val progress = readForegroundSwipeProgress(handler)
                foregroundSwipeMaxProgress = maxOf(foregroundSwipeMaxProgress, progress)
                if (isForegroundSwipeArmed(maxOf(progress, foregroundSwipeMaxProgress)) && !dragInHotzone) {
                    dragInHotzone = true
                    getFieldUp(handler, "mRecentsView")?.let { recents ->
                        if (recents is ViewGroup) attachHotzone(recents)
                    }
                    setHotzoneActive(true)
                }
            }
        }
    }

    private fun resetForegroundSwipeMotion() {
        foregroundSwipeStartY = Float.NaN
        foregroundSwipeMinY = Float.POSITIVE_INFINITY
        foregroundSwipeHeight = 0
        foregroundSwipeMaxProgress = 0f
    }

    private fun isForegroundSwipeArmed(progress: Float): Boolean {
        if (progress >= ZFLOW_SWIPE_PROGRESS_THRESHOLD) return true
        if (progress < NORMALIZED_SWIPE_PROGRESS_THRESHOLD) return false
        if (!isNormalizedLauncherFallback()) return false
        val height = foregroundSwipeHeight
        val startY = foregroundSwipeStartY
        val minY = foregroundSwipeMinY
        if (height <= 0 || startY.isNaN() || !minY.isFinite()) {
            return false
        }
        return startY >= height * FOREGROUND_EDGE_START_FRACTION &&
            minY <= height * FOREGROUND_TOP_PULL_FRACTION &&
            startY - minY >= height * FOREGROUND_MIN_DISTANCE_FRACTION
    }

    private fun isNormalizedLauncherFallback(): Boolean {
        return launcherPackageName == "app.lawnchair" ||
            launcherPackageName == "com.android.launcher3"
    }

    private fun resolveSwipeHeight(handler: Any): Int {
        val recentsView = getFieldUp(handler, "mRecentsView")
        if (recentsView is View && recentsView.height > 0) return recentsView.height
        return runCatching {
            (recentsView as? View)?.resources?.displayMetrics?.heightPixels ?: 0
        }.getOrDefault(0)
    }

    private fun readForegroundSwipeProgress(handler: Any): Float {
        val shift = getFieldUp(handler, "mCurrentShift") ?: return 0f
        val fieldValue = runCatching {
            (getFieldUp(shift, "value") as? Number)?.toFloat()
        }.getOrNull()
        if (fieldValue != null) return fieldValue
        return runCatching {
            (XposedHelpers.callMethod(shift, "getValue") as? Number)?.toFloat()
        }.getOrNull() ?: 0f
    }

    private fun resolveRunningTaskIdFromSwipeHandler(handler: Any): Int {
        val recentsView = getFieldUp(handler, "mRecentsView") ?: return -1
        val taskView = runCatching {
            XposedHelpers.callMethod(recentsView, "getRunningTaskView")
        }.getOrNull()
            ?: runCatching { XposedHelpers.callMethod(recentsView, "getCurrentPageTaskView") }.getOrNull()
            ?: return -1
        return extractTaskId(taskView)
    }

    private fun extractTaskId(taskView: Any): Int {
        // 1) getTaskIds(): IntArray / Array
        runCatching {
            val ids = XposedHelpers.callMethod(taskView, "getTaskIds")
            when (ids) {
                is IntArray -> ids.firstOrNull { it > 0 }?.let { return it }
                is LongArray -> ids.firstOrNull { it > 0 }?.toInt()?.let { return it }
                is Array<*> -> {
                    for (v in ids) {
                        val n = (v as? Number)?.toInt() ?: continue
                        if (n > 0) return n
                    }
                }
                is Collection<*> -> {
                    for (v in ids) {
                        val n = (v as? Number)?.toInt() ?: continue
                        if (n > 0) return n
                    }
                }
            }
        }
        // 2) getFirstTask() → Task → key → id
        runCatching {
            val task = XposedHelpers.callMethod(taskView, "getFirstTask") ?: return@runCatching
            val key = runCatching { XposedHelpers.getObjectField(task, "key") }.getOrNull()
                ?: runCatching { XposedHelpers.callMethod(task, "getKey") }.getOrNull()
                ?: return@runCatching
            readIdFromKey(key)?.let { return it }
        }
        // 3) getTaskContainers() → first → getTask() → key
        runCatching {
            val containers = XposedHelpers.callMethod(taskView, "getTaskContainers")
            val first = when (containers) {
                is Array<*> -> containers.firstOrNull()
                is List<*> -> containers.firstOrNull()
                else -> null
            } ?: return@runCatching
            val task = runCatching { XposedHelpers.callMethod(first, "getTask") }.getOrNull()
                ?: runCatching { XposedHelpers.getObjectField(first, "mTask") }.getOrNull()
                ?: runCatching { XposedHelpers.getObjectField(first, "task") }.getOrNull()
            if (task != null) {
                val key = runCatching { XposedHelpers.getObjectField(task, "key") }.getOrNull()
                if (key != null) readIdFromKey(key)?.let { return it }
            }
            // TaskContainer.getTaskId()?
            runCatching {
                (XposedHelpers.callMethod(first, "getTaskId") as? Number)?.toInt()
            }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        // 4) direct getTaskId on view
        runCatching {
            (XposedHelpers.callMethod(taskView, "getTaskId") as? Number)?.toInt()
        }.getOrNull()?.takeIf { it > 0 }?.let { return it }

        return -1
    }

    private fun readIdFromKey(key: Any): Int? {
        // Field "id" or R8-obfuscated int field; also getId()
        runCatching {
            (XposedHelpers.getIntField(key, "id"))
        }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        runCatching {
            (XposedHelpers.callMethod(key, "getId") as? Number)?.toInt()
        }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        // Scan declared fields for a plausible positive task id
        var c: Class<*>? = key.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                if (f.type != Int::class.javaPrimitiveType) continue
                f.isAccessible = true
                val v = runCatching { f.getInt(key) }.getOrDefault(0)
                // task ids are positive and typically small (< 100000)
                if (v in 1..999_999) {
                    // Prefer field names containing id
                    if (f.name.contains("id", ignoreCase = true) || f.name.startsWith("f")) {
                        return v
                    }
                }
            }
            c = c.superclass
        }
        return null
    }

    private fun getFieldUp(instance: Any, name: String): Any? {
        var c: Class<*>? = instance.javaClass
        while (c != null && c != Any::class.java) {
            runCatching {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(instance)
            }
            c = c.superclass
        }
        return null
    }

    // endregion

    // region Foreground swipe indicator
    //
    // CRITICAL: Quickstep RecentsView/PagedView assumes every child is a TaskView
    // (requireTaskViewAt). DragLayer itself is also unsafe to mutate synchronously while
    // Quickstep is dispatching/drawing a gesture: ViewGroup.dispatchDraw can observe its child
    // array between add/remove operations and crash Launcher. Xiaomi's SmallWindowCrop is a
    // visual overlay. Use ViewGroupOverlay so this hint never enters either real child list.

    /**
     * Resolve a parent that accepts arbitrary children (like Xiaomi root SmallWindowCrop).
     * Never return RecentsView / PagedView / TaskView — those crash on non-TaskView kids.
     */
    private fun resolveOverlayParent(from: View): ViewGroup? {
        var cur: View? = from
        var fallbackContent: ViewGroup? = null
        var fallbackDecor: ViewGroup? = null
        var fallbackRoot: ViewGroup? = null
        while (cur != null) {
            if (cur is ViewGroup && !isUnsafeOverlayParent(cur)) {
                val name = cur.javaClass.name
                val simple = cur.javaClass.simpleName
                when {
                    name.contains("BaseDragLayer") ||
                        simple == "DragLayer" ||
                        name.endsWith(".DragLayer") -> return cur
                    simple == "LauncherRootView" || name.contains("LauncherRootView") -> {
                        // Prefer DragLayer if we haven't hit it yet; keep as strong fallback.
                        fallbackRoot = cur
                    }
                    cur.id == android.R.id.content -> fallbackContent = cur
                    simple == "DecorView" || name.contains("DecorView") -> fallbackDecor = cur
                }
            }
            cur = cur.parent as? View
        }

        // Activity window fallbacks (context may be ContextThemeWrapper).
        val activity = findActivity(from.context)
        val content = activity?.findViewById<ViewGroup>(android.R.id.content)
        val decor = activity?.window?.decorView as? ViewGroup
        val chosen = fallbackRoot ?: fallbackContent ?: content ?: fallbackDecor ?: decor
        if (chosen != null && isUnsafeOverlayParent(chosen)) {
            XLog.e("$TAG resolveOverlayParent refused unsafe ${chosen.javaClass.name}")
            return null
        }
        return chosen
    }

    private fun isUnsafeOverlayParent(vg: ViewGroup): Boolean {
        val n = vg.javaClass.name
        // RecentsView extends PagedView; both index children as TaskView.
        return n.contains("RecentsView") ||
            n.contains("PagedView") ||
            n.contains("TaskView") ||
            n.contains("ClearAllButton")
    }

    private fun findActivity(ctx: Context?): Activity? {
        var c: Context? = ctx
        var depth = 0
        while (c != null && depth < 8) {
            if (c is Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    private fun attachHotzone(anchor: ViewGroup) {
        if (!isRecentsFreeformEnabled(anchor)) {
            removeHotzone()
            return
        }
        val generation = ++hotzoneGeneration
        val anchorRef = WeakReference(anchor)
        // Quickstep invokes us from gesture/draw-adjacent callbacks. Always cross one main-loop
        // boundary before touching the overlay, even when the callback itself is on main.
        mainHandler.post {
            if (generation != hotzoneGeneration) return@post
            val liveAnchor = anchorRef.get() ?: return@post
            attachHotzoneNow(liveAnchor)
        }
    }

    private fun attachHotzoneNow(anchor: ViewGroup) {
        val parent = resolveOverlayParent(anchor)
        if (parent == null) {
            XLog.e("$TAG no overlay parent from ${anchor.javaClass.name}")
            return
        }
        if (isUnsafeOverlayParent(parent)) {
            XLog.e("$TAG refusing RecentsView-like parent ${parent.javaClass.name}")
            return
        }

        val existing = hotzoneViewRef?.get()
        if (existing != null && hotzoneHostRef?.get() === parent) {
            layoutHotzone(parent, existing)
            existing.visibility = View.VISIBLE
            return
        }
        removeHotzoneNow()

        val ctx = parent.context ?: anchor.context
        val density = ctx.resources.displayMetrics.density
        val pillH = (PILL_HEIGHT_DP * density).toInt()

        // Visual-only foreground-swipe hint; it must not intercept Quickstep input.
        val container = FrameLayout(ctx).apply {
            tag = "hyper_freeform_recents_hotzone"
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val pillW = (104 * density).toInt()
        val containerH = (pillH + 36 * density).toInt()
        val containerW = pillW + (24 * density).toInt()
        val lp = FrameLayout.LayoutParams(
            containerW,
            containerH,
        )
        container.layoutParams = lp

        val pill = TextView(ctx).apply {
            text = "小窗"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(0x66000000)
                setStroke((1.5f * density).toInt(), 0x99FFFFFF.toInt())
            }
            alpha = 0.55f
            val pillLp = FrameLayout.LayoutParams(
                pillW,
                pillH,
                Gravity.CENTER,
            )
            layoutParams = pillLp
        }
        container.addView(pill)
        container.setTag(0x70F11E01, pill)

        try {
            container.measure(
                View.MeasureSpec.makeMeasureSpec(containerW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(containerH, View.MeasureSpec.EXACTLY),
            )
            layoutHotzone(parent, container)
            parent.overlay.add(container)
            hotzoneViewRef = WeakReference(container)
            hotzoneHostRef = WeakReference(parent)
            val listener = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val live = hotzoneViewRef?.get() ?: return@OnLayoutChangeListener
                if (hotzoneHostRef?.get() === view) {
                    layoutHotzone(view as ViewGroup, live)
                }
            }
            hotzoneLayoutListener = listener
            parent.addOnLayoutChangeListener(listener)
            XLog.i(
                "$TAG hotzone attached via overlay on ${parent.javaClass.simpleName} " +
                    "(${parent.javaClass.name}) anchor=${anchor.javaClass.simpleName}"
            )
        } catch (t: Throwable) {
            XLog.e("$TAG overlay hotzone on ${parent.javaClass.name} failed", t)
            removeHotzoneNow()
            // Last resort still uses ViewGroupOverlay; never mutate DecorView's child array.
            val decor = findActivity(ctx)?.window?.decorView as? ViewGroup
            if (decor != null && decor !== parent && !isUnsafeOverlayParent(decor)) {
                runCatching {
                    layoutHotzone(decor, container)
                    decor.overlay.add(container)
                    hotzoneViewRef = WeakReference(container)
                    hotzoneHostRef = WeakReference(decor)
                    XLog.i("$TAG hotzone attached via DecorView overlay fallback")
                }.onFailure { XLog.e("$TAG decor hotzone failed", it) }
            }
        }
    }

    private fun removeHotzone() {
        val generation = ++hotzoneGeneration
        mainHandler.post {
            if (generation == hotzoneGeneration) removeHotzoneNow()
        }
    }

    private fun removeHotzoneNow() {
        val v = hotzoneViewRef?.get()
        val host = hotzoneHostRef?.get()
        val listener = hotzoneLayoutListener
        if (host != null && listener != null) {
            runCatching { host.removeOnLayoutChangeListener(listener) }
        }
        if (v != null && host != null) runCatching { host.overlay.remove(v) }
        hotzoneViewRef = null
        hotzoneHostRef = null
        hotzoneLayoutListener = null
    }

    private fun layoutHotzone(host: ViewGroup, view: View) {
        val width = view.measuredWidth.takeIf { it > 0 }
            ?: view.layoutParams?.width?.takeIf { it > 0 }
            ?: return
        val height = view.measuredHeight.takeIf { it > 0 }
            ?: view.layoutParams?.height?.takeIf { it > 0 }
            ?: return
        val density = host.resources.displayMetrics.density
        val left = ((host.width - width) / 2).coerceAtLeast(0)
        val top = (20f * density).toInt()
        view.layout(left, top, left + width, top + height)
    }

    private fun setHotzoneActive(active: Boolean) {
        mainHandler.post {
            val container = hotzoneViewRef?.get() ?: return@post
            container.visibility = View.VISIBLE
            val pill = container.getTag(0x70F11E01) as? TextView
            if (pill != null) {
                pill.alpha = if (active) 1f else 0.55f
                pill.scaleX = if (active) 1.08f else 1f
                pill.scaleY = if (active) 1.08f else 1f
                val bg = pill.background as? GradientDrawable
                bg?.setColor(if (active) 0xCC0A84FF.toInt() else 0x66000000)
                pill.text = if (active) "释放进小窗" else "小窗"
            }
            container.alpha = if (active) 1f else 0.9f
        }
    }

    // endregion

    // region FreeformSystemShortcut suppression

    /**
     * Hide the stock recents freeform shortcut/menu item. HyperOS-style entry is the
     * foreground app swipe-up gesture above; leaving this button visible creates two different
     * paths and the stock one may launch a fresh/normal freeform task.
     */
    private fun hookFreeformSystemShortcut(cl: ClassLoader): Boolean {
        val className = "com.android.quickstep.TaskShortcutFactory\$FreeformSystemShortcut"
        val clazz = runCatching { XposedHelpers.findClass(className, cl) }.getOrNull()
            ?: return false
        var hooks = 0
        for ((methodName, result) in listOf(
            "getShortcut" to null,
            "isAvailable" to false,
            "onClick" to null,
            "startActivity" to null,
        )) {
            runCatching {
                XposedBridge.hookAllMethods(clazz, methodName, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = result
                    }
                })
            }.onSuccess { hooks += it.size }
                .onFailure {
                    XLog.d("$TAG $className has no $methodName hook point: ${it.message}")
                }
        }
        if (hooks > 0) {
            XLog.i("$TAG suppressing recents FreeformSystemShortcut x$hooks")
        }
        return hooks > 0
    }

    // endregion

    // region Trigger

    private fun triggerFromRecent(taskId: Int, reason: String, contextHost: Any? = null): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerUptime < 900L) {
            XLog.d("$TAG debounce skip task=$taskId reason=$reason")
            return false
        }
        if (contextHost != null && !isForegroundMiniConversionAllowed(contextHost, taskId)) {
            XLog.i(
                "$TAG letting stock HOME/Recents handle swipe for maximized task=$taskId",
            )
            return false
        }
        if (!FreeformManagerClient.isReady()) {
            XLog.e("$TAG hyper_freeform not ready")
            return false
        }
        lastTriggerUptime = now
        setHotzoneActive(false)
        mainHandler.postDelayed({ removeHotzone() }, 60L)
        XLog.i(
            "$TAG recents→mini queued after gesture settle task=$taskId reason=$reason " +
                "delay=${FOREGROUND_CONVERSION_SETTLE_MS}ms",
        )
        mainHandler.postDelayed({
            runCatching {
                FreeformManagerClient.startFromRecent(taskId, mini = true)
            }.onFailure { XLog.e("$TAG startFromRecent failed task=$taskId", it) }
        }, FOREGROUND_CONVERSION_SETTLE_MS)
        return true
    }

    private fun isRecentsFreeformEnabled(host: Any): Boolean {
        val ctx = resolveHostContext(host)
        if (ctx != null) FreeformManagerClient.initialize(ctx)
        if (!FreeformManagerClient.isEnabled()) return false
        val cr = ctx?.contentResolver ?: return true
        return runCatching {
            Settings.Global.getInt(cr, SETTINGS_RECENTS_FREEFORM, 1) != 0
        }.getOrDefault(true)
    }

    private fun resolveHostContext(host: Any): Context? = when (host) {
            is View -> host.context
            is Context -> host
            else -> runCatching {
                (XposedHelpers.callMethod(host, "asContext") as? Context)
                    ?: (XposedHelpers.callMethod(host, "getContext") as? Context)
                    ?: (XposedHelpers.callMethod(host, "getActivityContext") as? Context)
                    ?: (XposedHelpers.getObjectField(host, "mContainer") as? Context)
                    ?: ((XposedHelpers.getObjectField(host, "mContainer") as? View)?.context)
            }.getOrNull()
        }

    private fun isForegroundMiniConversionAllowed(host: Any, taskId: Int): Boolean {
        val ctx = resolveHostContext(host) ?: return true
        val blocked = runCatching {
            Settings.Global.getString(
                ctx.contentResolver,
                FreeformBridge.SETTING_SUPPRESS_FOREGROUND_MINI_TASKS,
            ).orEmpty()
                .split(',')
                .asSequence()
                .map(String::trim)
                .any { it == taskId.toString() }
        }.getOrDefault(false)
        return !blocked
    }

    // endregion
}
