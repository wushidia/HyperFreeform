package io.hyper.freeform.xposed.server

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.view.Display
import io.hyper.freeform.IFreeformManager
import io.hyper.freeform.service.FreeformBridge
import io.hyper.freeform.xposed.model.FreeformTaskState
import io.hyper.freeform.xposed.model.WindowState
import io.hyper.freeform.xposed.policy.FreeformPolicy
import io.hyper.freeform.xposed.shell.FreeformShellController
import io.hyper.freeform.xposed.shell.SplitScreenBridge
import io.hyper.freeform.xposed.utils.SystemServices
import io.hyper.freeform.xposed.utils.XLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-side freeform authority running in system_server.
 * Uses native WINDOWING_MODE_FREEFORM=5 (Xiaomi path), not VirtualDisplay.
 */
object FreeformManagerService : IFreeformManager.Stub() {
    private const val TAG = "FreeformManagerService"
    private const val VERSION_NAME = "1.0.0"
    private const val VERSION_CODE = 100
    const val SERVICE_NAME = "hyper_freeform"

    @Volatile
    private var ready = false

    @Volatile
    private var enabled = true

    private val mainHandler = Handler(Looper.getMainLooper())

    private const val MUMU_MARKER_PACKAGE = "com.netease.mumu.cloner"
    private var muMuDebugObserver: ContentObserver? = null
    private var muMuSystemUiRecoveryAttempted = false
    private var compatBridgeReceiver: BroadcastReceiver? = null

