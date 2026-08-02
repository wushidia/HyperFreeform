package io.hyper.freeform.service

import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import io.hyper.freeform.IFreeformManager

/**
 * Cross-process client for system_server FreeformManagerService.
 *
 * ServiceManager remains the preferred transport. Strict SELinux ROMs can reject custom service
 * names even from system_server, so commands transparently fall back to [FreeformBridge].
 */
object FreeformManagerClient {
    private const val TAG = "FreeformManagerClient"
    private const val SERVICE = "hyper_freeform"
    private const val SERVICE_RETRY_MS = 10_000L

    @Volatile
    private var service: IFreeformManager? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var preferCompatBridge = false

    @Volatile
    private var nextServiceLookupUptime = 0L

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    val isConnected: Boolean
        get() = isReady()

    private fun getServiceBinder(name: String): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            sm.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
        } catch (t: Throwable) {
            Log.e(TAG, "ServiceManager.getService failed", t)
            null
        }
    }

    private fun ensureService(): IFreeformManager? {
        service?.let { return it }
        if (preferCompatBridge) return null
        val now = SystemClock.uptimeMillis()
        if (now < nextServiceLookupUptime) return null
        return try {
            val binder = getServiceBinder(SERVICE)
            if (binder == null) {
                nextServiceLookupUptime = now + SERVICE_RETRY_MS
                return null
            }
            val svc = IFreeformManager.Stub.asInterface(binder)
            binder.linkToDeath({
                service = null
                nextServiceLookupUptime = 0L
            }, 0)
            service = svc
            svc
        } catch (t: Throwable) {
            nextServiceLookupUptime = now + SERVICE_RETRY_MS
            Log.e(TAG, "bind failed", t)
            null
        }
    }

    private fun resolveContext(): Context? {
        appContext?.let { return it }
        val resolved = runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getDeclaredMethod("currentApplication").apply {
                isAccessible = true
            }.invoke(null) as? Context
        }.getOrNull()?.applicationContext
        if (resolved != null) appContext = resolved
        return resolved
    }

    private fun isCompatBridgeReady(): Boolean {
        val context = resolveContext() ?: return false
        return runCatching {
            val cr = context.contentResolver
            val boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1)
            val readyBoot = Settings.Global.getInt(
                cr,
                FreeformBridge.SETTING_READY_BOOT,
                -2,
            )
            boot >= 0 && readyBoot == boot
        }.getOrDefault(false)
    }

    private fun sendBridge(
        operation: String,
        configure: Intent.() -> Unit = {},
    ): Boolean {
        if (!isCompatBridgeReady()) return false
        val context = resolveContext() ?: return false
        return runCatching {
            val intent = Intent(FreeformBridge.ACTION).apply {
                addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                putExtra(FreeformBridge.EXTRA_OPERATION, operation)
                configure()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle()
                context.sendBroadcast(intent, null, options)
            } else {
                context.sendBroadcast(intent)
            }
            true
        }.onFailure {
            Log.e(TAG, "compat bridge send failed operation=$operation", it)
        }.getOrDefault(false)
    }

    fun isReady(): Boolean {
        val binderReady = runCatching { ensureService()?.isReady == true }.getOrDefault(false)
        if (binderReady) return true
        val bridgeReady = isCompatBridgeReady()
        if (bridgeReady) preferCompatBridge = true
        return bridgeReady
    }

    fun startPackage(packageName: String, mini: Boolean = false) {
        val state = if (mini) 1 else 0
        val target = ensureService()
        if (target != null) {
            target.startFreeformPackage(packageName, 0, state)
        } else {
            sendBridge(FreeformBridge.OP_START_PACKAGE) {
                putExtra(FreeformBridge.EXTRA_PACKAGE, packageName)
                putExtra(FreeformBridge.EXTRA_WINDOW_STATE, state)
            }
        }
    }

    fun startComponent(component: ComponentName, mini: Boolean = false) {
        val state = if (mini) 1 else 0
        val target = ensureService()
        if (target != null) {
            target.startFreeform(component, 0, state)
        } else {
            sendBridge(FreeformBridge.OP_START_COMPONENT) {
                putExtra(FreeformBridge.EXTRA_COMPONENT, component.flattenToString())
                putExtra(FreeformBridge.EXTRA_WINDOW_STATE, state)
            }
        }
    }

    /**
     * Adopt an activity that SystemUI has just launched through the notification's original
     * PendingIntent with freeform ActivityOptions.  The PendingIntent must be sent by SystemUI so
     * its action/data/extras/task-stack semantics stay byte-for-byte identical to a normal click;
     * system_server only tracks and hardens the resulting native freeform task here.
     */
    fun adoptPendingIntentLaunch(
        packageName: String,
        component: ComponentName?,
        bounds: Rect,
        mini: Boolean = false,
    ): Boolean {
        if (packageName.isBlank() || bounds.isEmpty) return false
        val state = if (mini) 1 else 0
        return sendBridge(FreeformBridge.OP_ADOPT_PENDING_INTENT_LAUNCH) {
            putExtra(FreeformBridge.EXTRA_PACKAGE, packageName)
            component?.let {
                putExtra(FreeformBridge.EXTRA_COMPONENT, it.flattenToString())
            }
            putExtra(FreeformBridge.EXTRA_BOUNDS, bounds)
            putExtra(FreeformBridge.EXTRA_WINDOW_STATE, state)
        }
    }

    /**
     * Convert existing recents task to freeform (Xiaomi startSmallFreeformFromRecent).
     * @param mini true → windowState=MINI (default for recents hotzone)
     */
    fun startFromRecent(taskId: Int, mini: Boolean = true) {
        if (taskId <= 0) return
        val state = if (mini) 1 else 0
        val target = ensureService()
        if (target != null) {
            target.startFreeformFromRecent(taskId, state)
        } else {
            sendBridge(FreeformBridge.OP_START_RECENT) {
                putExtra(FreeformBridge.EXTRA_TASK_ID, taskId)
                putExtra(FreeformBridge.EXTRA_WINDOW_STATE, state)
            }
        }
    }

    fun dump(): String = runCatching {
        ensureService()?.dumpState()
            ?: if (isCompatBridgeReady()) "service=compat-bridge" else "service=null"
    }
        .getOrElse { it.message ?: "error" }

    fun openCount(): Int = runCatching { ensureService()?.openWindowCount ?: 0 }.getOrDefault(0)

    fun openPackages(): Set<String> = runCatching {
        val lines = ensureService()?.dumpState().orEmpty().lineSequence()
        lines.mapNotNull { line ->
            Regex("""\bpkg=([^\s]+)""").find(line)?.groupValues?.getOrNull(1)
        }.filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    fun setEnabled(enabled: Boolean) {
        val target = ensureService()
        if (target != null) {
            target.setEnabled(enabled)
        } else {
            sendBridge(FreeformBridge.OP_SET_ENABLED) {
                putExtra(FreeformBridge.EXTRA_ENABLED, enabled)
            }
        }
    }

    fun isEnabled(): Boolean {
        val target = runCatching { ensureService() }.getOrNull()
        if (target != null) return runCatching { target.isEnabled }.getOrDefault(true)
        val context = resolveContext() ?: return true
        return runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                FreeformBridge.SETTING_ENABLED,
                1,
            ) != 0
        }.getOrDefault(true)
    }

    fun closeTask(taskId: Int) {
        val target = ensureService()
        if (target != null) target.closeTask(taskId)
        else sendTaskBridge(FreeformBridge.OP_CLOSE_TASK, taskId)
    }

    fun pinTask(taskId: Int) {
        val target = ensureService()
        if (target != null) target.pinTask(taskId, true)
        else sendTaskBridge(FreeformBridge.OP_PIN_TASK, taskId)
    }

    fun unpinTask(taskId: Int) {
        val target = ensureService()
        if (target != null) target.unpinTask(taskId)
        else sendTaskBridge(FreeformBridge.OP_UNPIN_TASK, taskId)
    }

    /** Xiaomi startPinToFullscreen — maximize from pin bubble. */
    fun startPinToFullscreen(taskId: Int) {
        if (taskId <= 0) return
        val target = ensureService()
        if (target != null) target.startPinToFullscreen(taskId)
        else sendTaskBridge(FreeformBridge.OP_PIN_TO_FULLSCREEN, taskId)
    }

    /** Xiaomi updatePinFloatingWindowPos lite — bubble edge + Y while pinned. */
    fun updatePinFloatingWindowPos(taskId: Int, pinPos: Int, y: Int) {
        if (taskId <= 0) return
        val target = ensureService()
        if (target != null) {
            target.updatePinFloatingWindowPos(taskId, pinPos, y)
        } else {
            sendBridge(FreeformBridge.OP_UPDATE_PIN_POSITION) {
                putExtra(FreeformBridge.EXTRA_TASK_ID, taskId)
                putExtra(FreeformBridge.EXTRA_PIN_POSITION, pinPos)
                putExtra(FreeformBridge.EXTRA_Y, y)
            }
        }
    }

    /** Freeform → split. position: 0=top/left, 1=bottom/right. */
    fun splitTask(taskId: Int, position: Int = 0) {
        if (taskId <= 0) return
        val target = ensureService()
        if (target != null) {
            target.splitTask(taskId, position)
        } else {
            sendBridge(FreeformBridge.OP_SPLIT_TASK) {
                putExtra(FreeformBridge.EXTRA_TASK_ID, taskId)
                putExtra(FreeformBridge.EXTRA_POSITION, position)
            }
        }
    }

    /** Adjust the in-window DPI (Xiaomi small-window DPI zoom). dpi<=0 resets to system density. */
    fun setFreeformDpi(taskId: Int, dpi: Int) {
        if (taskId <= 0) return
        val target = ensureService()
        if (target != null) {
            target.setFreeformDpi(taskId, dpi)
        } else {
            sendBridge(FreeformBridge.OP_SET_FREEFORM_DPI) {
                putExtra(FreeformBridge.EXTRA_TASK_ID, taskId)
                putExtra(FreeformBridge.EXTRA_DPI, dpi)
            }
        }
    }

    fun getFreeformDpi(taskId: Int): Int =
        runCatching { ensureService()?.getFreeformDpi(taskId) ?: 0 }.getOrDefault(0)

    /** Set the global in-window DPI percentage (100 = follow system) via the service. */
    fun setGlobalDpiPercent(percent: Int): Boolean = runCatching {
        val normalized = FreeformBridge.sanitizeDpiPercent(percent)
        val target = ensureService()
        if (target != null) {
            target.setGlobalDpiPercent(normalized)
            true
        } else {
            sendBridge(FreeformBridge.OP_SET_GLOBAL_DPI) {
                putExtra(FreeformBridge.EXTRA_PERCENT, normalized)
            }
        }
    }.getOrDefault(false)

    fun getGlobalDpiPercent(): Int {
        val target = runCatching { ensureService() }.getOrNull()
        if (target != null) return runCatching { target.globalDpiPercent }
            .getOrDefault(FreeformBridge.DEFAULT_DPI_PERCENT)
        val context = resolveContext() ?: return FreeformBridge.DEFAULT_DPI_PERCENT
        return runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                FreeformBridge.SETTING_DPI_PERCENT,
                FreeformBridge.DEFAULT_DPI_PERCENT,
            )
        }.getOrDefault(FreeformBridge.DEFAULT_DPI_PERCENT)
    }

    /** Set the default normal freeform dimensions and resize existing windows live. */
    fun setGlobalWindowSizePercent(widthPercent: Int, heightPercent: Int): Boolean = runCatching {
        val width = FreeformBridge.sanitizeWindowSizePercent(widthPercent)
        val height = FreeformBridge.sanitizeWindowSizePercent(heightPercent)
        val target = ensureService()
        if (target != null) {
            target.setGlobalWindowSizePercent(width, height)
            true
        } else {
            sendBridge(FreeformBridge.OP_SET_GLOBAL_WINDOW_SIZE) {
                putExtra(FreeformBridge.EXTRA_WIDTH_PERCENT, width)
                putExtra(FreeformBridge.EXTRA_HEIGHT_PERCENT, height)
            }
        }
    }.getOrDefault(false)

    fun getGlobalWindowWidthPercent(): Int {
        val target = runCatching { ensureService() }.getOrNull()
        if (target != null) return runCatching { target.globalWindowWidthPercent }
            .getOrDefault(FreeformBridge.DEFAULT_WINDOW_WIDTH_PERCENT)
        val context = resolveContext() ?: return FreeformBridge.DEFAULT_WINDOW_WIDTH_PERCENT
        return runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                FreeformBridge.SETTING_WINDOW_WIDTH_PERCENT,
                FreeformBridge.DEFAULT_WINDOW_WIDTH_PERCENT,
            )
        }.getOrDefault(FreeformBridge.DEFAULT_WINDOW_WIDTH_PERCENT)
    }

    fun getGlobalWindowHeightPercent(): Int {
        val target = runCatching { ensureService() }.getOrNull()
        if (target != null) return runCatching { target.globalWindowHeightPercent }
            .getOrDefault(FreeformBridge.DEFAULT_WINDOW_HEIGHT_PERCENT)
        val context = resolveContext() ?: return FreeformBridge.DEFAULT_WINDOW_HEIGHT_PERCENT
        return runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                FreeformBridge.SETTING_WINDOW_HEIGHT_PERCENT,
                FreeformBridge.DEFAULT_WINDOW_HEIGHT_PERCENT,
            )
        }.getOrDefault(FreeformBridge.DEFAULT_WINDOW_HEIGHT_PERCENT)
    }

    fun collapseStatusBar() {
        val target = ensureService()
        if (target != null) target.collapseStatusBar()
        else sendBridge(FreeformBridge.OP_COLLAPSE_STATUS_BAR)
    }

    fun debugSimulateIme(visible: Boolean, height: Int) {
        val target = ensureService()
        if (target != null) {
            target.debugSimulateIme(visible, height)
        } else {
            sendBridge(FreeformBridge.OP_DEBUG_IME) {
                putExtra(FreeformBridge.EXTRA_SHOW, visible)
                putExtra(FreeformBridge.EXTRA_HEIGHT, height)
            }
        }
    }

    fun setSidebarSide(side: Int): Boolean = runCatching {
        val normalized = side.coerceIn(0, 1)
        val target = ensureService()
        if (target != null) {
            target.setSidebarSide(normalized)
            true
        } else {
            sendBridge(FreeformBridge.OP_SET_SIDEBAR_SIDE) {
                putExtra(FreeformBridge.EXTRA_SIDE, normalized)
            }
        }
    }.getOrDefault(false)

    fun getSidebarSide(): Int {
        val target = runCatching { ensureService() }.getOrNull()
        if (target != null) return runCatching { target.getSidebarSide() }.getOrDefault(1)
        val context = resolveContext() ?: return 1
        return runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                FreeformBridge.SETTING_SIDEBAR_SIDE,
                1,
            )
        }.getOrDefault(1).coerceIn(0, 1)
    }

    /** Persist the user's selected sidebar apps (CSV). Empty = show all launchable. */
    fun setSidebarApps(packagesCsv: String): Boolean = runCatching {
        val target = ensureService()
        if (target != null) {
            target.setSidebarApps(packagesCsv)
            true
        } else {
            sendBridge(FreeformBridge.OP_SET_SIDEBAR_APPS) {
                putExtra(FreeformBridge.EXTRA_PACKAGES_CSV, packagesCsv)
            }
        }
    }.getOrDefault(false)

    fun getSidebarApps(): String {
        val target = runCatching { ensureService() }.getOrNull()
        if (target != null) return runCatching { target.sidebarApps }.getOrDefault("")
        val context = resolveContext() ?: return ""
        return runCatching {
            Settings.Global.getString(
                context.contentResolver,
                FreeformBridge.SETTING_SIDEBAR_APPS,
            ) ?: ""
        }.getOrDefault("")
    }

    fun setSidebarShowAppNames(show: Boolean): Boolean = runCatching {
        val target = ensureService()
        if (target != null) {
            target.setSidebarShowAppNames(show)
            true
        } else {
            sendBridge(FreeformBridge.OP_SET_SIDEBAR_SHOW_NAMES) {
                putExtra(FreeformBridge.EXTRA_SHOW, show)
            }
        }
    }.getOrDefault(false)

    fun getSidebarShowAppNames(): Boolean {
        val target = runCatching { ensureService() }.getOrNull()
        if (target != null) return runCatching { target.sidebarShowAppNames }.getOrDefault(false)
        val context = resolveContext() ?: return false
        return runCatching {
            Settings.Global.getInt(
                context.contentResolver,
                FreeformBridge.SETTING_SIDEBAR_SHOW_NAMES,
                0,
            ) != 0
        }.getOrDefault(false)
    }

    private fun sendTaskBridge(operation: String, taskId: Int): Boolean {
        if (taskId <= 0) return false
        return sendBridge(operation) {
            putExtra(FreeformBridge.EXTRA_TASK_ID, taskId)
        }
    }
}
