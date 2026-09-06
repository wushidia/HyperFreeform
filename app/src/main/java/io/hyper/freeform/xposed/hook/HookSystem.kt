package io.hyper.freeform.xposed.hook

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.hyper.freeform.xposed.policy.FreeformPolicy
import io.hyper.freeform.xposed.server.FreeformManagerService
import io.hyper.freeform.xposed.utils.SystemServices
import io.hyper.freeform.xposed.utils.XLog

/**
 * system_server hooks: initialize FreeformManagerService inside system_server (Xiaomi server role).
 */
object HookSystem {
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookAmsSystemReady(lpparam)
    }

    private fun hookAmsSystemReady(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val ams = XposedHelpers.findClass(
                "com.android.server.am.ActivityManagerService",
                lpparam.classLoader
            )
            val methods = ams.declaredMethods.filter { it.name == "systemReady" }
            if (methods.isEmpty()) {
                val hooks = XposedBridge.hookAllMethods(
                    ams,
                    "finishBooting",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            onAmsReady(param.thisObject)
                        }
                    },
                )
                check(hooks.isNotEmpty()) { "AMS has no systemReady or finishBooting hook point" }
            } else {
                methods.forEach { m ->
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            onAmsReady(param.thisObject)
                        }
                    })
                }
            }
            XLog.d("Hooked AMS systemReady (${methods.size})")
        } catch (t: Throwable) {
            XLog.e("hookAmsSystemReady failed", t)
        }
    }

    @Volatile private var readyOnce = false
    @Volatile private var loggedSchedCfg = false
    @Volatile private var loggedInitialFreeformDpi = false
    @Volatile private var loggedFreeformInsetsSuppression = false
    @Volatile private var lastLoggedDeliveredDpi = Int.MIN_VALUE
    private val restoredRuntimeConfigLoggedTasks =
        java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    /** DPI attached to the synchronous ActivityStarter call that creates a module freeform Task. */
    private val launchingFreeformDpi = ThreadLocal<Int>()

    private fun onAmsReady(ams: Any) {
        if (readyOnce) return
        readyOnce = true
        try {
            SystemServices.init(ams)
            FreeformManagerService.systemReady()
            publishService()
            hookManagedTaskMinimumSize(ams.javaClass.classLoader)
            hookTaskEvents(ams)
            hookNormalLaunchOfTrackedFreeform(ams.javaClass.classLoader)
            hookFreeformSystemUiFlags(ams.javaClass.classLoader)
            hookFreeformInsetsControl(ams.javaClass.classLoader)
            hookImeVisibility(ams.javaClass.classLoader)
            hookFreeformMultiWindowAndOrientation(ams.javaClass.classLoader)
        } catch (t: Throwable) {
            XLog.e("onAmsReady failed", t)
        }
    }

    /**
     * Relax the Task minimum before the launch Activity's first configuration is resolved. Doing
     * this after startActivity returns leaves the child Activity at the old expanded width even if
     * the outer Task subsequently accepts the smaller bounds.
     */
    private fun hookManagedTaskMinimumSize(cl: ClassLoader?) {
        runCatching {
            val taskClass = XposedHelpers.findClass("com.android.server.wm.Task", cl)
            val loggedTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            val hooks = XposedBridge.hookAllMethods(
                taskClass,
                "adjustForMinimalTaskDimensions",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val task = param.thisObject ?: return
                        val taskId = taskId(task)
                        val packageName = taskPackageName(task)
                        if (!FreeformManagerService.shouldRelaxTaskMinDimensions(
                                taskId,
                                packageName,
                            )
                        ) return
                        runCatching {
                            val oldWidth = XposedHelpers.getIntField(task, "mMinWidth")
                            val oldHeight = XposedHelpers.getIntField(task, "mMinHeight")
                            XposedHelpers.setIntField(task, "mMinWidth", 0)
                            XposedHelpers.setIntField(task, "mMinHeight", 0)
                            if (loggedTasks.add(taskId)) {
                                XLog.i(
                                    "Task minimum relaxed before first layout task=$taskId " +
                                        "pkg=${packageName.orEmpty()} " +
                                        "min=${oldWidth}x${oldHeight} -> 0x0",
                                )
                            }
                        }.onFailure {
                            XLog.e("Task minimum pre-layout update failed task=$taskId", it)
                        }
                    }

                },
            )
            check(hooks.isNotEmpty()) { "Task.adjustForMinimalTaskDimensions hook unavailable" }
            XLog.i("Hooked Task.adjustForMinimalTaskDimensions for managed freeforms")

            // ActivityOptions creates and resolves a new freeform task before the service can add
            // it to the tracked map. Put the selected density into the Task's requested override
            // before that first resolve, so Task, every ActivityRecord and the client transaction
            // all inherit the same density from frame one. Merely patching ActivityRecord's final
            // object leaves Task at display DPI and causes a task-wide relaunch when a translucent
            // child Activity (gallery/viewer/dialog) is started later.
            val densityLoggedTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            val resolveHooks = XposedBridge.hookAllMethods(
                taskClass,
                "resolveOverrideConfiguration",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val task = param.thisObject ?: return
                        val taskId = taskId(task)
                        val packageName = taskPackageName(task)
                        runCatching {
                            val cfg = XposedHelpers.callMethod(
                                task,
                                "getRequestedOverrideConfiguration",
                            ) ?: return
                            val wc = XposedHelpers.getObjectField(
                                cfg,
                                "windowConfiguration",
                            ) ?: return
                            val mode = XposedHelpers.callMethod(
                                wc,
                                "getWindowingMode",
                            ) as? Int
                            val launchDpi = launchingFreeformDpi.get()
                            if (launchDpi == null &&
                                !FreeformManagerService.shouldPrimeFreeformTaskConfiguration(
                                    taskId,
                                    packageName,
                                    mode ?: 0,
                                )
                            ) return
                            val dpi = launchDpi ?: FreeformManagerService.freeformDpiOf(taskId)
                            val density = if (dpi > 0) dpi else 0
                            val field = cfg.javaClass.getField("densityDpi")
                            val previous = field.getInt(cfg)
                            if (previous != density) field.setInt(cfg, density)
                            if (densityLoggedTasks.add(taskId)) {
                                XLog.i(
                                    "Task first freeform config density primed task=$taskId " +
                                        "pkg=${packageName.orEmpty()} $previous->$density",
                                )
                            }
                        }.onFailure {
                            XLog.e("Task pre-resolve density failed task=$taskId", it)
                        }
                    }

                },
            )
            check(resolveHooks.isNotEmpty()) { "Task.resolveOverrideConfiguration hook unavailable" }
            XLog.i("Hooked Task.resolveOverrideConfiguration for first-frame freeform density")
        }.onFailure { XLog.e("managed Task minimum-size hook failed", it) }
    }

    private fun taskId(task: Any): Int {
        return runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrNull()
            ?: runCatching { XposedHelpers.callMethod(task, "getTaskId") as? Int }.getOrNull()
            ?: -1
    }

    private fun seedInitialFreeformReport(record: Any) {
        // -1 means this Activity has never reported a configuration to its client. Seed that
        // constructor-time snapshot from the just-resolved custom-DPI configuration before the
        // first ensureActivityConfiguration() can diff against the display-density value.
        val lastDisplayId = runCatching {
            XposedHelpers.getIntField(record, "mLastReportedDisplayId")
        }.getOrNull() ?: return
        if (lastDisplayId != -1) return
        val task = runCatching {
            XposedHelpers.callMethod(record, "getTask")
        }.getOrNull() ?: return
        val taskId = taskId(task)
        val packageName = activityRecordPackage(record)
        val managed = FreeformManagerService.isVisibleFreeformTask(taskId) ||
            FreeformManagerService.isPendingFreeformLaunch(packageName)
        if (!managed) return
        val current = runCatching {
            XposedHelpers.callMethod(record, "getConfiguration") as? Configuration
        }.getOrNull() ?: return
        val currentWc = XposedHelpers.getObjectField(current, "windowConfiguration") ?: return
        val currentMode = XposedHelpers.callMethod(currentWc, "getWindowingMode") as? Int
        if (currentMode != FreeformPolicy.WINDOWING_MODE_FREEFORM) return
        val global = runCatching {
            XposedHelpers.callMethod(
                record,
                "getProcessGlobalConfiguration",
            ) as? Configuration
        }.getOrNull() ?: return
        val override = runCatching {
            XposedHelpers.callMethod(
                record,
                "getMergedOverrideConfiguration",
            ) as? Configuration
        }.getOrNull() ?: return
        val stableOverride = Configuration(override)
        val dpi = FreeformManagerService.freeformDpiOf(taskId)
        canonicalizeManagedFreeformConfig(
            stableOverride,
            taskId,
            FreeformManagerService.freeformBoundsOf(taskId),
            appFullscreen = false,
        )
        runCatching {
            XposedHelpers.callMethod(
                record,
                "setLastReportedConfiguration",
                Configuration(global),
                stableOverride,
            )
            XLog.i(
                "Seeded initial freeform report task=$taskId " +
                    "pkg=${packageName.orEmpty()} dpi=$dpi",
            )
        }.onFailure {
            XLog.e("Initial freeform report seed failed task=$taskId", it)
        }
    }

    private fun taskPackageName(task: Any): String? {
        val activity = runCatching {
            XposedHelpers.callMethod(task, "getTopNonFinishingActivity")
        }.getOrNull() ?: runCatching {
            XposedHelpers.callMethod(task, "topRunningActivity")
        }.getOrNull()
        activity?.let { activityRecordPackage(it) }?.let { return it }
        val intent = runCatching {
            XposedHelpers.callMethod(task, "getBaseIntent") as? Intent
        }.getOrNull() ?: runCatching {
            XposedHelpers.getObjectField(task, "intent") as? Intent
        }.getOrNull()
        return intent?.component?.packageName ?: intent?.`package`
    }

    /**
     * Ordinary (non-freeform-options) starts must not inherit the always-on-top freeform root.
     * Otherwise a home/recents/icon launch becomes another freeform window and fights z-order,
     * which shows up as a flickering floating app. In-app navigation inside a managed freeform
     * is left untouched. Starts made by the module request WINDOWING_MODE_FREEFORM explicitly.
     */
    private fun hookNormalLaunchOfTrackedFreeform(cl: ClassLoader?) {
        runCatching {
            val starter = XposedHelpers.findClass("com.android.server.wm.ActivityStarter", cl)
            val activityRecord = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", cl)
            var hooked = 0
            val preferredMethods = starter.declaredMethods.filter {
                it.name == "startActivityUnchecked" &&
                    it.parameterTypes.firstOrNull() == activityRecord
            }.ifEmpty {
                starter.declaredMethods.filter {
                    it.name == "startActivityInner" &&
                        it.parameterTypes.firstOrNull() == activityRecord
                }
            }
            for (method in preferredMethods) {
                val optionsIndex = method.parameterTypes.indexOfFirst {
                    it.name == "android.app.ActivityOptions"
                }
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val start = param.args.getOrNull(0) ?: return
                        val source = param.args.getOrNull(1)
                        val options = if (optionsIndex >= 0) param.args.getOrNull(optionsIndex) else null
                        val launchWindowingMode = options?.let {
                            runCatching {
                                XposedHelpers.callMethod(it, "getLaunchWindowingMode") as? Int
                            }.getOrNull()
                        } ?: 0
                        // Module/notification/sidebar freeform launches explicitly request mode 5.
                        if (launchWindowingMode == FreeformPolicy.WINDOWING_MODE_FREEFORM) {
                            val packageName = activityRecordPackage(start)
                            if (FreeformManagerService.isPendingFreeformLaunch(packageName)) {
                                launchingFreeformDpi.set(
                                    FreeformPolicy.freeformDpiFromSettings(),
                                )
                                XLog.i(
                                    "ActivityStarter carries first-frame freeform DPI " +
                                        "pkg=${packageName.orEmpty()} " +
                                        "dpi=${launchingFreeformDpi.get()}",
                                )
                            }
                            return
                        }
                        // Preserve explicit split/PiP/other organizer launches; only ordinary or
                        // explicit fullscreen starts represent the user's normal app launch.
                        if (launchWindowingMode !in intArrayOf(0, FreeformPolicy.WINDOWING_MODE_FULLSCREEN)) {
                            return
                        }
                        val packageName = activityRecordPackage(start) ?: return
                        val sourceTaskId = activityRecordTaskId(source)
                        // Any Activity launched from a managed small-window task must preserve that
                        // task's mode. This includes cross-package system helpers (permissions,
                        // document pickers, credentials, etc.); forcing one of them fullscreen
                        // changes the whole source task and produces a visible window jump.
                        // Launcher/recents starts do not originate from the managed task, so they
                        // still take the explicit fullscreen path below.
                        val fromManagedFreeform = sourceTaskId != null &&
                            FreeformManagerService.isVisibleFreeformTask(sourceTaskId)
                        if (!fromManagedFreeform &&
                            FreeformManagerService.hasVisibleFreeformTask()
                        ) {
                            forceOrdinaryLaunchFullscreen(
                                param,
                                options,
                                optionsIndex,
                                packageName,
                                sourceTaskId,
                            )
                        }
                        FreeformManagerService.promoteTrackedFreeformForNormalLaunch(
                            packageName,
                            sourceTaskId,
                        )
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        launchingFreeformDpi.remove()
                        val sourceTaskId = activityRecordTaskId(param.args.getOrNull(1))
                        if (sourceTaskId != null &&
                            FreeformManagerService.isVisibleFreeformTask(sourceTaskId)
                        ) {
                            return
                        }
                        val start = param.args.getOrNull(0) ?: return
                        val packageName = activityRecordPackage(start) ?: return
                        if (FreeformManagerService.isPendingFreeformLaunch(packageName)) return
                        if (!FreeformManagerService.hasVisibleFreeformTask()) return
                        val taskId = activityRecordTaskId(start) ?: return
                        if (FreeformManagerService.isVisibleFreeformTask(taskId)) return
                        val mode = activityRecordWindowingMode(start) ?: return
                        if (mode != FreeformPolicy.WINDOWING_MODE_FREEFORM) return
                        FreeformManagerService.revertUnsolicitedFreeformLaunch(taskId, packageName)
                    }
                })
                hooked++
            }
            check(hooked > 0) { "no compatible ActivityStarter start method" }
            XLog.i("Hooked ActivityStarter normal-launch freeform promotion x$hooked")
        }.onFailure { XLog.e("normal-launch freeform promotion hook failed", it) }
    }

    private fun forceOrdinaryLaunchFullscreen(
        param: XC_MethodHook.MethodHookParam,
        options: Any?,
        optionsIndex: Int,
        packageName: String,
        sourceTaskId: Int?,
    ) {
        if (optionsIndex < 0) {
            XLog.e("ordinary launch has no ActivityOptions slot pkg=$packageName")
            return
        }
        val fullscreenOptions = options as? ActivityOptions
            ?: ActivityOptions.makeBasic().also { param.args[optionsIndex] = it }
        runCatching {
            XposedHelpers.callMethod(
                fullscreenOptions,
                "setLaunchWindowingMode",
                FreeformPolicy.WINDOWING_MODE_FULLSCREEN,
            )
        }.onFailure {
            XLog.e("ordinary launch fullscreen option failed pkg=$packageName", it)
        }
        runCatching {
            XposedHelpers.callMethod(fullscreenOptions, "setLaunchBounds", null as android.graphics.Rect?)
        }
        XLog.i(
            "ordinary launch forced fullscreen pkg=$packageName sourceTask=${sourceTaskId ?: -1}",
        )
    }

    /**
     * A focused freeform task would otherwise become the insets/system-bar control target and
     * pull a fullscreen app out of immersive mode (white status/navigation bars).
     */
    private fun hookFreeformSystemUiFlags(cl: ClassLoader?) {
        val hooker = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.result != true) return
                val taskId = systemUiTaskId(param.thisObject) ?: return
                if (FreeformManagerService.shouldIgnoreSystemUiFlags(taskId)) {
                    param.result = false
                }
            }
        }
        for (className in listOf(
            "com.android.server.wm.Task",
            "com.android.server.wm.WindowState",
            "com.android.server.wm.ActivityRecord",
        )) {
            runCatching {
                val clazz = XposedHelpers.findClass(className, cl)
                val hooks = XposedBridge.hookAllMethods(clazz, "canAffectSystemUiFlags", hooker)
                if (hooks.isNotEmpty()) {
                    XLog.i("Hooked $className.canAffectSystemUiFlags x${hooks.size}")
                }
            }.onFailure {
                XLog.d("$className.canAffectSystemUiFlags unavailable: ${it.message}")
            }
        }
    }

    /**
     * API35+ system bars follow the focused window's requestedVisibleTypes through InsetsPolicy,
     * not the legacy canAffectSystemUiFlags path. When a freeform small window takes focus over a
     * fullscreen immersive app it would otherwise become the status/navigation bar control target
     * and force the bars visible, breaking the underlying app's fullscreen. Redirect bar control
     * back to the top fullscreen-opaque window so that window keeps deciding bar visibility.
     */
    private fun hookFreeformInsetsControl(cl: ClassLoader?) {
        val insetsPolicy = runCatching {
            XposedHelpers.findClass("com.android.server.wm.InsetsPolicy", cl)
        }.getOrNull() ?: run {
            XLog.e("InsetsPolicy class not found")
            return
        }
        // Stop a freeform focus change from writing status/navigation bars into
        // mForciblyShowingTypes. Once that bit is stored, Android returns its permanent showing
        // target before reaching the built-in multi-window fallback to the fullscreen app.
        runCatching {
            val hooks = XposedBridge.hookAllMethods(
                insetsPolicy,
                "updateSystemBars",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // DisplayPolicy deliberately passes the underlying fullscreen window here
                        // when the real focus is freeform, so inspect its mFocusedWindow instead of
                        // trusting arg0. The tracked-task fallback covers first-layout timing gaps.
                        val displayFocus = displayPolicyFocusedWindow(param.thisObject)
                        val managedFreeformFocused =
                            displayFocus?.let(::windowModeOf) ==
                                FreeformPolicy.WINDOWING_MODE_FREEFORM ||
                                FreeformManagerService.hasVisibleFreeformTask()
                        if (!managedFreeformFocused) {
                            return
                        }
                        val topFs = topFullscreenOpaqueWindow(param.thisObject) ?: return
                        val forcedShowing = param.args.getOrNull(1) as? Int ?: return
                        val systemBars = android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
                        param.args[1] = forcedShowing and systemBars.inv()
                        // When no explicit force-show/hide type remains, this boolean would make
                        // updateSystemBars add both bars back. The normal control-target path still
                        // shows bars for a non-immersive underlying app and for a visible IME.
                        if (param.args.getOrNull(3) is Boolean) param.args[3] = false
                        if (!loggedFreeformInsetsSuppression) {
                            loggedFreeformInsetsSuppression = true
                            XLog.d(
                                "insets: suppress forced bars for freeform focus " +
                                    "old=$forcedShowing new=${param.args[1]}",
                            )
                        }
                    }
                },
            )
            if (hooks.isNotEmpty()) {
                XLog.i("Hooked InsetsPolicy.updateSystemBars x${hooks.size}")
            }
        }.onFailure {
            XLog.e("InsetsPolicy.updateSystemBars hook failed", it)
        }
        // Android 16 checks "forcibly shown" before its normal multi-window fallback to the
        // underlying fullscreen app. Limit the override to that permanent-target result; this
        // preserves user-requested transient bars and the navigation bar required by a visible IME.
        val redirect = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val focusedWin = param.args.getOrNull(0) ?: return
                if (windowModeOf(focusedWin) != FreeformPolicy.WINDOWING_MODE_FREEFORM) return
                val permanentTarget = runCatching {
                    XposedHelpers.getObjectField(
                        param.thisObject,
                        "mShowingPermanentControlTarget",
                    )
                }.getOrNull() ?: return
                if (param.result !== permanentTarget) return
                if (param.method.name == "getNavControlTargetInner" &&
                    isInsetsImeVisible(param.thisObject)
                ) {
                    return
                }
                val topFs = topFullscreenOpaqueWindow(param.thisObject) ?: return
                if (topFs === focusedWin) return
                param.result = topFs
                XLog.d("insets: redirect ${param.method.name} freeform->topFullscreen")
            }
        }
        var hooked = 0
        for (name in listOf("getStatusControlTargetInner", "getNavControlTargetInner")) {
            runCatching {
                val hooks = XposedBridge.hookAllMethods(insetsPolicy, name, redirect)
                if (hooks.isNotEmpty()) {
                    hooked += hooks.size
                    XLog.i("Hooked InsetsPolicy.$name x${hooks.size}")
                }
            }.onFailure {
                XLog.d("InsetsPolicy.$name hook unavailable: ${it.message}")
            }
        }
        if (hooked == 0) XLog.e("InsetsPolicy bar-control hooks not applied")
    }

    private fun windowModeOf(win: Any): Int? = runCatching {
        XposedHelpers.callMethod(win, "getWindowingMode") as? Int
    }.getOrNull() ?: runCatching {
        val task = XposedHelpers.callMethod(win, "getTask")
        XposedHelpers.callMethod(task, "getWindowingMode") as? Int
    }.getOrNull()

    private fun topFullscreenOpaqueWindow(insetsPolicy: Any): Any? {
        val policy = runCatching {
            XposedHelpers.getObjectField(insetsPolicy, "mPolicy")
        }.getOrNull() ?: return null
        return runCatching {
            XposedHelpers.callMethod(policy, "getTopFullscreenOpaqueWindow")
        }.getOrNull()
    }

    private fun displayPolicyFocusedWindow(insetsPolicy: Any): Any? {
        val policy = runCatching {
            XposedHelpers.getObjectField(insetsPolicy, "mPolicy")
        }.getOrNull() ?: return null
        return runCatching {
            XposedHelpers.getObjectField(policy, "mFocusedWindow")
        }.getOrNull()
    }

    private fun isInsetsImeVisible(insetsPolicy: Any): Boolean {
        val displayContent = runCatching {
            XposedHelpers.getObjectField(insetsPolicy, "mDisplayContent")
        }.getOrNull() ?: return false
        val imeWindow = runCatching {
            XposedHelpers.getObjectField(displayContent, "mInputMethodWindow")
        }.getOrNull() ?: return false
        return runCatching {
            XposedHelpers.callMethod(imeWindow, "isVisible") as? Boolean
        }.getOrNull() == true
    }

    private fun systemUiTaskId(obj: Any): Int? {
        val direct = taskIdOrNull(obj)
        if (direct != null && direct > 0) return direct
        val task = runCatching { XposedHelpers.callMethod(obj, "getTask") }.getOrNull()
            ?: runCatching { XposedHelpers.callMethod(obj, "getRootTask") }.getOrNull()
            ?: return null
        return taskIdOrNull(task)
    }

    private fun taskIdOrNull(obj: Any): Int? {
        val id = taskId(obj)
        return id.takeIf { it > 0 }
    }

    private fun activityRecordWindowingMode(record: Any): Int? {
        return runCatching {
            XposedHelpers.callMethod(record, "getWindowingMode") as? Int
        }.getOrNull() ?: runCatching {
            val task = XposedHelpers.callMethod(record, "getTask") ?: return@runCatching null
            XposedHelpers.callMethod(task, "getWindowingMode") as? Int
        }.getOrNull()
    }

    private fun activityRecordPackage(record: Any): String? {
        return runCatching {
            XposedHelpers.getObjectField(record, "packageName") as? String
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: runCatching {
            (XposedHelpers.getObjectField(record, "mActivityComponent") as? ComponentName)
                ?.packageName
        }.getOrNull()
    }

    private fun activityRecordTaskId(record: Any?): Int? {
        if (record == null) return null
        return runCatching {
            val task = XposedHelpers.callMethod(record, "getTask") ?: return@runCatching null
            runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrNull()
                ?: (XposedHelpers.callMethod(task, "getTaskId") as? Int)
        }.getOrNull()
    }

    /**
     * Xiaomi freeform landscape port. Two server-side hooks so freeform apps behave like
     * fullscreen for orientation purposes (fixes bilibili "暂不支持在分屏模式下使用"):
     *
     * 1) ActivityClientController.isInMultiWindowMode(token) → return false for our tracked
     *    freeform tasks. This is the app-facing query (Activity.isInMultiWindowMode); internal
     *    WM keeps using ActivityRecord.inMultiWindowMode(), so nothing else changes.
     * 2) ActivityClientController.setRequestedOrientation(token, orientation) → when a freeform
     *    app requests landscape, rotate the freeform window to a landscape rectangle
     *    (MiuiFreeFormManagerService.setRequestedOrientation).
     */
    private fun hookFreeformMultiWindowAndOrientation(cl: ClassLoader?) {
        val controllerNames = listOf(
            "com.android.server.wm.ActivityClientController",
            "com.android.server.wm.ActivityTaskManagerService",
        )
        var mwHooked = false
        var orHooked = false
        for (name in controllerNames) {
            val clazz = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: continue
            // 1) isInMultiWindowMode(token)
            if (!mwHooked) {
                runCatching {
                    XposedBridge.hookAllMethods(
                        clazz,
                        "isInMultiWindowMode",
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val taskId = taskIdFromActivityToken(param.args.getOrNull(0))
                                XLog.d("isInMultiWindowMode? task=$taskId result=${param.result} freeform=${taskId != null && FreeformManagerService.isVisibleFreeformTask(taskId)}")
                                if (param.result != true) return
                                if (taskId != null && FreeformManagerService.isVisibleFreeformTask(taskId)) {
                                    // App sees fullscreen → allows landscape fullscreen video.
                                    param.result = false
                                    XLog.d("isInMultiWindowMode->false freeform task=$taskId")
                                }
                            }
                        },
                    )
                    mwHooked = true
                    XLog.i("Hooked $name.isInMultiWindowMode for freeform landscape")
                }.onFailure { XLog.d("isInMultiWindowMode hook skip on $name: ${it.message}") }
            }
            // 2) setRequestedOrientation(token, orientation)
            if (!orHooked) {
                runCatching {
                    XposedBridge.hookAllMethods(
                        clazz,
                        "setRequestedOrientation",
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val token = param.args.getOrNull(0)
                                val orientation = param.args.getOrNull(1) as? Int ?: return
                                val taskId = taskIdFromActivityToken(token) ?: return
                                XLog.d("setRequestedOrientation task=$taskId o=$orientation freeform=${FreeformManagerService.isVisibleFreeformTask(taskId)}")
                                if (FreeformManagerService.isVisibleFreeformTask(taskId)) {
                                    FreeformManagerService.onAppRequestedOrientation(taskId, orientation)
                                }
                            }
                        },
                    )
                    orHooked = true
                    XLog.i("Hooked $name.setRequestedOrientation for freeform landscape")
                }.onFailure { XLog.d("setRequestedOrientation hook skip on $name: ${it.message}") }
            }
        }
        if (!mwHooked) XLog.e("freeform: isInMultiWindowMode hook not installed")
        if (!orHooked) XLog.e("freeform: setRequestedOrientation(client) hook not installed")

        // 3) Central catch-all: ActivityRecord.setRequestedOrientation(int) — hit by both the
        // client controller path and window-level orientation requests. thisObject is the AR.
        runCatching {
            val arClz = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", cl)
            XposedBridge.hookAllMethods(
                arClz,
                "setRequestedOrientation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val orientation = param.args.firstOrNull { it is Int } as? Int ?: return
                        val taskId = runCatching {
                            val task = XposedHelpers.callMethod(param.thisObject, "getTask") ?: return
                            XposedHelpers.getIntField(task, "mTaskId")
                        }.getOrNull() ?: return
                        val ff = FreeformManagerService.isVisibleFreeformTask(taskId)
                        XLog.d("AR.setRequestedOrientation task=$taskId o=$orientation freeform=$ff")
                        if (ff) {
                            FreeformManagerService.onAppRequestedOrientation(taskId, orientation)
                        }
                    }
                },
            )
            XLog.i("Hooked ActivityRecord.setRequestedOrientation (catch-all)")
        }.onFailure { XLog.d("AR.setRequestedOrientation hook skip: ${it.message}") }

        // 4) Make freeform apps stop detecting "split screen" so they allow landscape fullscreen
        // ("分屏模式下暂不支持全屏播放"). Apps like bilibili do NOT use the isInMultiWindowMode()
        // IPC (verified: that hook never fires) — they compare getCurrentWindowMetrics() to
        // getMaximumWindowMetrics(), i.e. the config's maxBounds. While maxBounds stays the full
        // display and the window is small, they treat it as split-screen.
        //
        // IMPORTANT: we override ONLY maxBounds (== the freeform bounds), and keep
        // windowingMode=freeform. An earlier version also reported windowingMode=FULLSCREEN, but
        // that made WM treat the task as an opaque fullscreen occluder and STOP drawing the
        // wallpaper/home behind the small window → black background. maxBounds alone fixes the
        // detection without the occlusion side effect. The window is then rotated to landscape by
        // the setRequestedOrientation hook above when the app requests it.
        runCatching {
            val arClz = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", cl)
            XposedBridge.hookAllMethods(
                arClz,
                "resolveOverrideConfiguration",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // The argument is ActivityRecord's parent (Task) configuration. Correct it
                        // BEFORE ActivityRecord resolves against it. Doing this in afterHookedMethod
                        // leaves the Activity resolved at display DPI, then mutates its parent to
                        // freeform DPI, producing a delayed CONFIG_DENSITY task-wide relaunch.
                        val resolved = param.args.getOrNull(0) ?: return
                        val taskId = runCatching {
                            val task = XposedHelpers.callMethod(param.thisObject, "getTask") ?: return
                            XposedHelpers.getIntField(task, "mTaskId")
                        }.getOrNull() ?: return
                        val winCfg = XposedHelpers.getObjectField(resolved, "windowConfiguration")
                            ?: return
                        val mode = XposedHelpers.callMethod(winCfg, "getWindowingMode") as? Int
                        if (mode != FreeformPolicy.WINDOWING_MODE_FREEFORM) return
                        // Tracked bounds (may be null on the FIRST resolution during launch, before
                        // FreeformManagerService has registered the task).
                        val ffBounds = FreeformManagerService.freeformBoundsOf(taskId)
                        canonicalizeManagedFreeformConfig(
                            resolved,
                            taskId,
                            ffBounds,
                            appFullscreen = false,
                        )
                        if (ffBounds == null && !loggedInitialFreeformDpi) {
                            loggedInitialFreeformDpi = true
                            XLog.i(
                                "Initial freeform config has custom DPI " +
                                    "task=$taskId dpi=${FreeformManagerService.freeformDpiOf(taskId)}",
                            )
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        param.thisObject?.let(::seedInitialFreeformReport)
                    }
                },
            )
            XLog.i("Hooked ActivityRecord.resolveOverrideConfiguration (report fullscreen + keep freeform bounds)")
        }.onFailure { XLog.d("resolveOverrideConfiguration hook skip: ${it.message}") }

        // ActivityRecord compares its next resolved configuration against mLastReportedConfiguration
        // to decide whether an Activity must relaunch. Keep the *override* half of that snapshot at
        // the same custom density as getConfiguration(). The previous delivery-only rewrite made
        // the client render at the selected density but left WM's snapshot override at display
        // density; opening any child
        // Activity then produced CONFIG_DENSITY | CONFIG_SCREEN_SIZE and relaunched the whole task.
        runCatching {
            val arClz = XposedHelpers.findClass("com.android.server.wm.ActivityRecord", cl)
            val loggedTasks = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            val hooks = XposedBridge.hookAllMethods(
                arClz,
                "setLastReportedConfiguration",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args.size < 2) return
                        val override = param.args[1] as? Configuration ?: return
                        val task = runCatching {
                            XposedHelpers.callMethod(param.thisObject, "getTask")
                        }.getOrNull() ?: return
                        val taskId = taskId(task)
                        val packageName = activityRecordPackage(param.thisObject)
                        val managed = FreeformManagerService.isVisibleFreeformTask(taskId) ||
                            FreeformManagerService.isPendingFreeformLaunch(packageName)
                        val taskMode = runCatching {
                            XposedHelpers.callMethod(task, "getWindowingMode") as? Int
                        }.getOrNull()
                        if (!managed || taskMode != FreeformPolicy.WINDOWING_MODE_FREEFORM) return
                        val clone = Configuration(override)
                        val dpi = FreeformManagerService.freeformDpiOf(taskId)
                        if (!canonicalizeManagedFreeformConfig(
                                clone,
                                taskId,
                                FreeformManagerService.freeformBoundsOf(taskId),
                                appFullscreen = false,
                            )
                        ) return
                        param.args[1] = clone
                        if (loggedTasks.add(taskId)) {
                            XLog.i(
                                "ActivityRecord last-reported override synchronized " +
                                    "task=$taskId pkg=${packageName.orEmpty()} dpi=$dpi",
                            )
                        }
                    }
                },
            )
            check(hooks.isNotEmpty()) { "setLastReportedConfiguration hook unavailable" }
            XLog.i("Hooked ActivityRecord.setLastReportedConfiguration for stable freeform DPI")
        }.onFailure { XLog.e("last-reported freeform DPI hook failed", it) }

        // 5) THE clean fix for bilibili "分屏模式下暂不支持全屏播放": inject windowingMode=FULLSCREEN
        // ONLY into the Configuration delivered to the app process, NOT WM's internal config.
        //
        // Decompiled evidence: bilibili PlayerFullscreenWidget.onClick refuses when
        // Activity.isInMultiWindowMode()==true; that returns the cached mIsInMultiWindowMode, which
        // Activity computes from getResources().getConfiguration().windowConfiguration
        // .getWindowingMode() via WindowConfiguration.inMultiWindowMode() (freeform→true).
        //
        // ActivityRecord.scheduleConfigurationChanged(cfg, ...) is exactly where WM sends that
        // config to the app (new ActivityConfigurationChangeItem(token, cfg, ...)). We clone cfg
        // and set its windowingMode to FULLSCREEN (keeping the freeform bounds/maxBounds), so the
        // app sees isInMultiWindowMode()==false while WM's own ActivityRecord config stays freeform
        // → home/wallpaper behind the small window stay drawn (no black background), and freeform
        // geometry/occlusion is untouched.
        // Decompiled evidence: bilibili PlayerFullscreenWidget.onClick refuses when
        // Activity.isInMultiWindowMode()==true. That returns the cached mIsInMultiWindowMode, which
        // ActivityThread.handleWindowingModeChangeIfNeeded sets to
        // WindowConfiguration.inMultiWindowMode(<the windowing mode of EVERY config it receives>)
        // — freeform(5)→true. So ANY config delivered to the app with windowingMode=freeform flips
        // it back to "multi-window", and the next fullscreen tap toasts. Cover EVERY config-carrying
        // client transaction item (not just the launch + config-change paths), replacing each
        // freeform config with a deep-independent FULLSCREEN clone. WM's own configs are untouched
        // (the items hold copies), so the task stays freeform → no black background.
        // Single choke point: ALL config-carrying items go through
        // ClientLifecycleManager.scheduleTransactionItem(thread, item) / scheduleTransaction(tx).
        // Hooking that (a METHOD — always fires; item constructors did NOT) lets us scan every
        // outgoing item for Configuration fields and flip freeform→fullscreen for the app.
        runCatching {
            val cfgClz = XposedHelpers.findClass("android.content.res.Configuration", cl)
            val mergedClz = runCatching {
                XposedHelpers.findClass("android.util.MergedConfiguration", cl)
            }.getOrNull()
            val itemBaseClz = runCatching {
                XposedHelpers.findClass("android.app.servertransaction.ClientTransactionItem", cl)
            }.getOrNull()
            val txClz = runCatching {
                XposedHelpers.findClass("android.app.servertransaction.ClientTransaction", cl)
            }.getOrNull()
            val clmClz = XposedHelpers.findClass("com.android.server.wm.ClientLifecycleManager", cl)

            fun handleArg(
                arg: Any?,
                transactionDpi: Int? = null,
                transactionTaskId: Int? = null,
            ) {
                if (arg == null) return
                // Varargs overloads (scheduleTransactionItems) pass a ClientTransactionItem[].
                if (arg.javaClass.isArray) {
                    val len = java.lang.reflect.Array.getLength(arg)
                    for (i in 0 until len) {
                        handleArg(
                            java.lang.reflect.Array.get(arg, i),
                            transactionDpi,
                            transactionTaskId,
                        )
                    }
                    return
                }
                when {
                    itemBaseClz != null && itemBaseClz.isInstance(arg) -> {
                        val taskId = taskIdForTransaction(arg) ?: transactionTaskId
                        val dpi = taskId?.let(FreeformManagerService::freeformDpiOf)
                            ?: transactionDpi
                        fixItemConfigs(cfgClz, mergedClz, arg, dpi, taskId)
                    }
                    txClz != null && txClz.isInstance(arg) -> {
                        val taskId = taskIdForTransaction(arg) ?: transactionTaskId
                        val dpi = taskId?.let(FreeformManagerService::freeformDpiOf)
                            ?: transactionDpi
                        // ClientTransaction bundles items (launch path).
                        runCatching {
                            val items = XposedHelpers.callMethod(arg, "getTransactionItems") as? List<*>
                                ?: (XposedHelpers.getObjectField(arg, "mActivityCallbacks") as? List<*>)
                            items?.forEach { fixItemConfigs(cfgClz, mergedClz, it, dpi, taskId) }
                        }
                        // Older single-item ClientTransaction also has mLifecycleStateRequest.
                        runCatching {
                            XposedHelpers.getObjectField(arg, "mLifecycleStateRequest")
                                ?.let { fixItemConfigs(cfgClz, mergedClz, it, dpi, taskId) }
                        }
                    }
                }
            }
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args.forEach { handleArg(it) }
                }
            }
            var n = 0
            // Every config-carrying dispatch variant: scheduleTransaction, scheduleTransactionItem,
            // scheduleTransactionItemNow (the resize path uses this), scheduleTransactionItems (varargs).
            for (m in clmClz.declaredMethods) {
                if (m.name.startsWith("scheduleTransaction")) {
                    runCatching { XposedBridge.hookMethod(m, hook); n++ }
                }
            }
            XLog.i("Hooked ClientLifecycleManager.scheduleTransaction* (app-only fullscreen for freeform) x$n")
        }.onFailure { XLog.d("scheduleTransaction hook skip: ${it.message}") }

        // 4) RELAYOUT / RESIZE synchronous config path. WindowManagerService fills the app's
        // MergedConfiguration out-param via WindowState.fillClientWindowFramesAndConfiguration, which
        // does setConfiguration(globalFullscreen, freeformOverride) → the app pulls a FREEFORM config
        // synchronously as the relayout() Binder return value — NO ClientTransactionItem, so the
        // ClientLifecycleManager hook can't see it. This is what flips e.g. bilibili's
        // VideoDetailsActivity from fullscreen(1) back to freeform(5) ~100ms after launch (→ toast),
        // AND re-flips it on every in-player relayout (tapping 切换分辨率 / 倍速 / 字幕 rebuilds the
        // player surface → new relayout() → the freeform config comes straight back to the app, which
        // then re-enters "multi-window" → the small-window glitches / toasts again). Fix ONLY the
        // transient out-param, never the window's own mLastReportedConfiguration.
        runCatching {
            val mergedClz = XposedHelpers.findClass("android.util.MergedConfiguration", cl)
            val wsClz = XposedHelpers.findClass("com.android.server.wm.WindowState", cl)
            val fillHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val lastReported = runCatching {
                        XposedHelpers.getObjectField(param.thisObject, "mLastReportedConfiguration")
                    }.getOrNull()
                    val taskId = taskIdForWindowState(param.thisObject)
                    for (a in param.args) {
                        if (a != null && mergedClz.isInstance(a) && a !== lastReported) {
                            fixMergedInPlace(
                                a,
                                taskDpi = taskId?.let(FreeformManagerService::freeformDpiOf),
                                taskId = taskId,
                            )
                        }
                    }
                }
            }
            var n = 0
            for (m in wsClz.declaredMethods) {
                if (m.name == "fillClientWindowFramesAndConfiguration") {
                    runCatching { XposedBridge.hookMethod(m, fillHook); n++ }
                }
            }
            XLog.i("Hooked WindowState.fillClientWindowFramesAndConfiguration (app-only fullscreen on relayout/resize) x$n")
        }.onFailure { XLog.d("fillClientWindowFramesAndConfiguration hook skip: ${it.message}") }

        // 5) APP-side fetched config: WindowState.getLastReportedConfiguration() /
        // getMergedConfiguration(). When the app re-queries its window config (bilibili taps
        // 切换分辨率 → VideoView/GLSurfaceView rebuilds → WindowManager.getCurrentWindowMetrics /
        // getMaximumWindowMetrics → these getters), WM hands back the FREEFORM record, re-flipping
        // the app into multi-window mid-playback. The same orphaned-config re-flip is the in-player
        // "小窗异常" the user reported. We do NOT mutate WM's record (that would loop / black bg);
        // we return a deep-cloned FULLSCREEN copy so the app reads fullscreen only via the getter.
        // Gated per-call to a tracked visible freeform task so non-freeform windows are untouched.
        runCatching {
            val mergedClz = XposedHelpers.findClass("android.util.MergedConfiguration", cl)
            val wsClz = XposedHelpers.findClass("com.android.server.wm.WindowState", cl)
            val cfgClz = XposedHelpers.findClass("android.content.res.Configuration", cl)
            val ctor = mergedClz.getConstructor(mergedClz)
            for (methodName in listOf(
                "getMergedConfiguration",
                "getLastReportedConfiguration",
            )) {
                for (m in wsClz.declaredMethods) {
                    if (m.name != methodName) continue
                    if (m.parameterTypes.isNotEmpty()) continue
                    runCatching {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val ws = param.thisObject ?: return
                                val merged = param.result ?: return
                                if (!mergedClz.isInstance(merged)) return
                                val taskId = taskIdForWindowState(ws) ?: return
                                if (!FreeformManagerService.isVisibleFreeformTask(taskId)) return
                                // Hand back a clone with windowingMode=fullscreen on BOTH inner
                                // configs; never touch the shared WM record.
                                val clone = ctor.newInstance(merged)
                                if (fixMergedInPlace(
                                        clone,
                                        taskDpi = FreeformManagerService.freeformDpiOf(taskId),
                                        taskId = taskId,
                                    )
                                ) {
                                    param.result = clone
                                }
                            }
                        })
                    }
                }
            }

            // WindowState.getConfiguration() returns the OVERRIDE (freeform) config directly to the
            // app on some relayout paths — flip it the same way (clone, fresh windowConfig).
            for (m in wsClz.declaredMethods) {
                if (m.name != "getConfiguration" || m.parameterTypes.isNotEmpty()) continue
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val ws = param.thisObject ?: return
                            val cfg = param.result ?: return
                            if (!cfgClz.isInstance(cfg)) return
                            val taskId = taskIdForWindowState(ws) ?: return
                            if (!FreeformManagerService.isVisibleFreeformTask(taskId)) return
                            val clone = cfgClz.getConstructor(cfgClz).newInstance(cfg)
                            if (canonicalizeManagedFreeformConfig(
                                    clone,
                                    taskId,
                                    FreeformManagerService.freeformBoundsOf(taskId),
                                    appFullscreen = true,
                                )
                            ) {
                                param.result = clone
                            }
                        }
                    })
                }
            }
            XLog.i("Hooked WindowState config getters (app-only fullscreen on app re-query)")
        }.onFailure { XLog.d("WindowState config-getter hook skip: ${it.message}") }
    }

    /**
     * True iff this WindowState belongs to a tracked, visible freeform task. Resolves the owning
     * task id via ActivityRecord→Task (guarded; some composite windows have no activity yet).
     */
    private fun taskIdForWindowState(ws: Any): Int? {
        return runCatching {
            val directTask = runCatching { XposedHelpers.callMethod(ws, "getTask") }.getOrNull()
                ?: runCatching {
                    val activity = XposedHelpers.getObjectField(ws, "mActivityRecord")
                    activity?.let { XposedHelpers.callMethod(it, "getTask") }
                }.getOrNull()
            val directTaskId = directTask?.let { task ->
                runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrNull()
                    ?: runCatching { XposedHelpers.callMethod(task, "getTaskId") as? Int }.getOrNull()
            }
            val taskId = directTaskId ?: run {
                val token = runCatching {
                    XposedHelpers.callMethod(ws, "getActivityToken")
                }.getOrNull() ?: runCatching {
                    XposedHelpers.getObjectField(ws, "mActivityToken")
                }.getOrNull() ?: return null
                taskIdFromActivityToken(token) ?: return null
            }
            taskId
        }.getOrNull()
    }

    private fun taskIdForTransaction(transaction: Any): Int? {
        val token = runCatching {
            XposedHelpers.callMethod(transaction, "getActivityToken")
        }.getOrNull() ?: runCatching {
            XposedHelpers.getObjectField(transaction, "mActivityToken")
        }.getOrNull()
        return taskIdFromActivityToken(token)
    }

    /** Scan a ClientTransactionItem for Configuration / MergedConfiguration fields and flip freeform→fullscreen. */
    private fun fixItemConfigs(
        cfgClz: Class<*>,
        mergedClz: Class<*>?,
        item: Any?,
        dpi: Int? = null,
        taskId: Int? = null,
    ) {
        if (item == null) return
        var c: Class<*>? = item.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                runCatching {
                    val t = f.type
                    if (t == cfgClz || cfgClz.isAssignableFrom(t)) {
                        reportFullscreenForFreeformConfig(cfgClz, item, f.name, dpi, taskId)
                    } else if (mergedClz != null && (t == mergedClz || mergedClz.isAssignableFrom(t))) {
                        f.isAccessible = true
                        val merged = f.get(item)
                        if (merged != null) {
                            // A transaction item (e.g. WindowStateResizeItem) may carry the window's
                            // own mLastReportedConfiguration by reference; mutating it would corrupt
                            // WM's record → resize loop. Clone → fix → replace the item's field.
                            val clone = runCatching {
                                mergedClz.getConstructor(mergedClz).newInstance(merged)
                            }.getOrNull()
                            if (clone != null && fixMergedInPlace(
                                    clone,
                                    taskDpi = dpi,
                                    taskId = taskId,
                                )
                            ) {
                                f.set(item, clone)
                            }
                        }
                    }
                }
            }
            c = c.superclass
        }
    }

    /**
     * Flip a MergedConfiguration's merged + override windowingMode freeform→fullscreen by mutating
     * its inner Configuration objects in place. Only safe on a MergedConfiguration whose inner
     * configs are independent copies (a fresh clone, or a transient relayout out-param filled via
     * setConfiguration — both deep-copy, so this never leaks into WM). Fields: mMergedConfig is what
     * the app applies (getMergedConfiguration); mOverrideConfig guards any re-merge. Returns true if
     * anything was freeform.
     */
    private fun fixMergedInPlace(
        merged: Any,
        taskDpi: Int? = null,
        taskId: Int? = null,
    ): Boolean {
        var changed = false
        val runtimeTaskIsFreeform = taskId != null &&
            SystemServices.taskWindowingMode(taskId) == FreeformPolicy.WINDOWING_MODE_FREEFORM
        val managedFreeform = taskId != null && runtimeTaskIsFreeform &&
            FreeformManagerService.isVisibleFreeformTask(taskId)
        val dpi = if (taskId == null || managedFreeform) {
            taskDpi ?: FreeformPolicy.freeformDpiFromSettings().takeIf { it > 0 }
        } else {
            null
        }
        for (mf in listOf("mMergedConfig", "mOverrideConfig")) {
            runCatching {
                val cfg = XposedHelpers.getObjectField(merged, mf) ?: return@runCatching
                val wc = XposedHelpers.getObjectField(cfg, "windowConfiguration") ?: return@runCatching
                val mode = XposedHelpers.callMethod(wc, "getWindowingMode") as? Int
                // Fullscreen snapshots include HOME and ordinary apps. Their activity override
                // sequence is independent of the Task/global sequence; replacing them with the
                // Task configuration makes later rotation updates look stale to ActivityThread.
                if (mode != FreeformPolicy.WINDOWING_MODE_FREEFORM) return@runCatching
                if (taskId != null) {
                    // A queued FREEFORM snapshot can arrive after maximize has switched the
                    // task to fullscreen and removed its tracking. Only that stale snapshot
                    // needs restoration; current fullscreen configurations are already correct.
                    if (!managedFreeform) {
                        if (SystemServices.taskWindowingMode(taskId) ==
                            FreeformPolicy.WINDOWING_MODE_FULLSCREEN
                        ) {
                            changed = replaceWithRuntimeTaskConfiguration(cfg, taskId) || changed
                        }
                        return@runCatching
                    }
                    changed = canonicalizeManagedFreeformConfig(
                        cfg,
                        taskId,
                        FreeformManagerService.freeformBoundsOf(taskId),
                        appFullscreen = true,
                    ) || changed
                } else {
                    injectConfiguredDpi(cfg, dpi)
                    XposedHelpers.callMethod(wc, "setWindowingMode", 1)
                    changed = true
                }
            }
        }
        if (changed && !loggedSchedCfg) {
            loggedSchedCfg = true
            XLog.i("app sees fullscreen for freeform (WM stays freeform) via MergedConfiguration")
        }
        return changed
    }

    /**
     * If [holder].[field] is a Configuration whose windowConfiguration is FREEFORM, replace it with
     * a deep-independent FULLSCREEN clone so the app process sees isInMultiWindowMode()==false while
     * WM's own config (a different object) stays freeform. The clone gets a brand-new
     * WindowConfiguration — the Configuration copy ctor can share that reference, which would leak
     * the fullscreen mode back into WM and turn the task fullscreen (black background).
     */
    private fun reportFullscreenForFreeformConfig(
        cfgClz: Class<*>,
        holder: Any,
        field: String,
        taskDpi: Int? = null,
        taskId: Int? = null,
    ) {
        runCatching {
            val cfg = XposedHelpers.getObjectField(holder, field) ?: return
            if (!cfgClz.isInstance(cfg)) return
            val winCfg = XposedHelpers.getObjectField(cfg, "windowConfiguration") ?: return
            val mode = XposedHelpers.callMethod(winCfg, "getWindowingMode") as? Int
            // Check the outgoing snapshot BEFORE consulting the runtime Task. In particular,
            // never replace a fullscreen LaunchActivityItem's global/override configurations:
            // doing so copies the Task seq into the activity override and prevents HOME from
            // accepting the smaller activity seq when a landscape app returns to portrait.
            if (mode != FreeformPolicy.WINDOWING_MODE_FREEFORM) return
            if (taskId != null && SystemServices.taskWindowingMode(taskId) !=
                FreeformPolicy.WINDOWING_MODE_FREEFORM
            ) {
                if (SystemServices.taskWindowingMode(taskId) !=
                    FreeformPolicy.WINDOWING_MODE_FULLSCREEN
                ) return
                // The item can have been built before maximize and queued after the task is
                // already fullscreen. Replace that stale snapshot with the runtime task config;
                // this restores the display density without any package/device special case.
                val clone = cfgClz.getConstructor(cfgClz).newInstance(cfg)
                if (replaceWithRuntimeTaskConfiguration(clone, taskId)) {
                    XposedHelpers.setObjectField(holder, field, clone)
                }
                return
            }
            if (taskId != null && !FreeformManagerService.isVisibleFreeformTask(taskId)) return
            val clone = cfgClz.getConstructor(cfgClz).newInstance(cfg)
            if (taskId != null) {
                if (!canonicalizeManagedFreeformConfig(
                        clone,
                        taskId,
                        FreeformManagerService.freeformBoundsOf(taskId),
                        appFullscreen = true,
                    )
                ) return
            } else {
                val wcClz = winCfg.javaClass
                val freshWin = wcClz.getConstructor(wcClz).newInstance(winCfg)
                XposedHelpers.callMethod(freshWin, "setWindowingMode", 1)
                XposedHelpers.setObjectField(clone, "windowConfiguration", freshWin)
                injectConfiguredDpi(
                    clone,
                    taskDpi ?: FreeformPolicy.freeformDpiFromSettings().takeIf { it > 0 },
                )
            }
            XposedHelpers.setObjectField(holder, field, clone)
            if (!loggedSchedCfg) {
                loggedSchedCfg = true
                XLog.i("app sees fullscreen for freeform (WM stays freeform) via ${holder.javaClass.simpleName}.$field")
            }
        }
    }

    /** Restore a stale freeform snapshot after maximize, retaining its delivery sequence. */
    private fun replaceWithRuntimeTaskConfiguration(config: Any, taskId: Int): Boolean {
        val runtime = SystemServices.taskConfiguration(taskId) ?: return false
        return runCatching {
            val setTo = config.javaClass.methods.firstOrNull {
                it.name == "setTo" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].isAssignableFrom(runtime.javaClass)
            } ?: return@runCatching false
            val seqField = config.javaClass.getField("seq")
            val deliverySeq = seqField.getInt(config)
            setTo.invoke(config, runtime)
            seqField.setInt(config, deliverySeq)
            if (restoredRuntimeConfigLoggedTasks.add(taskId)) {
                val density = runtime.javaClass.getField("densityDpi").getInt(runtime)
                val wc = XposedHelpers.getObjectField(runtime, "windowConfiguration")
                val mode = wc?.let {
                    XposedHelpers.callMethod(it, "getWindowingMode") as? Int
                }
                XLog.i(
                    "restored runtime task configuration task=$taskId " +
                        "mode=${mode ?: -1} density=$density",
                )
            }
            true
        }.onFailure {
            XLog.e("replace stale task config failed task=$taskId", it)
        }.getOrDefault(false)
    }

    /**
     * Make one Configuration internally coherent for the managed task: bounds, app/max bounds,
     * orientation, dp dimensions and user-selected density all describe the same frame. A fresh
     * WindowConfiguration prevents these app/report copies from mutating WM's shared record.
     */
    private fun canonicalizeManagedFreeformConfig(
        config: Any,
        taskId: Int,
        preferredBounds: android.graphics.Rect?,
        appFullscreen: Boolean,
    ): Boolean = runCatching {
        val originalWc = XposedHelpers.getObjectField(config, "windowConfiguration")
            ?: return@runCatching false
        val bounds = preferredBounds?.takeIf { !it.isEmpty }?.let { android.graphics.Rect(it) }
            ?: (XposedHelpers.callMethod(originalWc, "getBounds") as? android.graphics.Rect)
                ?.takeIf { !it.isEmpty }
                ?.let { android.graphics.Rect(it) }
            ?: return@runCatching false
        val freshWc = originalWc.javaClass.getConstructor(originalWc.javaClass)
            .newInstance(originalWc)
        runCatching { XposedHelpers.callMethod(freshWc, "setBounds", bounds) }
        runCatching { XposedHelpers.callMethod(freshWc, "setAppBounds", bounds) }
        runCatching { XposedHelpers.callMethod(freshWc, "setMaxBounds", bounds) }
        if (appFullscreen) {
            XposedHelpers.callMethod(
                freshWc,
                "setWindowingMode",
                FreeformPolicy.WINDOWING_MODE_FULLSCREEN,
            )
        }
        XposedHelpers.setObjectField(config, "windowConfiguration", freshWc)

        val requestedDpi = FreeformManagerService.freeformDpiOf(taskId)
        val currentDpi = config.javaClass.getField("densityDpi").getInt(config)
        val effectiveDpi = requestedDpi.takeIf { it > 0 }
            ?: currentDpi.takeIf { it > 0 }
            ?: SystemServices.systemContext.resources.displayMetrics.densityDpi
        if (requestedDpi > 0) {
            config.javaClass.getField("densityDpi").setInt(config, requestedDpi)
        }
        val density = effectiveDpi / 160f
        val wDp = (bounds.width() / density).toInt().coerceAtLeast(1)
        val hDp = (bounds.height() / density).toInt().coerceAtLeast(1)
        config.javaClass.getField("screenWidthDp").setInt(config, wDp)
        config.javaClass.getField("screenHeightDp").setInt(config, hDp)
        config.javaClass.getField("smallestScreenWidthDp").setInt(config, minOf(wDp, hDp))
        config.javaClass.getField("orientation")
            .setInt(config, if (bounds.width() > bounds.height()) 2 else 1)
        true
    }.onFailure {
        XLog.e("canonical freeform config failed task=$taskId", it)
    }.getOrDefault(false)

    /**
     * Put the selected density in the exact Configuration object delivered to the app. Server-side
     * ActivityRecord/Task configuration alone is insufficient: launch and relayout transactions
     * can carry a separately merged copy whose density has fallen back to the display value.
     */
    private fun injectConfiguredDpi(config: Any, requestedDpi: Int?): Boolean {
        val dpi = requestedDpi?.takeIf { it > 0 } ?: return false
        return runCatching {
            val previous = config.javaClass.getField("densityDpi").getInt(config)
            config.javaClass.getField("densityDpi").setInt(config, dpi)
            val wc = XposedHelpers.getObjectField(config, "windowConfiguration")
            val bounds = wc?.let {
                XposedHelpers.callMethod(it, "getBounds") as? android.graphics.Rect
            }
            if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                val density = dpi / 160f
                val wDp = (bounds.width() / density).toInt()
                val hDp = (bounds.height() / density).toInt()
                config.javaClass.getField("screenWidthDp").setInt(config, wDp)
                config.javaClass.getField("screenHeightDp").setInt(config, hDp)
                config.javaClass.getField("smallestScreenWidthDp")
                    .setInt(config, minOf(wDp, hDp))
            }
            if (lastLoggedDeliveredDpi != dpi) {
                lastLoggedDeliveredDpi = dpi
                XLog.i("Delivering freeform app configuration at dpi=$dpi bounds=$bounds")
            }
            previous != dpi
        }.onFailure {
            XLog.e("injectConfiguredDpi dpi=$dpi failed", it)
        }.getOrDefault(false)
    }

    /** Resolve an Activity IBinder token → owning task id via ActivityRecord.forTokenLocked. */
    private fun taskIdFromActivityToken(token: Any?): Int? {
        if (token == null) return null
        return runCatching {
            val arClz = XposedHelpers.findClass(
                "com.android.server.wm.ActivityRecord",
                token.javaClass.classLoader,
            )
            val ar = runCatching {
                XposedHelpers.callStaticMethod(arClz, "forTokenLocked", token)
            }.getOrNull() ?: runCatching {
                XposedHelpers.callStaticMethod(arClz, "forToken", token)
            }.getOrNull() ?: return null
            val task = XposedHelpers.callMethod(ar, "getTask") ?: return null
            runCatching { XposedHelpers.getIntField(task, "mTaskId") }.getOrNull()
                ?: (XposedHelpers.callMethod(task, "getRootTaskId") as? Int)
        }.getOrNull()
    }

    private fun publishService() {
        try {
            val sm = XposedHelpers.findClass("android.os.ServiceManager", null)
            XposedHelpers.callStaticMethod(
                sm,
                "addService",
                FreeformManagerService.SERVICE_NAME,
                FreeformManagerService as IBinder
            )
            XLog.i("Published service ${FreeformManagerService.SERVICE_NAME}")
        } catch (t: Throwable) {
            XLog.e("publishService failed", t)
        }
    }

    /**
     * Android 15: android.app.TaskStackListener is abstract, so we cannot newInstance it.
     * Prefer TaskChangeNotificationController hooks + optional ITaskStackListener binder proxy.
     */
    private fun hookTaskEvents(ams: Any) {
        val cl = ams.javaClass.classLoader
        // Prefer the in-process controller. Install lower-level alternatives only when the
        // preceding strategy is unavailable, so one task event is not delivered two or three times.
        val strategy = when {
            hookTaskChangeNotificationController(cl) -> "TaskChangeNotificationController"
            hookAtmsTaskLifecycle(cl) -> "ActivityTaskManagerService"
            registerTaskStackListenerProxy(cl) -> "ITaskStackListener"
            else -> null
        }

        if (strategy != null) {
            XLog.i("Task event hooks installed via $strategy")
        } else {
            XLog.e("hookTaskEvents: all strategies failed (non-fatal)")
        }
    }

    private fun hookTaskChangeNotificationController(cl: ClassLoader?): Boolean {
        return try {
            val ctrl = XposedHelpers.findClass(
                "com.android.server.wm.TaskChangeNotificationController",
                cl
            )
            check(ctrl.declaredMethods.any { it.name == "notifyTaskRemoved" }) {
                "notifyTaskRemoved unavailable"
            }
            check(ctrl.declaredMethods.any { it.name == "notifyTaskMovedToFront" }) {
                "notifyTaskMovedToFront unavailable"
            }
            val removedHooks = XposedBridge.hookAllMethods(
                ctrl,
                "notifyTaskRemoved",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val taskId = extractTaskId(param.args.getOrNull(0)) ?: return
                        FreeformManagerService.onTaskRemoved(taskId)
                    }
                },
            )
            val frontHooks = XposedBridge.hookAllMethods(
                ctrl,
                "notifyTaskMovedToFront",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val taskId = extractTaskId(param.args.getOrNull(0)) ?: return
                        FreeformManagerService.onTaskMovedToFront(taskId)
                    }
                },
            )
            val hooked = removedHooks.size + frontHooks.size
            check(removedHooks.isNotEmpty() && frontHooks.isNotEmpty()) {
                "incomplete task notification hook points"
            }
            XLog.d("Hooked TaskChangeNotificationController x$hooked")
            hooked > 0
        } catch (t: Throwable) {
            XLog.e("hook TaskChangeNotificationController failed", t)
            false
        }
    }

    private fun hookAtmsTaskLifecycle(cl: ClassLoader?): Boolean {
        return try {
            val atms = XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskManagerService",
                cl
            )
            var hooked = XposedBridge.hookAllMethods(
                atms,
                "removeTask",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val taskId = param.args.getOrNull(0) as? Int ?: return
                        FreeformManagerService.onTaskRemoved(taskId)
                    }
                },
            ).size
            runCatching {
                XposedBridge.hookAllMethods(atms, "moveTaskToFront", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val taskId = when (val a0 = param.args.getOrNull(0)) {
                            is Int -> a0
                            else -> extractTaskId(a0)
                        } ?: return
                        FreeformManagerService.onTaskMovedToFront(taskId)
                    }
                })
            }.onSuccess { hooked += it.size }
            check(hooked > 0) { "no ATMS task lifecycle hook points" }
            XLog.d("Hooked ATMS task lifecycle x$hooked")
            hooked > 0
        } catch (t: Throwable) {
            XLog.e("hook ATMS task lifecycle failed", t)
            false
        }
    }

    /**
     * Build a real Binder-backed ITaskStackListener without subclassing abstract TaskStackListener.
     * Uses ITaskStackListener.Default when present, else a dynamic Proxy that handles asBinder.
     */
    private fun registerTaskStackListenerProxy(cl: ClassLoader?): Boolean {
        return try {
            val listenerIface = XposedHelpers.findClass("android.app.ITaskStackListener", cl)
            val listener: Any = runCatching {
                // Android 13+ AIDL often ships Default concrete class
                val defaultClz = XposedHelpers.findClass("android.app.ITaskStackListener\$Default", cl)
                XposedHelpers.newInstance(defaultClz)
            }.getOrElse {
                // Fallback: only non-abstract TaskStackListener.
                createListenerViaStub(cl, listenerIface)
                    ?: return false
            }

            // RemoteCallbackList requires a real IBinder from asBinder(); Default often returns null.
            val binder = runCatching {
                XposedHelpers.callMethod(listener, "asBinder") as? android.os.IBinder
            }.getOrNull()
            if (binder == null) {
                XLog.d("Skip ITaskStackListener register: asBinder() null (controller hooks already active)")
                return false
            }

            hookListenerCallbacks(listener)

            val sm = XposedHelpers.findClass("android.os.ServiceManager", null)
            val atmBinder = XposedHelpers.callStaticMethod(sm, "getService", "activity_task")
            val stub = XposedHelpers.findClass("android.app.IActivityTaskManager\$Stub", null)
            val atm = XposedHelpers.callStaticMethod(stub, "asInterface", atmBinder)
            XposedHelpers.callMethod(atm, "registerTaskStackListener", listener)
            XLog.d("ITaskStackListener registered (${listener.javaClass.name})")
            true
        } catch (t: Throwable) {
            XLog.e("registerTaskStackListenerProxy failed", t)
            false
        }
    }

    private fun createListenerViaStub(cl: ClassLoader?, listenerIface: Class<*>): Any? {
        // Prefer android.app.TaskStackListener only if non-abstract.
        return try {
            val tsl = XposedHelpers.findClass("android.app.TaskStackListener", cl)
            if (!java.lang.reflect.Modifier.isAbstract(tsl.modifiers)) {
                XposedHelpers.newInstance(tsl)
            } else {
                // Use ITaskStackListener.Stub with a hand-rolled Binder transaction handler is too fragile.
                // Instead create an anonymous object of Stub via Proxy of the interface + wrap as Binder using
                // Stub.asInterface pattern is reverse. Skip if Default missing.
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun hookListenerCallbacks(listener: Any) {
        val tslClass = listener.javaClass
        // Prefer hooking superclass methods used by controller
        runCatching {
            XposedHelpers.findAndHookMethod(
                tslClass,
                "onTaskRemoved",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !== listener) return
                        val taskId = param.args[0] as? Int ?: return
                        FreeformManagerService.onTaskRemoved(taskId)
                    }
                }
            )
        }
        tslClass.methods.filter { it.name == "onTaskMovedToFront" }.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !== listener) return
                        val taskId = extractTaskId(param.args.getOrNull(0)) ?: return
                        FreeformManagerService.onTaskMovedToFront(taskId)
                    }
                })
            }
        }
    }

    private fun extractTaskId(value: Any?): Int? {
        return when (value) {
            is Int -> value
            null -> null
            else -> runCatching {
                value.javaClass.methods.firstOrNull {
                    it.name == "getTaskId" && it.parameterTypes.isEmpty()
                }?.invoke(value) as? Int
                    ?: value.javaClass.getField("taskId").getInt(value)
            }.getOrNull()
        }
    }

    /**
     * Event-driven IME visibility (Xiaomi DisplayImeController path, AOSP port).
     * Polling in FreeformManagerService remains as fallback.
     */
    private fun hookImeVisibility(cl: ClassLoader?) {
        var ok = false
        // InputMethodManagerService.setImeWindowStatus(...)
        runCatching {
            val clazz = XposedHelpers.findClass(
                "com.android.server.inputmethod.InputMethodManagerService",
                cl
            )
            XposedBridge.hookAllMethods(clazz, "setImeWindowStatus", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // vis flags typically arg index 1 or 2 depending on signature.
                    val height = runCatching { SystemServices.imeVisibleHeight() }.getOrDefault(0)
                    FreeformManagerService.onImeVisibilityChanged(height > 0, height)
                }
            })
            ok = true
            XLog.d("Hooked IMS setImeWindowStatus")
        }.onFailure { XLog.d("IMS setImeWindowStatus hook skipped: ${it.message}") }

        // ImeInsetsSourceProvider / DisplayContent ime visibility helpers when present.
        runCatching {
            val names = listOf(
                "com.android.server.wm.ImeInsetsSourceProvider",
                "com.android.server.wm.DisplayContent"
            )
            for (name in names) {
                val clazz = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: continue
                for (methodName in listOf("setImeShowing", "setImeInputTarget", "updateLocalImeState")) {
                    val methods = clazz.declaredMethods.filter { it.name == methodName }
                    if (methods.isEmpty()) continue
                    methods.forEach { m ->
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                val height = runCatching { SystemServices.imeVisibleHeight() }.getOrDefault(0)
                                FreeformManagerService.onImeVisibilityChanged(height > 0, height)
                            }
                        })
                    }
                    ok = true
                    XLog.d("Hooked $name.$methodName x${methods.size}")
                }
            }
        }.onFailure { XLog.d("ImeInsets hooks skipped: ${it.message}") }

        if (!ok) {
            XLog.d("IME event hooks unavailable; relying on poll fallback")
        }
    }
}