    private val compatBridgePackages = setOf(
        "io.hyper.freeform",
        "com.android.systemui",
        "com.android.launcher3",
        "app.lawnchair",
        "com.miui.home",
    )

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) block()
        else mainHandler.post(block)
    }

    private val tasks = ConcurrentHashMap<Int, FreeformTaskState>()
    /** Tasks maximized from freeform must keep the stock HOME/Recents swipe semantics. */
    private val suppressForegroundMiniTasks = ConcurrentHashMap.newKeySet<Int>()
    private var shell: FreeformShellController? = null
    private val pendingPinFinish = java.util.concurrent.ConcurrentHashMap<Int, Runnable>()
    private val pendingLaunchSplashChecks = java.util.concurrent.ConcurrentHashMap<String, Runnable>()
    private val launchAnimationDone = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val launchGeneration = java.util.concurrent.atomic.AtomicLong()
    private val activeLaunchGenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val completedLaunchGenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val lastPackageLaunchAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Keep app leash and chrome aligned while WM settles a resize/relayout transaction. */
    private val visualGuardRunnables = java.util.concurrent.ConcurrentHashMap<Int, Runnable>()
    /** Xiaomi pin shrink visual frame ticks (Folme setPinAnimInfo lite). */
    private val pendingPinVisual = java.util.concurrent.ConcurrentHashMap<Int, Runnable>()
    /** Post-WM fullscreen settle animation; unlike freeform transitions it survives task tracking removal. */
    private val fullscreenAnimRunnables = ConcurrentHashMap<Int, TransitionAnimSession>()

    @Volatile private var imeVisible: Boolean = false
    @Volatile private var imeHeight: Int = 0
    /** When true, poll must not override debugSimulateIme state. */
    @Volatile private var imeForced: Boolean = false
    @Volatile private var lastDisplayW: Int = 0
    @Volatile private var lastDisplayH: Int = 0
    private var displayListenerRegistered = false
    private val imePollRunnable = object : Runnable {
        override fun run() {
            pollImeAndDisplay()
            // Keep polling while freeform tasks exist or IME is up.
            if (tasks.isNotEmpty() || imeVisible) {
                mainHandler.postDelayed(this, 250L)
            }
        }
    }

    fun systemReady() {
        if (!SystemServices.isInitialized) {
            XLog.e("$TAG systemReady without SystemServices")
            return
        }
        ensureMuMuDebugPersistence()
        ensureMuMuSystemUiCaptionSuppression()
        // Task ids are only meaningful within this system_server lifetime. Drop stale markers
        // after a reboot so a later task-id reuse cannot disable the normal navigation gesture.
        suppressForegroundMiniTasks.clear()
        persistSuppressedForegroundMiniTasks()
        ensureFreeformSupport()
        shell = FreeformShellController()
        shell?.start()
        runCatching { io.hyper.freeform.xposed.shell.SidebarController.start() }
            .onFailure { XLog.e("SidebarController start failed", it) }
        registerDisplayListener()
        val (dw, dh) = FreeformPolicy.displaySize()
        lastDisplayW = dw
        lastDisplayH = dh
        enabled = runCatching {
            Settings.Global.getInt(
                SystemServices.systemContext.contentResolver,
                FreeformBridge.SETTING_ENABLED,
                1,
            ) != 0
        }.getOrDefault(true)
        ready = true
        if (registerCompatBridge()) {
            publishCompatBridgeState()
        }
        XLog.i("$TAG ready, freeform support enabled")
        mainHandler.post(imePollRunnable)
    }

    private fun persistSuppressedForegroundMiniTasks() {
        runCatching {
            val csv = suppressForegroundMiniTasks
                .asSequence()
                .sorted()
                .joinToString(",")
            Settings.Global.putString(
                SystemServices.systemContext.contentResolver,
                FreeformBridge.SETTING_SUPPRESS_FOREGROUND_MINI_TASKS,
                csv,
            )
        }.onFailure { XLog.e("$TAG persist swipe suppression failed", it) }
    }

    private fun suppressForegroundMiniForTask(taskId: Int) {
        if (taskId > 0 && suppressForegroundMiniTasks.add(taskId)) {
            persistSuppressedForegroundMiniTasks()
            XLog.d("suppress foreground swipe→mini task=$taskId")
        }
    }

    private fun clearForegroundMiniSuppression(taskId: Int) {
        if (taskId > 0 && suppressForegroundMiniTasks.remove(taskId)) {
            persistSuppressedForegroundMiniTasks()
        }
    }

    private fun cancelFullscreenTransitionAnim(taskId: Int) {
        fullscreenAnimRunnables.remove(taskId)?.let {
            mainHandler.removeCallbacks(it.runnable)
        }
    }

    /**
     * Animate the leash after WM has committed fullscreen. The leash now contains the fullscreen
     * buffer, so the first frame is an aspect-preserving cover of the old card rather than a
     * non-uniform stretch of the old freeform buffer.
     */
    private fun animateFullscreenLeash(
        taskId: Int,
        from: Rect,
        fullscreen: Rect,
        fromRadius: Float,
    ) {
        cancelFullscreenTransitionAnim(taskId)
        val startMs = System.currentTimeMillis()
        val duration = FreeformPolicy.MAXIMIZE_ANIM_MS.coerceAtLeast(1L)
        val tick = object : Runnable {
            override fun run() {
                fullscreenAnimRunnables.remove(taskId)
                if (SystemServices.taskWindowingMode(taskId) !=
                    FreeformPolicy.WINDOWING_MODE_FULLSCREEN
                ) return
                val p = ((System.currentTimeMillis() - startMs).toFloat() / duration)
                    .coerceIn(0f, 1f)
                val posP = FreeformPolicy.folmeSpring(
                    p,
                    FreeformPolicy.SPRING_MAXIMIZE_POS_DAMPING,
                    FreeformPolicy.SPRING_MAXIMIZE_POS_RESPONSE,
                )
                val sizeP = FreeformPolicy.folmeSpring(
                    p,
                    FreeformPolicy.SPRING_MAXIMIZE_SIZE_DAMPING,
                    FreeformPolicy.SPRING_MAXIMIZE_SIZE_RESPONSE,
                )
                val frame = FreeformPolicy.maximizeFullscreenFrame(from, fullscreen, posP, sizeP)
                SystemServices.applyFullscreenTransitionVisual(
                    taskId,
                    frame,
                    fromRadius,
                    0f,
                    sizeP,
                )
                if (p < 1f) {
                    fullscreenAnimRunnables[taskId] = TransitionAnimSession(this) {}
                    mainHandler.postDelayed(this, 16L)
                } else {
                    SystemServices.resetTaskLeashForFullscreen(taskId)
                }
            }
        }
        val first = FreeformPolicy.maximizeFullscreenFrame(from, fullscreen, 0f, 0f)
        SystemServices.applyFullscreenTransitionVisual(taskId, first, fromRadius, 0f, 0f)
        fullscreenAnimRunnables[taskId] = TransitionAnimSession(tick) {}
        mainHandler.postDelayed(tick, 16L)
    }

    /**
     * ServiceManager.addService needs a ROM sepolicy service_context entry. Stock strict-SELinux
     * ROMs do not have one for this module, so accept a small command protocol through a dynamic
     * system_server receiver and authorize the real sender UID before dispatch.
     */
    private fun registerCompatBridge(): Boolean {
        if (compatBridgeReceiver != null) return true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != FreeformBridge.ACTION) return
                val senderUid = trustedCompatSenderUid(this)
                if (senderUid < 0) {
                    XLog.e("$TAG rejected compat bridge sender")
                    return
                }
                val operation = intent.getStringExtra(FreeformBridge.EXTRA_OPERATION).orEmpty()
                runCatching {
                    dispatchCompatBridge(operation, intent)
                    XLog.i("$TAG compat bridge op=$operation uid=$senderUid")
                }.onFailure {
                    XLog.e("$TAG compat bridge failed op=$operation uid=$senderUid", it)
                }
            }
        }
        return runCatching {
            SystemServices.systemContext.registerReceiver(
                receiver,
                IntentFilter(FreeformBridge.ACTION),
                null,
                mainHandler,
                Context.RECEIVER_EXPORTED,
            )
            compatBridgeReceiver = receiver
            XLog.i("$TAG compat bridge registered")
            true
        }.onFailure {
            XLog.e("$TAG compat bridge registration failed", it)
        }.getOrDefault(false)
    }

    private fun trustedCompatSenderUid(receiver: BroadcastReceiver): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return -1
        val uid = receiver.sentFromUid
        if (uid == Process.SYSTEM_UID) return uid
        val packages = runCatching {
            SystemServices.packageManager.getPackagesForUid(uid).orEmpty()
        }.getOrDefault(emptyArray())
        return if (packages.any(compatBridgePackages::contains)) uid else -1
    }

    private fun dispatchCompatBridge(operation: String, intent: Intent) {
        val taskId = intent.getIntExtra(FreeformBridge.EXTRA_TASK_ID, -1)
        when (operation) {
            FreeformBridge.OP_START_COMPONENT -> {
                val component = ComponentName.unflattenFromString(
                    intent.getStringExtra(FreeformBridge.EXTRA_COMPONENT).orEmpty(),
                ) ?: return
                startFreeform(
                    component,
                    0,
                    intent.getIntExtra(FreeformBridge.EXTRA_WINDOW_STATE, WindowState.NORMAL),
                )
            }
            FreeformBridge.OP_START_PACKAGE -> startFreeformPackage(
                intent.getStringExtra(FreeformBridge.EXTRA_PACKAGE),
                0,
                intent.getIntExtra(FreeformBridge.EXTRA_WINDOW_STATE, WindowState.NORMAL),
            )
            FreeformBridge.OP_START_RECENT -> startFreeformFromRecent(
                taskId,
                intent.getIntExtra(FreeformBridge.EXTRA_WINDOW_STATE, WindowState.MINI),
            )
            FreeformBridge.OP_ADOPT_PENDING_INTENT_LAUNCH -> {
                val packageName = intent.getStringExtra(FreeformBridge.EXTRA_PACKAGE)
                    ?.takeIf { it.isNotBlank() }
                    ?: return
                val component = ComponentName.unflattenFromString(
                    intent.getStringExtra(FreeformBridge.EXTRA_COMPONENT).orEmpty(),
                )
                val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(FreeformBridge.EXTRA_BOUNDS, Rect::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(FreeformBridge.EXTRA_BOUNDS) as? Rect
                } ?: return
                adoptPendingIntentLaunch(
                    packageName,
                    component,
                    bounds,
                    intent.getIntExtra(
                        FreeformBridge.EXTRA_WINDOW_STATE,
                        WindowState.NORMAL,
                    ),
                )
            }
            FreeformBridge.OP_SET_ENABLED -> setEnabled(
                intent.getBooleanExtra(FreeformBridge.EXTRA_ENABLED, true),
            )
            FreeformBridge.OP_CLOSE_TASK -> closeTask(taskId)
            FreeformBridge.OP_PIN_TASK -> pinTask(taskId, true)
            FreeformBridge.OP_UNPIN_TASK -> unpinTask(taskId)
            FreeformBridge.OP_PIN_TO_FULLSCREEN -> startPinToFullscreen(taskId)
            FreeformBridge.OP_UPDATE_PIN_POSITION -> updatePinFloatingWindowPos(
                taskId,
                intent.getIntExtra(FreeformBridge.EXTRA_PIN_POSITION, 1),
                intent.getIntExtra(FreeformBridge.EXTRA_Y, 0),
            )
            FreeformBridge.OP_SPLIT_TASK -> splitTask(
                taskId,
                intent.getIntExtra(FreeformBridge.EXTRA_POSITION, 0),
            )
            FreeformBridge.OP_SET_FREEFORM_DPI -> setFreeformDpi(
                taskId,
                intent.getIntExtra(FreeformBridge.EXTRA_DPI, 0),
            )
            FreeformBridge.OP_SET_GLOBAL_DPI -> setGlobalDpiPercent(
                intent.getIntExtra(
                    FreeformBridge.EXTRA_PERCENT,
                    FreeformPolicy.DEFAULT_DPI_PERCENT,
                ),
            )
            FreeformBridge.OP_SET_GLOBAL_WINDOW_SIZE -> setGlobalWindowSizePercent(
                intent.getIntExtra(
                    FreeformBridge.EXTRA_WIDTH_PERCENT,
                    FreeformPolicy.DEFAULT_WINDOW_WIDTH_PERCENT,
                ),
                intent.getIntExtra(
                    FreeformBridge.EXTRA_HEIGHT_PERCENT,
                    FreeformPolicy.DEFAULT_WINDOW_HEIGHT_PERCENT,
                ),
            )
            FreeformBridge.OP_COLLAPSE_STATUS_BAR -> collapseStatusBar()
            FreeformBridge.OP_DEBUG_IME -> debugSimulateIme(
                intent.getBooleanExtra(FreeformBridge.EXTRA_SHOW, false),
                intent.getIntExtra(FreeformBridge.EXTRA_HEIGHT, 0),
            )
            FreeformBridge.OP_SET_SIDEBAR_SIDE -> setSidebarSide(
                intent.getIntExtra(FreeformBridge.EXTRA_SIDE, 1),
            )
            FreeformBridge.OP_SET_SIDEBAR_APPS -> setSidebarApps(
                intent.getStringExtra(FreeformBridge.EXTRA_PACKAGES_CSV),
            )
            FreeformBridge.OP_SET_SIDEBAR_SHOW_NAMES -> setSidebarShowAppNames(
                intent.getBooleanExtra(FreeformBridge.EXTRA_SHOW, false),
            )
            else -> XLog.e("$TAG unknown compat bridge op=$operation")
        }
    }

    private fun publishCompatBridgeState() {
        runCatching {
            val cr = SystemServices.systemContext.contentResolver
            val boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1)
            if (boot >= 0) {
                Settings.Global.putInt(cr, FreeformBridge.SETTING_READY_BOOT, boot)
            }
            Settings.Global.putInt(
                cr,
                FreeformBridge.SETTING_ENABLED,
                if (enabled) 1 else 0,
            )
        }.onFailure { XLog.e("$TAG compat bridge state publish failed", it) }
    }

    /**
     * MuMu resets adb_enabled late during boot even though its host-side ADB transport remains up.
     * Keep developer options and ADB enabled for this development image, but only when MuMu's own
     * marker package is installed so a physical device never has debugging forced on by the module.
     */
    private fun ensureMuMuDebugPersistence() {
        if (muMuDebugObserver != null) return
        val isMuMu = runCatching {
            SystemServices.packageManager.getPackageInfo(MUMU_MARKER_PACKAGE, 0)
        }.isSuccess
        if (!isMuMu) return

        val cr = SystemServices.systemContext.contentResolver
        fun reassert() {
            runCatching {
                if (Settings.Global.getInt(cr, "development_settings_enabled", 0) != 1) {
                    Settings.Global.putInt(cr, "development_settings_enabled", 1)
                }
                if (Settings.Global.getInt(cr, "adb_enabled", 0) != 1) {
                    Settings.Global.putInt(cr, "adb_enabled", 1)
                }
            }.onFailure { XLog.e("MuMu debug settings reassert failed", it) }
        }

        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                mainHandler.post { reassert() }
            }
        }
        runCatching {
            cr.registerContentObserver(
                Settings.Global.getUriFor("development_settings_enabled"),
                false,
                observer,
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor("adb_enabled"),
                false,
                observer,
            )
            muMuDebugObserver = observer
            reassert()
            // MuMu also applies late boot defaults; cover those races in addition to the observer.
            longArrayOf(1_500L, 5_000L, 15_000L).forEach { delay ->
                mainHandler.postDelayed({ reassert() }, delay)
            }
            XLog.i("MuMu debug persistence armed")
        }.onFailure { XLog.e("MuMu debug persistence setup failed", it) }
    }

    /**
     * MuMu can start SystemUI before LSPosed has finished loading app-process scopes. If that boot
     * race occurs, the AOSP caption (minimize/maximize buttons) is recreated even though the scope
     * is correct. SystemUI writes a per-boot marker after installing the suppression hooks; restart
     * it once when the marker is missing so the normal LSPosed injection path gets another chance.
     */
    private fun ensureMuMuSystemUiCaptionSuppression() {
        val isMuMu = runCatching {
            SystemServices.packageManager.getPackageInfo(MUMU_MARKER_PACKAGE, 0)
        }.isSuccess
        if (!isMuMu) return

        fun hookActiveThisBoot(): Boolean {
            val cr = SystemServices.systemContext.contentResolver
            val boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1)
            val hookedBoot = Settings.Global.getInt(
                cr,
                FreeformPolicy.SETTINGS_SYSTEMUI_HOOK_BOOT,
                -2,
            )
            return boot >= 0 && hookedBoot == boot
        }

        mainHandler.postDelayed({
            if (hookActiveThisBoot()) {
                XLog.i("SystemUI caption suppression confirmed")
                return@postDelayed
            }
            if (muMuSystemUiRecoveryAttempted) return@postDelayed
            muMuSystemUiRecoveryAttempted = true
            runCatching {
                val process = SystemServices.activityManager.runningAppProcesses
                    ?.firstOrNull { it.processName == "com.android.systemui" }
                    ?: error("SystemUI process not found")
                val getService = ActivityManager::class.java.getDeclaredMethod("getService")
                    .apply { isAccessible = true }
                val am = getService.invoke(null) ?: error("ActivityManager service unavailable")
                val kill = am.javaClass.methods.firstOrNull {
                    it.name == "killApplicationProcess" && it.parameterTypes.size == 2
                } ?: error("killApplicationProcess unavailable")
                XLog.i(
                    "SystemUI caption suppression marker missing; restarting SystemUI once " +
                        "pid=${process.pid}",
                )
                kill.invoke(am, process.processName, process.uid)
            }.onFailure { XLog.e("SystemUI caption suppression recovery failed", it) }
        }, 12_000L)

        mainHandler.postDelayed({
            if (hookActiveThisBoot()) {
                XLog.i("SystemUI caption suppression recovery verified")
            } else {
                XLog.e("SystemUI caption suppression is not active after recovery")
            }
        }, 20_000L)
    }

    private fun ensureFreeformSupport() {
        writeFreeformSettings()
        forceAtmsFreeformFlags()
        // MuMu/some builds reset global settings around boot; re-assert shortly after ready.
        mainHandler.postDelayed({
            writeFreeformSettings()
            forceAtmsFreeformFlags()
        }, 1500)
        mainHandler.postDelayed({
            writeFreeformSettings()
        }, 5000)
    }

    private fun writeFreeformSettings() {
        runCatching {
            val cr = SystemServices.systemContext.contentResolver
            Settings.Global.putInt(cr, "enable_freeform_support", 1)
            Settings.Global.putInt(cr, "force_resizable_activities", 1)
            // Some AOSP forks also read developer option key.
            Settings.Global.putInt(cr, "freeform_window_management", 1)
            val currentDpiV2 = Settings.Global.getInt(
                cr,
                FreeformPolicy.SETTINGS_DPI_PERCENT,
                -1,
            )
            if (currentDpiV2 < 0) {
                val legacy = Settings.Global.getInt(
                    cr,
                    FreeformPolicy.SETTINGS_DPI_PERCENT_LEGACY,
                    -1,
                )
                val migrated = if (legacy >= 0) {
                    FreeformBridge.migrateLegacyDpiPercent(legacy)
                } else {
                    FreeformPolicy.DEFAULT_DPI_PERCENT
                }
                Settings.Global.putInt(
                    cr,
                    FreeformPolicy.SETTINGS_DPI_PERCENT,
                    migrated,
                )
                XLog.i("Migrated freeform DPI legacy=$legacy -> realPercent=$migrated")
            } else {
                val sanitized = FreeformBridge.sanitizeDpiPercent(currentDpiV2)
                if (sanitized != currentDpiV2) {
                    Settings.Global.putInt(cr, FreeformPolicy.SETTINGS_DPI_PERCENT, sanitized)
                }
            }
            if (Settings.Global.getInt(cr, FreeformPolicy.SETTINGS_WINDOW_WIDTH_PERCENT, -1) < 0) {
                Settings.Global.putInt(
                    cr,
                    FreeformPolicy.SETTINGS_WINDOW_WIDTH_PERCENT,
                    FreeformPolicy.DEFAULT_WINDOW_WIDTH_PERCENT,
                )
            }
            if (Settings.Global.getInt(cr, FreeformPolicy.SETTINGS_WINDOW_HEIGHT_PERCENT, -1) < 0) {
                Settings.Global.putInt(
                    cr,
                    FreeformPolicy.SETTINGS_WINDOW_HEIGHT_PERCENT,
                    FreeformPolicy.DEFAULT_WINDOW_HEIGHT_PERCENT,
                )
            }
            // Defaults for entry toggles (app UI mirrors these keys).
            if (Settings.Global.getInt(cr, "hyper_freeform_notification", -1) < 0) {
                Settings.Global.putInt(cr, "hyper_freeform_notification", 1)
            }
            if (Settings.Global.getInt(cr, "hyper_freeform_recents", -1) < 0) {
                Settings.Global.putInt(cr, "hyper_freeform_recents", 1)
            }
            // Default sidebar on the right (Xiaomi-like); app can flip via setSidebarSide.
            if (Settings.Global.getInt(cr, io.hyper.freeform.xposed.shell.SidebarController.SETTING_SIDE, -1) < 0) {
                Settings.Global.putInt(cr, io.hyper.freeform.xposed.shell.SidebarController.SETTING_SIDE, 1)
            }
            val enabled = Settings.Global.getInt(cr, "enable_freeform_support", 0)
            XLog.d("freeform settings enable_freeform_support=$enabled")
        }.onFailure { XLog.e("Failed to write freeform settings", it) }
    }

    private fun forceAtmsFreeformFlags() {
        runCatching {
            val atms = SystemServices.activityTaskManagerService() ?: return@runCatching
            for (field in listOf(
                "mSupportsFreeformWindowManagement",
                "mSupportsMultiWindow",
                "mSupportsMultiDisplay"
            )) {
                runCatching {
                    var c: Class<*>? = atms.javaClass
                    while (c != null) {
                        try {
                            val f = c.getDeclaredField(field)
                            f.isAccessible = true
                            if (f.type == java.lang.Boolean.TYPE) {
                                f.setBoolean(atms, true)
                            }
                            break
                        } catch (_: NoSuchFieldException) {
                            c = c.superclass
                        }
                    }
                }
            }
            XLog.d("Forced ATMS freeform capability flags")
        }.onFailure { XLog.e("forceAtmsFreeformFlags failed", it) }
    }

    fun onTaskRemoved(taskId: Int) {
        clearForegroundMiniSuppression(taskId)
        cancelPendingPinFinish(taskId)
        cancelPendingLandscapeRestore(taskId)
        cancelVisualSettleGuard(taskId)
        stopLiveResizeVisual(taskId)
        preLandscapeBounds.remove(taskId)
        tasks.remove(taskId)?.let {
            it.pinAnimating = false
            mainHandler.post { shell?.onTaskRemoved(taskId) }
            XLog.d("Task removed $taskId ${it.packageName}")
        }
    }

    fun onTaskMovedToFront(taskId: Int) {
        val state = tasks[taskId] ?: return
        if (WindowState.isPinned(state.windowState)) {
            // Pin hide/reorder emits moved-to-front noise. Only treat as user restore when:
            // 1) past pin settle grace, and
            // 2) task is no longer force-hidden (user really brought it back).
            val age = System.currentTimeMillis() - state.pinActiveTime
            if (age < 4000L) {
                XLog.d("Ignore moved-to-front during pin grace task=$taskId age=${age}ms")
                return
            }
            val stillHidden = runCatching {
                val task = SystemServices.findTask(taskId) ?: return@runCatching true
                // If force-hidden flags still set, this is not a user restore.
                var c: Class<*>? = task.javaClass
                while (c != null) {
                    try {
                        val f = c.getDeclaredField("mForceHiddenFlags")
                        f.isAccessible = true
                        val flags = f.getInt(task)
                        return@runCatching flags != 0
                    } catch (_: NoSuchFieldException) {
                        c = c.superclass
                    }
                }
                false
            }.getOrDefault(false)
            if (stillHidden) {
                XLog.d("Ignore moved-to-front for still-hidden pin task=$taskId")
                return
            }
            mainHandler.post { unpinTask(taskId) }
        } else {
            mainHandler.post { shell?.onTaskFocused(taskId) }
        }
    }

    /**
     * ActivityStarter hook entry for an ordinary (non-freeform-options) external launch.
     * If its package already owns a managed small-window Task, convert that same Task back to
     * fullscreen synchronously before ActivityStarter chooses/reuses it. Launches originating from
     * a managed freeform Task (including cross-package system helpers) must remain freeform.
     */
    fun promoteTrackedFreeformForNormalLaunch(packageName: String, sourceTaskId: Int?): Int? {
        val state = tasks.values.firstOrNull {
            it.packageName == packageName && WindowState.isVisibleFreeform(it.windowState)
        } ?: return null
        if (sourceTaskId == state.taskId) return null

        val taskId = state.taskId
        XLog.i(
            "normal launch promotes freeform task=$taskId pkg=$packageName " +
                "sourceTask=${sourceTaskId ?: -1}",
        )
        cancelPendingPinFinish(taskId)
        cancelPendingLandscapeRestore(taskId)
        cancelVisualSettleGuard(taskId)
        stopLiveResizeVisual(taskId)
        preLandscapeBounds.remove(taskId)
        if (WindowState.isPinned(state.windowState)) {
            SystemServices.showTaskFromPin(taskId)
        }
        SystemServices.resetTaskLeashForFullscreen(taskId)
        val exited = SystemServices.exitTaskToFullscreen(
            taskId,
            normalLaunchPreparing = true,
        )
        tasks.remove(taskId)
        shell?.onTaskRemoved(taskId)
        XLog.i(
            "normal launch fullscreen prepared task=$taskId pkg=$packageName exited=$exited",
        )
        return taskId
    }

    /**
     * Xiaomi onImeVisibilityChanged port.
     * NORMAL freeforms that cross imeTop are offset up; IME hide restores preImeBounds.
     * MINI freeforms only clamp against imeTop (no full restore stack).
     */
    fun onImeVisibilityChanged(visible: Boolean, height: Int) {
        val h = height.coerceAtLeast(0)
        val changed = (visible != imeVisible) || (visible && h != imeHeight) || (!visible && imeVisible)
        imeVisible = visible && h > 0
        imeHeight = if (imeVisible) h else 0
        if (!changed && !tasks.values.any { it.imeAvoiding }) return
        mainHandler.post { applyImePolicy("event") }
    }

    /** Xiaomi drag-start: hide IME and drop temporary avoid so follow-hand does not jump. */
    fun onGestureStart(taskId: Int) {
        mainHandler.post {
            if (imeVisible || imeHeight > 0) {
                SystemServices.hideSoftInput()
            }
            val state = tasks[taskId] ?: return@post
            if (state.imeAvoiding) {
                // Keep current visual bounds as the new baseline after user takes over.
                state.imeAvoiding = false
                state.preImeBounds.setEmpty()
                XLog.d("Cleared IME avoid on gesture task=$taskId")
            }
        }
    }

    fun onDisplayChanged() {
        mainHandler.post { relayoutForDisplayChange("listener") }
    }

    private fun registerDisplayListener() {
        if (displayListenerRegistered) return
        runCatching {
            val dm = SystemServices.displayManager
            dm.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {
                    // External/fold display gone: drop chrome residuals for vanished surfaces.
                    mainHandler.post { cleanupOrphanTasks("displayRemoved:$displayId") }
                }
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        onDisplayChanged()
                    }
                }
            }, mainHandler)
            displayListenerRegistered = true
            XLog.d("DisplayListener registered for freeform relayout")
        }.onFailure { XLog.e("registerDisplayListener failed", it) }
        registerSidebarObserver()
        registerScreenReceiver()
        registerDpiObserver()
        registerWindowSizeObserver()
    }

    private var sidebarObserverRegistered = false
    private var screenReceiverRegistered = false
    private var dpiObserverRegistered = false
    private var windowSizeObserverRegistered = false
    private var lastAppliedGlobalDpi = Int.MIN_VALUE
    private var lastAppliedWindowSize = Long.MIN_VALUE
    private var lastOrphanSweepAt = 0L

    /**
     * Observe the user's global freeform DPI setting (set in the app). On change, re-apply the
     * density to every open freeform window so the adjustment takes effect live.
     */
    private fun registerDpiObserver() {
        if (dpiObserverRegistered) return
        runCatching {
            val cr = SystemServices.systemContext.contentResolver
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    mainHandler.post { applyGlobalDpiToAll("observer") }
                }
            }
            cr.registerContentObserver(
                Settings.Global.getUriFor(FreeformPolicy.SETTINGS_DPI_PERCENT),
                false,
                observer,
            )
            dpiObserverRegistered = true
            XLog.d("Freeform DPI observer registered")
        }.onFailure { XLog.e("registerDpiObserver failed", it) }
    }

    private fun applyGlobalDpiToAll(reason: String) {
        val dpi = FreeformPolicy.freeformDpiFromSettings()
        // Settings.Global.putInt also fires the observer.  Applying both service-set and observer
        // used to request two activity/process restarts per click, making windows disappear and
        // reappear.  Freshly bound tasks receive DPI in bindLaunchedTask, so identical repeats are
        // safe to drop here.
        if (dpi == lastAppliedGlobalDpi) {
            XLog.d("applyGlobalDpi($reason) unchanged dpi=$dpi; skip")
            return
        }
        var failed = false
        for (state in tasks.values) {
            if (!WindowState.isVisibleFreeform(state.windowState)) continue
            // Per-task explicit override (setFreeformDpi) still wins.
            if (state.freeformDpi > 0) continue
            if (!SystemServices.applyFreeformDensity(state.taskId, dpi, state.bounds)) {
                XLog.e("applyGlobalDpi($reason) failed task=${state.taskId} dpi=$dpi")
                failed = true
            }
        }
        if (failed) {
            // Do not let the observer duplicate get deduplicated after a transient WM race.
            lastAppliedGlobalDpi = Int.MIN_VALUE
            if (reason != "retry") {
                mainHandler.postDelayed({ applyGlobalDpiToAll("retry") }, 80L)
            }
        } else {
            lastAppliedGlobalDpi = dpi
        }
        XLog.i("applyGlobalDpi($reason) dpi=$dpi tasks=${tasks.size}")
    }

    /** Observe and apply the user's normal-window width/height while windows are already open. */
    private fun registerWindowSizeObserver() {
        if (windowSizeObserverRegistered) return
        runCatching {
            val cr = SystemServices.systemContext.contentResolver
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    mainHandler.post { applyGlobalWindowSizeToAll("observer") }
                }
            }
            cr.registerContentObserver(
                Settings.Global.getUriFor(FreeformPolicy.SETTINGS_WINDOW_WIDTH_PERCENT),
                false,
                observer,
            )
            cr.registerContentObserver(
                Settings.Global.getUriFor(FreeformPolicy.SETTINGS_WINDOW_HEIGHT_PERCENT),
                false,
                observer,
            )
            windowSizeObserverRegistered = true
            XLog.d("Freeform window-size observers registered")
        }.onFailure { XLog.e("registerWindowSizeObserver failed", it) }
    }

    private fun applyGlobalWindowSizeToAll(reason: String) {
        val widthPercent = FreeformPolicy.windowWidthPercentFromSettings()
        val heightPercent = FreeformPolicy.windowHeightPercentFromSettings()
        val sizeKey = (widthPercent.toLong() shl 32) or (heightPercent.toLong() and 0xffffffffL)
        if (sizeKey == lastAppliedWindowSize) {
            XLog.d(
                "applyGlobalWindowSize($reason) unchanged " +
                    "width=$widthPercent height=$heightPercent; skip",
            )
            return
        }
        lastAppliedWindowSize = sizeKey
        val defaultVisual = FreeformPolicy.defaultNormalBounds()
        val defaultBase = FreeformPolicy.normalBaseBoundsForVisual(defaultVisual)
        var resizedCount = 0
        for (state in tasks.values) {
            state.restoreNormalBounds = Rect(defaultBase)
            if (state.windowState != WindowState.NORMAL || state.landscape ||
                !state.landscapeTaskBounds.isEmpty
            ) continue

            val resized = FreeformPolicy.resizeKeepingCenter(state.bounds, defaultVisual)
            state.bounds = resized
            state.scale = FreeformPolicy.scaleFromBounds(resized, defaultBase)
            applyNormalGeometry(state.taskId, state)
            shell?.onStateChanged(state.snapshot())
            resizedCount++
        }
        XLog.i(
            "applyGlobalWindowSize($reason) width=$widthPercent height=$heightPercent " +
                "resized=$resizedCount tasks=${tasks.size}",
        )
    }

    private fun registerSidebarObserver() {
        if (sidebarObserverRegistered) return
        runCatching {
            val cr = SystemServices.systemContext.contentResolver
            val observer = object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    mainHandler.post { applySidebarAvoid("observer") }
                }
            }
            cr.registerContentObserver(
                Settings.Secure.getUriFor(FreeformPolicy.SETTINGS_SIDEBAR_BOUNDS),
                false,
                observer
            )
            sidebarObserverRegistered = true
            XLog.d("Sidebar bounds observer registered")
        }.onFailure { XLog.e("registerSidebarObserver failed", it) }
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        runCatching {
            val filter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_SCREEN_ON)
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                    when (intent?.action) {
                        android.content.Intent.ACTION_SCREEN_OFF ->
                            mainHandler.post { cleanupOrphanTasks("screenOff") }
                        android.content.Intent.ACTION_SCREEN_ON ->
                            mainHandler.post {
                                cleanupOrphanTasks("screenOn")
                                // Re-assert surface style after wake (avoid residual black frames).
                                for (state in tasks.values) {
                                    if (WindowState.isVisibleFreeform(state.windowState)) {
                                        applySurfaceStyle(state.taskId, state.bounds, state.windowState)
                                    }
                                }
                            }
                    }
                }
            }
            SystemServices.systemContext.registerReceiver(receiver, filter)
            screenReceiverRegistered = true
            XLog.d("Screen on/off receiver registered for freeform surface cleanup")
        }.onFailure { XLog.e("registerScreenReceiver failed", it) }
    }

    /** Drop tracked freeforms whose ATMS task vanished (kill-process / display residual). */
    private fun cleanupOrphanTasks(reason: String) {
        if (tasks.isEmpty()) return
        // If RootWindowContainer is temporarily unavailable, skip — do not mass-drop.
        if (SystemServices.rootWindowContainer() == null) {
            XLog.d("cleanupOrphanTasks skip reason=$reason (no RWC)")
            return
        }
        val gone = ArrayList<Int>()
        for (id in tasks.keys.toList()) {
            val task = SystemServices.findTask(id)
            if (task == null) gone.add(id)
        }
        for (id in gone) {
            XLog.i("Orphan freeform cleanup reason=$reason task=$id")
            tasks.remove(id)
            shell?.onTaskRemoved(id)
        }
    }

    /**
     * Xiaomi sidebar_bounds → mini 让位.
     * Normal freeforms are left alone; mini windows shift vertically off the sidebar line.
     */
    private fun applySidebarAvoid(reason: String) {
        val bars = FreeformPolicy.sidebarBounds()
        XLog.d("applySidebarAvoid reason=$reason bars=$bars")
        if (bars.isEmpty()) return
        val movable = FreeformPolicy.movableRestriction()
        for (state in tasks.values) {
            if (state.windowState != WindowState.MINI) continue
            val adjusted = FreeformPolicy.adjustBoundsForSidebarIfNeed(state.bounds, movable, bars)
            if (adjusted == state.bounds) continue
            state.bounds = Rect(adjusted)
            state.restoreMiniBounds = Rect(adjusted)
            SystemServices.applyMiniFreeformVisual(
                state.taskId,
                miniSourceBounds(state),
                adjusted,
            )
            shell?.onStateChanged(state.snapshot())
            XLog.d("Mini sidebar avoid task=${state.taskId} -> $adjusted")
        }
    }

    private fun pollImeAndDisplay() {
        if (!ready) return
        // Throttled orphan sweep — app process death / task removed without callback.
        val now = System.currentTimeMillis()
        if (tasks.isNotEmpty() && now - lastOrphanSweepAt >= 5_000L) {
            lastOrphanSweepAt = now
            cleanupOrphanTasks("poll")
        }
        // Display size change fallback (some builds skip listener for metrics-only updates).
        runCatching {
            val (dw, dh) = FreeformPolicy.displaySize()
            if (lastDisplayW != 0 && lastDisplayH != 0 && (dw != lastDisplayW || dh != lastDisplayH)) {
                relayoutForDisplayChange("poll")
            } else {
                lastDisplayW = dw
                lastDisplayH = dh
            }
        }
        // Do not rewrite ordinary task leashes from this 250ms poll. Known resize/transition
        // points already own a short settle guard; an unbounded competing transaction loop is
        // what made some landscape launches flash continuously.
        // Auto-restore app-landscape freeforms whose app left its landscape activity. Physical
        // display orientation is deliberately not part of this state: a portrait freeform parked
        // at the edge of a wide display is still a normal, resizable portrait freeform.
        // Do not restore an
        // ambiguous orientation immediately: player controls often rebuild the video surface and
        // briefly report UNSPECIFIED while switching quality/speed/subtitles.
        runCatching {
            for (st in tasks.values) {
                val appLandscape = st.landscapeTaskBounds.width() > 0 &&
                    st.landscapeTaskBounds.height() > 0
                // Heal state written by the old implementation, which marked every task as
                // landscape merely because the physical display was wide.
                if (st.landscape != appLandscape) st.landscape = appLandscape
                if (!appLandscape || st.windowState != WindowState.NORMAL) continue
                val o = SystemServices.topActivityRequestedOrientation(st.taskId) ?: continue
                if (FreeformPolicy.isPortraitOrientation(o)) {
                    cancelPendingLandscapeRestore(st.taskId)
                    XLog.i("auto-restore explicit portrait: top activity orientation=$o task=${st.taskId}")
                    onAppRequestedOrientation(st.taskId, o)
                } else if (!FreeformPolicy.isLandscapeOrientation(o)) {
                    scheduleLandscapeRestore(st.taskId, o)
                } else if (st.landscapeTaskBounds.width() > 0 &&
                    st.landscapeTaskBounds.width() != st.bounds.width()
                ) {
                    cancelPendingLandscapeRestore(st.taskId)
                    // Re-assert the full-landscape→window scale (WM/surface passes reset the leash
                    // transform, which would blow the fullscreen render back to full size).
                    SystemServices.applyMiniFreeformVisual(
                        st.taskId, st.landscapeTaskBounds, st.bounds, miniStyle = false,
                    )
                }
            }
        }
        if (imeForced) {
            // Keep debugSimulateIme state until explicitly cleared.
            return
        }
        val height = runCatching { SystemServices.imeVisibleHeight() }.getOrDefault(0)
        val visible = height > 0
        if (visible != imeVisible || (visible && height != imeHeight) || (!visible && tasks.values.any { it.imeAvoiding })) {
            imeVisible = visible
            imeHeight = height
            applyImePolicy("poll")
        }
    }

    private fun applyImePolicy(reason: String) {
        if (tasks.isEmpty()) return
        if (imeVisible && imeHeight > 0) {
            for (state in tasks.values) {
                if (!WindowState.isVisibleFreeform(state.windowState)) continue
                val mini = state.windowState == WindowState.MINI
                // Xiaomi primarily avoids focused normal freeforms; we apply to all visible freeforms
                // since multi-window MuMu may not report focus reliably.
                val target = FreeformPolicy.applyImeAvoid(state.bounds, imeHeight, mini = mini) ?: continue
                if (!state.imeAvoiding) {
                    state.preImeBounds = Rect(state.bounds)
                    state.imeAvoiding = true
                }
                state.bounds = Rect(target)
                // Do not overwrite restoreNormalBounds — pin/mini ownership.
                if (mini) {
                    SystemServices.applyMiniFreeformVisual(
                        state.taskId,
                        miniSourceBounds(state),
                        target,
                    )
                } else {
                    // Visual-scale aware: keeps the base-sized task, moves/scales the leash.
                    applyNormalGeometry(state.taskId, state)
                }
                shell?.onStateChanged(state.snapshot())
                XLog.d(
                    "IME avoid($reason) task=${state.taskId} h=$imeHeight -> $target pre=${state.preImeBounds}"
                )
            }
        } else {
            for (state in tasks.values) {
                if (!state.imeAvoiding) continue
                val restore = if (state.preImeBounds.width() > 0) {
                    Rect(state.preImeBounds)
                } else {
                    Rect(state.bounds)
                }
                // After rotation while IME was up, clamp restored bounds.
                val clamped = FreeformPolicy.relayoutAfterDisplayChange(restore)
                state.bounds = clamped
                state.imeAvoiding = false
                state.preImeBounds.setEmpty()
                if (state.windowState == WindowState.MINI) {
                    SystemServices.applyMiniFreeformVisual(
                        state.taskId,
                        miniSourceBounds(state),
                        clamped,
                    )
                } else {
                    applyNormalGeometry(state.taskId, state)
                }
                shell?.onStateChanged(state.snapshot())
                XLog.d("IME reset($reason) task=${state.taskId} -> $clamped")
            }
        }
    }

    private fun relayoutForDisplayChange(reason: String) {
        val (dw, dh) = FreeformPolicy.displaySize()
        if (dw == lastDisplayW && dh == lastDisplayH && reason != "listener") {
            // listener may fire without size change (refresh rate); still reclamp once lightly
        }
        val previousDisplayW = lastDisplayW
        val previousDisplayH = lastDisplayH
        val sizeChanged = dw != previousDisplayW || dh != previousDisplayH
        val returningPortrait = sizeChanged &&
            previousDisplayW > previousDisplayH && dw < dh
        lastDisplayW = dw
        lastDisplayH = dh
        if (tasks.isEmpty()) return
        val landscape = dw > dh
        for (state in tasks.values) {
            if (WindowState.isPinned(state.windowState)) {
                // Keep pin logical freeform bounds; reclamp bubble dock for new metrics.
                val y = if (state.pinY >= 0) state.pinY else state.bounds.top
                state.pinY = y
                state.pinFloatingWindowPos =
                    FreeformPolicy.pinFloatingWindowPos(state.pinPos, state.pinY)
                shell?.onStateChanged(state.snapshot())
                continue
            }
            if (!WindowState.isVisibleFreeform(state.windowState)) continue
            // Prefer stable restore bounds as size source so temporary landscape shrink
            // does not permanently destroy portrait freeform height (Xiaomi-like).
            val base = when {
                state.imeAvoiding && state.preImeBounds.width() > 0 -> Rect(state.preImeBounds)
                state.windowState == WindowState.NORMAL && state.restoreNormalBounds.width() > 0 -> {
                    val r = Rect(state.restoreNormalBounds)
                    // A landscape launch stores a true portrait base. On the return transition its
                    // placement is restored below, including any user move relative to the wide
                    // display's default dock. Other relayouts keep the current top-left preference.
                    if (!returningPortrait) r.offsetTo(state.bounds.left, state.bounds.top)
                    r
                }
                state.windowState == WindowState.MINI && state.restoreMiniBounds.width() > 0 -> {
                    val r = Rect(state.restoreMiniBounds)
                    r.offsetTo(state.bounds.left, state.bounds.top)
                    r
                }
                else -> Rect(state.bounds)
            }
            var next = if (
                returningPortrait &&
                state.windowState == WindowState.NORMAL &&
                state.landscapeTaskBounds.isEmpty
            ) {
                FreeformPolicy.relayoutLandscapeToPortrait(
                    landscapeVisual = state.bounds,
                    portraitBase = base,
                    previousDisplayW = previousDisplayW,
                    previousDisplayH = previousDisplayH,
                )
            } else if (
                landscape &&
                state.windowState == WindowState.NORMAL &&
                state.landscapeTaskBounds.isEmpty
            ) {
                // A normal portrait freeform stays portrait-shaped in landscape and follows the
                // selected sidebar edge. Explicit app-requested landscape windows keep their
                // dedicated 16:9 task/leash path below instead.
                FreeformPolicy.landscapeSideBounds(base)
            } else {
                FreeformPolicy.relayoutAfterDisplayChange(base)
            }
            if (state.imeAvoiding && imeVisible && imeHeight > 0) {
                // Recompute IME avoid against new display metrics.
                FreeformPolicy.applyImeAvoid(
                    next,
                    imeHeight,
                    mini = state.windowState == WindowState.MINI
                )?.let { next = it }
            } else if (state.imeAvoiding && (!imeVisible || imeHeight <= 0)) {
                state.imeAvoiding = false
                state.preImeBounds.setEmpty()
            }
            // Display-driven relayout must NOT clobber restore*Bounds; those are the
            // stable pre-rotation / pre-IME sizes used when metrics return.
            state.bounds = Rect(next)
            // `landscape` describes app-requested landscape content, not display rotation.
            state.landscape = !state.landscapeTaskBounds.isEmpty
            if (state.windowState == WindowState.MINI) {
                SystemServices.applyMiniFreeformVisual(
                    state.taskId,
                    miniSourceBounds(state),
                    next,
                )
            } else {
                applyNormalGeometry(state.taskId, state)
            }
            shell?.onStateChanged(state.snapshot())
            XLog.d(
                "Display relayout($reason) task=${state.taskId} sizeChanged=$sizeChanged " +
                    "display=${dw}x${dh} -> $next"
            )
        }
        // Re-run multi-window avoid after rotation so windows do not stack fully.
        val visible = tasks.values.filter { WindowState.isVisibleFreeform(it.windowState) }
        if (visible.size >= 2) {
            val ordered = visible.sortedBy { it.taskId }
            for (i in 1 until ordered.size) {
                val mobile = ordered[i]
                val fixed = ordered[i - 1]
                val adjusted = FreeformPolicy.avoidSiblings(
                    Rect(mobile.bounds),
                    listOf(Rect(fixed.bounds))
                )
                if (adjusted != mobile.bounds) {
                    mobile.bounds = Rect(adjusted)
                    if (mobile.windowState == WindowState.MINI) {
                        SystemServices.applyMiniFreeformVisual(
                            mobile.taskId,
                            miniSourceBounds(mobile),
                            adjusted,
                        )
                    } else {
                        applyNormalGeometry(mobile.taskId, mobile)
                    }
                    shell?.onStateChanged(mobile.snapshot())
                }
            }
        }
    }

    fun getState(taskId: Int): FreeformTaskState? = tasks[taskId]

    /**
     * True when [taskId] is a tracked, visible freeform (normal/mini) — NOT pinned/bubble.
     * Used by the isInMultiWindowMode hook so freeform apps (e.g. bilibili) do not refuse
     * landscape fullscreen with "暂不支持在分屏模式下使用".
     */
    fun isVisibleFreeformTask(taskId: Int): Boolean {
        val s = tasks[taskId] ?: return false
        return WindowState.isVisibleFreeform(s.windowState)
    }

    /** Used by ActivityStarter hooks to keep ordinary launcher starts out of freeform roots. */
    fun hasVisibleFreeformTask(): Boolean =
        tasks.values.any { WindowState.isVisibleFreeform(it.windowState) }

    /**
     * Focused freeform windows must not drive status/navigation bar appearance. Otherwise opening
     * a small window over an immersive fullscreen app shows white system bars.
     */
    fun shouldIgnoreSystemUiFlags(taskId: Int): Boolean {
        val s = tasks[taskId] ?: return false
        return WindowState.isVisibleFreeform(s.windowState) || s.pinAnimating
    }

    /**
     * Safety net: an ordinary start inherited WINDOWING_MODE_FREEFORM from the focused small
     * window. Convert that untracked task back to fullscreen before it starts flickering.
     */
    fun revertUnsolicitedFreeformLaunch(taskId: Int, packageName: String) {
        if (taskId <= 0 || tasks.containsKey(taskId)) return
        if (isPendingFreeformLaunch(packageName)) return
        XLog.i("revert unsolicited freeform task=$taskId pkg=$packageName")
        SystemServices.resetTaskLeashForFullscreen(taskId)
        SystemServices.exitTaskToFullscreen(taskId)
    }

    /**
     * True during the first-layout gap before a pending module launch is added to [tasks], or for
     * an already tracked task. Used by the Task hook so the launch Activity never receives an
     * independently minimum-expanded configuration.
     */
    fun shouldRelaxTaskMinDimensions(taskId: Int, packageName: String?): Boolean {
        if (taskId > 0 && tasks.containsKey(taskId)) return true
        if (packageName.isNullOrBlank()) return false
        val active = activeLaunchGenerations[packageName] ?: return false
        return completedLaunchGenerations[packageName] != active
    }

    /**
     * Whether Task.resolveOverrideConfiguration should seed the selected density now. A newly
     * created ActivityOptions task can still report requested windowingMode=undefined on its very
     * first resolve, so the pending launch generation is the authoritative gate for that phase.
     * Once tracked, require an actual visible freeform mode so fullscreen/split exit can clear the
     * density normally.
     */
    fun shouldPrimeFreeformTaskConfiguration(
        taskId: Int,
        packageName: String?,
        requestedWindowingMode: Int,
    ): Boolean {
        val pending = packageName?.let { pkg ->
            val active = activeLaunchGenerations[pkg]
            active != null && completedLaunchGenerations[pkg] != active
        } == true
        if (pending) return true
        val state = tasks[taskId] ?: return false
        return WindowState.isVisibleFreeform(state.windowState) &&
            requestedWindowingMode == FreeformPolicy.WINDOWING_MODE_FREEFORM
    }

    fun isPendingFreeformLaunch(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val active = activeLaunchGenerations[packageName] ?: return false
        return completedLaunchGenerations[packageName] != active
    }

    /**
     * Current render/configuration bounds for a tracked visible task, else null.
     *
     * A scaled normal freeform has two rectangles: [FreeformTaskState.bounds] is the small visual
     * mask, while this source rectangle is what WM and the app actually render. Reporting the
     * visual mask as the app configuration makes minimum-size enforcement widen the child window
     * behind our back, which is exactly how content escaped the landscape mask.
     */
    fun freeformBoundsOf(taskId: Int): Rect? {
        val s = tasks[taskId] ?: return null
        if (!WindowState.isVisibleFreeform(s.windowState)) return null
        pendingOrientationTransitions[taskId]?.let { return Rect(it.sourceBounds) }
        return if (!s.landscapeTaskBounds.isEmpty) {
            Rect(s.landscapeTaskBounds)
        } else {
            normalTaskSourceBounds(s)
        }
    }

    /**
     * densityDpi a freeform task should render at: an explicit per-task override (setFreeformDpi)
     * wins; otherwise the user's global freeform DPI setting; 0 = follow system. Read by the
     * resolveOverrideConfiguration hook, so a fresh window opens at the right DPI with no relaunch.
     */
    fun freeformDpiOf(taskId: Int): Int {
        val perTask = tasks[taskId]?.freeformDpi ?: 0
        if (perTask > 0) return perTask
        return FreeformPolicy.freeformDpiFromSettings()
    }

    /**
     * Xiaomi-like in-window DPI zoom: override the densityDpi the freeform app renders at.
     * dpi<=0 resets to system density. The override is applied through the app's resolved
     * Configuration (HookSystem) and committed with a relayout so the app reloads resources.
     */
    override fun setFreeformDpi(taskId: Int, dpi: Int) {
        mainHandler.post {
            val state = tasks[taskId] ?: run {
                XLog.e("setFreeformDpi: unknown task=$taskId")
                return@post
            }
            val sysDpi = SystemServices.systemContext.resources.displayMetrics.densityDpi
            // Clamp to a sane band around the system density (~0.5x .. 2x).
            val normalized = if (dpi <= 0) 0 else dpi.coerceIn(
                (sysDpi * 0.5f).toInt().coerceAtLeast(120),
                (sysDpi * 2.0f).toInt(),
            )
            if (normalized == state.freeformDpi) return@post
            state.freeformDpi = normalized
            // Write density into the task's requested override config so WM dispatches the change.
            SystemServices.applyFreeformDensity(taskId, normalized, state.bounds)
            shell?.onStateChanged(state.snapshot())
            XLog.i("setFreeformDpi task=$taskId dpi=$normalized (system=$sysDpi)")
        }
    }

    override fun getFreeformDpi(taskId: Int): Int = tasks[taskId]?.freeformDpi ?: 0

    /**
     * Set the global in-window DPI percentage. Written to Settings.Global here (system_server has
     * WRITE permission; the app process does not) and applied live to all open freeform windows.
     */
    override fun setGlobalDpiPercent(percent: Int) {
        mainHandler.post {
            val clamped = FreeformBridge.sanitizeDpiPercent(percent)
            runCatching {
                Settings.Global.putInt(
                    SystemServices.systemContext.contentResolver,
                    FreeformPolicy.SETTINGS_DPI_PERCENT,
                    clamped,
                )
            }.onFailure { XLog.e("setGlobalDpiPercent write failed", it) }
            applyGlobalDpiToAll("service-set")
            XLog.i("setGlobalDpiPercent=$clamped")
        }
    }

    override fun getGlobalDpiPercent(): Int = runCatching {
        FreeformBridge.sanitizeDpiPercent(Settings.Global.getInt(
            SystemServices.systemContext.contentResolver,
            FreeformPolicy.SETTINGS_DPI_PERCENT,
            FreeformPolicy.DEFAULT_DPI_PERCENT,
        ))
    }.getOrDefault(FreeformPolicy.DEFAULT_DPI_PERCENT)

    /** Persist normal-window dimensions and resize every eligible open window immediately. */
    override fun setGlobalWindowSizePercent(widthPercent: Int, heightPercent: Int) {
        mainHandler.post {
            val width = FreeformBridge.sanitizeWindowSizePercent(widthPercent)
            val height = FreeformBridge.sanitizeWindowSizePercent(heightPercent)
            runCatching {
                val cr = SystemServices.systemContext.contentResolver
                Settings.Global.putInt(cr, FreeformPolicy.SETTINGS_WINDOW_WIDTH_PERCENT, width)
                Settings.Global.putInt(cr, FreeformPolicy.SETTINGS_WINDOW_HEIGHT_PERCENT, height)
            }.onFailure { XLog.e("setGlobalWindowSizePercent write failed", it) }
            applyGlobalWindowSizeToAll("service-set")
            XLog.i("setGlobalWindowSizePercent=${width}x$height")
        }
    }

    override fun getGlobalWindowWidthPercent(): Int =
        FreeformPolicy.windowWidthPercentFromSettings()

    override fun getGlobalWindowHeightPercent(): Int =
        FreeformPolicy.windowHeightPercentFromSettings()

    /** Debug: drive the app-orientation path directly (verify landscape freeform rotation). */
    override fun debugRequestOrientation(taskId: Int, orientation: Int) {
        XLog.i("debugRequestOrientation task=$taskId o=$orientation")
        // Faithfully mirror the real flow (bilibili fullscreen button → Activity.setRequestedOrientation):
        // record the orientation on the ActivityRecord so the auto-restore poll reads it as landscape
        // instead of fighting the render-full-scale back to portrait.
        runCatching { SystemServices.setTopActivityRequestedOrientation(taskId, orientation) }
        onAppRequestedOrientation(taskId, orientation)
    }

    override fun setSidebarSide(side: Int) {
        val s = side.coerceIn(0, 1)
        mainHandler.post {
            runCatching {
                Settings.Global.putInt(
                    SystemServices.systemContext.contentResolver,
                    io.hyper.freeform.xposed.shell.SidebarController.SETTING_SIDE,
                    s,
                )
            }.onFailure { XLog.e("setSidebarSide write failed", it) }
            XLog.i("setSidebarSide=$s")
            // ContentObserver on SidebarController also reacts; reattach is redundant-safe.
        }
    }

    override fun getSidebarSide(): Int = runCatching {
        Settings.Global.getInt(
            SystemServices.systemContext.contentResolver,
            io.hyper.freeform.xposed.shell.SidebarController.SETTING_SIDE,
            1,
        )
    }.getOrDefault(1).coerceIn(0, 1)

    /**
     * Persist the user's sidebar app selection (comma-separated packages). Written from
     * system_server (only it has WRITE_SECURE/GLOBAL permission for these keys) and observed by
     * SidebarController so the open panel refreshes live. Empty = show all launchable apps.
     */
    override fun setSidebarApps(packagesCsv: String?) {
        val cleaned = packagesCsv.orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(",")
        mainHandler.post {
            runCatching {
                Settings.Global.putString(
                    SystemServices.systemContext.contentResolver,
                    io.hyper.freeform.xposed.shell.SidebarController.SETTING_APPS,
                    cleaned,
                )
            }.onFailure { XLog.e("setSidebarApps write failed", it) }
            XLog.i("setSidebarApps count=${if (cleaned.isEmpty()) 0 else cleaned.split(',').size}")
        }
    }

    override fun getSidebarApps(): String = runCatching {
        Settings.Global.getString(
            SystemServices.systemContext.contentResolver,
            io.hyper.freeform.xposed.shell.SidebarController.SETTING_APPS,
        ) ?: ""
    }.getOrDefault("")

    override fun setSidebarShowAppNames(show: Boolean) {
        mainHandler.post {
            runCatching {
                Settings.Global.putInt(
                    SystemServices.systemContext.contentResolver,
                    io.hyper.freeform.xposed.shell.SidebarController.SETTING_SHOW_APP_NAMES,
                    if (show) 1 else 0,
                )
            }.onFailure { XLog.e("setSidebarShowAppNames write failed", it) }
            XLog.i("setSidebarShowAppNames=$show")
        }
    }

    override fun getSidebarShowAppNames(): Boolean = runCatching {
        Settings.Global.getInt(
            SystemServices.systemContext.contentResolver,
            io.hyper.freeform.xposed.shell.SidebarController.SETTING_SHOW_APP_NAMES,
            0,
        ) != 0
    }.getOrDefault(false)

    /** Portrait normal bounds remembered before an app-requested landscape rotation. */
    private val preLandscapeBounds = ConcurrentHashMap<Int, Rect>()

    /**
     * Canonical geometry published synchronously when an app requests orientation, before WM starts
     * resolving that request. Configuration hooks read this so no stale portrait/landscape frame is
     * delivered while the actual task resize is waiting on the system-server main thread.
     */
    private data class PendingOrientationTransition(
        val orientation: Int,
        val landscape: Boolean,
        val visualBounds: Rect,
        val sourceBounds: Rect,
    )
    private val pendingOrientationTransitions =
        ConcurrentHashMap<Int, PendingOrientationTransition>()
    private val pendingOrientationApplies = ConcurrentHashMap<Int, Runnable>()

    /** Pending restore for an ambiguous orientation emitted during a player surface rebuild. */
    private val pendingLandscapeRestores = ConcurrentHashMap<Int, Runnable>()

    private const val LANDSCAPE_RESTORE_DEBOUNCE_MS = 1200L

    private fun cancelPendingLandscapeRestore(taskId: Int) {
        pendingLandscapeRestores.remove(taskId)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun scheduleLandscapeRestore(taskId: Int, orientation: Int) {
        if (pendingLandscapeRestores.containsKey(taskId)) return
        val action = Runnable {
            pendingLandscapeRestores.remove(taskId)
            val state = tasks[taskId] ?: return@Runnable
            if (state.landscapeTaskBounds.isEmpty || state.windowState != WindowState.NORMAL) {
                return@Runnable
            }
            val current = SystemServices.topActivityRequestedOrientation(taskId) ?: orientation
            if (FreeformPolicy.isLandscapeOrientation(current)) return@Runnable
            XLog.i(
                "restore landscape after stable non-landscape request task=$taskId " +
                    "orientation=$current",
            )
            onAppRequestedOrientation(taskId, current)
        }
        pendingLandscapeRestores[taskId] = action
        mainHandler.postDelayed(action, LANDSCAPE_RESTORE_DEBOUNCE_MS)
    }

    /**
     * Xiaomi MiuiFreeFormManagerService.setRequestedOrientation port:
     * when the app inside a freeform requests landscape, rotate the freeform window itself
     * to a landscape rectangle (so landscape video plays inside the small window instead of
     * being refused as split-screen). Requesting portrait again restores the prior bounds.
     */
    fun onAppRequestedOrientation(taskId: Int, orientation: Int) {
        val state = tasks[taskId] ?: return
        if (state.windowState != WindowState.NORMAL) return
        val wantLandscape = FreeformPolicy.isLandscapeOrientation(orientation)
        val wantPortrait = FreeformPolicy.isPortraitOrientation(orientation)
        val pending = pendingOrientationTransitions[taskId]
        val appLandscape = !state.landscapeTaskBounds.isEmpty || pending?.landscape == true

        val transition = when {
            wantLandscape -> {
                cancelPendingLandscapeRestore(taskId)
                val portrait = Rect(
                    preLandscapeBounds[taskId]
                        ?: pending?.takeIf { !it.landscape }?.visualBounds
                        ?: state.bounds,
                )
                preLandscapeBounds.putIfAbsent(taskId, Rect(portrait))
                val visual = FreeformPolicy.landscapeBoundsFor(portrait)
                PendingOrientationTransition(
                    orientation = orientation,
                    landscape = true,
                    visualBounds = Rect(visual),
                    sourceBounds = FreeformPolicy.landscapeSourceBoundsFor(portrait),
                )
            }
            wantPortrait && appLandscape -> {
                cancelPendingLandscapeRestore(taskId)
                val restore = Rect(
                    preLandscapeBounds[taskId]
                        ?: pending?.takeIf { !it.landscape }?.visualBounds
                        ?: FreeformPolicy.defaultNormalBounds(),
                )
                PendingOrientationTransition(
                    orientation = orientation,
                    landscape = false,
                    visualBounds = Rect(restore),
                    sourceBounds = Rect(restore),
                )
            }
            else -> {
                if (appLandscape) scheduleLandscapeRestore(taskId, orientation)
                return
            }
        }

        // Publish before ActivityRecord resolves the request. Repeated controller/ActivityRecord
        // hooks simply replace the plan; one main-thread runnable commits only the latest request.
        pendingOrientationTransitions[taskId] = transition
        val action = Runnable {
            val currentAction = pendingOrientationApplies.remove(taskId)
            if (currentAction == null) return@Runnable
            val planned = pendingOrientationTransitions[taskId] ?: return@Runnable
            val current = tasks[taskId] ?: run {
                pendingOrientationTransitions.remove(taskId, planned)
                return@Runnable
            }
            if (current.windowState != WindowState.NORMAL) {
                pendingOrientationTransitions.remove(taskId, planned)
                return@Runnable
            }

            if (planned.landscape) {
                val prevVisual = Rect(current.bounds)
                current.landscape = true
                current.bounds = Rect(planned.visualBounds)
                // Preserve restoreNormalBounds: it is the configured portrait window, not the
                // temporary landscape card.
                current.landscapeTaskBounds = Rect(planned.sourceBounds)
                stopLiveResizeVisual(taskId)
                applyNormalGeometry(taskId, current)
                SystemServices.applyFreeformDensity(
                    taskId,
                    freeformDpiOf(taskId),
                    current.landscapeTaskBounds,
                )
                // Density dispatch triggers another surface placement; finish with the canonical
                // crop/scale/radius and its settle guard so corners survive the config change.
                applyNormalGeometry(taskId, current)
                // MIUI orientation sweep: ROTATE_POSITION_Z_EASE spring(0.95, 0.42) leash
                // transition from the portrait card to the landscape card (visual only;
                // the task config already committed above).
                animateOrientationTransition(taskId, current, prevVisual)
                shell?.onStateChanged(current.snapshot())
                XLog.i(
                    "app landscape fullscreen task=$taskId source=${current.landscapeTaskBounds} " +
                        "vis=${current.bounds} dpi=${freeformDpiOf(taskId)}",
                )
            } else {
                val prevVisual = Rect(current.bounds)
                val restore = Rect(planned.visualBounds)
                preLandscapeBounds.remove(taskId)
                current.landscape = false
                current.bounds = Rect(restore)
                current.restoreNormalBounds = Rect(restore)
                current.landscapeTaskBounds = Rect()
                stopLiveResizeVisual(taskId)
                applyNormalGeometry(taskId, current)
                SystemServices.applyFreeformDensity(taskId, freeformDpiOf(taskId), restore)
                // Re-assert after density/config dispatch; identity-size windows need the same
                // late corner-radius guard as scaled landscape windows.
                applyNormalGeometry(taskId, current)
                animateOrientationTransition(taskId, current, prevVisual)
                shell?.onStateChanged(current.snapshot())
                XLog.i(
                    "app portrait freeform task=$taskId -> $restore " +
                        "(orientation=${planned.orientation} dpi=${freeformDpiOf(taskId)})",
                )
            }
            pendingOrientationTransitions.remove(taskId, planned)
        }
        if (pendingOrientationApplies.putIfAbsent(taskId, action) == null) {
            mainHandler.post(action)
        }
    }

    fun allStates(): List<FreeformTaskState> = tasks.values.map { it.snapshot() }

    fun updateState(taskId: Int, mutator: (FreeformTaskState) -> Unit) {
        tasks[taskId]?.let {
            mutator(it)
            mainHandler.post { shell?.onStateChanged(it.snapshot()) }
        }
    }

    override fun getVersionName(): String = VERSION_NAME
    override fun getVersionCode(): Int = VERSION_CODE
    override fun isReady(): Boolean = ready

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        runOnMain {
            runCatching {
                Settings.Global.putInt(
                    SystemServices.systemContext.contentResolver,
                    FreeformBridge.SETTING_ENABLED,
                    if (enabled) 1 else 0,
                )
            }.onFailure { XLog.e("setEnabled bridge state write failed", it) }
            ensureFreeformSupport()
        }
    }

    override fun isEnabled(): Boolean = enabled

    override fun collapseStatusBar() {
        SystemServices.collapseStatusBar()
    }

    /**
     * Debug/MuMu helper: drive the same path as real IME hooks.
     * visible=false clears avoid and restores preImeBounds.
     */
    override fun debugSimulateIme(visible: Boolean, height: Int) {
        // Force path for MuMu (soft IME rarely surfaces). Poll will not clobber while forced.
        imeForced = visible && height > 0
        XLog.d("debugSimulateIme visible=$visible height=$height forced=$imeForced")
        onImeVisibilityChanged(visible, height)
    }

    override fun getOpenWindowCount(): Int =
        tasks.values.count { WindowState.isVisibleFreeform(it.windowState) }

    override fun getOpenTaskIds(): IntArray =
        tasks.filterValues { WindowState.isVisibleFreeform(it.windowState) || WindowState.isPinned(it.windowState) }
            .keys.toIntArray()

    override fun dumpState(): String = buildString {
        appendLine(
            "ready=$ready enabled=$enabled count=${tasks.size} " +
                "imeVisible=$imeVisible imeHeight=$imeHeight display=${lastDisplayW}x${lastDisplayH}"
        )
        appendLine("capability ${FreeformPolicy.capabilityDump()}")
        appendLine("sidebar=${FreeformPolicy.sidebarBounds()}")
        tasks.values.forEach {
            appendLine(
                "task=${it.taskId} pkg=${it.packageName} state=${WindowState.label(it.windowState)} " +
                    "fgPin=${it.foregroundPin} component=${it.component} " +
                    "bounds=${it.bounds} scale=${it.scale} imeAvoid=${it.imeAvoiding} " +
                    "pinPos=${it.pinPos} pinY=${it.pinY} pinFloat=${it.pinFloatingWindowPos} " +
                    "preIme=${it.preImeBounds} restoreN=${it.restoreNormalBounds} restoreM=${it.restoreMiniBounds}"
            )
        }
    }

    override fun startFreeform(component: ComponentName?, userId: Int, windowState: Int) {
        if (component == null) return
        mainHandler.post {
            startFreeformInternal(component, userId, windowState, allowMultipleTask = false)
        }
    }

    override fun startFreeformPackage(packageName: String?, userId: Int, windowState: Int) {
        if (packageName.isNullOrBlank()) return
        mainHandler.post {
            if (FreeformPolicy.isBlacklisted(packageName)) {
                XLog.e("Blacklisted package $packageName")
                return@post
            }
            val launch = SystemServices.packageManager.getLaunchIntentForPackage(packageName)
            val component = launch?.component
            if (component == null) {
                XLog.e("No launch activity for $packageName")
                return@post
            }
            startFreeformInternal(component, userId, windowState, allowMultipleTask = false)
        }
    }

    /**
     * Track and harden the task created by SystemUI sending a notification's original
     * PendingIntent.  Launching stays in SystemUI because reconstructing an Intent here would lose
     * the notification's destination action, data, extras, ClipData and task-stack identity.
     */
    private fun adoptPendingIntentLaunch(
        packageName: String,
        requestedComponent: ComponentName?,
        requestedBounds: Rect,
        desiredState: Int,
    ) {
        mainHandler.post {
            if (!enabled || !FreeformPolicy.supportsFreeform()) {
                XLog.e("Cannot adopt notification launch: freeform unavailable")
                return@post
            }
            if (FreeformPolicy.isBlacklisted(packageName)) {
                XLog.e("Blacklisted notification target $packageName")
                return@post
            }

            val component = requestedComponent
                ?: SystemServices.packageManager.getLaunchIntentForPackage(packageName)?.component
                ?: ComponentName(packageName, packageName)
            enforceWindowLimit(addingComponent = component.flattenToString())

            val state = if (desiredState == WindowState.MINI) {
                WindowState.MINI
            } else {
                WindowState.NORMAL
            }
            val desired = FreeformPolicy.clampBounds(requestedBounds)
            val samePackageTask = tasks.values.firstOrNull { it.packageName == packageName }
            val otherVisible = tasks.values.filter {
                it.packageName != packageName && WindowState.isVisibleFreeform(it.windowState)
            }
            var bounds = when {
                samePackageTask != null -> Rect(samePackageTask.bounds)
                state == WindowState.NORMAL && otherVisible.size == 1 ->
                    secondaryNormalBounds(desired, otherVisible.first().bounds)
                else -> FreeformPolicy.placeNewFreeformBounds(
                    desired,
                    otherVisible.map { Rect(it.bounds) },
                )
            }
            if (state == WindowState.MINI) {
                bounds = FreeformPolicy.adjustBoundsForSidebarIfNeed(bounds)
            }
            val normalBaseBounds = if (state == WindowState.NORMAL) {
                FreeformPolicy.normalBaseBoundsForVisual(desired)
            } else {
                desired
            }

            val generation = launchGeneration.incrementAndGet()
            activeLaunchGenerations[packageName] = generation
            completedLaunchGenerations.remove(packageName)
            launchAnimationDone.remove(packageName)

            fun bind(attempt: Int) {
                bindLaunchedTask(
                    component,
                    0,
                    state,
                    bounds,
                    normalBaseBounds,
                    generation,
                    attempt,
                )
            }
            bind(0)
            mainHandler.postDelayed({ bind(1) }, 80L)
            mainHandler.postDelayed({ bind(2) }, 280L)
            mainHandler.postDelayed({ bind(3) }, 700L)
            XLog.i(
                "Adopting notification PendingIntent launch pkg=$packageName " +
                    "component=$component state=${WindowState.label(state)} bounds=$bounds",
            )
        }
    }


    /**
     * Xiaomi startSmallFreeformFromRecent / lunchSmallFreeformFromRecent equivalent.
     * Converts an existing task (from Recents) into WINDOWING_MODE_FREEFORM + windowState,
     * rather than cold-starting a new activity (notification MULTIPLE_TASK path).
     */
    override fun startFreeformFromRecent(taskId: Int, windowState: Int) {
        if (taskId <= 0) return
        mainHandler.post {
            startFreeformFromRecentInternal(taskId, windowState)
        }
    }

    private fun startFreeformFromRecentInternal(taskId: Int, desiredState: Int) {
        // Explicit Recents hot-zone conversion is an intentional opt-in; it may clear the
        // navigation tombstone left by a previous freeform maximize for this task.
        clearForegroundMiniSuppression(taskId)
        if (!enabled) {
            XLog.e("Freeform disabled")
            return
        }
        if (!FreeformPolicy.supportsFreeform()) {
            XLog.e("Device does not support freeform")
            return
        }

        val task = SystemServices.findTask(taskId)
        val pkg = resolvePackageFromTask(task, taskId)
        if (pkg.isNullOrBlank()) {
            XLog.e("startFreeformFromRecent: cannot resolve package for task=$taskId")
            return
        }
        if (FreeformPolicy.isBlacklisted(pkg)) {
            XLog.e("Blacklisted package $pkg (fromRecent task=$taskId)")
            return
        }

        // Already tracked freeform: just switch mini/normal as requested.
        val existing = tasks[taskId]
        if (existing != null && WindowState.isVisibleFreeform(existing.windowState)) {
            val wantMini = desiredState == WindowState.MINI
            if (wantMini && existing.windowState != WindowState.MINI) {
                switchMini(taskId, true)
            } else if (!wantMini && existing.windowState == WindowState.MINI) {
                switchMini(taskId, false)
            } else {
                runCatching { SystemServices.activityManager.moveTaskToFront(taskId, 0) }
                applySurfaceStyle(taskId, existing.bounds, existing.windowState)
            }
            XLog.i("startFreeformFromRecent: already freeform task=$taskId -> reassert")
            return
        }

        val recentComponent = resolveComponentFromTask(task, pkg)?.flattenToString()
        enforceWindowLimit(addingComponent = recentComponent)

        val state = if (desiredState == WindowState.MINI) WindowState.MINI else WindowState.NORMAL
        val desired = if (state == WindowState.MINI) {
            FreeformPolicy.defaultMiniBounds()
        } else {
            FreeformPolicy.defaultNormalBounds()
        }
        val existingBounds = tasks.values
            .filter { WindowState.isVisibleFreeform(it.windowState) }
            .map { Rect(it.bounds) }
        var bounds = FreeformPolicy.placeNewFreeformBounds(desired, existingBounds)
        if (state == WindowState.MINI) {
            bounds = FreeformPolicy.adjustBoundsForSidebarIfNeed(bounds)
        }
        // MINI is a suspended preview of a normally-sized freeform task.  The visual bounds are
        // deliberately small, but the Task configuration and the single-tap restore target must
        // remain the ordinary normal-window size.  Using `desired` here used to save the MINI
        // rectangle as restoreNormalBounds: the first tap changed the logical state to NORMAL
        // without changing its size, after which subsequent touches fell through to the app.
        val normalBaseBounds = FreeformPolicy.normalBaseBoundsForVisual(
            if (state == WindowState.MINI) FreeformPolicy.defaultNormalBounds() else desired,
        )
        val taskBounds = if (state == WindowState.MINI) {
            Rect(normalBaseBounds)
        } else {
            Rect(bounds)
        }

        // Leash continuity (reduce black frame): apply freeform mode+bounds+style BEFORE
        // startActivityFromRecents so overview exit does not flash fullscreen / empty frame.
        // Xiaomi packs setWindowingMode/setBounds into the same WCT/RemoteTransition.
        hardenFreeformTask(taskId, taskBounds)
        if (state == WindowState.MINI) {
            SystemServices.applyMiniFreeformVisual(taskId, taskBounds, bounds)
        } else {
            applySurfaceStyle(taskId, bounds, state)
        }

        // Prefer AOSP FreeformSystemShortcut path: startActivityFromRecents + freeform options.
        // Fallback: direct setTaskWindowingMode + resize already applied above.
        val launched = startActivityFromRecentsFreeform(taskId, taskBounds)
        if (!launched) {
            XLog.d("startActivityFromRecents failed/unavailable, harden path for task=$taskId")
        }
        // Re-assert immediately after recents launch (same frame path).
        hardenFreeformTask(taskId, taskBounds)
        if (state == WindowState.MINI) {
            SystemServices.applyMiniFreeformVisual(taskId, taskBounds, bounds)
        } else {
            applySurfaceStyle(taskId, bounds, state)
        }

        val userId = resolveUserIdFromTask(task)
        val componentName = resolveComponentFromTask(task, pkg)
        val taskState = FreeformTaskState(
            taskId = taskId,
            windowState = state,
            bounds = Rect(bounds),
            scale = FreeformPolicy.scaleFromBounds(bounds, normalBaseBounds),
            restoreNormalBounds = Rect(normalBaseBounds),
            restoreMiniBounds = FreeformPolicy.defaultMiniBounds(),
            packageName = pkg,
            userId = userId,
            component = componentName?.flattenToString().orEmpty(),
            activeTime = System.currentTimeMillis(),
        )
        if (state == WindowState.NORMAL) {
            taskState.restoreNormalBounds = Rect(normalBaseBounds)
        } else {
            taskState.restoreMiniBounds = Rect(bounds)
        }
        tasks[taskId] = taskState
        shell?.onTaskAdded(taskState.snapshot())
        mainHandler.removeCallbacks(imePollRunnable)
        mainHandler.post(imePollRunnable)

        if (taskState.windowState == WindowState.NORMAL) {
            applyNormalGeometry(taskId, taskState)
        } else {
            SystemServices.applyMiniFreeformVisual(
                taskId,
                miniSourceBounds(taskState),
                taskState.bounds,
            )
        }

        runCatching { SystemServices.activityManager.moveTaskToFront(taskId, 0) }
        // Late harden: some builds re-apply fullscreen after leaving overview.
        // Keep short + one longer backup; surface style applied each time.
        mainHandler.postDelayed({
            tasks[taskId]?.let { current ->
                setTaskWindowingMode(taskId, FreeformPolicy.WINDOWING_MODE_FREEFORM)
                if (current.windowState == WindowState.NORMAL) {
                    applyNormalGeometry(taskId, current)
                } else {
                    hardenFreeformTask(taskId, miniSourceBounds(current))
                    applySurfaceStyle(taskId, current.bounds, current.windowState)
                }
            }
        }, 120)
        mainHandler.postDelayed({
            tasks[taskId]?.let { current ->
                setTaskWindowingMode(taskId, FreeformPolicy.WINDOWING_MODE_FREEFORM)
                if (current.windowState == WindowState.NORMAL) {
                    applyNormalGeometry(taskId, current)
                } else {
                    hardenFreeformTask(taskId, miniSourceBounds(current))
                    applySurfaceStyle(taskId, current.bounds, current.windowState)
                }
            }
        }, 450)

        XLog.i(
            "Started freeform FROM_RECENT task=$taskId pkg=$pkg " +
                "state=${WindowState.label(state)} bounds=$bounds launched=$launched " +
                "preHardened=true"
        )
    }

    private fun startActivityFromRecentsFreeform(taskId: Int, bounds: Rect): Boolean {
        return runCatching {
            val options = ActivityOptions.makeBasic()
            setLaunchWindowingMode(options, FreeformPolicy.WINDOWING_MODE_FREEFORM)
            setLaunchBounds(options, bounds)
            requestIconSplashScreen(options)
            val bundle = options.toBundle()

            val r1 = runCatching {
                SystemServices.invokeAtm("startActivityFromRecents", taskId, bundle)
            }.getOrNull()
            if (r1 != null) {
                XLog.d("startActivityFromRecents ATM => $r1")
                return@runCatching true
            }

            val r2 = runCatching {
                val atmClz = Class.forName("android.app.ActivityTaskManager")
                val svc = atmClz.getMethod("getService").invoke(null)
                val m = svc.javaClass.methods.firstOrNull {
                    it.name == "startActivityFromRecents" && it.parameterTypes.size == 2
                } ?: return@runCatching null
                m.invoke(svc, taskId, bundle)
            }.getOrNull()
            if (r2 != null) {
                XLog.d("startActivityFromRecents ActivityTaskManager => $r2")
                return@runCatching true
            }

            val r3 = runCatching {
                val am = SystemServices.activityManager
                val m = am.javaClass.methods.firstOrNull {
                    it.name == "startActivityFromRecents" && it.parameterTypes.size >= 2
                } ?: return@runCatching null
                m.invoke(am, taskId, bundle)
            }.getOrNull()
            r3 != null
        }.onFailure {
            XLog.e("startActivityFromRecentsFreeform failed task=$taskId", it)
        }.getOrDefault(false)
    }

    private fun resolvePackageFromTask(task: Any?, taskId: Int): String? {
        if (task != null) {
            for (name in listOf(
                "realActivity", "mRealActivity", "topActivity", "mTopActivity", "origActivity"
            )) {
                val pkg = runCatching {
                    var c: Class<*>? = task.javaClass
                    var v: Any? = null
                    while (c != null && v == null) {
                        try {
                            val f = c.getDeclaredField(name)
                            f.isAccessible = true
                            v = f.get(task)
                        } catch (_: NoSuchFieldException) {
                            c = c.superclass
                        }
                    }
                    when (v) {
                        is ComponentName -> v.packageName
                        is String -> v.substringBefore('/').takeIf { it.contains('.') }
                        else -> null
                    }
                }.getOrNull()
                if (!pkg.isNullOrBlank()) return pkg
            }

            val intentPkg = runCatching {
                var c: Class<*>? = task.javaClass
                var intent: Any? = null
                while (c != null && intent == null) {
                    for (fname in listOf("intent", "mIntent", "mBaseIntent")) {
                        try {
                            val f = c.getDeclaredField(fname)
                            f.isAccessible = true
                            intent = f.get(task)
                            if (intent != null) break
                        } catch (_: NoSuchFieldException) {
                        }
                    }
                    c = c.superclass
                }
                (intent as? Intent)?.`package`
                    ?: (intent as? Intent)?.component?.packageName
            }.getOrNull()
            if (!intentPkg.isNullOrBlank()) return intentPkg

            for (mName in listOf("getBasePackageName", "getPackageName")) {
                val v = runCatching {
                    task.javaClass.methods.firstOrNull {
                        it.name == mName && it.parameterTypes.isEmpty()
                    }?.invoke(task) as? String
                }.getOrNull()
                if (!v.isNullOrBlank() && v.contains('.')) return v
            }
        }

        return runCatching {
            @Suppress("DEPRECATION")
            val list = SystemServices.activityManager.getRunningTasks(64)
            val info = list.firstOrNull { it.id == taskId || it.taskId == taskId }
            info?.topActivity?.packageName
                ?: info?.baseActivity?.packageName
        }.getOrNull()
    }

    private fun resolveUserIdFromTask(task: Any?): Int {
        if (task == null) return 0
        for (name in listOf("mUserId", "userId")) {
            val v = runCatching {
                var c: Class<*>? = task.javaClass
                while (c != null) {
                    try {
                        val f = c.getDeclaredField(name)
                        f.isAccessible = true
                        return@runCatching f.getInt(task)
                    } catch (_: NoSuchFieldException) {
                        c = c.superclass
                    }
                }
                null
            }.getOrNull()
            if (v != null) return v
        }
        return 0
    }

    private fun resolveComponentFromTask(task: Any?, pkg: String): ComponentName? {
        if (task != null) {
            for (name in listOf("realActivity", "mRealActivity", "topActivity", "mTopActivity")) {
                val v = runCatching {
                    var c: Class<*>? = task.javaClass
                    var value: Any? = null
                    while (c != null && value == null) {
                        try {
                            val f = c.getDeclaredField(name)
                            f.isAccessible = true
                            value = f.get(task)
                        } catch (_: NoSuchFieldException) {
                            c = c.superclass
                        }
                    }
                    value as? ComponentName
                }.getOrNull()
                if (v != null) return v
            }
        }
        return runCatching {
            SystemServices.packageManager.getLaunchIntentForPackage(pkg)?.component
        }.getOrNull()
    }

    /**
     * With one full-size freeform already visible, a second 960px card can cover roughly 70% of
     * it on a 1440px display and looks as if the first app vanished.  Launch the second as a 0.75
     * visual card on the opposite edge; bindLaunchedTask still keeps the full normal render source.
     */
    private fun secondaryNormalBounds(desired: Rect, existing: Rect): Rect {
        val restriction = FreeformPolicy.movableRestriction()
        val (displayW, _) = FreeformPolicy.displaySize()
        val width = (desired.width() * 0.75f).toInt().coerceAtLeast(1)
        val height = (desired.height() * 0.75f).toInt().coerceAtLeast(1)
        val existingOnLeft = existing.exactCenterX() < displayW / 2f
        val left = if (existingOnLeft) {
            restriction.right - width
        } else {
            restriction.left
        }.coerceIn(restriction.left, (restriction.right - width).coerceAtLeast(restriction.left))
        val top = desired.top.coerceIn(
            restriction.top,
            (restriction.bottom - height).coerceAtLeast(restriction.top),
        )
        return FreeformPolicy.clampBounds(Rect(left, top, left + width, top + height))
    }

    private fun startFreeformInternal(
        component: ComponentName,
        userId: Int,
        desiredState: Int,
        allowMultipleTask: Boolean,
    ) {
        if (!enabled) {
            XLog.e("Freeform disabled")
            return
        }
        if (!FreeformPolicy.supportsFreeform()) {
            XLog.e("Device does not support freeform")
            return
        }
        if (FreeformPolicy.isBlacklisted(component.packageName)) {
            XLog.e("Blacklisted package ${component.packageName}")
            return
        }

        if (!allowMultipleTask) {
            val alreadyOpen = tasks.values.firstOrNull {
                it.packageName == component.packageName
            }
            if (alreadyOpen != null) {
                touchActive(alreadyOpen.taskId)
                when {
                    WindowState.isPinned(alreadyOpen.windowState) -> {
                        unpinTask(alreadyOpen.taskId)
                    }
                    WindowState.isVisibleFreeform(alreadyOpen.windowState) -> {
                        runCatching { SystemServices.activityManager.moveTaskToFront(alreadyOpen.taskId, 0) }
                        val wantMini = desiredState == WindowState.MINI
                        if (wantMini && alreadyOpen.windowState != WindowState.MINI) {
                            switchMini(alreadyOpen.taskId, true)
                        } else if (!wantMini && alreadyOpen.windowState == WindowState.MINI) {
                            switchMini(alreadyOpen.taskId, false)
                        } else {
                            applySurfaceStyle(alreadyOpen.taskId, alreadyOpen.bounds, alreadyOpen.windowState)
                            shell?.onTaskFocused(alreadyOpen.taskId)
                        }
                    }
                    else -> startFreeformFromRecentInternal(alreadyOpen.taskId, desiredState)
                }
                XLog.d(
                    "Reuse tracked package task=${alreadyOpen.taskId} " +
                        "pkg=${component.packageName} state=${WindowState.label(alreadyOpen.windowState)}"
                )
                return
            }

            val backgroundTaskId = findTaskId(component.packageName)
            if (backgroundTaskId > 0) {
                XLog.i(
                    "Reuse background task=$backgroundTaskId pkg=${component.packageName} " +
                        "for freeform launch"
                )
                startFreeformFromRecentInternal(backgroundTaskId, desiredState)
                return
            }

            val now = android.os.SystemClock.uptimeMillis()
            val previous = lastPackageLaunchAt.put(component.packageName, now)
            if (previous != null && now - previous < 800L) {
                XLog.d("Debounce duplicate package launch pkg=${component.packageName}")
                return
            }
        }

        val generation = launchGeneration.incrementAndGet()
        activeLaunchGenerations[component.packageName] = generation
        completedLaunchGenerations.remove(component.packageName)

        enforceWindowLimit(addingComponent = component.flattenToString())

        val state = if (desiredState == WindowState.MINI) WindowState.MINI else WindowState.NORMAL
        val desired = if (state == WindowState.MINI) {
            FreeformPolicy.defaultMiniBounds()
        } else {
            FreeformPolicy.defaultNormalBounds()
        }
        // Xiaomi avoidOtherFreeformTaskIfNeed: do not fully cover existing freeforms.
        val existingStates = tasks.values
            .filter { WindowState.isVisibleFreeform(it.windowState) }
        val existingBounds = existingStates.map { Rect(it.bounds) }
        var bounds = if (state == WindowState.NORMAL && existingStates.size == 1) {
            secondaryNormalBounds(desired, existingStates.first().bounds)
        } else {
            FreeformPolicy.placeNewFreeformBounds(desired, existingBounds)
        }
        if (state == WindowState.MINI) {
            bounds = FreeformPolicy.adjustBoundsForSidebarIfNeed(bounds)
        }
        val normalBaseBounds = if (state == WindowState.NORMAL) {
            FreeformPolicy.normalBaseBoundsForVisual(desired)
        } else {
            desired
        }

        shell?.showLaunchSplash(component.packageName, bounds)
        launchAnimationDone.remove(component.packageName)

        try {
            val intent = Intent().apply {
                this.component = component
                `package` = component.packageName
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                if (allowMultipleTask) addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            }

            val options = ActivityOptions.makeBasic()
            setLaunchWindowingMode(options, FreeformPolicy.WINDOWING_MODE_FREEFORM)
            setLaunchBounds(options, bounds)
            requestIconSplashScreen(options)

            val userHandle = userHandleOf(userId)
            val ctx = SystemServices.systemContext
            // Prefer startActivityAsUser
            try {
                val method = ctx.javaClass.getMethod(
                    "startActivityAsUser",
                    Intent::class.java,
                    android.os.Bundle::class.java,
                    UserHandle::class.java
                )
                method.invoke(ctx, intent, options.toBundle(), userHandle)
            } catch (_: Throwable) {
                ctx.startActivity(intent, options.toBundle())
            }

            // startActivity is synchronous through ATMS. Resolve and harden in this same main-loop
            // turn so the minimum-size correction is merged into the opening transition instead
            // of appearing later as a second CHANGE flash.
            bindLaunchedTask(
                component, userId, state, bounds, normalBaseBounds, generation, attempt = 0,
            )
            // Backups catch builds that do not expose the task until a later traversal, plus
            // delayed fullscreen reverts.
            mainHandler.postDelayed({
                bindLaunchedTask(
                    component, userId, state, bounds, normalBaseBounds, generation, attempt = 1,
                )
            }, 80)
            mainHandler.postDelayed({
                bindLaunchedTask(
                    component, userId, state, bounds, normalBaseBounds, generation, attempt = 2,
                )
            }, 280)
            mainHandler.postDelayed({
                bindLaunchedTask(
                    component, userId, state, bounds, normalBaseBounds, generation, attempt = 3,
                )
            }, 700)
        } catch (t: Throwable) {
            shell?.dismissLaunchSplash(component.packageName)
            XLog.e("startFreeform failed for $component", t)
        }
    }

    /**
     * After startActivity, force WINDOWING_MODE_FREEFORM + bounds if launch options were ignored.
     */
    private fun bindLaunchedTask(
        component: ComponentName,
        userId: Int,
        state: Int,
        bounds: Rect,
        normalBaseBounds: Rect,
        generation: Long,
        attempt: Int,
    ) {
        if (activeLaunchGenerations[component.packageName] != generation ||
            completedLaunchGenerations[component.packageName] == generation
        ) return
        val taskId = findTaskId(component.packageName)
        if (taskId <= 0) {
            if (attempt >= 3) {
                XLog.e("Could not resolve task for ${component.packageName}")
            }
            return
        }
        val existing = tasks[taskId]
        if (existing == null) {
            val taskState = FreeformTaskState(
                taskId = taskId,
                windowState = state,
                bounds = Rect(bounds),
                scale = if (state == WindowState.NORMAL) {
                    FreeformPolicy.scaleFromBounds(bounds, normalBaseBounds)
                } else 1f,
                restoreNormalBounds = if (state == WindowState.NORMAL) {
                    Rect(normalBaseBounds)
                } else {
                    FreeformPolicy.normalBaseBoundsForVisual(bounds)
                },
                restoreMiniBounds = FreeformPolicy.defaultMiniBounds(),
                packageName = component.packageName,
                userId = userId,
                component = component.flattenToString(),
                activeTime = System.currentTimeMillis(),
            )
            if (state == WindowState.NORMAL) {
                // Keep a full normal render source even when the second window launches as a
                // smaller visual card, so both apps remain visible without shrinking app layout.
                taskState.restoreNormalBounds = Rect(normalBaseBounds)
            } else {
                taskState.restoreMiniBounds = Rect(bounds)
            }
            tasks[taskId] = taskState
            shell?.onTaskAdded(taskState.snapshot())
            mainHandler.removeCallbacks(imePollRunnable)
            mainHandler.post(imePollRunnable)
            XLog.i(
                "Started freeform task=$taskId ${component.packageName} " +
                    "state=${WindowState.label(state)} bounds=$bounds attempt=$attempt"
            )
        } else {
            // Keep tracked bounds authoritative on re-bind.
            existing.bounds = Rect(bounds)
            existing.windowState = state
        }
        completedLaunchGenerations[component.packageName] = generation
        // Use the tracked visual/source pair so WM's task-size floor cannot diverge from chrome.
        setTaskWindowingMode(taskId, FreeformPolicy.WINDOWING_MODE_FREEFORM)
        if (!SystemServices.setFreeformAlwaysOnTop(taskId, true)) {
            XLog.e("launch always-on-top apply failed task=$taskId")
        }
        val tracked = tasks[taskId]
        if (tracked?.windowState == WindowState.NORMAL) {
            applyNormalGeometry(taskId, tracked)
        } else {
            hardenFreeformTask(taskId, bounds)
            applySurfaceStyle(taskId, bounds, state)
        }
        // HookSystem injects the selected DPI while ActivityRecord resolves its very first
        // freeform configuration. Writing the task override here, after the launch activity has
        // resumed, forces a density relaunch; apps with duplicate-launch guards (for example TIM)
        // then close both launch activities. Keep post-launch task configuration unchanged.
        animateOpenLaunch(taskId, component.packageName)
        waitForLaunchWindowDrawn(taskId, component.packageName)
    }

    /**
     * MIUI enter-freeform open animation (TO_FREEFORM_POSITION_SIZE_EASE = spring(0.95, 0.4)):
     * the window grows from 0.86x and fades in (openVisualFrame), with the opaque launch splash
     * tracking the same frames. The old one-shot leash animation visibly restarted when WM
     * placed the first app surface mid-flight (zoom glitch); this 16ms self-healing tick
     * re-asserts the current frame instead, so an external reset heals within one frame.
     */
    private fun animateOpenLaunch(taskId: Int, packageName: String) {
        val state = tasks[taskId]
        if (state == null || !WindowState.isVisibleFreeform(state.windowState)) {
            finishOpenWithoutScale(taskId, packageName)
            return
        }
        val mini = state.windowState == WindowState.MINI
        val bounds = Rect(state.bounds)
        val source = if (mini) miniSourceBounds(state) else normalTaskSourceBounds(state)
        val startFrame = FreeformPolicy.openVisualFrame(bounds, 0f)
        val from = Rect(
            startFrame.left.toInt(),
            startFrame.top.toInt(),
            (startFrame.left + startFrame.width).toInt(),
            (startFrame.top + startFrame.height).toInt(),
        )
        val radius = FreeformPolicy.freeformVisibleCornerRadiusPx(
            mini, bounds.width(), bounds.height(),
        )
        // applyNormalGeometry arms a final-state settle guard. During the enter animation that
        // guard would reassert the full-size frame every 16ms and fight the opening sweep.
        cancelVisualSettleGuard(taskId)
        animateTaskVisualTransition(
            taskId = taskId,
            source = source,
            from = from,
            to = bounds,
            durationMs = FreeformPolicy.OPEN_ANIM_MS,
            posDamping = FreeformPolicy.SPRING_TO_FREEFORM_DAMPING,
            posResponse = FreeformPolicy.SPRING_TO_FREEFORM_RESPONSE,
            sizeDamping = FreeformPolicy.SPRING_TO_FREEFORM_DAMPING,
            sizeResponse = FreeformPolicy.SPRING_TO_FREEFORM_RESPONSE,
            fromAlpha = startFrame.alpha,
            toAlpha = 1f,
            fromRadius = radius,
            toRadius = radius,
            guard = { WindowState.isVisibleFreeform(it.windowState) },
            frameHook = { frame -> shell?.onLaunchAnimFrame(packageName, frame) },
            onCancel = {
                // A close/pin/maximize/split can supersede the 300ms enter animation. The launch
                // mask only needs to wait for first content now; keeping this false would hold
                // the splash until its 8s timeout.
                launchAnimationDone[packageName] = true
            },
        ) {
            finishOpenWithoutScale(taskId, packageName)
        }
    }

    private fun finishOpenWithoutScale(taskId: Int, packageName: String) {
        val state = tasks[taskId]
        if (state == null) {
            launchAnimationDone[packageName] = true
            return
        }
        val bounds = Rect(state.bounds)
        val source = SystemServices.taskBounds(taskId) ?: if (state.windowState == WindowState.NORMAL) {
            normalTaskSourceBounds(state)
        } else {
            miniSourceBounds(state)
        }
        // Re-assert the final frame once, without changing its scale or alpha.  This also clears
        // a stale transform left by a previous task instance before the splash is dismissed.
        SystemServices.applyMiniFreeformVisual(
            taskId,
            source,
            bounds,
            miniStyle = state.windowState == WindowState.MINI,
        )
        val finalFrame = FreeformPolicy.openVisualFrame(bounds, 1f)
        shell?.onWindowAnimFrame(taskId, finalFrame)
        shell?.onLaunchAnimFrame(packageName, finalFrame)
        launchAnimationDone[packageName] = true
        XLog.d("launch visual settled task=$taskId pkg=$packageName bounds=$bounds source=$source")
    }

    private fun waitForLaunchWindowDrawn(taskId: Int, packageName: String) {
        if (pendingLaunchSplashChecks.containsKey(packageName)) return
        val started = android.os.SystemClock.uptimeMillis()
        val check = object : Runnable {
            override fun run() {
                val drawn = SystemServices.isTaskMainWindowDrawn(taskId)
                val timedOut = android.os.SystemClock.uptimeMillis() - started >= 8_000L
                val animationDone = launchAnimationDone[packageName] == true
                if ((drawn && animationDone) || timedOut || !tasks.containsKey(taskId)) {
                    pendingLaunchSplashChecks.remove(packageName, this)
                    launchAnimationDone.remove(packageName)
                    // The app's first BLAST/SurfaceView buffer can make WM run one last surface
                    // placement after the launch transaction, clearing Task cornerRadius while
                    // leaving our numeric radius calculation intact. Re-assert the complete
                    // crop/radius geometry only after the real main window is drawn, then let the
                    // settle guard cover the remaining placement frames.
                    tasks[taskId]?.let { current ->
                        if (current.windowState == WindowState.NORMAL) {
                            applyNormalGeometry(taskId, current)
                        } else if (current.windowState == WindowState.MINI) {
                            SystemServices.applyMiniFreeformVisual(
                                taskId,
                                miniSourceBounds(current),
                                current.bounds,
                            )
                        }
                    }
                    shell?.dismissLaunchSplash(packageName)
                    XLog.d(
                        "launch splash settle task=$taskId pkg=$packageName " +
                        "drawn=$drawn animationDone=$animationDone timedOut=$timedOut",
                    )
                } else {
                    mainHandler.postDelayed(this, 32L)
                }
            }
        }
        pendingLaunchSplashChecks[packageName] = check
        mainHandler.post(check)
    }

    private fun hardenFreeformTask(taskId: Int, bounds: Rect) {
        // Prime density before the mode switch. Combining both changes into the same WM resolve is
        // essential for translucent child Activities: a later density correction would relaunch
        // the whole task and make stateful parent pages fall back to their root screen.
        SystemServices.primeFreeformDensity(taskId, freeformDpiOf(taskId))
        setTaskWindowingMode(taskId, FreeformPolicy.WINDOWING_MODE_FREEFORM)
        resizeTaskInternal(taskId, bounds)
        if (!SystemServices.setFreeformAlwaysOnTop(taskId, true)) {
            XLog.e("hardenFreeformTask always-on-top failed task=$taskId")
        }
    }

    private fun enforceWindowLimit(addingComponent: String? = null) {
        val max = FreeformPolicy.maxFreeformCount()
        if (max <= 0) {
            // Capability denied: close any residual visible freeforms.
            val residual = tasks.values.filter { WindowState.isVisibleFreeform(it.windowState) }
            for (s in residual) {
                XLog.d("maxFreeformCount=0, closing residual task=${s.taskId}")
                closeTaskInternal(s.taskId)
            }
            return
        }
        val visible = tasks.values.filter { WindowState.isVisibleFreeform(it.windowState) }
        if (visible.size < max) return
        // Xiaomi getReplaceFreeForm + payment/transfer protect lists.
        val ordered = visible.sortedBy { it.activeTime }
        val victim = ordered.firstOrNull { state ->
            !FreeformPolicy.shouldSkipReplaceFreeform(
                victimComponent = state.component,
                victimPackage = state.packageName,
                addingComponent = addingComponent,
            )
        }
        if (victim == null) {
            XLog.d(
                "Window limit $max reached but all candidates protected " +
                    "(pay/transfer); skip replace adding=$addingComponent"
            )
            return
        }
        XLog.d(
            "Window limit $max reached, closing LRU task ${victim.taskId} " +
                "pkg=${victim.packageName} component=${victim.component} " +
                "activeAge=${System.currentTimeMillis() - victim.activeTime}ms " +
                "adding=$addingComponent"
        )
        closeTaskInternal(victim.taskId)
    }

    override fun moveTask(taskId: Int, bounds: Rect?) {
        if (bounds == null) return
        runOnMain {
            touchActive(taskId)
            // MOVE samples use a leash-only fast path.  Stop its ticker but leave the last
            // transform visible until the one real Task-bounds commit below is styled/cropped.
            stopLiveResizeVisual(taskId)
            val siblings = tasks.values
                .filter { it.taskId != taskId && WindowState.isVisibleFreeform(it.windowState) }
                .map { Rect(it.bounds) }
            val clamped = FreeformPolicy.avoidSiblings(
                FreeformPolicy.clampBounds(bounds),
                siblings
            )
            updateState(taskId) {
                it.bounds = Rect(clamped)
                // User-driven geometry supersedes temporary IME avoid baseline.
                if (it.imeAvoiding) {
                    it.imeAvoiding = false
                    it.preImeBounds.setEmpty()
                }
                if (it.windowState == WindowState.NORMAL) {
                    // bounds owns the visual position.  restoreNormalBounds is the parked render
                    // source and must keep a stable origin; moving it on ACTION_UP starts a WM
                    // CHANGE and makes the content jump sideways before the leash is restored.
                    if (it.restoreNormalBounds.width() <= 0 ||
                        it.restoreNormalBounds.height() <= 0
                    ) {
                        it.restoreNormalBounds = Rect(clamped)
                    }
                }
                if (it.windowState == WindowState.MINI) it.restoreMiniBounds = Rect(clamped)
            }
            val state = tasks[taskId]
            if (state?.windowState == WindowState.MINI) {
                val source = miniSourceBounds(state)
                SystemServices.applyMiniFreeformVisual(taskId, source, clamped)
            } else if (state != null) {
                applyNormalGeometry(taskId, state)
            }
        }
    }

    override fun resizeTask(taskId: Int, bounds: Rect?, scale: Float) {
        if (bounds == null) return
        runOnMain {
            touchActive(taskId)
            val state = tasks[taskId] ?: return@runOnMain
            if (!state.landscapeTaskBounds.isEmpty) {
                // Landscape fullscreen players place interactive controls along the complete
                // bottom edge. A stale corner gesture must never collapse the full-display task
                // source into its scaled visual bounds (content then renders in the top-left of a
                // larger chrome frame). Reassert the authoritative source/visual pair instead.
                stopLiveResizeVisual(taskId)
                applyNormalGeometry(taskId, state)
                shell?.onStateChanged(state.snapshot())
                XLog.d("ignore resize for landscape fullscreen task=$taskId bounds=$bounds")
                return@runOnMain
            }
            // Xiaomi mini is not corner-resized; ignore residual samples after enter.
            if (state.windowState == WindowState.MINI) {
                val expandScale = if (scale > 0f) scale else FreeformPolicy.scaleFromBounds(
                    FreeformPolicy.clampBounds(bounds),
                    if (state.restoreNormalBounds.width() > 0) state.restoreNormalBounds
                    else FreeformPolicy.defaultNormalBounds(),
                )
                if (expandScale < 0.7f) {
                    return@runOnMain
                }
            }
            var clamped = FreeformPolicy.clampBounds(bounds)
            var newState = state.windowState
            // Xiaomi keeps a stable normal base for scale; do not let interactive shrink rewrite it.
            val baseNormal = if (state.restoreNormalBounds.width() > 0) {
                state.restoreNormalBounds
            } else {
                FreeformPolicy.defaultNormalBounds()
            }
            val effectiveScale =
                if (scale > 0f) scale else FreeformPolicy.scaleFromBounds(clamped, baseNormal)

            if (state.windowState == WindowState.NORMAL && FreeformPolicy.shouldEnterMini(effectiveScale)) {
                newState = WindowState.MINI
                // Remember the last true normal size before mini conversion.
                if (state.restoreNormalBounds.width() <= 0) {
                    state.restoreNormalBounds = Rect(state.bounds)
                }
                // Bottom-corner shrink settles diagonally opposite: BL -> top-right, BR -> top-left.
                val nearRight = FreeformPolicy.miniNearRightAfterResize(baseNormal, clamped)
                clamped = FreeformPolicy.defaultMiniBounds(nearRight = nearRight)
                clamped = FreeformPolicy.adjustBoundsForSidebarIfNeed(clamped)
                state.windowState = WindowState.MINI
                state.bounds = Rect(clamped)
                state.scale = 0.55f
                state.restoreMiniBounds = Rect(clamped)
                // Live-resize ticker must not fight the mini transform from here on.
                stopLiveResizeVisual(taskId)
                // Keep normal Task configuration; mini is a scaled leash preview.
                resizeTaskInternal(taskId, baseNormal)
                SystemServices.applyMiniFreeformVisual(taskId, baseNormal, clamped)
                shell?.onStateChanged(state.snapshot())
                XLog.i(
                    "resize -> mini task=$taskId scale=$effectiveScale base=$baseNormal " +
                        "nearRight=$nearRight bounds=$clamped",
                )
                return@runOnMain
            } else if (state.windowState == WindowState.MINI && effectiveScale >= 0.7f) {
                newState = WindowState.NORMAL
                if (state.restoreNormalBounds.width() > 0) {
                    clamped = Rect(state.restoreNormalBounds)
                    clamped.offsetTo(bounds.left, bounds.top)
                    clamped = FreeformPolicy.clampBounds(clamped)
                }
                XLog.i("resize mini -> normal task=$taskId scale=$effectiveScale")
            }

            state.bounds = Rect(clamped)
            state.scale = effectiveScale
            state.windowState = newState
            if (newState == WindowState.NORMAL) {
                // Expand base when user grows window; never shrink base during resize.
                if (state.restoreNormalBounds.width() <= 0 ||
                    (clamped.width() >= state.restoreNormalBounds.width() &&
                        clamped.height() >= state.restoreNormalBounds.height() &&
                        effectiveScale >= 0.95f)
                ) {
                    state.restoreNormalBounds = Rect(clamped)
                }
            }
            stopLiveResizeVisual(taskId)
            applyNormalGeometry(taskId, state)
            shell?.onStateChanged(state.snapshot())
        }
    }

    /**
     * NORMAL geometry invariant: an identity window commits the exact visual rectangle to WM.
     * Only an explicit aspect-preserving corner scale retains a larger render source and uses the
     * task leash. This prevents the app surface and chrome from acquiring independent sizes.
     */
    private fun applyNormalGeometry(taskId: Int, state: FreeformTaskState) {
        val visual = state.bounds
        if (state.landscapeTaskBounds.width() > 0 &&
            state.landscapeTaskBounds.height() > 0
        ) {
            val requested = Rect(state.landscapeTaskBounds)
            val before = SystemServices.taskBounds(taskId)
            val needsResize = before == null || before != requested
            if (needsResize && !SystemServices.resizeTask(taskId, requested, 0)) {
                XLog.e("applyNormalGeometry landscape resize task=$taskId failed")
            }
            val taskRect = if (!needsResize && before != null) {
                Rect(before)
            } else {
                SystemServices.taskBounds(taskId) ?: requested
            }
            state.landscapeTaskBounds = Rect(taskRect)
            SystemServices.applyMiniFreeformVisual(
                taskId,
                taskRect,
                visual,
                miniStyle = false,
            )
            startVisualSettleGuard(taskId, taskRect, visual)
            return
        }
        val taskRect = normalTaskSourceBounds(state)
        val before = SystemServices.taskBounds(taskId)
        val needsResize = before == null || before != taskRect
        if (needsResize && !SystemServices.resizeTask(taskId, taskRect, 0)) {
            XLog.e("applyNormalGeometry resize task=$taskId failed")
        }
        // resizeTask may synchronously clamp a task for minimum size or display limits. Always use
        // the authoritative post-resize rectangle for the matrix and crop, never the requested one.
        val actualTaskRect = if (!needsResize && before != null) {
            Rect(before)
        } else {
            SystemServices.taskBounds(taskId) ?: taskRect
        }
        if (visual == actualTaskRect) {
            SystemServices.clearLiveResizeVisual(taskId, visual)
        } else {
            SystemServices.applyMiniFreeformVisual(
                taskId,
                actualTaskRect,
                visual,
                miniStyle = false,
            )
        }
        startVisualSettleGuard(taskId, actualTaskRect, visual)
    }

    /**
     * A real task resize is asynchronous on several Xiaomi/HyperOS builds.  Surface placement may
     * therefore overwrite the final leash matrix *after* ACTION_UP stopped the live-resize ticker.
     * Keep the final crop/matrix authoritative only for the short WM settle window.
     */
    private fun startVisualSettleGuard(taskId: Int, sourceBounds: Rect, visualBounds: Rect) {
        cancelVisualSettleGuard(taskId)
        if (sourceBounds.width() <= 0 || sourceBounds.height() <= 0 ||
            visualBounds.width() <= 0 || visualBounds.height() <= 0
        ) return

        val expectedSource = Rect(sourceBounds)
        val expectedVisual = Rect(visualBounds)
        val started = android.os.SystemClock.uptimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val current = tasks[taskId]
                val ownsGuard = visualGuardRunnables[taskId] === this
                if (!ownsGuard || current == null || current.windowState != WindowState.NORMAL ||
                    current.pinAnimating || current.bounds != expectedVisual ||
                    liveResizeVisuals.containsKey(taskId)
                ) {
                    visualGuardRunnables.remove(taskId, this)
                    return
                }

                // WM may clamp or finish applying the source bounds between frames. This also runs
                // for identity-size windows: their matrix is stable, but a late app/SurfaceView
                // buffer can still reset the Task crop's corner radius to zero.
                val currentSource = SystemServices.taskBounds(taskId) ?: expectedSource
                SystemServices.applyMiniFreeformVisual(
                    taskId,
                    currentSource,
                    expectedVisual,
                    miniStyle = false,
                    logResult = false,
                )
                if (android.os.SystemClock.uptimeMillis() - started < 700L) {
                    mainHandler.postDelayed(this, 16L)
                } else {
                    visualGuardRunnables.remove(taskId, this)
                }
            }
        }
        visualGuardRunnables[taskId] = tick
        mainHandler.postDelayed(tick, 16L)
    }

    private fun cancelVisualSettleGuard(taskId: Int) {
        visualGuardRunnables.remove(taskId)?.let { mainHandler.removeCallbacks(it) }
    }

    /**
     * System-server local fast path used only by freeform chrome MOVE events.
     * Binder resizeTask remains the single commit path on gesture settle.
     *
     * The transform is SELF-HEALING: WM/Shell surface-placement passes (decoration relayout,
     * onTaskInfoChanged position writes, ...) reset the task leash between our transactions, and
     * during a slow drag with pauses no MOVE event re-asserts it — the app then renders at full
     * base size, overflowing the chrome frame (issue: 缩小时内容超出遮罩框). A per-frame ticker
     * re-applies the latest transform until the gesture settles, so any external reset heals
     * within ~16ms (invisible).
     */
    private class LiveResizeVisual(val base: Rect, val visual: Rect)
    private val liveResizeVisuals = java.util.concurrent.ConcurrentHashMap<Int, LiveResizeVisual>()
    private val liveResizeTickers = java.util.concurrent.ConcurrentHashMap<Int, Runnable>()

    fun applyLiveResizeVisual(taskId: Int, baseBounds: Rect, visualBounds: Rect) {
        runOnMain {
            val st = tasks[taskId] ?: return@runOnMain
            // A late MOVE sample queued behind the mini conversion must not re-register the live
            // transform over the mini visual (shell's state snapshot lags the service).
            if (st.windowState == WindowState.MINI) return@runOnMain
            cancelVisualSettleGuard(taskId)
            liveResizeVisuals[taskId] = LiveResizeVisual(Rect(baseBounds), Rect(visualBounds))
            SystemServices.applyLiveResizeVisual(taskId, baseBounds, visualBounds)
            startLiveResizeTicker(taskId)
        }
    }

    private fun startLiveResizeTicker(taskId: Int) {
        if (liveResizeTickers.containsKey(taskId)) return
        val tick = object : Runnable {
            override fun run() {
                val v = liveResizeVisuals[taskId]
                if (v == null || !tasks.containsKey(taskId)) {
                    liveResizeTickers.remove(taskId)
                    return
                }
                SystemServices.applyLiveResizeVisual(taskId, v.base, v.visual)
                mainHandler.postDelayed(this, 16L)
            }
        }
        liveResizeTickers[taskId] = tick
        mainHandler.postDelayed(tick, 16L)
    }

    private fun stopLiveResizeVisual(taskId: Int) {
        liveResizeVisuals.remove(taskId)
        liveResizeTickers.remove(taskId)?.let { mainHandler.removeCallbacks(it) }
    }

    fun clearLiveResizeVisual(taskId: Int, bounds: Rect) {
        runOnMain {
            stopLiveResizeVisual(taskId)
            val st = tasks[taskId] ?: return@runOnMain
            // If the gesture converted to mini, the mini visual owns the leash transform now —
            // resetting to identity here would blow the content back up to full size.
            if (st.windowState == WindowState.MINI) return@runOnMain
            SystemServices.clearLiveResizeVisual(taskId, bounds)
        }
    }

    override fun closeTask(taskId: Int) {
        mainHandler.post {
            val state = tasks[taskId]
            // Xiaomi window-close animation (shrink+fade) for a visible freeform, then remove.
            // Pinned/bubble or unknown tasks close immediately (bubble has its own removal).
            if (state != null && WindowState.isVisibleFreeform(state.windowState) &&
                !state.pinAnimating
            ) {
                animateCloseThenRemove(taskId, Rect(state.bounds))
            } else {
                closeTaskInternal(taskId)
            }
        }
    }

    private fun animateCloseThenRemove(taskId: Int, bounds: Rect) {
        val startMs = System.currentTimeMillis()
        val duration = FreeformPolicy.CLOSE_ANIM_MS.coerceAtLeast(1L)
        // Leash-local size = the task's real (base) size; bounds may be a scaled visual.
        val source = tasks[taskId]?.let { miniSourceBounds(it) } ?: Rect(bounds)
        val tick = object : Runnable {
            override fun run() {
                if (!tasks.containsKey(taskId)) return
                val elapsed = System.currentTimeMillis() - startMs
                val p = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                // sinOut(300) close ease ≈ sin(p*π/2).
                val eased = Math.sin(p * Math.PI / 2.0).toFloat()
                val frame = FreeformPolicy.closeVisualFrame(bounds, eased)
                SystemServices.applyCloseVisual(taskId, source, frame)
                shell?.onWindowAnimFrame(taskId, frame)
                if (p < 1f) {
                    mainHandler.postDelayed(this, 16L)
                } else {
                    closeTaskInternal(taskId, fromAnimation = true)
                }
            }
        }
        val first = FreeformPolicy.closeVisualFrame(bounds, 0f)
        SystemServices.applyCloseVisual(taskId, source, first)
        shell?.onWindowAnimFrame(taskId, first)
        mainHandler.postDelayed(tick, 16L)
    }

    // (close-from-animation path removes the task without un-fading the leash — see below)

    private fun closeTaskInternal(taskId: Int, fromAnimation: Boolean = false) {
        if (taskId <= 0) {
            XLog.e("closeTask invalid taskId=$taskId")
            return
        }
        cancelTransitionAnim(taskId)
        if (fromAnimation) {
            // The custom shrink+fade close animation already left the leash faded to alpha 0.
            // Do NOT clearFreeformSurfaceStyle / showTaskFromPin here — those reset the leash to
            // fully visible right before removeTask, which is what makes the AOSP freeform close
            // transition flash the window back at the end. Hide the leash outright, then remove.
            runCatching { SystemServices.hideTaskLeashForClose(taskId) }
        } else {
            runCatching { SystemServices.clearFreeformSurfaceStyle(taskId) }
            // Ensure pin-hidden tasks are visible to remove paths.
            runCatching { SystemServices.showTaskFromPin(taskId) }
        }
        val ok = runCatching { SystemServices.removeTask(taskId) }.getOrDefault(false)
        if (!ok) {
            XLog.e("closeTask $taskId: removeTask returned false, trying fallbacks")
            runCatching { SystemServices.invokeAtm("moveTaskToBack", taskId, true) }
            runCatching {
                SystemServices.invokeAtm(
                    "setTaskWindowingMode",
                    taskId,
                    FreeformPolicy.WINDOWING_MODE_FULLSCREEN,
                    true
                )
            }
            // Final force: ActivityManager.removeTask once more after mode change.
            runCatching { SystemServices.removeTask(taskId) }
        } else {
            XLog.d("closeTask $taskId removed")
        }
        cancelVisualSettleGuard(taskId)
        stopLiveResizeVisual(taskId)
        tasks.remove(taskId)
        shell?.onTaskRemoved(taskId)
    }

    override fun fullscreenTask(taskId: Int) {
        mainHandler.post { fullscreenTaskInternal(taskId) }
    }

    /**
     * Xiaomi MiuiFreeformModePinHandler.startPinToFullscreen.
     * Maximize a pin/bubble task directly without first restoring freeform chrome.
     * Reuses fullscreenTaskInternal (unhide + exit freeform + keep process).
     */
    override fun startPinToFullscreen(taskId: Int) {
        mainHandler.post {
            val state = tasks[taskId]
            if (state == null) {
                XLog.e("startPinToFullscreen: unknown task=$taskId")
                return@post
            }
            if (!WindowState.isPinned(state.windowState) && !state.foregroundPin) {
                // Not pinned — fall back to normal maximize for resilience.
                XLog.d("startPinToFullscreen: task=$taskId not pin (ws=${state.windowState}), fullscreen anyway")
            } else {
                XLog.i(
                    "startPinToFullscreen task=$taskId pkg=${state.packageName} " +
                        "ws=${WindowState.label(state.windowState)} fgPin=${state.foregroundPin}",
                )
            }
            fullscreenTaskInternal(taskId)
        }
    }

    /**
     * Xiaomi MiuiFreeformModePinHandler.updatePinFloatingWindowPos:
     * bubble drag / edge snap persists pin edge + Y into server state (touch active).
     * Does not change freeform restore bounds (page state keep).
     */
    override fun updatePinFloatingWindowPos(taskId: Int, pinPos: Int, y: Int) {
        mainHandler.post { updatePinFloatingWindowPosInternal(taskId, pinPos, y, fromTouch = true) }
    }

    fun updatePinFloatingWindowPosInternal(
        taskId: Int,
        pinPos: Int,
        y: Int,
        fromTouch: Boolean = true,
    ) {
        val state = tasks[taskId] ?: run {
            XLog.e("updatePinFloatingWindowPos: unknown task=$taskId")
            return
        }
        if (!WindowState.isPinned(state.windowState)) {
            XLog.d(
                "updatePinFloatingWindowPos ignored task=$taskId " +
                    "ws=${WindowState.label(state.windowState)} (not pin)",
            )
            return
        }
        val edge = if (pinPos == 1) 1 else 0
        val pos = FreeformPolicy.pinFloatingWindowPos(edge, y)
        state.pinPos = edge
        state.pinY = pos.top
        state.pinFloatingWindowPos.set(pos)
        if (fromTouch) {
            // Xiaomi: touch drag refreshes pinActiveTime / LRU.
            state.pinActiveTime = System.currentTimeMillis()
            state.activeTime = state.pinActiveTime
        }
        shell?.onStateChanged(state.snapshot())
        XLog.i(
            "updatePinFloatingWindowPos task=$taskId pinPos=$edge pinY=${state.pinY} " +
                "pos=$pos fromTouch=$fromTouch",
        )
    }

    /**
     * Freeform → fullscreen (Xiaomi maximize / bottom-caption fullscreen).
     * Keeps the same task alive (no removeTask) so page state is preserved.
     * Xiaomi: setAlwaysOnTop(false) + setWindowingMode(0) + setBounds(null).
     */
    private fun fullscreenTaskInternal(taskId: Int) {
        val tracked = tasks[taskId]
        if (tracked != null &&
            (WindowState.isVisibleFreeform(tracked.windowState) ||
                WindowState.isPinned(tracked.windowState))
        ) {
            // The next bottom swipe belongs to the stock launcher navigation path. Without this
            // tombstone HookLauncher mistakes the just-maximized task for a normal foreground app
            // and converts it straight back to MINI instead of showing HOME/Recents.
            suppressForegroundMiniForTask(taskId)
        }
        if (tracked != null && WindowState.isVisibleFreeform(tracked.windowState) &&
            !tracked.pinAnimating
        ) {
            // Commit WM fullscreen first. The post-commit leash animation then operates on the
            // fullscreen buffer, avoiding the visibly stretched old freeform buffer.
            val from = Rect(tracked.bounds)
            val (dw, dh) = FreeformPolicy.displaySize()
            val full = Rect(0, 0, dw, dh)
            val fromRadius = FreeformPolicy.freeformVisibleCornerRadiusPx(
                tracked.windowState == WindowState.MINI,
                from.width(),
                from.height(),
            )
            cancelVisualSettleGuard(taskId)
            stopLiveResizeVisual(taskId)
            // Chrome does not ride along on a maximize; MIUI drops the caption up front.
            shell?.onTaskRemoved(taskId)
            performFullscreenExit(taskId)
            animateFullscreenLeash(taskId, from, full, fromRadius)
            return
        }
        performFullscreenExit(taskId)
    }

    private fun performFullscreenExit(taskId: Int) {
        val tracked = tasks[taskId]
        try {
            cancelFullscreenTransitionAnim(taskId)
            cancelTransitionAnim(taskId)
            cancelVisualSettleGuard(taskId)
            stopLiveResizeVisual(taskId)
            // If pinned, unhide first so fullscreen surface is visible.
            if (tracked != null && WindowState.isPinned(tracked.windowState)) {
                SystemServices.showTaskFromPin(taskId)
            }
            // Remove live-resize/pin transforms before mode/bounds transition.
            SystemServices.resetTaskLeashForFullscreen(taskId)
            val exited = SystemServices.exitTaskToFullscreen(taskId)
            // Binder/ATM fallback still useful on some builds.
            setTaskWindowingMode(taskId, FreeformPolicy.WINDOWING_MODE_FULLSCREEN)
            // Final density safety net: the ATM mode switch may have re-resolved the task with
            // the stale freeform density. No-op when already cleared.
            SystemServices.clearTaskDensityOverride(taskId)
            runCatching {
                SystemServices.activityManager.moveTaskToFront(taskId, 0)
            }
            // WMS may update leash geometry during mode change; clear once more after it.
            SystemServices.resetTaskLeashForFullscreen(taskId)
            XLog.i(
                "fullscreenTask task=$taskId pkg=${tracked?.packageName.orEmpty()} " +
                    "exited=$exited (kept alive, freeform tracking cleared)"
            )
        } catch (t: Throwable) {
            XLog.e("fullscreenTask failed", t)
        }
        tasks.remove(taskId)
        shell?.onTaskRemoved(taskId)
    }

    override fun splitTask(taskId: Int, position: Int) {
        mainHandler.post { splitTaskInternal(taskId, position) }
    }

    /**
     * Freeform → split (Xiaomi MulWinSwitch switchFreeformToSplit).
     * Prefer SystemUI SplitScreenController.moveToStage; fallback Task WCT multi-window half.
     * Clears freeform chrome/tracking; keeps task process alive.
     */
    private fun splitTaskInternal(taskId: Int, position: Int) {
        val pos = if (position == FreeformPolicy.SPLIT_POSITION_BOTTOM_OR_RIGHT) 1 else 0
        val tracked = tasks[taskId]
        if (tracked != null && WindowState.isVisibleFreeform(tracked.windowState) &&
            !tracked.pinAnimating
        ) {
            // MIUI freeform→split: TO_FULLSCREEN_SPLIT position spring(0.85, 0.55) /
            // size spring(0.9, 0.48) sweep into the stage half, then the stage handoff runs.
            val from = Rect(tracked.bounds)
            val source = if (tracked.windowState == WindowState.MINI) {
                miniSourceBounds(tracked)
            } else {
                normalTaskSourceBounds(tracked)
            }
            val half = FreeformPolicy.splitHalfBounds(pos)
            val fromRadius = FreeformPolicy.freeformVisibleCornerRadiusPx(
                tracked.windowState == WindowState.MINI,
                from.width(),
                from.height(),
            )
            cancelVisualSettleGuard(taskId)
            stopLiveResizeVisual(taskId)
            shell?.onTaskRemoved(taskId)
            animateTaskVisualTransition(
                taskId = taskId,
                source = source,
                from = from,
                to = half,
                durationMs = FreeformPolicy.SPLIT_ANIM_MS,
                posDamping = FreeformPolicy.SPRING_SPLIT_POS_DAMPING,
                posResponse = FreeformPolicy.SPRING_SPLIT_POS_RESPONSE,
                sizeDamping = FreeformPolicy.SPRING_SPLIT_SIZE_DAMPING,
                sizeResponse = FreeformPolicy.SPRING_SPLIT_SIZE_RESPONSE,
                fromRadius = fromRadius,
                toRadius = 0f,
            ) {
                performSplitExit(taskId, pos)
            }
            return
        }
        performSplitExit(taskId, pos)
    }

    private fun performSplitExit(taskId: Int, pos: Int) {
        val tracked = tasks[taskId]
        try {
            cancelTransitionAnim(taskId)
            if (tracked != null && WindowState.isPinned(tracked.windowState)) {
                SystemServices.showTaskFromPin(taskId)
            }
            SystemServices.clearFreeformSurfaceStyle(taskId)

            // Xiaomi: setAlwaysOnTop(false) before stage entry.
            runCatching {
                val task = SystemServices.findTask(taskId) ?: return@runCatching
                task.javaClass.methods.firstOrNull {
                    it.name == "setAlwaysOnTop" && it.parameterTypes.size == 1
                }?.invoke(task, false)
            }

            // Cross-process shell request first (SystemUI SplitScreenController / SoSc).
            val viaShellReq = SplitScreenBridge.requestFromServer(
                SystemServices.systemContext,
                taskId,
                pos
            )

            // Task WCT fallback after short delay so shell can win on devices that have it.
            // AOSP/MuMu without stage entry still gets multi-window half bounds.
            mainHandler.postDelayed({
                fun currentMode(): Int? = runCatching {
                    val task = SystemServices.findTask(taskId) ?: return@runCatching null
                    task.javaClass.methods.firstOrNull {
                        it.name == "getWindowingMode" && it.parameterTypes.isEmpty()
                    }?.invoke(task) as? Int
                }.getOrNull()
                val modeBefore = currentMode()
                // Xiaomi freeform→split should land multi-window stage geometry.
                // AOSP moveToStage may "succeed" but leave fullscreen with empty stages on MuMu;
                // only skip Task path when already MULTI_WINDOW.
                val alreadySplit = modeBefore == FreeformPolicy.WINDOWING_MODE_MULTI_WINDOW
                if (!alreadySplit) {
                    val viaTask = SystemServices.exitTaskToSplit(taskId, pos)
                    if (!viaTask) {
                        setTaskWindowingMode(taskId, FreeformPolicy.WINDOWING_MODE_MULTI_WINDOW)
                        runCatching {
                            SystemServices.resizeTask(
                                taskId,
                                FreeformPolicy.splitHalfBounds(pos),
                                0
                            )
                        }
                    }
                    runCatching {
                        SystemServices.activityManager.moveTaskToFront(taskId, 0)
                    }
                    val modeAfter = currentMode()
                    XLog.i(
                        "splitTask fallback task=$taskId pos=$pos viaTask=$viaTask " +
                            "modeBefore=$modeBefore modeAfter=$modeAfter " +
                            "viaShellReq=$viaShellReq"
                    )
                } else {
                    XLog.i(
                        "splitTask shell multi-window ok task=$taskId pos=$pos " +
                            "mode=$modeBefore viaShellReq=$viaShellReq"
                    )
                }
            }, 280L)

            XLog.i(
                "splitTask task=$taskId pos=$pos pkg=${tracked?.packageName.orEmpty()} " +
                    "viaShellReq=$viaShellReq (freeform tracking cleared)"
            )
        } catch (t: Throwable) {
            XLog.e("splitTask failed", t)
        }
        tasks.remove(taskId)
        shell?.onTaskRemoved(taskId)
    }

    override fun switchMini(taskId: Int, mini: Boolean) {
        mainHandler.post {
            val state = tasks[taskId] ?: return@post
            if (mini && state.windowState == WindowState.NORMAL) {
                var miniBounds = FreeformPolicy.defaultMiniBounds(
                    nearRight = FreeformPolicy.pinEdge(state.bounds) == 1
                )
                miniBounds = FreeformPolicy.adjustBoundsForSidebarIfNeed(miniBounds)
                val fromBounds = Rect(state.bounds)
                state.restoreNormalBounds = Rect(state.bounds)
                state.bounds = miniBounds
                state.windowState = WindowState.MINI
                state.restoreMiniBounds = Rect(miniBounds)
                val source = Rect(state.restoreNormalBounds)
                // Keep normal Task configuration and scale its leash into mini bounds.
                resizeTaskInternal(taskId, state.restoreNormalBounds)
                // MIUI applyFreeformToMiniAnimation: DEFAULT_EASE spring(0.95, 0.35) shrink
                // sweep on the leash; real bounds stay at the normal source the whole time.
                val fromRadius = FreeformPolicy.freeformVisibleCornerRadiusPx(
                    false, fromBounds.width(), fromBounds.height(),
                )
                val toRadius = FreeformPolicy.freeformVisibleCornerRadiusPx(
                    true, miniBounds.width(), miniBounds.height(),
                )
                animateTaskVisualTransition(
                    taskId = taskId,
                    source = source,
                    from = fromBounds,
                    to = miniBounds,
                    durationMs = FreeformPolicy.MINI_ENTER_ANIM_MS,
                    posDamping = FreeformPolicy.SPRING_DEFAULT_DAMPING,
                    posResponse = FreeformPolicy.SPRING_DEFAULT_RESPONSE,
                    sizeDamping = FreeformPolicy.SPRING_DEFAULT_DAMPING,
                    sizeResponse = FreeformPolicy.SPRING_DEFAULT_RESPONSE,
                    fromRadius = fromRadius,
                    toRadius = toRadius,
                    guard = { it.windowState == WindowState.MINI },
                ) {
                    SystemServices.applyMiniFreeformVisual(taskId, source, miniBounds)
                }
                shell?.onStateChanged(state.snapshot())
            } else if (!mini && state.windowState == WindowState.MINI) {
                val normal = if (state.restoreNormalBounds.width() > 0) {
                    Rect(state.restoreNormalBounds)
                } else FreeformPolicy.defaultNormalBounds()
                val from = Rect(state.bounds)
                state.bounds = normal
                state.windowState = WindowState.NORMAL
                shell?.onStateChanged(state.snapshot())
                // Xiaomi MINI_TO_FREEFORM spring expand: scale the leash up from mini→normal,
                // then commit real bounds. source = normal config size.
                animateExpandFromMini(taskId, Rect(normal), from, normal)
            }
        }
    }

    /**
     * Xiaomi MINI_TO_FREEFORM_EASE spring: animate the task leash from [fromBounds] up to
     * [toBounds] (a slight overshoot from the spring), then commit real bounds + clear transform.
     */
    private fun animateExpandFromMini(taskId: Int, source: Rect, fromBounds: Rect, toBounds: Rect) {
        cancelTransitionAnim(taskId)
        val startMs = System.currentTimeMillis()
        val duration = FreeformPolicy.EXPAND_ANIM_MS.coerceAtLeast(1L)
        val tick = object : Runnable {
            override fun run() {
                val state = tasks[taskId] ?: return
                if (state.windowState != WindowState.NORMAL) return
                val elapsed = System.currentTimeMillis() - startMs
                val p = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val e = FreeformPolicy.folmeSpring(
                    p,
                    FreeformPolicy.SPRING_MINI_EXPAND_DAMPING,
                    FreeformPolicy.SPRING_MINI_EXPAND_RESPONSE,
                )
                fun lerp(a: Int, b: Int) = (a + (b - a) * e).toInt()
                val cur = Rect(
                    lerp(fromBounds.left, toBounds.left),
                    lerp(fromBounds.top, toBounds.top),
                    lerp(fromBounds.right, toBounds.right),
                    lerp(fromBounds.bottom, toBounds.bottom),
                )
                SystemServices.applyMiniFreeformVisual(taskId, source, cur)
                if (p < 1f) {
                    mainHandler.postDelayed(this, 16L)
                } else {
                    SystemServices.resetPinShrinkVisual(taskId, toBounds)
                    resizeTaskInternal(taskId, toBounds)
                }
            }
        }
        mainHandler.postDelayed(tick, 16L)
    }

    private class TransitionAnimSession(
        val runnable: Runnable,
        val onCancel: () -> Unit,
    )

    /** Running generic transition animations (open/mini/maximize/split/rotate/unpin), per task. */
    private val transitionAnimRunnables = ConcurrentHashMap<Int, TransitionAnimSession>()

    private fun cancelTransitionAnim(taskId: Int) {
        transitionAnimRunnables.remove(taskId)?.let { session ->
            mainHandler.removeCallbacks(session.runnable)
            session.onCancel()
        }
    }

    fun isTransitionAnimating(taskId: Int): Boolean = transitionAnimRunnables.containsKey(taskId)

    /**
     * MIUI orientation-change sweep (ROTATE_POSITION_Z_EASE = spring(0.95, 0.42)): the leash
     * flies from the previous visual rect to the committed one. The real task geometry has
     * already been applied by the caller; this only smooths the visual switch, then re-asserts
     * the canonical geometry (and its settle guard) at the end.
     */
    private fun animateOrientationTransition(
        taskId: Int,
        state: FreeformTaskState,
        prevVisual: Rect,
    ) {
        if (prevVisual.isEmpty || prevVisual == state.bounds) return
        val source = if (state.landscapeTaskBounds.width() > 0 &&
            state.landscapeTaskBounds.height() > 0
        ) {
            Rect(state.landscapeTaskBounds)
        } else {
            normalTaskSourceBounds(state)
        }
        val target = Rect(state.bounds)
        val radius = FreeformPolicy.freeformVisibleCornerRadiusPx(
            false, target.width(), target.height(),
        )
        // The settle guard started by applyNormalGeometry would fight the sweep; the final
        // re-assert below starts a fresh one.
        cancelVisualSettleGuard(taskId)
        animateTaskVisualTransition(
            taskId = taskId,
            source = source,
            from = prevVisual,
            to = target,
            durationMs = FreeformPolicy.ROTATE_ANIM_MS,
            posDamping = FreeformPolicy.SPRING_ROTATE_DAMPING,
            posResponse = FreeformPolicy.SPRING_ROTATE_RESPONSE,
            sizeDamping = FreeformPolicy.SPRING_ROTATE_DAMPING,
            sizeResponse = FreeformPolicy.SPRING_ROTATE_RESPONSE,
            fromRadius = radius,
            toRadius = radius,
            guard = { it.windowState == WindowState.NORMAL },
        ) {
            tasks[taskId]?.let { applyNormalGeometry(taskId, it) }
        }
    }

    /**
     * MIUI-style leash transition: dual Folme springs (position/size), lerped corner radius and
     * alpha, visual-only until [onEnd] commits the real geometry. Used for the transitions MIUI
     * animates: normal→mini, unpin restore, maximize, split and orientation change.
     */
    private fun animateTaskVisualTransition(
        taskId: Int,
        source: Rect,
        from: Rect,
        to: Rect,
        durationMs: Long,
        posDamping: Float,
        posResponse: Float,
        sizeDamping: Float,
        sizeResponse: Float,
        fromAlpha: Float = 1f,
        toAlpha: Float = 1f,
        fromRadius: Float,
        toRadius: Float,
        guard: (FreeformTaskState) -> Boolean = { true },
        frameHook: (FreeformPolicy.WindowVisualFrame) -> Unit = {},
        onCancel: () -> Unit = {},
        onEnd: () -> Unit,
    ) {
        cancelTransitionAnim(taskId)
        val startMs = System.currentTimeMillis()
        val duration = durationMs.coerceAtLeast(1L)
        val tick = object : Runnable {
            override fun run() {
                transitionAnimRunnables.remove(taskId)
                val state = tasks[taskId]
                if (state == null || !guard(state)) {
                    onCancel()
                    return
                }
                val elapsed = System.currentTimeMillis() - startMs
                val p = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                val posP = FreeformPolicy.folmeSpring(p, posDamping, posResponse)
                val sizeP = FreeformPolicy.folmeSpring(p, sizeDamping, sizeResponse)
                val frame = FreeformPolicy.transitionVisualFrame(
                    from, to, posP, sizeP, fromAlpha, toAlpha,
                )
                SystemServices.applyTransitionVisual(
                    taskId, source, frame, fromRadius, toRadius, sizeP,
                )
                shell?.onWindowAnimFrame(taskId, frame)
                frameHook(frame)
                if (p < 1f) {
                    transitionAnimRunnables[taskId] = TransitionAnimSession(this, onCancel)
                    mainHandler.postDelayed(this, 16L)
                } else {
                    onEnd()
                }
            }
        }
        // Apply the first frame synchronously so the transition starts within the same
        // main-thread message as the state change (no final-state flash in between).
        val first = FreeformPolicy.transitionVisualFrame(from, to, 0f, 0f, fromAlpha, toAlpha)
        SystemServices.applyTransitionVisual(taskId, source, first, fromRadius, toRadius, 0f)
        shell?.onWindowAnimFrame(taskId, first)
        frameHook(first)
        transitionAnimRunnables[taskId] = TransitionAnimSession(tick, onCancel)
        mainHandler.postDelayed(tick, 16L)
    }

    override fun pinTask(taskId: Int, pin: Boolean) {
        if (pin) mainHandler.post { pinTaskInternal(taskId) }
        else mainHandler.post { unpinTask(taskId) }
    }

    private fun pinTaskInternal(taskId: Int) {
        val state = tasks[taskId] ?: return
        cancelTransitionAnim(taskId)
        stopLiveResizeVisual(taskId)
        // Already fully pinned (bubble phase): ignore.
        if (WindowState.isPinned(state.windowState) && !state.pinAnimating) return
        // Re-entry while animating: keep first animation.
        if (state.pinAnimating) return
        val target = WindowState.pinTarget(state.windowState)
        // Keep the last visible size for unpin restore (page state keep). A mini is brought fully
        // back on-screen at its nearest resting edge; a normal window returns to the center.
        if (state.windowState == WindowState.MINI) {
            state.restoreMiniBounds = FreeformPolicy.visibleMiniRestingBounds(state.bounds)
        } else if (state.windowState == WindowState.NORMAL) {
            state.restoreNormalBounds = FreeformPolicy.centerBounds(state.bounds)
        }
        // Bounds stay put: Xiaomi pin does NOT resize offscreen (avoids config thrash / relaunch).
        state.windowState = target
        state.pinPos = FreeformPolicy.pinEdge(state.bounds)
        // Xiaomi getFinalPinBounds lite: seed bubble Y from freeform top until user drags.
        state.pinY = state.bounds.top.coerceAtLeast(80)
        state.pinFloatingWindowPos = FreeformPolicy.pinFloatingWindowPos(state.pinPos, state.pinY)
        state.pinActiveTime = System.currentTimeMillis()
        state.activeTime = state.pinActiveTime
        // Xiaomi foreground pin: higher priority pin for audio/nav/game whitelist when RAM>6.
        // Regular edge pin still works for everyone; fg flag only changes always-on-top policy.
        val fg = FreeformPolicy.allowsForegroundPin(state.packageName)
        state.foregroundPin = fg
        state.alwaysOnTop = fg

        // Xiaomi startPinAnimation window: still visible + interruptible before hide/bubble.
        state.pinAnimating = true
        cancelPendingPinFinish(taskId)
        cancelPendingPinVisual(taskId)
        shell?.onPinAnimating(state.snapshot())
        startPinShrinkVisual(state)
        XLog.i(
            "pin anim start task=$taskId pos=${state.pinPos} fgPin=$fg " +
                "boundsKept=${state.bounds} ms=${FreeformPolicy.PIN_ANIM_MS}",
        )
        val finish = Runnable { finishPinAnimation(taskId) }
        pendingPinFinish[taskId] = finish
        mainHandler.postDelayed(finish, FreeformPolicy.PIN_ANIM_MS)
    }

    private fun cancelPendingPinFinish(taskId: Int) {
        pendingPinFinish.remove(taskId)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun cancelPendingPinVisual(taskId: Int) {
        pendingPinVisual.remove(taskId)?.let { mainHandler.removeCallbacks(it) }
    }

    /**
     * Drive Xiaomi-like freeform→pin shrink on task leash + shell chrome.
     * ~16ms ticks over PIN_ANIM_MS; interrupt cancels and resets.
     */
    private fun startPinShrinkVisual(state: FreeformTaskState) {
        val taskId = state.taskId
        val bounds = Rect(state.bounds)
        // Leash-local size = the task's real (base) size; bounds may be a scaled visual.
        val source = miniSourceBounds(state)
        val pinPos = state.pinPos
        val startMs = System.currentTimeMillis()
        val duration = FreeformPolicy.PIN_ANIM_MS.coerceAtLeast(1L)
        val tick = object : Runnable {
            override fun run() {
                val cur = tasks[taskId] ?: return
                if (!cur.pinAnimating) return
                val elapsed = System.currentTimeMillis() - startMs
                val p = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                // MIUI pin eases: PIN_POSITION_EASE = spring(0.78, 0.6) for the edge flight,
                // PIN_WIDTH_HEIGHT_EASE = spring(1.0, 0.35) for the shrink itself.
                val posP = FreeformPolicy.folmeSpring(
                    p,
                    FreeformPolicy.SPRING_PIN_POS_DAMPING,
                    FreeformPolicy.SPRING_PIN_POS_RESPONSE,
                )
                val sizeP = FreeformPolicy.folmeSpring(
                    p,
                    FreeformPolicy.SPRING_PIN_SIZE_DAMPING,
                    FreeformPolicy.SPRING_PIN_SIZE_RESPONSE,
                )
                val frame = FreeformPolicy.pinVisualFrame(source, bounds, pinPos, posP, sizeP)
                SystemServices.applyPinShrinkVisual(taskId, source, frame, sizeP)
                shell?.onWindowAnimFrame(taskId, frame)
                if (p < 1f && cur.pinAnimating) {
                    pendingPinVisual[taskId] = this
                    mainHandler.postDelayed(this, 16L)
                } else {
                    pendingPinVisual.remove(taskId)
                }
            }
        }
        // Apply first frame immediately so interrupt window already shows motion.
        val first = FreeformPolicy.pinVisualFrame(source, bounds, pinPos, 0f)
        SystemServices.applyPinShrinkVisual(taskId, source, first, 0f)
        shell?.onWindowAnimFrame(taskId, first)
        pendingPinVisual[taskId] = tick
        mainHandler.postDelayed(tick, 16L)
    }

    private fun resetPinShrinkVisual(state: FreeformTaskState) {
        cancelPendingPinVisual(state.taskId)
        SystemServices.resetPinShrinkVisual(state.taskId, state.bounds)
        shell?.onWindowAnimFrame(state.taskId, FreeformPolicy.openVisualFrame(state.bounds, 1f))
    }

    /**
     * Xiaomi onPinAnimFinished: hide task leash + show bubble after interruptible window.
     */
    private fun finishPinAnimation(taskId: Int) {
        pendingPinFinish.remove(taskId)
        val state = tasks[taskId] ?: return
        if (!state.pinAnimating) return
        if (!WindowState.isPinned(state.windowState)) {
            state.pinAnimating = false
            cancelPendingPinVisual(taskId)
            return
        }
        state.pinAnimating = false
        cancelPendingPinVisual(taskId)
        // Leave leash at end shrink; hideTaskForPin hides it. reset on unpin show.
        // Xiaomi path: keep task + keep bounds, force-hide + leash hide, setAlwaysOnTop(fg).
        // Do NOT removeTask / do NOT offscreen resizeTask (page state keep).
        val hid = SystemServices.hideTaskForPin(taskId)
        if (!hid) {
            XLog.e("finishPinAnimation: hideTaskForPin failed task=$taskId (still keeping task/bounds)")
        }
        val fg = state.foregroundPin
        // Reflect always-on-top for foreground pin (Xiaomi keeps higher z for fg pin).
        // Regular pin stays alwaysOnTop=false from hideTaskForPin.
        runCatching {
            val task = SystemServices.findTask(taskId) ?: return@runCatching
            val m = task.javaClass.methods.firstOrNull {
                it.name == "setAlwaysOnTop" && it.parameterTypes.size == 1
            }
            m?.invoke(task, fg)
        }
        shell?.onPinned(state.snapshot())
        XLog.i(
            "Pinned task=$taskId pos=${state.pinPos} fgPin=$fg hidden=$hid " +
                "boundsKept=${state.bounds} (anim finished, no offscreen resize, page state keep)",
        )
    }

    /**
     * Xiaomi MiuiFreeformModePinHandler.handleInterruptPin:
     * touch during freeform→pin animation immediately restores freeform (no bubble).
     * After animation, same path as unpin.
     */
    fun interruptPinTask(taskId: Int) {
        mainHandler.post { interruptPinTaskInternal(taskId) }
    }

    private fun interruptPinTaskInternal(taskId: Int) {
        cancelPendingPinFinish(taskId)
        val state = tasks[taskId] ?: return
        if (!WindowState.isPinned(state.windowState) && !state.pinAnimating) return
        val wasAnimating = state.pinAnimating
        state.pinAnimating = false
        if (wasAnimating) {
            val restoreState = WindowState.unpinRestore(state.windowState)
            val restoreBounds = if (restoreState == WindowState.MINI) {
                val candidate = if (state.restoreMiniBounds.width() > 0) {
                    state.restoreMiniBounds
                } else {
                    state.bounds
                }
                FreeformPolicy.visibleMiniRestingBounds(candidate)
            } else {
                val candidate = if (state.restoreNormalBounds.width() > 0) {
                    state.restoreNormalBounds
                } else {
                    state.bounds
                }
                FreeformPolicy.centerBounds(candidate)
            }
            state.windowState = restoreState
            state.bounds = Rect(restoreBounds)
            state.foregroundPin = false
            state.alwaysOnTop = true
            // Task was never force-hidden; restore its authoritative resting geometry directly.
            if (restoreState == WindowState.MINI) {
                val source = miniSourceBounds(state)
                SystemServices.resetPinShrinkVisual(taskId, source)
                hardenFreeformTask(taskId, source)
                SystemServices.applyMiniFreeformVisual(taskId, source, restoreBounds)
            } else {
                SystemServices.resetPinShrinkVisual(taskId, restoreBounds)
                resizeTaskInternal(taskId, restoreBounds)
                hardenFreeformTask(taskId, restoreBounds)
            }
            shell?.onUnpinned(state.snapshot())
            XLog.i(
                "interruptPin anim task=$taskId -> ${WindowState.label(restoreState)} " +
                    "bounds=$restoreBounds (no hide, no relaunch, shrink reset)",
            )
            return
        }
        // Already in bubble phase: full unpin restore.
        XLog.i("interruptPin post-anim task=$taskId -> unpin")
        unpinTask(taskId)
    }

    override fun unpinTask(taskId: Int) {
        cancelPendingPinFinish(taskId)
        cancelPendingPinVisual(taskId)
        val state = tasks[taskId] ?: return
        state.pinAnimating = false
        if (!WindowState.isPinned(state.windowState)) return
        val restoreState = WindowState.unpinRestore(state.windowState)
        // Prefer last visible freeform bounds; fall back to defaults only if missing.
        val bounds = when {
            restoreState == WindowState.MINI && state.restoreMiniBounds.width() > 0 ->
                FreeformPolicy.visibleMiniRestingBounds(state.restoreMiniBounds)
            restoreState != WindowState.MINI && state.restoreNormalBounds.width() > 0 ->
                FreeformPolicy.centerBounds(state.restoreNormalBounds)
            state.bounds.width() > 0 && restoreState == WindowState.MINI ->
                FreeformPolicy.visibleMiniRestingBounds(state.bounds)
            state.bounds.width() > 0 ->
                FreeformPolicy.centerBounds(state.bounds)
            restoreState == WindowState.MINI ->
                FreeformPolicy.defaultMiniBounds(nearRight = state.pinPos == 1)
            else ->
                FreeformPolicy.defaultNormalBounds()
        }
        state.windowState = restoreState
        state.bounds = bounds
        state.foregroundPin = false
        state.alwaysOnTop = true
        // Reverse Xiaomi pin hide; do not startActivity (page state keep).
        SystemServices.showTaskFromPin(taskId)
        // MIUI applyUnPinAnimation: the window expands back from the floating-icon edge
        // position with TO_FREEFORM_POSITION_SIZE_EASE = spring(0.95, 0.4); the real bounds
        // commit happens once the sweep finishes.
        val source = if (restoreState == WindowState.MINI) {
            miniSourceBounds(state)
        } else {
            Rect(bounds)
        }
        val bubbleFrame = FreeformPolicy.pinVisualFrame(source, bounds, state.pinPos, 1f, 1f)
        val bubbleRect = Rect(
            bubbleFrame.left.toInt(),
            bubbleFrame.top.toInt(),
            (bubbleFrame.left + bubbleFrame.width).toInt(),
            (bubbleFrame.top + bubbleFrame.height).toInt(),
        )
        val bubbleDensity = SystemServices.systemContext.resources.displayMetrics.density
        val bubbleRadius = 64f * bubbleDensity * 0.28f
        val toRadius = FreeformPolicy.freeformVisibleCornerRadiusPx(
            restoreState == WindowState.MINI,
            bounds.width(),
            bounds.height(),
        )
        animateTaskVisualTransition(
            taskId = taskId,
            source = source,
            from = bubbleRect,
            to = Rect(bounds),
            durationMs = FreeformPolicy.UNPIN_ANIM_MS,
            posDamping = FreeformPolicy.SPRING_TO_FREEFORM_DAMPING,
            posResponse = FreeformPolicy.SPRING_TO_FREEFORM_RESPONSE,
            sizeDamping = FreeformPolicy.SPRING_TO_FREEFORM_DAMPING,
            sizeResponse = FreeformPolicy.SPRING_TO_FREEFORM_RESPONSE,
            fromAlpha = bubbleFrame.alpha,
            toAlpha = 1f,
            fromRadius = bubbleRadius,
            toRadius = toRadius,
            guard = { !WindowState.isPinned(it.windowState) && !it.pinAnimating },
        ) {
            // Clear any leftover freeform→pin shrink matrix from finish path.
            if (restoreState == WindowState.MINI) {
                SystemServices.resetPinShrinkVisual(taskId, source)
                hardenFreeformTask(taskId, source)
                SystemServices.applyMiniFreeformVisual(taskId, source, bounds)
            } else {
                SystemServices.resetPinShrinkVisual(taskId, bounds)
                // Re-assert same freeform bounds (no-op if pin kept them) + surface style.
                resizeTaskInternal(taskId, bounds)
                hardenFreeformTask(taskId, bounds)
            }
        }
        runCatching {
            SystemServices.activityManager.moveTaskToFront(taskId, 0)
        }
        shell?.onUnpinned(state.snapshot())
        XLog.i(
            "Unpinned task=$taskId -> ${WindowState.label(restoreState)} " +
                "bounds=$bounds (no relaunch)"
        )
    }

    private fun touchActive(taskId: Int) {
        tasks[taskId]?.activeTime = System.currentTimeMillis()
    }

    private fun applySurfaceStyle(taskId: Int, bounds: Rect, windowState: Int = tasks[taskId]?.windowState ?: WindowState.NORMAL) {
        val mini = windowState == WindowState.MINI
        val state = tasks[taskId]
        if (mini && state != null) {
            // A MINI task is configured at its normal source size; state.bounds is the only
            // authoritative visual/input frame.  Callers such as resizeTaskInternal pass the Task
            // source bounds here, and treating those as the visual bounds briefly made MINI an
            // identity-sized, interactive app window.
            SystemServices.applyMiniFreeformVisual(taskId, miniSourceBounds(state), state.bounds)
        } else {
            SystemServices.applyFreeformSurfaceStyle(taskId, bounds, false)
        }
    }

    private fun miniSourceBounds(state: FreeformTaskState): Rect {
        return if (state.restoreNormalBounds.width() > 0 && state.restoreNormalBounds.height() > 0) {
            Rect(state.restoreNormalBounds)
        } else {
            FreeformPolicy.defaultNormalBounds()
        }
    }

    private fun normalTaskSourceBounds(state: FreeformTaskState): Rect =
        FreeformPolicy.taskSourceBoundsForVisual(
            baseBounds = miniSourceBounds(state),
            visualBounds = state.bounds,
        )

    private fun resizeTaskInternal(taskId: Int, bounds: Rect) {
        val ok = SystemServices.resizeTask(taskId, bounds, 0)
        if (!ok) {
            XLog.e("resizeTaskInternal $taskId failed")
        } else {
            val st = tasks[taskId]
            applySurfaceStyle(taskId, bounds, st?.windowState ?: WindowState.NORMAL)
        }
    }


    private fun setTaskWindowingMode(taskId: Int, mode: Int) {
        runCatching {
            SystemServices.invokeAtm("setTaskWindowingMode", taskId, mode, true)
        }.onFailure {
            runCatching {
                val am = SystemServices.activityManager
                am.javaClass.methods.firstOrNull {
                    it.name == "setTaskWindowingMode" && it.parameterTypes.size >= 2
                }?.let { m ->
                    if (m.parameterTypes.size == 3) m.invoke(am, taskId, mode, true)
                    else m.invoke(am, taskId, mode)
                }
            }.onFailure { e -> XLog.e("setTaskWindowingMode failed", e) }
        }
    }

    private fun setLaunchWindowingMode(options: ActivityOptions, mode: Int) {
        runCatching {
            val m = ActivityOptions::class.java.methods.first {
                it.name == "setLaunchWindowingMode" && it.parameterTypes.size == 1
            }
            m.invoke(options, mode)
        }.onFailure {
            runCatching {
                val f = ActivityOptions::class.java.getDeclaredField("mLaunchWindowingMode")
                f.isAccessible = true
                f.setInt(options, mode)
            }.onFailure { e -> XLog.e("setLaunchWindowingMode failed", e) }
        }
    }

    private fun setLaunchBounds(options: ActivityOptions, bounds: Rect) {
        runCatching {
            val m = ActivityOptions::class.java.methods.first {
                it.name == "setLaunchBounds" && it.parameterTypes.size == 1
            }
            m.invoke(options, bounds)
        }.onFailure { XLog.e("setLaunchBounds failed", it) }
    }

    /** Request Android's icon-style starting window so a cold/slow app paints immediately. */
    private fun requestIconSplashScreen(options: ActivityOptions) {
        runCatching {
            val method = ActivityOptions::class.java.methods.first {
                it.name == "setSplashScreenStyle" && it.parameterTypes.size == 1
            }
            // ActivityOptions.SPLASH_SCREEN_STYLE_ICON (hidden constant on some SDK stubs).
            method.invoke(options, 1)
        }.onFailure { XLog.e("setSplashScreenStyle failed", it) }
    }

    private fun userHandleOf(userId: Int): UserHandle {
        return runCatching {
            val ctor = UserHandle::class.java.getConstructor(Int::class.javaPrimitiveType)
            ctor.newInstance(userId)
        }.getOrElse {
            runCatching {
                UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
                    .invoke(null, userId) as UserHandle
            }.getOrElse { UserHandle.getUserHandleForUid(userId * 100000) }
        }
    }

    private fun findTaskId(packageName: String): Int {
        return try {
            @Suppress("DEPRECATION")
            val tasks = SystemServices.activityManager.getRunningTasks(32)
            tasks.firstOrNull {
                it.topActivity?.packageName == packageName ||
                    it.baseActivity?.packageName == packageName
            }?.id ?: -1
        } catch (t: Throwable) {
            // Fallback AppTasks
            try {
                val appTasks = SystemServices.activityManager.appTasks
                for (t in appTasks) {
                    val info = t.taskInfo ?: continue
                    val pkg = info.topActivity?.packageName ?: info.baseActivity?.packageName
                    if (pkg == packageName) {
                        return info.taskId
                    }
                }
            } catch (_: Throwable) {
            }
            XLog.e("findTaskId failed", t)
            -1
        }
    }
}
