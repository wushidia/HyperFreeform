package io.hyper.freeform.xposed.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Display
import android.view.WindowManager
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Cached system services from ActivityManagerService (system_server).
 * Task ops follow the Xiaomi freeform server path (native freeform Task, not VirtualDisplay).
 */
@SuppressLint("StaticFieldLeak")
object SystemServices {
    /** AOSP WindowContainer force-hidden flag used by pin / hide paths. */
    const val FLAG_FORCE_HIDDEN_FOR_PINNED_TASK = 1
    /** MIUI-specific force-hidden flag observed in freeform pin WCT path. */
    const val FLAG_FORCE_HIDDEN_MIUI_PIN = 65536

    lateinit var systemContext: Context
        private set
    lateinit var windowManager: WindowManager
        private set
    lateinit var displayManager: DisplayManager
        private set
    lateinit var activityManager: ActivityManager
        private set
    lateinit var packageManager: PackageManager
        private set

    private lateinit var activityManagerService: Any
    private var activityTaskManagerBinder: IBinder? = null
    private var activityTaskManagerService: Any? = null
    private var statusBarService: Any? = null
    @Volatile private var systemUiContext: Context? = null

    val isInitialized: Boolean
        get() = this::systemContext.isInitialized

    private fun serviceManagerGet(name: String): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            sm.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
        } catch (t: Throwable) {
            XLog.e("ServiceManager.getService($name) failed", t)
            null
        }
    }

    fun init(ams: Any) {
        activityManagerService = ams
        systemContext = ams.javaClass.getDeclaredField("mContext").apply { isAccessible = true }
            .get(ams) as Context
        windowManager = systemContext.getSystemService(WindowManager::class.java)
        displayManager = systemContext.getSystemService(DisplayManager::class.java)
        activityManager = systemContext.getSystemService(ActivityManager::class.java)
        packageManager = systemContext.packageManager
        activityTaskManagerBinder = serviceManagerGet("activity_task")
        statusBarService = serviceManagerGet("statusbar")
        activityTaskManagerService = resolveAtms(ams)
        XLog.d(
            "SystemServices initialized atms=${activityTaskManagerService?.javaClass?.name ?: "null"}"
        )
    }


    /**
     * Best-effort IME visible height in px (Xiaomi DisplayInfo.getImeHeight source).
     * Tries WindowManagerInternal then DisplayContent InputMethod frame.
     */
    fun imeVisibleHeight(displayId: Int = Display.DEFAULT_DISPLAY): Int {
        // 1) WindowManagerInternal.getInputMethodWindowVisibleHeight(displayId)
        runCatching {
            val localServices = Class.forName("com.android.server.LocalServices")
            val wmiClass = Class.forName("com.android.server.wm.WindowManagerInternal")
            val wmi = localServices.getMethod("getService", Class::class.java).invoke(null, wmiClass)
                ?: return@runCatching
            val methods = wmi.javaClass.methods.filter { it.name == "getInputMethodWindowVisibleHeight" }
            for (m in methods) {
                val value = when (m.parameterTypes.size) {
                    0 -> m.invoke(wmi)
                    1 -> m.invoke(wmi, displayId)
                    else -> continue
                }
                if (value is Int && value >= 0) return value
            }
        }
        // 2) DisplayContent input method window frame height
        runCatching {
            val rwc = rootWindowContainer() ?: return@runCatching
            val getDisplayContent = rwc.javaClass.methods.firstOrNull {
                it.name == "getDisplayContent" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Integer.TYPE
            } ?: return@runCatching
            val dc = getDisplayContent.invoke(rwc, displayId) ?: return@runCatching
            val imeWin = fieldGet(dc, "mInputMethodWindow")
                ?: callNoArg(dc, "getImeLayeringTarget")
            if (imeWin != null) {
                val frame = callNoArg(imeWin, "getFrame") as? Rect
                    ?: fieldGet(imeWin, "mFrame") as? Rect
                    ?: fieldGet(imeWin, "mWindowFrames")?.let { fieldGet(it, "mFrame") as? Rect }
                if (frame != null && frame.height() > 0) {
                    val ( _, dh) = runCatching {
                        val bounds = windowManager.currentWindowMetrics.bounds
                        bounds.width() to bounds.height()
                    }.getOrDefault(0 to 0)
                    // Visible IME height is distance from frame top to display bottom when docked bottom.
                    if (dh > 0 && frame.top in 1 until dh) return (dh - frame.top).coerceAtLeast(0)
                    return frame.height()
                }
            }
            // ImeInsetsSourceProvider path
            val provider = callNoArg(dc, "getImeInsetsSourceProvider")
                ?: fieldGet(dc, "mImeInsetsSourceProvider")
            if (provider != null) {
                val source = callNoArg(provider, "getSource") ?: fieldGet(provider, "mSource")
                if (source != null) {
                    val visible = runCatching {
                        callNoArg(source, "isVisible") as? Boolean
                    }.getOrNull() ?: true
                    if (visible) {
                        val frame = callNoArg(source, "getFrame") as? Rect
                            ?: fieldGet(source, "mFrame") as? Rect
                        if (frame != null && frame.height() > 0) return frame.height()
                    }
                }
            }
        }
        return 0
    }

    fun isImeShowing(displayId: Int = Display.DEFAULT_DISPLAY): Boolean = imeVisibleHeight(displayId) > 0

    /** Hide soft input globally (Xiaomi drag-start clears IME). */
    fun hideSoftInput() {
        runCatching {
            val imm = systemContext.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            val methods = imm.javaClass.methods.filter { it.name.contains("hideSoftInput", ignoreCase = true) }
            // Prefer hideSoftInputFromWindow with null token fallback via InputMethodManagerInternal
            val localServices = Class.forName("com.android.server.LocalServices")
            val imiClass = runCatching {
                Class.forName("com.android.server.inputmethod.InputMethodManagerInternal")
            }.getOrNull()
            if (imiClass != null) {
                val imi = localServices.getMethod("getService", Class::class.java).invoke(null, imiClass)
                if (imi != null) {
                    val hide = imi.javaClass.methods.firstOrNull {
                        it.name == "hideCurrentInput" || it.name == "hideAllInputMethods" ||
                            it.name.startsWith("hide")
                    }
                    if (hide != null) {
                        when (hide.parameterTypes.size) {
                            0 -> hide.invoke(imi)
                            1 -> hide.invoke(imi, 0)
                            2 -> hide.invoke(imi, 0, 0)
                            else -> {}
                        }
                        return@runCatching
                    }
                }
            }
            // Public IMM path as last resort (may no-op without token).
            methods.firstOrNull { it.parameterTypes.size >= 2 }?.let { m ->
                runCatching { m.invoke(imm, null, 0) }
            }
        }.onFailure { XLog.e("hideSoftInput failed", it) }
    }


    fun activityTaskManagerBinder(): Any? = activityTaskManagerBinder

    fun activityTaskManagerService(): Any? {
        if (activityTaskManagerService != null) return activityTaskManagerService
        if (this::activityManagerService.isInitialized) {
            activityTaskManagerService = resolveAtms(activityManagerService)
        }
        return activityTaskManagerService
    }

    private fun resolveAtms(ams: Any): Any? {
        // AMS.mActivityTaskManager is usually the real ATMS instance in system_server.
        val direct = fieldGet(ams, "mActivityTaskManager")
        if (direct != null && direct.javaClass.name.contains("ActivityTaskManagerService")) {
            return direct
        }
        // Some builds expose LocalService; climb to outer ATMS.
        val local = fieldGet(ams, "mAtmInternal") ?: direct
        if (local != null) {
            val outer = fieldGet(local, "this$0")
            if (outer != null && outer.javaClass.name.contains("ActivityTaskManagerService")) {
                return outer
            }
            if (local.javaClass.name.contains("ActivityTaskManagerService")) {
                return local
            }
        }
        // Fallback: singleton-style getters
        runCatching {
            val clazz = Class.forName("com.android.server.wm.ActivityTaskManagerService")
            clazz.methods.firstOrNull {
                it.name.equals("getService", true) && it.parameterTypes.isEmpty() &&
                    java.lang.reflect.Modifier.isStatic(it.modifiers)
            }?.invoke(null)?.let { return it }
        }
        return direct
    }

    fun collapseStatusBar() {
        val stub = statusBarService ?: return
        runCatching {
            val asInterface = Class.forName("com.android.internal.statusbar.IStatusBarService\$Stub")
                .getMethod("asInterface", IBinder::class.java)
            val svc = asInterface.invoke(null, stub)
            svc.javaClass.getMethod("collapsePanels").invoke(svc)
        }.onFailure { XLog.e("collapseStatusBar failed", it) }
    }

    /** Read the active SystemUI overlay resource so freeform chrome follows the ROM theme. */
    fun systemUiDimensionPx(names: List<String>): Int? {
        val ctx = systemUiContext ?: runCatching {
            systemContext.createPackageContext(
                "com.android.systemui",
                Context.CONTEXT_IGNORE_SECURITY,
            )
        }.getOrNull()?.also { systemUiContext = it } ?: return null
        for (name in names) {
            val id = ctx.resources.getIdentifier(name, "dimen", "com.android.systemui")
            if (id == 0) continue
            val value = runCatching { ctx.resources.getDimensionPixelSize(id) }.getOrNull()
            if (value != null && value > 0) return value
        }
        return null
    }

    fun invokeAtm(method: String, vararg args: Any?): Any? {
        val binder = activityTaskManagerBinder ?: return null
        val stub = Class.forName("android.app.IActivityTaskManager\$Stub")
        val atm = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        val m = atm.javaClass.methods.firstOrNull {
            it.name == method && it.parameterTypes.size == args.size
        } ?: return null
        return m.invoke(atm, *args)
    }

    fun findMethod(clazz: Class<*>, name: String, argc: Int): Method? =
        clazz.methods.firstOrNull { it.name == name && it.parameterTypes.size == argc }

    /**
     * Resolve Task by id via RootWindowContainer.anyTaskForId (system_server only).
     */
    fun findTask(taskId: Int): Any? {
        if (taskId <= 0) return null
        val rwc = rootWindowContainer() ?: return null
        val methods = rwc.javaClass.methods.filter { it.name == "anyTaskForId" }
        // Prefer anyTaskForId(int)
        methods.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == Integer.TYPE
        }?.let { m ->
            return runCatching { m.invoke(rwc, taskId) }.getOrNull()
        }
        // anyTaskForId(int, int matchMode) — MATCH_ATTACHED_TASK_OR_RECENT_TASKS often = 2
        methods.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Integer.TYPE &&
                it.parameterTypes[1] == Integer.TYPE
        }?.let { m ->
            for (mode in intArrayOf(0, 1, 2, 3)) {
                val t = runCatching { m.invoke(rwc, taskId, mode) }.getOrNull()
                if (t != null) return t
            }
        }
        return null
    }

    /** Authoritative task bounds after WM has applied any minimum-size/display clamping. */
    fun taskBounds(taskId: Int): Rect? {
        val task = findTask(taskId) ?: return null
        val bounds = runCatching { callNoArg(task, "getBounds") as? Rect }.getOrNull()
            ?: runCatching { fieldGet(task, "mBounds") as? Rect }.getOrNull()
            ?: return null
        return Rect(bounds)
    }

    /** True only after the task's real base-application window (not StartingWindow) is drawn. */
    fun isTaskMainWindowDrawn(taskId: Int): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return@runCatching false
            val activity = callNoArg(task, "topRunningActivity")
                ?: callNoArg(task, "getTopNonFinishingActivity")
                ?: fieldGet(task, "mResumedActivity")
                ?: return@runCatching false
            val window = callNoArg(activity, "findMainWindow")
                ?: callNoArg(activity, "getMainWindow")
                ?: return@runCatching false
            val attrs = fieldGet(window, "mAttrs")
            val type = (fieldGet(attrs ?: return@runCatching false, "type") as? Int)
                ?: (callNoArg(attrs, "getType") as? Int)
                ?: 0
            if (type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
                return@runCatching false
            }
            val direct = callNoArg(window, "isDrawn") as? Boolean
                ?: callNoArg(window, "isDrawnLw") as? Boolean
            if (direct != null) return@runCatching direct
            val animator = fieldGet(window, "mWinAnimator") ?: return@runCatching false
            val drawState = fieldGet(animator, "mDrawState") as? Int ?: return@runCatching false
            drawState >= 4 // WindowStateAnimator.HAS_DRAWN
        }.getOrDefault(false)
    }

    fun rootWindowContainer(): Any? {
        val atms = activityTaskManagerService() ?: return null
        fieldGet(atms, "mRootWindowContainer")?.let { return it }
        val supervisor = fieldGet(atms, "mTaskSupervisor") ?: return null
        return fieldGet(supervisor, "mRootWindowContainer")
    }

    /**
     * Xiaomi pin hide: keep task alive + keep bounds, force-hide surface + drop always-on-top.
     * Evidence: MiuiFreeformModePinHandler.hideTask (transaction.hide(leash)) and
     * MiuiFreeFormManagerService setForceHidden(65536) — NOT offscreen resizeTask.
     * Offscreen resize can config-change / relaunch activities and lose page state.
     */
    fun hideTaskForPin(taskId: Int): Boolean {
        val task = findTask(taskId)
        if (task == null) {
            XLog.e("hideTaskForPin: task $taskId not found")
            return hideTaskFallback(taskId)
        }
        return withGlobalLock {
            var ok = false
            ok = setTaskAlwaysOnTop(task, false) || ok
            ok = setForceHidden(task, true) || ok
            // Always hide leash (Xiaomi pin path). setForceHidden alone is not enough on AOSP/MuMu.
            ok = setTaskLeashVisible(task, visible = false) || ok
            // Reorder behind home when possible (Xiaomi positionTaskBehindHome-ish)
            runCatching {
                val displayArea = callNoArg(task, "getDisplayArea") ?: callNoArg(task, "getTaskDisplayArea")
                if (displayArea != null) {
                    val m = displayArea.javaClass.methods.firstOrNull {
                        it.name == "positionTaskBehindHome" && it.parameterTypes.size == 1
                    }
                    m?.invoke(displayArea, task)
                    val m2 = displayArea.javaClass.methods.firstOrNull {
                        it.name == "positionChildAt" && it.parameterTypes.size >= 2
                    }
                    // POSITION_BOTTOM = Int.MIN_VALUE on WindowContainer
                    if (m == null && m2 != null && m2.parameterTypes.size >= 2) {
                        if (m2.parameterTypes.size == 3) {
                            m2.invoke(displayArea, Integer.MIN_VALUE, task, false)
                        }
                    }
                }
            }.onFailure { XLog.e("hideTaskForPin reorder failed", it) }
            // Ensure visibility bookkeeping updates without destroying activities.
            runCatching {
                callNoArg(task, "ensureActivitiesVisible")
            }
            ok
        }.also {
            if (!it) XLog.e("hideTaskForPin failed for $taskId")
            else XLog.d("hideTaskForPin ok task=$taskId (bounds kept, no offscreen resize)")
        }
    }

    /**
     * Reverse pin hide: show leash + clear force-hidden + always-on-top, ready for bounds restore.
     * Does not startActivity; task process/page state must remain.
     */
    fun showTaskFromPin(taskId: Int): Boolean {
        val task = findTask(taskId)
        if (task == null) {
            XLog.e("showTaskFromPin: task $taskId not found")
            return false
        }
        return withGlobalLock {
            var ok = false
            ok = setForceHidden(task, false) || ok
            ok = setTaskLeashVisible(task, visible = true) || ok
            ok = setTaskAlwaysOnTop(task, true) || ok
            runCatching {
                val m = task.javaClass.methods.firstOrNull {
                    it.name == "moveToFront" && it.parameterTypes.size <= 2
                }
                when {
                    m == null -> Unit
                    m.parameterTypes.isEmpty() -> m.invoke(task)
                    m.parameterTypes.size == 1 -> m.invoke(task, "hyper_freeform_unpin")
                    else -> m.invoke(task, "hyper_freeform_unpin", false)
                }
            }
            runCatching { callNoArg(task, "ensureActivitiesVisible") }
            ok
        }.also {
            if (!it) XLog.e("showTaskFromPin failed for $taskId")
            else XLog.d("showTaskFromPin ok task=$taskId")
        }
    }

    private fun hideTaskFallback(taskId: Int): Boolean {
        // Last resort without Task object: move to back (still keeps process; no removeTask).
        return runCatching {
            invokeAtm("moveTaskToBack", taskId, true)
            true
        }.getOrDefault(false)
    }

    /**
     * Hide/show task SurfaceControl leash.
     * Prefer Task.getSyncTransaction() (Xiaomi service path), else standalone Transaction.
     */
    private fun setTaskLeashVisible(task: Any, visible: Boolean): Boolean {
        return runCatching {
            val leash = fieldGet(task, "mSurfaceControl")
                ?: callNoArg(task, "getSurfaceControl")
                ?: return@runCatching false
            val scClass = Class.forName("android.view.SurfaceControl")
            val op = if (visible) "show" else "hide"

            // 1) Xiaomi: task.getSyncTransaction().hide/show(sc)
            val syncTx = callNoArg(task, "getSyncTransaction")
            if (syncTx != null) {
                runCatching {
                    syncTx.javaClass.getMethod(op, scClass).invoke(syncTx, leash)
                    // Sync tx is applied by WM later; also try apply if present.
                    runCatching { syncTx.javaClass.getMethod("apply").invoke(syncTx) }
                    return@runCatching true
                }
            }

            // 2) Pending transaction
            val pendingTx = callNoArg(task, "getPendingTransaction")
            if (pendingTx != null) {
                runCatching {
                    pendingTx.javaClass.getMethod(op, scClass).invoke(pendingTx, leash)
                    runCatching { pendingTx.javaClass.getMethod("apply").invoke(pendingTx) }
                    return@runCatching true
                }
            }

            // 3) Standalone SurfaceControl.Transaction
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val tx = txClass.getDeclaredConstructor().newInstance()
            txClass.getMethod(op, scClass).invoke(tx, leash)
            txClass.getMethod("apply").invoke(tx)
            true
        }.onFailure {
            XLog.e("setTaskLeashVisible visible=$visible failed", it)
        }.getOrDefault(false)
    }

    private fun setForceHidden(task: Any, hidden: Boolean): Boolean {
        val methods = task.javaClass.methods.filter { it.name == "setForceHidden" }
        // Prefer (int flags, boolean set)
        val m2 = methods.firstOrNull {
            it.parameterTypes.size == 2 &&
                it.parameterTypes[0] == Integer.TYPE &&
                it.parameterTypes[1] == java.lang.Boolean.TYPE
        }
        if (m2 != null) {
            // Try AOSP flag then MIUI flag. Prefer first successful apply.
            val flags = intArrayOf(FLAG_FORCE_HIDDEN_FOR_PINNED_TASK, FLAG_FORCE_HIDDEN_MIUI_PIN)
            for (flag in flags) {
                val applied = runCatching {
                    val r = m2.invoke(task, flag, hidden)
                    r !is Boolean || r
                }.getOrDefault(false)
                if (applied) return true
            }
            // Still attempt AOSP flag even if boolean false — method may report no-op.
            runCatching { m2.invoke(task, FLAG_FORCE_HIDDEN_FOR_PINNED_TASK, hidden) }
            return true
        }
        val m1 = methods.firstOrNull {
            it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE
        }
        if (m1 != null) {
            runCatching { m1.invoke(task, hidden) }
            return true
        }
        // No setForceHidden API — leash path is handled separately by setTaskLeashVisible.
        return false
    }

    /**
     * Keep a managed freeform root above ordinary fullscreen roots without taking focus.
     * Task.setAlwaysOnTop() also reorders it inside TaskDisplayArea; ensureActivitiesVisible then
     * updates WM visibility so a newly opened fullscreen app remains behind the small window.
     */
    fun setFreeformAlwaysOnTop(taskId: Int, onTop: Boolean): Boolean {
        val task = findTask(taskId) ?: return false
        return withGlobalLock {
            val applied = setTaskAlwaysOnTop(task, onTop)
            runCatching { callNoArg(task, "ensureActivitiesVisible") }
            if (applied) XLog.d("setFreeformAlwaysOnTop task=$taskId onTop=$onTop")
            applied
        }
    }

    private fun setTaskAlwaysOnTop(task: Any, onTop: Boolean): Boolean {
        val m = task.javaClass.methods.firstOrNull {
            it.name == "setAlwaysOnTop" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == java.lang.Boolean.TYPE
        }
        if (m != null) {
            return runCatching {
                m.invoke(task, onTop)
                true
            }.onFailure { XLog.e("Task.setAlwaysOnTop($onTop) failed", it) }
                .getOrDefault(false)
        }
        // Configuration path used by MIUI when method missing
        return runCatching {
            val getCfg = task.javaClass.methods.firstOrNull {
                it.name == "getRequestedOverrideConfiguration" && it.parameterTypes.isEmpty()
            } ?: return@runCatching false
            val cfg = getCfg.invoke(task) ?: return@runCatching false
            val winCfg = fieldGet(cfg, "windowConfiguration") ?: return@runCatching false
            val setAot = winCfg.javaClass.methods.firstOrNull {
                it.name == "setAlwaysOnTop" && it.parameterTypes.size == 1
            } ?: return@runCatching false
            setAot.invoke(winCfg, onTop)
            val apply = task.javaClass.methods.firstOrNull {
                it.name == "onRequestedOverrideConfigurationChanged" && it.parameterTypes.size == 1
            }
            apply?.invoke(task, cfg)
            true
        }.getOrDefault(false)
    }

    /**
     * Best-effort removeTask from system_server.
     * Tries ATM binder, ATMS instance, TaskSupervisor, ActivityManager, root-task removal.
     */
    fun removeTask(taskId: Int): Boolean {
        if (taskId <= 0) {
            XLog.e("removeTask invalid id=$taskId")
            return false
        }

        // Unhide first so remove paths can see the task.
        runCatching {
            findTask(taskId)?.let { setForceHidden(it, false) }
        }

        // 1) IActivityTaskManager.removeTask
        runCatching {
            val r = invokeAtm("removeTask", taskId)
            if (r is Boolean) {
                if (r) return true
            } else if (r != null) return true
        }.onFailure { XLog.e("removeTask ATM binder(1) failed", it) }
        runCatching {
            val r = invokeAtm("removeTask", taskId, 0)
            if (r is Boolean) {
                if (r) return true
            } else if (r != null) return true
        }.onFailure { XLog.e("removeTask ATM binder(2) failed", it) }

        // 2) ATMS instance removeTask / removeTaskById
        val atms = activityTaskManagerService()
        if (atms != null) {
            val removed = withGlobalLock {
                val methods = atms.javaClass.methods.filter {
                    it.name == "removeTask" || it.name == "removeTaskById"
                }
                for (m in methods) {
                    val r = runCatching {
                        when (m.parameterTypes.size) {
                            1 -> if (m.parameterTypes[0] == Integer.TYPE) m.invoke(atms, taskId) else null
                            2 -> when {
                                m.parameterTypes[0] == Integer.TYPE &&
                                    m.parameterTypes[1] == Integer.TYPE -> m.invoke(atms, taskId, 0)
                                m.parameterTypes[0] == Integer.TYPE &&
                                    m.parameterTypes[1] == java.lang.Boolean.TYPE ->
                                    m.invoke(atms, taskId, true)
                                else -> null
                            }
                            3 -> if (m.parameterTypes[0] == Integer.TYPE) {
                                // removeTaskById(taskId, killProcess, reason) variants
                                when {
                                    m.parameterTypes[1] == java.lang.Boolean.TYPE &&
                                        m.parameterTypes[2] == java.lang.Boolean.TYPE ->
                                        m.invoke(atms, taskId, true, true)
                                    m.parameterTypes[1] == java.lang.Boolean.TYPE &&
                                        m.parameterTypes[2] == String::class.java ->
                                        m.invoke(atms, taskId, true, "hyper_freeform_close")
                                    else -> null
                                }
                            } else null
                            4 -> if (m.parameterTypes[0] == Integer.TYPE) {
                                // removeTaskById(id, killProcess, removeFromRecents, reason)
                                m.invoke(atms, taskId, true, true, "hyper_freeform_close")
                            } else null
                            else -> null
                        }
                    }.getOrNull()
                    if (r is Boolean && r) return@withGlobalLock true
                    if (r != null && r !is Boolean) return@withGlobalLock true
                }
                false
            }
            if (removed) return true
        }

        // 3) TaskSupervisor.removeTask(Task) / removeRootTask(Task)
        runCatching {
            val task = findTask(taskId)
            val supervisor = atms?.let { fieldGet(it, "mTaskSupervisor") }
            if (task != null && supervisor != null) {
                val ok = withGlobalLock {
                    val mRemove = supervisor.javaClass.methods.firstOrNull {
                        it.name == "removeTask" && it.parameterTypes.size >= 1
                    }
                    if (mRemove != null) {
                        val r = when (mRemove.parameterTypes.size) {
                            1 -> mRemove.invoke(supervisor, task)
                            2 -> mRemove.invoke(supervisor, task, true)
                            3 -> mRemove.invoke(supervisor, task, true, true)
                            4 -> mRemove.invoke(supervisor, task, true, true, "hyper_freeform_close")
                            else -> mRemove.invoke(supervisor, task, true, true, "hyper_freeform_close", false)
                        }
                        if (r is Boolean) r else true
                    } else {
                        val mRoot = supervisor.javaClass.methods.firstOrNull {
                            it.name == "removeRootTask" && it.parameterTypes.size == 1
                        }
                        if (mRoot != null) {
                            mRoot.invoke(supervisor, task)
                            true
                        } else false
                    }
                }
                if (ok) return true
            }
        }.onFailure { XLog.e("removeTask via supervisor failed", it) }

        // 4) ActivityManager.removeTask
        runCatching {
            val m = activityManager.javaClass.methods.firstOrNull {
                it.name == "removeTask" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Integer.TYPE
            }
            if (m != null) {
                val r = m.invoke(activityManager, taskId)
                if (r is Boolean) {
                    if (r) return true
                } else if (r != null) return true
            }
        }.onFailure { XLog.e("removeTask via ActivityManager failed", it) }

        // 5) Finish all activities in task then remove
        runCatching {
            val task = findTask(taskId) ?: return@runCatching
            withGlobalLock {
                val top = callNoArg(task, "getTopNonFinishingActivity")
                    ?: callNoArg(task, "getTopActivity")
                if (top != null) {
                    top.javaClass.methods.filter { it.name.startsWith("finish") }.forEach { m ->
                        runCatching {
                            when (m.parameterTypes.size) {
                                0 -> m.invoke(top)
                                1 -> if (m.parameterTypes[0] == String::class.java) {
                                    m.invoke(top, "hyper_freeform_close")
                                } else Unit
                                else -> Unit
                            }
                        }
                    }
                }
                task.javaClass.methods.firstOrNull {
                    it.name == "removeImmediately" && it.parameterTypes.isEmpty()
                }?.invoke(task)
                task.javaClass.methods.firstOrNull {
                    it.name == "removeIfPossible" && it.parameterTypes.isEmpty()
                }?.invoke(task)
                true
            }
            // Re-check
            if (findTask(taskId) == null) return true
        }.onFailure { XLog.e("removeTask finish-path failed", it) }

        // 6) Verify disappearance even if APIs returned false/void
        if (findTask(taskId) == null) {
            XLog.d("removeTask $taskId already gone")
            return true
        }

        return false
    }

    /**
     * Xiaomi-like in-window DPI zoom: write [dpi] into the task's REQUESTED override configuration
     * (densityDpi + recomputed screen dp values from [bounds]) so WM sees a real config change and
     * dispatches it to the app, forcing a relayout. dpi<=0 clears the override (system density).
     */
    fun primeFreeformDensity(taskId: Int, dpi: Int): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return false
            withGlobalLock {
                val getCfg = task.javaClass.methods.firstOrNull {
                    it.name == "getRequestedOverrideConfiguration" && it.parameterTypes.isEmpty()
                } ?: return@withGlobalLock false
                val cfg = getCfg.invoke(task) ?: return@withGlobalLock false
                val density = if (dpi > 0) dpi else 0
                val field = cfg.javaClass.getField("densityDpi")
                val previous = field.getInt(cfg)
                if (previous != density) field.setInt(cfg, density)
                // Deliberately do not call onRequestedOverrideConfigurationChanged here. This is
                // used immediately before the task's mode/bounds operation, allowing WM to resolve
                // one coherent freeform configuration instead of first publishing display DPI and
                // later relaunching every Activity for the custom DPI.
                if (previous != density) {
                    XLog.i("primed freeform density task=$taskId $previous->$density")
                }
                true
            }
        }.onFailure { XLog.e("primeFreeformDensity task=$taskId failed", it) }
            .getOrDefault(false)
    }

    fun applyFreeformDensity(taskId: Int, dpi: Int, bounds: Rect): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return false
            withGlobalLock {
                val getCfg = task.javaClass.methods.firstOrNull {
                    it.name == "getRequestedOverrideConfiguration" && it.parameterTypes.isEmpty()
                } ?: return@withGlobalLock false
                val cfg = getCfg.invoke(task) ?: return@withGlobalLock false
                // Copy so we mutate a fresh config (WM diff-checks against the current one).
                val cfgCopy = runCatching {
                    cfg.javaClass.getConstructor(cfg.javaClass).newInstance(cfg)
                }.getOrDefault(cfg)
                // DENSITY_DPI_UNDEFINED = 0 → inherit system density. ONLY set densityDpi — do NOT
                // bake screenWidthDp/heightDp/smallestScreenWidthDp into the STORED override config:
                // WM recomputes those from the density + CURRENT bounds on every resolution, so
                // baking them freezes portrait dp that no longer matches after the window rotates to
                // landscape (freeform video fullscreen) → app renders its portrait layout in a
                // landscape window. Density alone is enough for the in-window DPI zoom.
                val density = if (dpi > 0) dpi else 0
                cfgCopy.javaClass.getField("densityDpi").setInt(cfgCopy, density)
                val onChanged = task.javaClass.methods.firstOrNull {
                    it.name == "onRequestedOverrideConfigurationChanged" &&
                        it.parameterTypes.size == 1
                } ?: return@withGlobalLock false
                onChanged.invoke(task, cfgCopy)
                runCatching { callNoArg(task, "ensureActivitiesVisible") }
                // Nudge the top activity to re-evaluate its configuration (dispatch to app).
                runCatching {
                    val top = callNoArg(task, "getTopNonFinishingActivity")
                        ?: callNoArg(task, "topRunningActivityLocked")
                    if (top != null) {
                        top.javaClass.methods.firstOrNull {
                            it.name == "ensureActivityConfiguration" && it.parameterTypes.size <= 2
                        }?.let { m ->
                            when (m.parameterTypes.size) {
                                0 -> m.invoke(top)
                                1 -> m.invoke(top, 0)
                                else -> m.invoke(top, 0, false)
                            }
                        }
                    }
                }
                // ensureActivityConfiguration dispatches the new resolved configuration and, when
                // the app cannot handle density changes, performs ActivityRecord's normal relaunch.
                // Do not restartProcessIfVisible here: killing the whole process briefly removes
                // every window owned by that app and made an unrelated open freeform disappear.
                XLog.d("applyFreeformDensity task=$taskId dpi=$density bounds=$bounds")
                true
            }
        }.onFailure { XLog.e("applyFreeformDensity task=$taskId failed", it) }
            .getOrDefault(false)
    }

    /**
     * The top activity's WM-RESOLVED window bounds. After a task resize commit, WM may re-resolve
     * the leaf activity LARGER than the task (per-app min size / aspect enforcement — e.g. bilibili
     * re-expands to an 880px-wide floor) → content overflows the chrome frame. Read this back to
     * detect and adopt the enforced size.
     */
    fun topActivityResolvedBounds(taskId: Int): Rect? {
        return runCatching {
            val task = findTask(taskId) ?: return null
            val top = callNoArg(task, "getTopNonFinishingActivity")
                ?: callNoArg(task, "topRunningActivityLocked")
                ?: return null
            val cfg = callNoArg(top, "getConfiguration") ?: return null
            val wc = fieldGet(cfg, "windowConfiguration") ?: return null
            val b = callNoArg(wc, "getBounds") as? Rect ?: return null
            if (b.width() > 0 && b.height() > 0) Rect(b) else null
        }.getOrNull()
    }

    /**
     * The top activity's REQUESTED screen orientation (ActivityInfo.screenOrientation constant).
     * Used to auto-restore a landscape freeform when the app leaves its landscape activity WITHOUT
     * calling setRequestedOrientation (e.g. bilibili back-navigates from the fullscreen video to a
     * portrait home activity — the window would otherwise stay landscape).
     */
    fun topActivityRequestedOrientation(taskId: Int): Int? {
        return runCatching {
            val task = findTask(taskId) ?: return null
            val top = callNoArg(task, "getTopNonFinishingActivity")
                ?: callNoArg(task, "topRunningActivityLocked")
                ?: return null
            // ActivityRecord.getOverrideOrientation() is the requested screenOrientation constant
            // on API 34/35 (there is no getRequestedOrientation on ActivityRecord).
            (runCatching { callNoArg(top, "getOverrideOrientation") }.getOrNull() as? Int)
                ?: (runCatching { callNoArg(top, "getRequestedOrientation") }.getOrNull() as? Int)
                ?: (fieldGet(top, "mOrientation") as? Int)
        }.getOrNull()
    }

    /**
     * Debug/test helper: set the top activity's requested screenOrientation constant on its
     * ActivityRecord (what bilibili's real fullscreen button does via Activity.setRequestedOrientation).
     * Makes [topActivityRequestedOrientation] report it so the auto-restore poll matches the real flow.
     */
    fun setTopActivityRequestedOrientation(taskId: Int, orientation: Int): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return false
            val top = callNoArg(task, "getTopNonFinishingActivity")
                ?: callNoArg(task, "topRunningActivityLocked")
                ?: return false
            withGlobalLock {
                val m = top.javaClass.methods.firstOrNull {
                    it.name == "setRequestedOrientation" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Integer.TYPE
                } ?: top.javaClass.methods.firstOrNull {
                    it.name == "setOrientation" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Integer.TYPE
                }
                if (m != null) { m.invoke(top, orientation); true } else false
            }
        }.onFailure { XLog.e("setTopActivityRequestedOrientation failed", it) }.getOrDefault(false)
    }

    fun resizeTask(taskId: Int, bounds: Rect, resizeMode: Int = 0): Boolean {
        // Managed freeforms are intentionally allowed to be smaller than AOSP's 220dp
        // default_minimal_size_resizable_task.  If the Task keeps that default, WM expands only
        // one axis while our chrome keeps the requested rectangle, so the app surface and mask
        // can never share one aspect ratio (especially on a landscape display).
        relaxTaskMinDimensions(taskId)

        // 1) ActivityManager.resizeTask
        runCatching {
            val methods = activityManager.javaClass.methods.filter { it.name == "resizeTask" }
            val m3 = methods.firstOrNull { it.parameterTypes.size == 3 }
            if (m3 != null) {
                m3.invoke(activityManager, taskId, bounds, resizeMode)
                return true
            }
            val m2 = methods.firstOrNull { it.parameterTypes.size == 2 }
            if (m2 != null) {
                m2.invoke(activityManager, taskId, bounds)
                return true
            }
        }.onFailure { XLog.e("resizeTask AM failed", it) }

        // 2) ATM binder
        runCatching {
            val r = invokeAtm("resizeTask", taskId, bounds, resizeMode)
            if (r != null) return true
        }

        // 3) Direct Task bounds via configuration (pin offscreen may need this)
        runCatching {
            val task = findTask(taskId) ?: return@runCatching
            withGlobalLock {
                val setBounds = task.javaClass.methods.firstOrNull {
                    it.name == "setBounds" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Rect::class.java
                }
                if (setBounds != null) {
                    setBounds.invoke(task, bounds)
                    return@withGlobalLock true
                }
                val getCfg = task.javaClass.methods.firstOrNull {
                    it.name == "getRequestedOverrideConfiguration" && it.parameterTypes.isEmpty()
                } ?: return@withGlobalLock false
                val cfg = getCfg.invoke(task) ?: return@withGlobalLock false
                val winCfg = fieldGet(cfg, "windowConfiguration") ?: return@withGlobalLock false
                winCfg.javaClass.methods.firstOrNull {
                    it.name == "setBounds" && it.parameterTypes.size == 1
                }?.invoke(winCfg, bounds)
                task.javaClass.methods.firstOrNull {
                    it.name == "onRequestedOverrideConfigurationChanged" && it.parameterTypes.size == 1
                }?.invoke(task, cfg)
                true
            }
            return true
        }.onFailure { XLog.e("resizeTask direct failed", it) }

        return false
    }

    /**
     * Disable the per-Task fallback minimum for a module-managed freeform.
     *
     * Task.adjustForMinimalTaskDimensions() uses mDefaultMinSize only while mMinWidth/mMinHeight
     * are negative.  Explicit zeroes retain any bounds we request without changing the device's
     * global resource or the policy for unrelated tasks.
     */
    fun relaxTaskMinDimensions(taskId: Int): Boolean {
        val task = findTask(taskId) ?: return false
        return withGlobalLock {
            val oldWidth = fieldGet(task, "mMinWidth") as? Int
            val oldHeight = fieldGet(task, "mMinHeight") as? Int
            val widthSet = fieldSetInt(task, "mMinWidth", 0)
            val heightSet = fieldSetInt(task, "mMinHeight", 0)
            val ok = widthSet && heightSet
            if (ok && (oldWidth != 0 || oldHeight != 0)) {
                XLog.i(
                    "relaxTaskMinDimensions task=$taskId " +
                        "min=${oldWidth ?: "?"}x${oldHeight ?: "?"} -> 0x0",
                )
            } else if (!ok) {
                XLog.e(
                    "relaxTaskMinDimensions fields unavailable task=$taskId " +
                        "class=${task.javaClass.name}",
                )
            }
            ok
        }
    }

    private fun withGlobalLock(block: () -> Boolean): Boolean {
        val atms = activityTaskManagerService()
        val lock = atms?.let {
            fieldGet(it, "mGlobalLock")
                ?: runCatching { callNoArg(it, "getGlobalLock") }.getOrNull()
        }
        return if (lock != null) {
            synchronized(lock) { block() }
        } else {
            block()
        }
    }


    /**
     * Xiaomi freeform visual: corner radius + crop (+ optional shadow) on task leash.
     * Uses SurfaceControl.Transaction from system_server (native freeform, not VD).
     */
    fun applyFreeformSurfaceStyle(taskId: Int, bounds: Rect, isMini: Boolean): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return@runCatching false
            val leash = fieldGet(task, "mSurfaceControl")
                ?: callNoArg(task, "getSurfaceControl")
                ?: return@runCatching false
            val scClass = Class.forName("android.view.SurfaceControl")
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val tx = txClass.getDeclaredConstructor().newInstance()
            val radius = io.hyper.freeform.xposed.policy.FreeformPolicy
                .freeformVisibleCornerRadiusPx(isMini, bounds.width(), bounds.height())
            runCatching {
                txClass.getMethod("setCornerRadius", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, radius)
            }
            // Crop to task bounds so radius clips content.
            val w = bounds.width().coerceAtLeast(1)
            val h = bounds.height().coerceAtLeast(1)
            val cropOk = runCatching {
                txClass.getMethod("setWindowCrop", scClass, Integer.TYPE, Integer.TYPE)
                    .invoke(tx, leash, w, h)
                true
            }.getOrDefault(false) || runCatching {
                txClass.getMethod("setWindowCrop", scClass, Rect::class.java)
                    .invoke(tx, leash, Rect(0, 0, w, h))
                true
            }.getOrDefault(false)
            // Best-effort soft shadow (may no-op on some builds).
            val shadow = io.hyper.freeform.xposed.policy.FreeformPolicy.freeformShadowRadiusPx(isMini)
            runCatching {
                txClass.getMethod("setShadowRadius", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, shadow)
            }
            txClass.getMethod("apply").invoke(tx)
            XLog.d(
                "surfaceStyle task=$taskId mini=$isMini r=$radius crop=$cropOk shadow=$shadow"
            )
            true
        }.onFailure {
            XLog.e("applyFreeformSurfaceStyle failed task=$taskId", it)
        }.getOrDefault(false)
    }

    /**
     * Xiaomi visual-scale freeform: keep [sourceBounds] as the Task configuration and render it
     * into [visualBounds] using the task leash. This preserves the app's normal layout — used for
     * MINI ([miniStyle]=true) and for scaled NORMAL windows (缩放小窗, [miniStyle]=false; the task
     * never resizes below WM's minimal-task floor and the aspect ratio is inherently preserved).
     */
    fun applyMiniFreeformVisual(
        taskId: Int,
        sourceBounds: Rect,
        visualBounds: Rect,
        miniStyle: Boolean = true,
    ): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return@runCatching false
            val leash = fieldGet(task, "mSurfaceControl")
                ?: callNoArg(task, "getSurfaceControl")
                ?: return@runCatching false
            val scClass = Class.forName("android.view.SurfaceControl")
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val tx = txClass.getDeclaredConstructor().newInstance()
            val sourceW = sourceBounds.width().coerceAtLeast(1)
            val sourceH = sourceBounds.height().coerceAtLeast(1)
            val sx = visualBounds.width().coerceAtLeast(1).toFloat() / sourceW
            val sy = visualBounds.height().coerceAtLeast(1).toFloat() / sourceH
            val scale = minOf(sx, sy).coerceIn(0.1f, 1f)
            leashSetScale(txClass, scClass, tx, leash, scale, scale)
            runCatching {
                txClass.getMethod(
                    "setPosition",
                    scClass,
                    java.lang.Float.TYPE,
                    java.lang.Float.TYPE,
                ).invoke(tx, leash, visualBounds.left.toFloat(), visualBounds.top.toFloat())
            }
            runCatching {
                txClass.getMethod("setWindowCrop", scClass, Integer.TYPE, Integer.TYPE)
                    .invoke(tx, leash, sourceW, sourceH)
            }.recoverCatching {
                txClass.getMethod("setWindowCrop", scClass, Rect::class.java)
                    .invoke(tx, leash, Rect(0, 0, sourceW, sourceH))
            }
            val visibleRadius = io.hyper.freeform.xposed.policy.FreeformPolicy
                .freeformVisibleCornerRadiusPx(
                    miniStyle,
                    visualBounds.width(),
                    visualBounds.height(),
                )
            val leashRadius = io.hyper.freeform.xposed.policy.FreeformPolicy
                .freeformLeashCornerRadiusPx(
                    miniStyle,
                    visualBounds.width(),
                    visualBounds.height(),
                    scale,
                )
            runCatching {
                txClass.getMethod("setCornerRadius", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, leashRadius)
            }
            runCatching {
                txClass.getMethod("setShadowRadius", scClass, java.lang.Float.TYPE)
                    .invoke(
                        tx,
                        leash,
                        io.hyper.freeform.xposed.policy.FreeformPolicy
                            .freeformShadowRadiusPx(miniStyle) / scale,
                    )
            }
            txClass.getMethod("apply").invoke(tx)
            XLog.d(
                "scaledVisual task=$taskId mini=$miniStyle source=$sourceBounds " +
                    "visual=$visualBounds scale=$scale visibleRadius=$visibleRadius " +
                    "leashRadius=$leashRadius",
            )
            true
        }.onFailure {
            XLog.e("applyMiniFreeformVisual task=$taskId failed", it)
        }.getOrDefault(false)
    }

    /**
     * Xiaomi freeform → fullscreen exit on Task object:
     * setAlwaysOnTop(false), setWindowingMode(0/UNDEFINED or 1/FULLSCREEN),
     * clear override bounds (null), keep task alive.
     * Evidence: MiuiFreeFormManagerService.exitFreeformIfEnterSplitScreen / moveTaskToBack.
     */
    fun exitTaskToFullscreen(taskId: Int, normalLaunchPreparing: Boolean = false): Boolean {
        val task = findTask(taskId)
        if (task == null) {
            XLog.e("exitTaskToFullscreen: task $taskId not found")
            // Binder fallback
            runCatching { invokeAtm("setTaskWindowingMode", taskId, 1, true) }
            runCatching {
                val (dw, dh) = run {
                    val dm = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                    val m = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    dm.getRealMetrics(m)
                    m.widthPixels to m.heightPixels
                }
                resizeTask(taskId, Rect(0, 0, dw, dh), 0)
            }
            return false
        }
        return withGlobalLock {
            var ok = false
            ok = setTaskAlwaysOnTop(task, false) || ok

            // Clear override bounds first (Xiaomi setBounds(null)).
            runCatching {
                val setBounds = task.javaClass.methods.firstOrNull {
                    it.name == "setBounds" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Rect::class.java
                }
                setBounds?.invoke(task, null as Rect?)
                ok = true
            }.onFailure { XLog.e("exitTaskToFullscreen setBounds(null) failed", it) }

            // Prefer setWindowingMode(0=UNDEFINED inherit) then 1=FULLSCREEN.
            val setMode = task.javaClass.methods.firstOrNull {
                it.name == "setWindowingMode" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Integer.TYPE
            }
            if (setMode != null) {
                val applied = runCatching {
                    setMode.invoke(task, 0) // WINDOWING_MODE_UNDEFINED
                    true
                }.getOrDefault(false) || runCatching {
                    setMode.invoke(task, 1) // WINDOWING_MODE_FULLSCREEN
                    true
                }.getOrDefault(false)
                ok = applied || ok
            }

            // Configuration path (Xiaomi exitFreeformIfEnterSplitScreen).
            runCatching {
                val getCfg = task.javaClass.methods.firstOrNull {
                    it.name == "getRequestedOverrideConfiguration" && it.parameterTypes.isEmpty()
                } ?: return@runCatching
                val cfg = getCfg.invoke(task) ?: return@runCatching
                // Copy configuration
                val cfgCopy = runCatching {
                    cfg.javaClass.getConstructor(cfg.javaClass).newInstance(cfg)
                }.getOrElse { cfg }
                val winCfg = fieldGet(cfgCopy, "windowConfiguration") ?: return@runCatching
                winCfg.javaClass.methods.firstOrNull {
                    it.name == "setAlwaysOnTop" && it.parameterTypes.size == 1
                }?.invoke(winCfg, false)
                winCfg.javaClass.methods.firstOrNull {
                    it.name == "setWindowingMode" && it.parameterTypes.size == 1
                }?.invoke(winCfg, 0)
                winCfg.javaClass.methods.firstOrNull {
                    it.name == "setBounds" && it.parameterTypes.size == 1
                }?.invoke(winCfg, null as Rect?)
                // The DPI override belongs to the small-window presentation. A normal/fullscreen
                // launch must inherit the display density again instead of keeping (for example)
                // a 60% freeform density across the full screen.
                runCatching { cfgCopy.javaClass.getField("densityDpi").setInt(cfgCopy, 0) }
                task.javaClass.methods.firstOrNull {
                    it.name == "onRequestedOverrideConfigurationChanged" && it.parameterTypes.size == 1
                }?.invoke(task, cfgCopy)
                ok = true
            }.onFailure { XLog.e("exitTaskToFullscreen config path failed", it) }

            // A normal ActivityStarter launch already owns the pending transition. Avoid a nested
            // ATM Binder mode request from its hook; the direct Task/config paths above are enough.
            if (!normalLaunchPreparing) {
                runCatching { invokeAtm("setTaskWindowingMode", taskId, 1, true) }
            }

            // Expand to full display as visual fallback if still freeform-sized.
            runCatching {
                val dm = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                dm.getRealMetrics(m)
                val full = Rect(0, 0, m.widthPixels, m.heightPixels)
                val setBounds = task.javaClass.methods.firstOrNull {
                    it.name == "setBounds" && it.parameterTypes.size == 1
                }
                // Only if mode switch failed to clear bounds — set full display bounds.
                setBounds?.invoke(task, full)
            }

            if (!normalLaunchPreparing) {
                runCatching {
                    val m = task.javaClass.methods.firstOrNull {
                        it.name == "moveToFront" && it.parameterTypes.size <= 2
                    }
                    when {
                        m == null -> Unit
                        m.parameterTypes.isEmpty() -> m.invoke(task)
                        m.parameterTypes.size == 1 -> m.invoke(task, "hyper_freeform_fullscreen")
                        else -> m.invoke(task, "hyper_freeform_fullscreen", false)
                    }
                }
            }
            ok
        }.also {
            if (!it) XLog.e("exitTaskToFullscreen failed task=$taskId")
            else XLog.d("exitTaskToFullscreen ok task=$taskId")
        }
    }


    /**
     * Freeform → split (Xiaomi MulWinSwitch startFreeformToSplit WCT core on AOSP).
     *
     * Xiaomi shell path: setAlwaysOnTop(false) + SoSc prepareEnter/moveToStage + transition.
     * AOSP port without SoSc:
     *   1) setAlwaysOnTop(false)
     *   2) setWindowingMode(MULTI_WINDOW=6)
     *   3) setBounds(half screen for position)
     *   4) best-effort reparent under existing multi-window stage root
     *   5) keep task alive (page state keep)
     *
     * @param position 0=top/left, 1=bottom/right
     */
    fun exitTaskToSplit(taskId: Int, position: Int): Boolean {
        val task = findTask(taskId)
        if (task == null) {
            XLog.e("exitTaskToSplit: task $taskId not found")
            runCatching { invokeAtm("setTaskWindowingMode", taskId, 6, true) }
            runCatching {
                val half = splitHalfRect(position)
                resizeTask(taskId, half, 0)
            }
            return false
        }
        val half = splitHalfRect(position)
        return withGlobalLock {
            var ok = false
            ok = setTaskAlwaysOnTop(task, false) || ok

            // Prefer setWindowingMode(MULTI_WINDOW=6), then FULLSCREEN half as last resort.
            val setMode = task.javaClass.methods.firstOrNull {
                it.name == "setWindowingMode" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Integer.TYPE
            }
            if (setMode != null) {
                val applied = runCatching {
                    setMode.invoke(task, 6)
                    true
                }.onFailure {
                    XLog.e("exitTaskToSplit setWindowingMode(6) failed", it)
                }.getOrDefault(false)
                if (!applied) {
                    runCatching { setMode.invoke(task, 1) }
                } else {
                    ok = true
                }
            } else {
                runCatching { invokeAtm("setTaskWindowingMode", taskId, 6, true) }
                    .onSuccess { ok = true }
            }

            // Apply half-screen bounds (Xiaomi split stage geometry without SoSc divider anim).
            runCatching {
                val setBounds = task.javaClass.methods.firstOrNull {
                    it.name == "setBounds" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Rect::class.java
                }
                setBounds?.invoke(task, half)
                ok = true
            }.onFailure { XLog.e("exitTaskToSplit setBounds failed", it) }

            // Best-effort reparent into existing multi-window stage root (AOSP split stages).
            runCatching { reparentToSplitStage(task, position) }
                .onFailure { XLog.e("exitTaskToSplit reparent skipped", it) }

            // Config path mirror (some builds only honor override configuration).
            runCatching {
                val getCfg = task.javaClass.methods.firstOrNull {
                    it.name == "getRequestedOverrideConfiguration" && it.parameterTypes.isEmpty()
                } ?: return@runCatching
                val cfg = getCfg.invoke(task) ?: return@runCatching
                val winCfg = fieldGet(cfg, "windowConfiguration") ?: return@runCatching
                runCatching {
                    winCfg.javaClass.methods.firstOrNull {
                        it.name == "setWindowingMode" && it.parameterTypes.size == 1
                    }?.invoke(winCfg, 6)
                }
                runCatching {
                    winCfg.javaClass.methods.firstOrNull {
                        it.name == "setBounds" && it.parameterTypes.size == 1
                    }?.invoke(winCfg, half)
                }
                runCatching {
                    winCfg.javaClass.methods.firstOrNull {
                        it.name == "setAlwaysOnTop" && it.parameterTypes.size == 1
                    }?.invoke(winCfg, false)
                }
                task.javaClass.methods.firstOrNull {
                    it.name == "onRequestedOverrideConfigurationChanged" && it.parameterTypes.size == 1
                }?.invoke(task, cfg)
                ok = true
            }.onFailure { XLog.e("exitTaskToSplit config path failed", it) }

            runCatching { callNoArg(task, "ensureActivitiesVisible") }
            runCatching {
                val m = task.javaClass.methods.firstOrNull {
                    it.name == "moveToFront" && it.parameterTypes.size <= 2
                }
                when {
                    m == null -> Unit
                    m.parameterTypes.isEmpty() -> m.invoke(task)
                    m.parameterTypes.size == 1 -> m.invoke(task, "hyper_freeform_to_split")
                    else -> m.invoke(task, "hyper_freeform_to_split", false)
                }
            }
            // Keep the split leaf focusable/visible after leaving freeform alwaysOnTop.
            runCatching {
                task.javaClass.methods.firstOrNull {
                    it.name == "setFocusable" && it.parameterTypes.size == 1
                }?.invoke(task, true)
            }
            runCatching { invokeAtm("setFocusedTask", taskId) }
            runCatching {
                val atms = activityTaskManagerService() ?: return@runCatching
                atms.javaClass.methods.firstOrNull {
                    it.name == "setFocusedTask" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Integer.TYPE
                }?.invoke(atms, taskId)
            }
            ok
        }.also {
            if (!it) XLog.e("exitTaskToSplit failed task=$taskId pos=$position")
            else XLog.d("exitTaskToSplit ok task=$taskId pos=$position bounds=$half")
        }
    }

    private fun splitHalfRect(position: Int): Rect {
        val dm = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val m = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        dm.getRealMetrics(m)
        val w = m.widthPixels
        val h = m.heightPixels
        val landscape = w > h
        return if (landscape) {
            val mid = w / 2
            if (position == 1) Rect(mid, 0, w, h) else Rect(0, 0, mid, h)
        } else {
            val mid = h / 2
            if (position == 1) Rect(0, mid, w, h) else Rect(0, 0, w, mid)
        }
    }

    /**
     * Reparent task under AOSP split multi-window stage root when present.
     * Evidence: dumpsys shows root Task#3 with multi-window children stages.
     */
    private fun reparentToSplitStage(task: Any, position: Int): Boolean {
        val rwc = rootWindowContainer() ?: return false
        // Find multi-window stage roots (AOSP dumpsys: empty mode=multi-window under split root).
        val candidates = mutableListOf<Any>()
        fun walk(node: Any, depth: Int) {
            if (depth > 8) return
            if (node === task) return
            val mode = runCatching {
                node.javaClass.methods.firstOrNull {
                    it.name == "getWindowingMode" && it.parameterTypes.isEmpty()
                }?.invoke(node) as? Int
            }.getOrNull()
            val childCount = runCatching {
                node.javaClass.methods.firstOrNull {
                    it.name == "getChildCount" && it.parameterTypes.isEmpty()
                }?.invoke(node) as? Int
            }.getOrNull() ?: 0
            // Prefer empty multi-window stage roots; also accept low-child stage parents.
            if (mode == 6 && childCount <= 1 && node.javaClass.name.contains(".Task")) {
                candidates.add(node)
            }
            for (i in 0 until childCount) {
                val child = runCatching {
                    node.javaClass.methods.firstOrNull {
                        it.name == "getChildAt" && it.parameterTypes.size == 1
                    }?.invoke(node, i)
                }.getOrNull() ?: continue
                walk(child, depth + 1)
            }
        }
        walk(rwc, 0)
        // Prefer empty stages first.
        val ordered = candidates.sortedBy { c ->
            runCatching {
                c.javaClass.methods.firstOrNull {
                    it.name == "getChildCount" && it.parameterTypes.isEmpty()
                }?.invoke(c) as? Int
            }.getOrNull() ?: 0
        }
        if (ordered.isEmpty()) return false
        val parent = if (position == 1 && ordered.size > 1) ordered[1] else ordered[0]
        // Empty AOSP stage roots under the dormant split root are often invisible.
        // Reparenting into them hides the task (MuMu evidence). Only reparent when parent is live.
        val parentVisible = runCatching {
            val m = parent.javaClass.methods.firstOrNull {
                it.name == "isVisible" && it.parameterTypes.isEmpty()
            } ?: parent.javaClass.methods.firstOrNull {
                it.name == "isVisibleRequested" && it.parameterTypes.isEmpty()
            }
            m?.invoke(parent) as? Boolean
        }.getOrNull()
        if (parentVisible != true) {
            XLog.d(
                "reparentToSplitStage skip: stage parent not visible " +
                    "(visible=$parentVisible) — keep task as root multi-window half"
            )
            return false
        }

        // Pick reparent overload whose 1st arg accepts the stage Task.
        // AOSP Task has reparent(TaskDisplayArea, boolean) — must NOT pick that for Task parent.
        // Preferred: Task.reparent(Task, int, boolean, String) or WindowContainer.reparent(WC, int).
        val methods = linkedSetOf<java.lang.reflect.Method>()
        var cls: Class<*>? = task.javaClass
        while (cls != null) {
            methods.addAll(cls.declaredMethods.filter { it.name == "reparent" })
            methods.addAll(cls.methods.filter { it.name == "reparent" })
            cls = cls.superclass
        }
        val compatible = methods.filter { m ->
            val pts = m.parameterTypes
            pts.isNotEmpty() &&
                !pts[0].name.contains("TaskDisplayArea") &&
                pts[0].isAssignableFrom(parent.javaClass)
        }.sortedByDescending { it.parameterTypes.size }

        var lastError: Throwable? = null
        for (reparent in compatible) {
            val ok = runCatching {
                reparent.isAccessible = true
                val pts = reparent.parameterTypes
                val args = arrayOfNulls<Any>(pts.size)
                args[0] = parent
                for (i in 1 until pts.size) {
                    val pt = pts[i]
                    args[i] = when {
                        pt == Integer.TYPE || pt == Integer::class.java -> Integer.MAX_VALUE
                        pt == java.lang.Boolean.TYPE || pt == java.lang.Boolean::class.java -> true
                        pt == String::class.java -> "hyper_freeform_to_split"
                        else -> null
                    }
                }
                reparent.invoke(task, *args)
                true
            }.onFailure {
                lastError = it
            }.getOrDefault(false)
            if (ok) {
                XLog.i(
                    "reparentToSplitStage ok via ${reparent.declaringClass.simpleName}." +
                        "reparent(${reparent.parameterTypes.joinToString { it.simpleName }}) " +
                        "parent=${parent.javaClass.simpleName}"
                )
                return true
            }
        }
        if (lastError != null) {
            XLog.e("reparentToSplitStage failed", lastError)
        } else {
            XLog.d(
                "reparentToSplitStage: no compatible reparent for parent=" +
                    parent.javaClass.name + " methods=" +
                    methods.joinToString { m ->
                        m.parameterTypes.joinToString(prefix = "reparent(", postfix = ")") { it.simpleName }
                    }
            )
        }
        return false
    }


    /**
     * Xiaomi freeform→pin Folme shrink lite on task leash (setPinAnimInfo-ish).
     * progress 0 = full freeform, 1 = ~bubble size at pin edge.
     * Does NOT change Task bounds (page state keep); visual only until hide/interrupt.
     */
    fun applyPinShrinkVisual(
        taskId: Int,
        sourceBounds: Rect,
        frame: io.hyper.freeform.xposed.policy.FreeformPolicy.WindowVisualFrame,
        progress: Float,
    ): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return@runCatching false
            val leash = fieldGet(task, "mSurfaceControl")
                ?: callNoArg(task, "getSurfaceControl")
                ?: return@runCatching false
            val scClass = Class.forName("android.view.SurfaceControl")
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val tx = txClass.getDeclaredConstructor().newInstance()
            val p = progress.coerceIn(0f, 1f)
            val srcW = sourceBounds.width().coerceAtLeast(1).toFloat()
            val srcH = sourceBounds.height().coerceAtLeast(1).toFloat()
            val scale = minOf(frame.width / srcW, frame.height / srcH).coerceIn(0.05f, 1.5f)
            leashSetScale(txClass, scClass, tx, leash, scale, scale)
            runCatching {
                txClass.getMethod(
                    "setPosition",
                    scClass,
                    java.lang.Float.TYPE,
                    java.lang.Float.TYPE,
                ).invoke(tx, leash, frame.left, frame.top)
            }
            runCatching {
                txClass.getMethod("setAlpha", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, frame.alpha)
            }
            val cropW = srcW.toInt().coerceAtLeast(1)
            val cropH = srcH.toInt().coerceAtLeast(1)
            runCatching {
                txClass.getMethod("setWindowCrop", scClass, Rect::class.java)
                    .invoke(tx, leash, Rect(0, 0, cropW, cropH))
            }.recoverCatching {
                txClass.getMethod("setWindowCrop", scClass, Integer.TYPE, Integer.TYPE)
                    .invoke(tx, leash, cropW, cropH)
            }
            val radius = io.hyper.freeform.xposed.policy.FreeformPolicy.freeformCornerRadiusPx(false)
            val density = SystemServices.systemContext.resources.displayMetrics.density
            val bubble = 64f * density
            val endRadius = bubble * 0.28f
            val visibleRadius = radius + (endRadius - radius) * p
            runCatching {
                txClass.getMethod("setCornerRadius", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, visibleRadius / scale)
            }
            txClass.getMethod("apply").invoke(tx)
            true
        }.onFailure {
            XLog.e("applyPinShrinkVisual task=$taskId p=$progress failed", it)
        }.getOrDefault(false)
    }

    /**
     * Xiaomi window-close animation: shrink the task leash slightly toward its center and fade it
     * out (WINDOW_CLOSE_ALPHA_EASE). [progress] 0→1; caller removes the task when it reaches 1.
     */
    /**
     * Hide the task leash outright (alpha 0 + hide) right before removeTask on the animated-close
     * path, so the AOSP/Shell freeform close transition has nothing visible to animate → no flash
     * of the default close animation at the end of our custom shrink+fade.
     */
    fun hideTaskLeashForClose(taskId: Int): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return@runCatching false
            val leash = fieldGet(task, "mSurfaceControl")
                ?: callNoArg(task, "getSurfaceControl")
                ?: return@runCatching false
            val scClass = Class.forName("android.view.SurfaceControl")
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val tx = txClass.getDeclaredConstructor().newInstance()
            runCatching {
                txClass.getMethod("setAlpha", scClass, java.lang.Float.TYPE).invoke(tx, leash, 0f)
            }
            runCatching { txClass.getMethod("hide", scClass).invoke(tx, leash) }
            txClass.getMethod("apply").invoke(tx)
            true
        }.onFailure { XLog.e("hideTaskLeashForClose task=$taskId failed", it) }
            .getOrDefault(false)
    }

    fun applyCloseVisual(
        taskId: Int,
        sourceBounds: Rect,
        frame: io.hyper.freeform.xposed.policy.FreeformPolicy.WindowVisualFrame,
    ): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return@runCatching false
            val leash = fieldGet(task, "mSurfaceControl")
                ?: callNoArg(task, "getSurfaceControl")
                ?: return@runCatching false
            val scClass = Class.forName("android.view.SurfaceControl")
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val tx = txClass.getDeclaredConstructor().newInstance()
            val srcW = sourceBounds.width().coerceAtLeast(1).toFloat()
            val srcH = sourceBounds.height().coerceAtLeast(1).toFloat()
            val scale = minOf(frame.width / srcW, frame.height / srcH).coerceIn(0.05f, 1.5f)
            leashSetScale(txClass, scClass, tx, leash, scale, scale)
            runCatching {
                txClass.getMethod("setPosition", scClass, java.lang.Float.TYPE, java.lang.Float.TYPE)
                    .invoke(tx, leash, frame.left, frame.top)
            }
            runCatching {
                txClass.getMethod("setAlpha", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, frame.alpha)
            }
            txClass.getMethod("apply").invoke(tx)
            true
        }.onFailure { XLog.e("applyCloseVisual task=$taskId failed", it) }
            .getOrDefault(false)
    }

    fun applyOpenVisual(
        taskId: Int,
        sourceBounds: Rect,
        frame: io.hyper.freeform.xposed.policy.FreeformPolicy.WindowVisualFrame,
    ): Boolean = applyCloseVisual(taskId, sourceBounds, frame)

    /**
     * Restore leash after pin-anim interrupt / before unpin show.
     * Identity matrix + original freeform position + full alpha/crop/radius.
     */
    fun resetPinShrinkVisual(taskId: Int, bounds: Rect): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return@runCatching false
            val leash = fieldGet(task, "mSurfaceControl")
                ?: callNoArg(task, "getSurfaceControl")
                ?: return@runCatching false
            val scClass = Class.forName("android.view.SurfaceControl")
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val tx = txClass.getDeclaredConstructor().newInstance()
            leashSetScale(txClass, scClass, tx, leash, 1f, 1f)
            runCatching {
                txClass.getMethod(
                    "setPosition",
                    scClass,
                    java.lang.Float.TYPE,
                    java.lang.Float.TYPE,
                ).invoke(tx, leash, bounds.left.toFloat(), bounds.top.toFloat())
            }
            runCatching {
                txClass.getMethod("setAlpha", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, 1f)
            }
            val w = bounds.width().coerceAtLeast(1)
            val h = bounds.height().coerceAtLeast(1)
            runCatching {
                txClass.getMethod("setWindowCrop", scClass, Integer.TYPE, Integer.TYPE)
                    .invoke(tx, leash, w, h)
            }.recoverCatching {
                txClass.getMethod("setWindowCrop", scClass, Rect::class.java)
                    .invoke(tx, leash, Rect(0, 0, w, h))
            }
            val radius = io.hyper.freeform.xposed.policy.FreeformPolicy
                .freeformVisibleCornerRadiusPx(false, w, h)
            runCatching {
                txClass.getMethod("setCornerRadius", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, radius)
            }
            txClass.getMethod("apply").invoke(tx)
            true
        }.onFailure {
            XLog.e("resetPinShrinkVisual task=$taskId failed", it)
        }.getOrDefault(false)
    }

    /**
     * Visual-only freeform corner-resize (Xiaomi live path lite).
     * Scales/positions the task leash from [baseBounds] toward [visualBounds]
     * WITHOUT calling resizeTask — avoids config/surface thrash every MOVE.
     * Commit real bounds via resizeTask on gesture UP, then clearLiveResizeVisual.
     */
    fun applyLiveResizeVisual(taskId: Int, baseBounds: Rect, visualBounds: Rect): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return@runCatching false
            val leash = fieldGet(task, "mSurfaceControl")
                ?: callNoArg(task, "getSurfaceControl")
                ?: return@runCatching false
            val scClass = Class.forName("android.view.SurfaceControl")
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val tx = txClass.getDeclaredConstructor().newInstance()
            val bw = baseBounds.width().coerceAtLeast(1).toFloat()
            val bh = baseBounds.height().coerceAtLeast(1).toFloat()
            val vw = visualBounds.width().coerceAtLeast(1).toFloat()
            val vh = visualBounds.height().coerceAtLeast(1).toFloat()
            // Aspect-preserving freeform resize uses uniform scale; fall back to avg if skewed.
            val sx = vw / bw
            val sy = vh / bh
            val scale = ((sx + sy) * 0.5f).coerceIn(0.2f, 3.0f)
            leashSetScale(txClass, scClass, tx, leash, scale, scale)
            runCatching {
                txClass.getMethod(
                    "setPosition",
                    scClass,
                    java.lang.Float.TYPE,
                    java.lang.Float.TYPE,
                ).invoke(tx, leash, visualBounds.left.toFloat(), visualBounds.top.toFloat())
            }
            val radius = io.hyper.freeform.xposed.policy.FreeformPolicy
                .freeformLeashCornerRadiusPx(
                    false,
                    visualBounds.width(),
                    visualBounds.height(),
                    scale,
                )
            runCatching {
                txClass.getMethod("setCornerRadius", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, radius)
            }
            // Keep crop on original task surface; visual size comes from matrix.
            runCatching {
                txClass.getMethod("setWindowCrop", scClass, Integer.TYPE, Integer.TYPE)
                    .invoke(tx, leash, bw.toInt(), bh.toInt())
            }
            txClass.getMethod("apply").invoke(tx)
            true
        }.onFailure {
            XLog.e("applyLiveResizeVisual task=$taskId failed", it)
        }.getOrDefault(false)
    }

    /**
     * Restore identity leash transform after live resize visual, before/after resizeTask commit.
     */
    fun clearLiveResizeVisual(taskId: Int, bounds: Rect): Boolean {
        // Same restore path as pin-shrink interrupt (identity matrix + position + crop/radius).
        return resetPinShrinkVisual(taskId, bounds)
    }

    fun clearFreeformSurfaceStyle(taskId: Int): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return@runCatching false
            val leash = fieldGet(task, "mSurfaceControl")
                ?: callNoArg(task, "getSurfaceControl")
                ?: return@runCatching false
            val scClass = Class.forName("android.view.SurfaceControl")
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val tx = txClass.getDeclaredConstructor().newInstance()
            runCatching {
                txClass.getMethod("setCornerRadius", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, 0f)
            }
            runCatching {
                txClass.getMethod("setWindowCrop", scClass, Integer.TYPE, Integer.TYPE)
                    .invoke(tx, leash, 0, 0)
            }
            runCatching {
                txClass.getMethod("setShadowRadius", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, 0f)
            }
            txClass.getMethod("apply").invoke(tx)
            true
        }.getOrDefault(false)
    }

    /**
     * Clear every Task leash property that freeform resize/pin paths can override.
     * Fullscreen owns display geometry, so the task leash must return to origin with
     * identity matrix and no crop. Leaving only position/crop behind produces the
     * observed fullscreen black bands after maximizing a resized freeform task.
     */
    fun resetTaskLeashForFullscreen(taskId: Int): Boolean {
        return runCatching {
            val task = findTask(taskId) ?: return@runCatching false
            val leash = fieldGet(task, "mSurfaceControl")
                ?: callNoArg(task, "getSurfaceControl")
                ?: return@runCatching false
            val scClass = Class.forName("android.view.SurfaceControl")
            val txClass = Class.forName("android.view.SurfaceControl\$Transaction")
            val tx = txClass.getDeclaredConstructor().newInstance()
            leashSetScale(txClass, scClass, tx, leash, 1f, 1f)
            runCatching {
                txClass.getMethod(
                    "setPosition",
                    scClass,
                    java.lang.Float.TYPE,
                    java.lang.Float.TYPE,
                ).invoke(tx, leash, 0f, 0f)
            }
            runCatching {
                txClass.getMethod("setAlpha", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, 1f)
            }
            // Android SurfaceControl uses 0x0 as "clear crop" for this overload.
            runCatching {
                txClass.getMethod("setWindowCrop", scClass, Integer.TYPE, Integer.TYPE)
                    .invoke(tx, leash, 0, 0)
            }.recoverCatching {
                txClass.getMethod("setWindowCrop", scClass, Rect::class.java)
                    .invoke(tx, leash, null as Rect?)
            }
            runCatching {
                txClass.getMethod("setCornerRadius", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, 0f)
            }
            runCatching {
                txClass.getMethod("setShadowRadius", scClass, java.lang.Float.TYPE)
                    .invoke(tx, leash, 0f)
            }
            txClass.getMethod("apply").invoke(tx)
            XLog.d("resetTaskLeashForFullscreen task=$taskId")
            true
        }.onFailure {
            XLog.e("resetTaskLeashForFullscreen task=$taskId failed", it)
        }.getOrDefault(false)
    }

    /**
     * Scale a task leash. The public 4-arg SurfaceControl.Transaction.setMatrix overload is
     * unreliable on AOSP/MuMu API 35 for container leashes (reflection succeeds but the
     * container transform does not propagate a scale to the child buffer — the freeform
     * leash showed translate-only in SurfaceFlinger, so mini rendered at full size and
     * overflowed the window). setScale (API 31+) is the path Xiaomi's own freeform code uses;
     * prefer it and keep setMatrix as a legacy fallback.
     */
    private fun leashSetScale(
        txClass: Class<*>,
        scClass: Class<*>,
        tx: Any,
        leash: Any,
        scaleX: Float,
        scaleY: Float,
    ): Boolean {
        val viaScale = runCatching {
            txClass.getMethod("setScale", scClass, java.lang.Float.TYPE, java.lang.Float.TYPE)
                .invoke(tx, leash, scaleX, scaleY)
            true
        }.getOrDefault(false)
        if (viaScale) return true
        return runCatching {
            txClass.getMethod(
                "setMatrix",
                scClass,
                java.lang.Float.TYPE,
                java.lang.Float.TYPE,
                java.lang.Float.TYPE,
                java.lang.Float.TYPE,
            ).invoke(tx, leash, scaleX, 0f, 0f, scaleY)
            true
        }.onFailure { XLog.e("leashSetScale failed sx=$scaleX sy=$scaleY", it) }
            .getOrDefault(false)
    }

    private fun callNoArg(obj: Any, name: String): Any? {
        return runCatching {
            obj.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.isEmpty()
            }?.invoke(obj)
        }.getOrNull()
    }

    private fun fieldGet(obj: Any, field: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(field)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return null
    }

    private fun fieldSetInt(obj: Any, field: String, value: Int): Boolean {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(field)
                f.isAccessible = true
                f.setInt(obj, value)
                return true
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return false
    }
}
