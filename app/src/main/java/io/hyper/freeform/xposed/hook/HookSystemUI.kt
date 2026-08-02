package io.hyper.freeform.xposed.hook

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.hyper.freeform.service.FreeformManagerClient
import io.hyper.freeform.xposed.policy.FreeformPolicy
import io.hyper.freeform.xposed.shell.SplitScreenBridge
import io.hyper.freeform.xposed.utils.XLog
import java.lang.ref.WeakReference
import kotlin.math.abs

/**
 * SystemUI notification freeform entry (Xiaomi AppMiniWindowRowTouchHelper path, AOSP port).
 *
 * Xiaomi wires AppMiniWindowRowTouchHelper into NotificationStackScrollLayout's touchHandler.
 * On AOSP/MuMu there is no that helper, so we:
 *   1) hook NotificationStackScrollLayout intercept/touch (parent owns vertical scroll)
 *   2) resolve ExpandableNotificationRow under finger via getChildAtRawPosition
 *   3) DISTANCE (60dp) / SPEED (vy>1000 + 15dp) thresholds
 *   4) send the notification's original activity PendingIntent with freeform ActivityOptions
 *   5) collapse the shade
 *
 * Full RemoteTransition leash handoff is optional fidelity; MuMu still gets usable entry.
 */
object HookSystemUI {
    private const val ROW_CLASS =
        "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    private const val STACK_CLASS =
        "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"
    private const val SHADE_WINDOW_CLASS =
        "com.android.systemui.shade.NotificationShadeWindowView"

    /** Xiaomi res/values/dimens.xml mini_window_trigger_threshold */
    private const val TRIGGER_THRESHOLD_DP = 15f

    /** Xiaomi res/values/dimens.xml mini_window_max_trigger_threshold */
    private const val MAX_TRIGGER_THRESHOLD_DP = 60f

    /** Xiaomi AppMiniWindowRowTouchHelper SPEED path: yVelocity > 1000 px/s */
    private const val SPEED_VELOCITY_Y = 1000f

    /** Runtime tag for our Xiaomi heads_up_mini_window_bar clone. */
    private const val MINI_BAR_TAG = 0x70F11E21

    /** Settings.Global key mirrored from app Prefs (default true when absent). */
    private const val SETTINGS_NOTIFICATION_FREEFORM = "hyper_freeform_notification"
    private const val EXTRA_KEEP_HEADS_UP = "hyper_freeform_keep_heads_up"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Cooldown so one gesture cannot fire repeatedly. */
    @Volatile
    private var lastTriggerUptime = 0L

    @Volatile
    private var markedHookBoot = Int.MIN_VALUE

    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var pointerId = -1
    private var tracking = false
    private var armed = false
    private var triggered = false
    private var abandoned = false
    private var activeRow: Any? = null
    private var notificationStackRef: WeakReference<Any>? = null

    @Volatile
    private var loggedStickyDemoHeadsUp = false

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        XLog.d("HookSystemUI init for ${lpparam.packageName}")
        val cl = lpparam.classLoader
        hookStickyDemoHeadsUp(cl)
        val stackOk = hookStack(cl)
        val shadeWindowOk = hookShadeWindow(cl)
        val rowOk = hookRowFallback(cl)
        val splitOk = hookSplitScreenController(cl)
        val decorOk = hookShellFreeformWindowDecor(cl)
        val decorRecoveryOk = hookMuMuCaptionRecovery(cl)
        val decorBaseOk = hookBaseWindowDecorationRelayout(cl)
        val decorRelayoutOk = hookShellDecorationRelayout(cl)
        val closeTransOk = hookFreeformCloseTransition(cl)
        val captionSuppressionOk = decorOk && decorRecoveryOk && decorBaseOk && decorRelayoutOk
        // Cross-process freeform→split requests from system_server.
        runCatching {
            val app = XposedHelpers.findClass(
                "android.app.ActivityThread",
                cl
            )
            fun tryRegister(tag: String) {
                runCatching {
                    val c = XposedHelpers.callStaticMethod(app, "currentApplication")
                        as? android.content.Context
                    if (c != null) {
                        FreeformManagerClient.initialize(c)
                        if (captionSuppressionOk) markSystemUiHookActive(c, tag)
                        SplitScreenBridge.registerObserver(c)
                        XLog.d("SplitScreenBridge observer setup via $tag")
                    }
                }.onFailure { XLog.e("SplitScreenBridge observer setup failed ($tag)", it) }
            }
            tryRegister("immediate")
            // Application/Context may not exist at loadPackage; retry a few times.
            listOf(1_000L, 3_000L, 8_000L).forEach { delay ->
                mainHandler.postDelayed({ tryRegister("delayed-$delay") }, delay)
            }
        }.onFailure { XLog.e("SplitScreenBridge observer setup failed", it) }
        if (!stackOk && !rowOk && !shadeWindowOk) {
            XLog.e("HookSystemUI: no notification touch hooks installed")
        } else {
            XLog.i(
                "HookSystemUI: notification freeform entry armed " +
                    "(stack=$stackOk rowFallback=$rowOk splitBridge=$splitOk " +
                    "decor=$decorOk decorRecovery=$decorRecoveryOk decorBase=$decorBaseOk " +
                    "decorRelayout=$decorRelayoutOk " +
                    "closeTrans=$closeTransOk)"
            )
        }
    }

    private fun hookStickyDemoHeadsUp(cl: ClassLoader) {
        val classNames = listOf(
            "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl\$HeadsUpEntry",
            "com.android.systemui.statusbar.policy.HeadsUpManager\$HeadsUpEntry",
        )
        for (className in classNames) {
            val clazz = XposedHelpers.findClassIfExists(className, cl) ?: continue
            val stickyHooks = runCatching {
                XposedBridge.hookAllMethods(
                    clazz,
                    "isSticky",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!shouldKeepDemoHeadsUp(param.thisObject)) return
                            param.result = true
                            if (!loggedStickyDemoHeadsUp) {
                                loggedStickyDemoHeadsUp = true
                                XLog.d("HookSystemUI: keeping demo heads-up sticky")
                            }
                        }
                    },
                )
            }.getOrNull().orEmpty()
            val removalHooks = runCatching {
                XposedBridge.hookAllMethods(
                    clazz,
                    "scheduleAutoRemovalCallback",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (shouldKeepDemoHeadsUp(param.thisObject)) {
                                param.result = null
                            }
                        }
                    },
                )
            }.getOrNull().orEmpty()
            if (stickyHooks.isNotEmpty() && removalHooks.isNotEmpty()) return
        }
        XLog.d("HookSystemUI: sticky demo heads-up path unavailable")
    }

    private fun shouldKeepDemoHeadsUp(headsUpEntry: Any): Boolean {
        val entry = runCatching {
            XposedHelpers.getObjectField(headsUpEntry, "mEntry")
        }.getOrNull() ?: return false
        val sbn = runCatching {
            XposedHelpers.getObjectField(entry, "mSbn")
        }.getOrNull() ?: return false
        return notificationFromSbn(sbn)
            ?.extras
            ?.getBoolean(EXTRA_KEEP_HEADS_UP, false)
            ?: false
    }

    private fun markSystemUiHookActive(context: Context, tag: String) {
        runCatching {
            val cr = context.contentResolver
            val boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1)
            if (boot < 0 || markedHookBoot == boot) return
            if (Settings.Global.putInt(cr, FreeformPolicy.SETTINGS_SYSTEMUI_HOOK_BOOT, boot)) {
                markedHookBoot = boot
                XLog.i("HookSystemUI: caption suppression active boot=$boot via $tag")
            }
        }.onFailure { XLog.e("HookSystemUI: active marker failed", it) }
    }

    /**
     * MuMu periodically recreates a missing AOSP caption independently of the normal task-opening
     * path. Stop that recovery decision at its source for freeform tasks, while leaving desktop and
     * fullscreen task recovery untouched.
     */
    private fun hookMuMuCaptionRecovery(cl: ClassLoader): Boolean {
        val clazz = runCatching {
            XposedHelpers.findClass(
                "com.android.wm.shell.windowdecor.MuMuCaptionWindowDecorationExt",
                cl,
            )
        }.getOrNull() ?: run {
            XLog.d("HookSystemUI: MuMu caption recovery class missing")
            return false
        }
        var hooked = 0
        for (method in clazz.declaredMethods) {
            if (method.name != "shouldRecoverMissingCaption" ||
                method.returnType != java.lang.Boolean.TYPE
            ) {
                continue
            }
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val info = param.args.firstOrNull {
                            it != null && (
                                it.javaClass.name == "android.app.ActivityManager\$RunningTaskInfo" ||
                                    it.javaClass.name.endsWith("TaskInfo")
                                )
                        } ?: return
                        if (isShellFreeformTaskInfo(info)) {
                            param.result = false
                            XLog.d(
                                "HookSystemUI: block MuMu caption recovery " +
                                    "freeform task=${taskIdOf(info)}",
                            )
                        }
                    }
                })
                hooked++
            }.onFailure { XLog.e("HookSystemUI: MuMu caption recovery hook failed", it) }
        }
        if (hooked > 0) {
            XLog.i("HookSystemUI: MuMu caption recovery suppression armed x$hooked")
        }
        return hooked > 0
    }

    /**
     * Suppress the caption at WindowDecoration's common relayout boundary before any OEM/AOSP
     * transaction can show it. Subclass relayout methods still run so task crop/position and WCT
     * updates remain intact; only the caption view and its inset source are disabled.
     */
    private fun hookBaseWindowDecorationRelayout(cl: ClassLoader): Boolean {
        val clazz = runCatching {
            XposedHelpers.findClass("com.android.wm.shell.windowdecor.WindowDecoration", cl)
        }.getOrNull() ?: run {
            XLog.d("HookSystemUI: base WindowDecoration missing")
            return false
        }
        val scClz = runCatching {
            XposedHelpers.findClass("android.view.SurfaceControl", cl)
        }.getOrNull()
        val txClz = runCatching {
            XposedHelpers.findClass("android.view.SurfaceControl\$Transaction", cl)
        }.getOrNull()
        var hooked = 0
        for (method in clazz.declaredMethods) {
            if (method.name != "relayout" || method.parameterTypes.isEmpty()) continue
            if (!method.parameterTypes[0].name.endsWith("WindowDecoration\$RelayoutParams")) continue
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val relayoutParams = param.args.getOrNull(0) ?: return
                        val info = runCatching {
                            XposedHelpers.getObjectField(relayoutParams, "mRunningTaskInfo")
                        }.getOrNull() ?: runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mTaskInfo")
                        }.getOrNull() ?: return
                        if (!isShellFreeformTaskInfo(info)) return
                        runCatching {
                            XposedHelpers.setBooleanField(
                                relayoutParams,
                                "mIsCaptionVisible",
                                false,
                            )
                        }
                        runCatching {
                            XposedHelpers.setBooleanField(relayoutParams, "mIsInsetSource", false)
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = runCatching {
                            XposedHelpers.getObjectField(param.thisObject, "mTaskInfo")
                        }.getOrNull() ?: return
                        if (isShellFreeformTaskInfo(info)) {
                            hideDecorationCaption(param.thisObject, scClz, txClz)
                        }
                    }
                })
                hooked++
            }.onFailure { XLog.e("HookSystemUI: base decoration relayout hook failed", it) }
        }
        if (hooked > 0) {
            XLog.i("HookSystemUI: base caption visibility suppression armed x$hooked")
        }
        return hooked > 0
    }

    // WM Shell transition modes (android.view.WindowManager.TRANSIT_*)
    private const val TRANSIT_CLOSE = 2
    private const val TRANSIT_TO_BACK = 4

    /**
     * Suppress the AOSP freeform close animation flash.
     *
     * Our FreeformManagerService already plays a Xiaomi-style shrink+fade close on the task leash
     * and only THEN calls removeTask. WM responds with a CLOSE Shell transition whose
     * DefaultTransitionHandler re-shows the leash and plays a ~250ms task-close AnimationSet on top
     * — the brief "AOSP 自由窗口关闭动画" the user sees at the very end.
     *
     * Empirically (MuMu 15) that transition is a SINGLE change: the freeform task closing, with the
     * home wallpaper already visible behind it (no other change). So when a transition's changes are
     * ALL freeform tasks going away (CLOSE / TO_BACK), we hide their leashes in the start
     * transaction and finish the transition immediately with no animation — exactly the no-op
     * pattern AOSP's own SleepHandler uses (startTransaction.apply(); finishCallback(); return true).
     * Any transition that also opens/changes another window is left fully intact.
     */
    private fun hookFreeformCloseTransition(cl: ClassLoader): Boolean {
        val handler = runCatching {
            XposedHelpers.findClass("com.android.wm.shell.transition.DefaultTransitionHandler", cl)
        }.getOrNull() ?: run {
            XLog.d("HookSystemUI: DefaultTransitionHandler missing (no close-anim suppression)")
            return false
        }
        val scClz = runCatching { XposedHelpers.findClass("android.view.SurfaceControl", cl) }.getOrNull()
        val txClz = runCatching {
            XposedHelpers.findClass("android.view.SurfaceControl\$Transaction", cl)
        }.getOrNull()
        var hooked = 0
        for (m in handler.declaredMethods) {
            if (m.name != "startAnimation") continue
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val args = param.args
                        val info = args.firstOrNull {
                            it != null && it.javaClass.name == "android.window.TransitionInfo"
                        } ?: return
                        if (!isPureFreeformClose(info)) return
                        val startTx = args.firstOrNull {
                            it != null && it.javaClass.name == "android.view.SurfaceControl\$Transaction"
                        }
                        val finishCb = args.firstOrNull {
                            it != null && it.javaClass.methods.any { mm -> mm.name == "onTransitionFinished" }
                        }
                        val finishMethod = finishCb?.javaClass?.methods
                            ?.firstOrNull { it.name == "onTransitionFinished" }
                        // If we can't finish the transition ourselves, do NOT consume it — letting
                        // it hang would freeze every animation. Fall through to the AOSP handler.
                        if (startTx == null || finishCb == null || finishMethod == null ||
                            scClz == null || txClz == null
                        ) {
                            return
                        }
                        runCatching {
                            val changes = XposedHelpers.callMethod(info, "getChanges") as? List<*>
                            changes?.forEach { ch ->
                                val leash = ch?.let {
                                    runCatching { XposedHelpers.callMethod(it, "getLeash") }.getOrNull()
                                } ?: return@forEach
                                runCatching {
                                    txClz.getMethod("setAlpha", scClz, java.lang.Float.TYPE)
                                        .invoke(startTx, leash, 0f)
                                }
                                runCatching { txClz.getMethod("hide", scClz).invoke(startTx, leash) }
                            }
                            txClz.getMethod("apply").invoke(startTx)
                            // startAnimation already runs on Shell's HandlerExecutor. MuMu asserts
                            // that the finish callback stays on this exact executor, so finish here
                            // instead of reposting to ActivityThread's main Handler.
                            when (finishMethod.parameterTypes.size) {
                                0 -> finishMethod.invoke(finishCb)
                                1 -> finishMethod.invoke(finishCb, null)
                                2 -> finishMethod.invoke(finishCb, null, null)
                                else -> finishMethod.invoke(
                                    finishCb, *arrayOfNulls(finishMethod.parameterTypes.size),
                                )
                            }
                            // We fully handled the transition (no animation) → skip AOSP handler.
                            param.result = true
                            XLog.d("HookSystemUI: suppressed AOSP freeform close animation")
                        }.onFailure {
                            // Best-effort recovery: ensure the transition still finishes.
                            XLog.e("HookSystemUI: freeform close suppress failed", it)
                            runCatching {
                                when (finishMethod.parameterTypes.size) {
                                    0 -> finishMethod.invoke(finishCb)
                                    1 -> finishMethod.invoke(finishCb, null)
                                    else -> finishMethod.invoke(
                                        finishCb, *arrayOfNulls(finishMethod.parameterTypes.size),
                                    )
                                }
                                param.result = true
                            }
                        }
                    }
                })
                hooked++
            }.onFailure { XLog.e("HookSystemUI: hook startAnimation failed", it) }
        }
        if (hooked > 0) {
            XLog.i("HookSystemUI: freeform close-anim suppression armed (x$hooked)")
        }
        return hooked > 0
    }

    /** True iff every change in the transition is a freeform task going away (CLOSE / TO_BACK). */
    private fun isPureFreeformClose(info: Any): Boolean {
        return runCatching {
            val changes = XposedHelpers.callMethod(info, "getChanges") as? List<*> ?: return false
            if (changes.isEmpty()) return false
            for (ch in changes) {
                ch ?: return false
                val mode = (XposedHelpers.callMethod(ch, "getMode") as? Int) ?: return false
                if (mode != TRANSIT_CLOSE && mode != TRANSIT_TO_BACK) return false
                val taskInfo = runCatching { XposedHelpers.callMethod(ch, "getTaskInfo") }.getOrNull()
                    ?: return false
                if (!isShellFreeformTaskInfo(taskInfo)) return false
            }
            true
        }.getOrDefault(false)
    }

    /**
     * Definitive AOSP freeform caption chokepoint. MuMu's MuMuCaptionWindowDecorationExt runs a
     * PERIODIC refresher (refreshDesktopWindowDecorations → DesktopDecorRefresher.refreshTask) that
     * calls CaptionWindowDecoration.relayout(...) DIRECTLY, rebuilding the "Caption of Task=N"
     * surface for freeform tasks — bypassing onTaskOpening/createWindowDecoration entirely.
     *
     * We must NOT skip relayout: it also positions the task surface + applies a
     * WindowContainerTransaction, so skipping it left the app surface stale during a drag (white
     * screen / lag until recovery). Instead let relayout run in full, then (afterHookedMethod) HIDE
     * just the caption's own decoration surface (buttons) and CLOSE its DragResizeInputListener —
     * the input path that let the user drag the task out from under our overlay (separation) and
     * triggered AOSP's direction-agnostic pin-to-corner. The app content is a sibling surface, so
     * hiding the decoration surface never blanks the app.
     */
    private fun hookShellDecorationRelayout(cl: ClassLoader): Boolean {
        var ok = false
        val decorations = listOf(
            // MuMu's own caption decoration OVERRIDES relayout, so hooking the AOSP parents alone
            // missed it (its caption re-appeared on bilibili / browser). Hook the Ext class directly.
            "com.android.wm.shell.windowdecor.MuMuCaptionWindowDecorationExt",
            "com.android.wm.shell.windowdecor.CaptionWindowDecoration",
            "com.android.wm.shell.windowdecor.DesktopModeWindowDecoration",
        )
        val scClz = runCatching { XposedHelpers.findClass("android.view.SurfaceControl", cl) }.getOrNull()
        val txClz = runCatching {
            XposedHelpers.findClass("android.view.SurfaceControl\$Transaction", cl)
        }.getOrNull()
        for (name in decorations) {
            val clazz = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()
            if (clazz == null) {
                XLog.d("HookSystemUI: decoration class missing: $name")
                continue
            }
            var hooked = 0
            // 1) relayout afterHook: do NOT skip relayout (it also positions the task surface +
            //    applies a WindowContainerTransaction; skipping it caused white-screen during drags
            //    in an earlier build). Let it run, then hide any caption surface it created. This
            //    covers BOTH the AOSP relayout path and MuMu's per-task refresher relayout — the
            //    true chokepoint — so whichever surface holds the visible caption is hidden while
            //    the app content (a sibling surface) is untouched.
            for (m in clazz.declaredMethods) {
                if (m.name != "relayout") continue
                if (m.parameterTypes.isEmpty()) continue
                runCatching {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            trackDecoration(param.thisObject, scClz, txClz)
                            hideDecorationCaption(param.thisObject, scClz, txClz)
                        }
                    })
                    hooked++
                }
            }
            // 2) Constructor afterHook + short delayed re-hides. MuMu builds/shows the caption via
            // its own (obfuscated) refresher AFTER construction, not via AOSP relayout, so hook the
            // decoration's construction and hide the caption across the race window it appears in.
            runCatching {
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val decor = param.thisObject
                        val firstTrack = trackDecoration(decor, scClz, txClz)
                        hideDecorationCaption(decor, scClz, txClz)
                        if (firstTrack) {
                            for (delay in longArrayOf(50, 150, 350, 700, 1200)) {
                                mainHandler.postDelayed(
                                    { hideDecorationCaption(decor, scClz, txClz) }, delay,
                                )
                            }
                        }
                    }
                })
                hooked++
            }
            for (method in clazz.declaredMethods) {
                val lower = method.name.lowercase()
                if (!lower.contains("caption") || lower == "relayout") continue
                if (!(lower.contains("show") || lower.contains("create") ||
                        lower.contains("update") || lower.contains("refresh") ||
                        lower.contains("visibility") || lower.contains("recover"))) continue
                runCatching {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val decor = param.thisObject ?: return
                            if (!isDecorationForFreeform(decor)) return
                            trackDecoration(decor, scClz, txClz)
                            hideDecorationCaption(decor, scClz, txClz)
                        }
                    })
                    hooked++
                }
            }
            if (hooked > 0) {
                ok = true
                XLog.i("HookSystemUI: Shell caption suppression armed on $name x$hooked")
            } else {
                XLog.d("HookSystemUI: no caption hooks on $name")
            }
        }
        return ok
    }

    /** Throttle counter for the caption-field diagnostic. */
    private var captionDiagCount = 0

    private val trackedDecorations = java.util.concurrent.ConcurrentHashMap<
        Int,
        java.lang.ref.WeakReference<Any>
    >()
    private val decorationSweepStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun trackDecoration(decor: Any, scClz: Class<*>?, txClz: Class<*>?): Boolean {
        val key = System.identityHashCode(decor)
        val previous = trackedDecorations.put(key, java.lang.ref.WeakReference(decor))
        val firstTrack = previous?.get() !== decor
        if (!decorationSweepStarted.compareAndSet(false, true)) return firstTrack
        val sweep = object : Runnable {
            override fun run() {
                trackedDecorations.entries.removeIf { (_, ref) ->
                    val live = ref.get() ?: return@removeIf true
                    if (isDecorationForFreeform(live)) {
                        hideDecorationCaption(live, scClz, txClz)
                    }
                    false
                }
                mainHandler.postDelayed(this, 250L)
            }
        }
        mainHandler.postDelayed(sweep, 250L)
        return firstTrack
    }

    /**
     * Hide the AOSP caption surface(s) + disable its drag input, without touching the app surface.
     *
     * Hiding only mDecorationContainerSurface was not enough: MuMu's player relayouts reparent the
     * caption under its own leash ("Caption of Task=N"), so the container hide no longer covers it.
     * Hide EVERY SurfaceControl field on the decoration whose name looks caption/decoration-related
     * (never the task/app surface), so whichever one actually holds the visible caption is covered.
     */
    private fun hideDecorationCaption(decor: Any, scClz: Class<*>?, txClz: Class<*>?) {
        // Disable the caption's drag/resize input (rogue drag path).
        runCatching {
            val drl = XposedHelpers.getObjectField(decor, "mDragResizeListener")
            if (drl != null) {
                runCatching { XposedHelpers.callMethod(drl, "close") }
                XposedHelpers.setObjectField(decor, "mDragResizeListener", null)
            }
        }
        if (scClz == null || txClz == null) return
        runCatching {
            val tx = txClz.getDeclaredConstructor().newInstance()
            var any = false
            val found = mutableListOf<String>()
            var c: Class<*>? = decor.javaClass
            val seen = HashSet<String>()
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (!scClz.isAssignableFrom(f.type)) continue
                    if (!seen.add(f.name)) continue
                    val n = f.name.lowercase()
                    // Never hide the app/task content surface.
                    if (n.contains("task") || n.contains("app") || n.contains("content")) continue
                    // Caption chrome surfaces (mDecorationContainerSurface, mCaptionContainerSurface,
                    // mCaptionSurface, view-host leash, etc.).
                    if (!(n.contains("caption") || n.contains("decoration"))) continue
                    runCatching {
                        f.isAccessible = true
                        val surf = f.get(decor) ?: return@runCatching
                        found.add(f.name)
                        runCatching {
                            txClz.getMethod("setAlpha", scClz, java.lang.Float.TYPE).invoke(tx, surf, 0f)
                        }
                        runCatching { txClz.getMethod("hide", scClz).invoke(tx, surf) }
                            .recoverCatching {
                                txClz.getMethod("setVisibility", scClz, java.lang.Boolean.TYPE)
                                    .invoke(tx, surf, false)
                            }
                        any = true
                    }
                }
                c = c.superclass
            }
            if (any) txClz.getMethod("apply").invoke(tx)
            if (captionDiagCount < 12) {
                captionDiagCount++
                val allSc = mutableListOf<String>()
                var cc: Class<*>? = decor.javaClass
                while (cc != null && cc != Any::class.java) {
                    cc.declaredFields.filter { scClz.isAssignableFrom(it.type) }
                        .forEach { allSc.add(it.name) }
                    cc = cc.superclass
                }
                XLog.i("HookSystemUI: caption diag decor=${decor.javaClass.simpleName} hidden=$found allSC=$allSc")
            }
        }
    }

    /**
     * Suppress AOSP Shell freeform window decorations ("Caption of Task=N").
     * Xiaomi uses Miui freeform chrome instead of CaptionWindowDecorViewModel;
     * we provide HyperFreeformCaption chrome, so native back/min/max/close must go.
     *
     * Evidence (MuMu SurfaceFlinger): Caption of Task=*, Caption of Task=*Leash
     * from WindowDecoration.setTitle("Caption of Task=" + taskId).
     */
    private fun hookShellFreeformWindowDecor(cl: ClassLoader): Boolean {
        var ok = false
        val viewModels = listOf(
            "com.android.wm.shell.windowdecor.CaptionWindowDecorViewModel",
            "com.android.wm.shell.windowdecor.DesktopModeWindowDecorViewModel",
        )
        for (name in viewModels) {
            val clazz = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()
            if (clazz == null) {
                XLog.d("HookSystemUI: window decor class missing: $name")
                continue
            }
            var hooked = 0
            // Periodic caption rebuilds via the refresher read entries straight out of
            // mWindowDecorByTaskId; prune that map of freeform tasks so the relayout-per-task
            // path never has a decoration to relayout for our tasks.
            runCatching { hookPruneDecorMapForFreeform(clazz) }
            for (m in clazz.declaredMethods) {
                val n = m.name
                if (n.startsWith("shouldShowWindowDecor") &&
                    m.returnType == java.lang.Boolean.TYPE &&
                    m.parameterTypes.isNotEmpty()
                ) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val info = param.args.getOrNull(0) ?: return
                            if (isShellFreeformTaskInfo(info)) {
                                param.result = false
                            }
                        }
                    })
                    hooked++
                }
                if (n.startsWith("createWindowDecoration") && m.parameterTypes.isNotEmpty()) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val info = param.args.getOrNull(0) ?: return
                            if (isShellFreeformTaskInfo(info)) {
                                // Skip native caption create; our FreeformShellController owns chrome.
                                param.result = null
                                XLog.d(
                                    "HookSystemUI: skip Shell createWindowDecoration " +
                                        "freeform task=${taskIdOf(info)}",
                                )
                            }
                        }
                    })
                    hooked++
                }
                // Hook ShellTaskOrganizer -> WindowDecorViewModel boundary methods too.
                // Android 15 may invoke/in-line shouldShow/create within this class,
                // while these interface entry points remain external dispatch targets.
                if (n == "onTaskOpening" && m.parameterTypes.isNotEmpty()) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val info = param.args.getOrNull(0) ?: return
                            if (!isShellFreeformTaskInfo(info)) return
                            destroyShellWindowDecoration(param.thisObject, info)
                            param.result = false
                            XLog.d(
                                "HookSystemUI: skip Shell onTaskOpening " +
                                    "freeform task=${taskIdOf(info)}",
                            )
                        }
                    })
                    hooked++
                }
                if ((n == "onTaskChanging" ||
                        n == "onTaskClosing" ||
                        n == "onTaskInfoChanged") &&
                    m.parameterTypes.isNotEmpty()
                ) {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val info = param.args.getOrNull(0) ?: return
                            if (!isShellFreeformTaskInfo(info)) return
                            destroyShellWindowDecoration(param.thisObject, info)
                            param.result = null
                            XLog.d(
                                "HookSystemUI: skip Shell ${m.name} " +
                                    "freeform task=${taskIdOf(info)}",
                            )
                        }
                    })
                    hooked++
                }
            }
            if (hooked > 0) {
                ok = true
                XLog.i("HookSystemUI: Shell freeform decor suppressed on $name hooks=$hooked")
            } else {
                XLog.e("HookSystemUI: no decor methods hooked on $name")
            }
        }
        return ok
    }

    /**
     * MuMu's refresher relayouts decorations it pulls straight out of `mWindowDecorByTaskId`. We
     * prune any freeform task from that map the instant one lands in it, and replace it with null
     * so the caption decoration for our tasks is never retained — the relayout-per-task path then
     * has nothing to relayout. We also destroy the evicted decoration so its surface is gone.
     * Non-freeform tasks (desktop mode proper) are untouched. Guards: defensively iterates the
     * map to find the bound decorations that belong to a freeform task (cheaper than keying on
     * task ids we'd have to track here).
     */
    private fun hookPruneDecorMapForFreeform(clazz: Class<*>) {
        // Hook createWindowDecoration$1 / createWindowDecoration (both spellings) so that AFTER a
        // decoration is created for a freeform task, we immediately evict+destroy it.
        val decorations = listOf(
            "com.android.wm.shell.windowdecor.CaptionWindowDecoration",
            "com.android.wm.shell.windowdecor.DesktopModeWindowDecoration",
        ).mapNotNull { runCatching { XposedHelpers.findClass(it, clazz.classLoader) }.getOrNull() }

        for (m in clazz.declaredMethods) {
            val n = m.name
            if (!n.startsWith("createWindowDecoration")) continue
            if (m.parameterTypes.isEmpty()) continue
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val info = param.args.getOrNull(0) ?: return
                    if (!isShellFreeformTaskInfo(info)) return
                    val vm = param.thisObject ?: return
                    // Evict the just-added decoration and destroy it.
                    runCatching {
                        val map = XposedHelpers.getObjectField(vm, "mWindowDecorByTaskId") ?: return@runCatching
                        val taskId = taskIdOf(info)
                        if (taskId < 0) return@runCatching
                        val evicted = runCatching {
                            XposedHelpers.callMethod(map, "removeReturnOld", taskId)
                        }.getOrNull() ?: runCatching {
                            @Suppress("DEPRECATION")
                            XposedHelpers.callMethod(map, "remove", taskId)
                        }.getOrNull()
                        if (evicted != null && decorations.any { it.isInstance(evicted) }) {
                            runCatching { XposedHelpers.callMethod(evicted, "close") }
                                .onFailure { XposedHelpers.callMethod(evicted, "release") }
                        }
                    }
                }
            })
        }

        // Defensive periodic sweep: even if an entry point slips (an inlined create, a recovered
        // caption via MuMuCaptionWindowDecorationExt.shouldRecoverMissingCaption), this pulls any
        // freeform-task decoration out of mWindowDecorByTaskId within ~1s and destroys it. Cheap.
        // Resolve VM singletons lazily (they may not exist at hook time) — capture them at ctor.
        runCatching {
            XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    viewModelsLocal[clazz.name] = param.thisObject
                }
            })
        }
        if (!decorMapSweepStarted.compareAndSet(false, true)) return
        val sweep = object : Runnable {
            override fun run() {
                runCatching {
                    for ((_, inst) in viewModelsLocal) {
                        val map = runCatching {
                            XposedHelpers.getObjectField(inst, "mWindowDecorByTaskId")
                        }.getOrNull() ?: continue
                        var size = runCatching { XposedHelpers.callMethod(map, "size") as? Int }
                            .getOrNull() ?: 0
                        var i = 0
                        while (i < size) {
                            val key = runCatching {
                                XposedHelpers.callMethod(map, "keyAt", i) as? Int
                            }.getOrNull()
                            val value = runCatching {
                                XposedHelpers.callMethod(map, "valueAt", i)
                            }.getOrNull()
                            if (value != null && isDecorationForFreeform(value)) {
                                runCatching { XposedHelpers.callMethod(value, "close") }
                                    .onFailure { XposedHelpers.callMethod(value, "release") }
                                if (key != null) {
                                    runCatching { XposedHelpers.callMethod(map, "remove", key) }
                                    size = runCatching {
                                        XposedHelpers.callMethod(map, "size") as? Int
                                    }.getOrNull() ?: 0
                                    // don't advance i — shifted into this slot
                                } else {
                                    i++
                                }
                            } else {
                                i++
                            }
                        }
                    }
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
        mainHandler.postDelayed(sweep, 1000L)
    }

    /** ViewModel singletons captured at construction for the periodic sweep. */
    private val viewModelsLocal = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val decorMapSweepStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True iff a decoration object's mTaskInfo is a freeform task. */
    private fun isDecorationForFreeform(decor: Any): Boolean {
        return runCatching {
            val info = XposedHelpers.getObjectField(decor, "mTaskInfo")
                ?: XposedHelpers.callMethod(decor, "getTaskInfo")
            info != null && isShellFreeformTaskInfo(info)
        }.getOrDefault(false)
    }

    private fun isShellFreeformTaskInfo(info: Any): Boolean {
        return runCatching {
            val mode = when (val m = runCatching {
                info.javaClass.methods.firstOrNull {
                    it.name == "getWindowingMode" && it.parameterTypes.isEmpty()
                }?.invoke(info)
            }.getOrNull()) {
                is Int -> m
                is Number -> m.toInt()
                else -> {
                    // Fallback: configuration.windowConfiguration.windowingMode
                    val cfg = XposedHelpers.getObjectField(info, "configuration")
                        ?: XposedHelpers.callMethod(info, "getConfiguration")
                    val wc = XposedHelpers.getObjectField(cfg, "windowConfiguration")
                    XposedHelpers.callMethod(wc, "getWindowingMode") as Int
                }
            }
            mode == FreeformPolicy.WINDOWING_MODE_FREEFORM
        }.onFailure {
            XLog.e("HookSystemUI: resolve task windowing mode failed", it)
        }.getOrDefault(false)
    }

    private fun destroyShellWindowDecoration(host: Any, info: Any) {
        runCatching {
            XposedHelpers.callMethod(host, "destroyWindowDecoration", info)
        }.onFailure {
            XLog.e(
                "HookSystemUI: destroy Shell caption failed task=${taskIdOf(info)}",
                it,
            )
        }
    }

    private fun taskIdOf(info: Any): Int {
        return runCatching {
            XposedHelpers.getIntField(info, "taskId")
        }.getOrDefault(-1)
    }

    /**
     * Capture AOSP SplitScreenController for freeform→split (Xiaomi MulWinSwitch equivalent).
     */
    private fun hookSplitScreenController(cl: ClassLoader): Boolean {
        val names = listOf(
            "com.android.wm.shell.splitscreen.SplitScreenController",
            "com.android.wm.shell.sosc.SoScSplitScreenController",
        )
        var ok = false
        for (name in names) {
            val clazz = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull() ?: continue
            runCatching {
                XposedBridge.hookAllMethods(
                    clazz,
                    "onInit",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            SplitScreenBridge.attach(param.thisObject)
                        }
                    }
                )
                // Attach on common entry points in case onInit already ran before hook.
                for (methodName in listOf("onKeyguardVisibilityChanged", "moveToStage", "startTasks")) {
                    runCatching {
                        XposedBridge.hookAllMethods(
                            clazz,
                            methodName,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    SplitScreenBridge.attach(param.thisObject)
                                }
                            }
                        )
                    }
                }
                XposedBridge.hookAllConstructors(
                    clazz,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            XLog.d("SplitScreenController ctor seen ${clazz.name}")
                            // onInit usually follows; still keep reference early.
                            mainHandler.postDelayed({
                                SplitScreenBridge.attach(param.thisObject)
                            }, 500L)
                        }
                    }
                )
                XLog.i("HookSystemUI: hooked $name for split bridge")
                ok = true
            }.onFailure {
                XLog.e("HookSystemUI: hook $name failed", it)
            }
        }
        return ok
    }

    private fun hookStack(cl: ClassLoader): Boolean {
        val stackClass = runCatching { XposedHelpers.findClass(STACK_CLASS, cl) }.getOrNull()
            ?: run {
                XLog.e("HookSystemUI: $STACK_CLASS not found")
                return false
            }

        var ok = false
        runCatching {
            XposedBridge.hookAllConstructors(
                stackClass,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        notificationStackRef = WeakReference(param.thisObject ?: return)
                    }
                },
            )
        }.onSuccess { ok = it.isNotEmpty() || ok }
            .onFailure { XLog.e("hook stack constructor failed", it) }

        runCatching {
            XposedBridge.hookAllMethods(
                stackClass,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        notificationStackRef = WeakReference(param.thisObject ?: return)
                    }
                },
            )
        }.onSuccess { ok = it.isNotEmpty() || ok }
            .onFailure { XLog.e("hook stack attachment failed", it) }

        runCatching {
            XposedHelpers.findAndHookMethod(
                stackClass,
                "onInterceptTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val stack = param.thisObject ?: return
                        val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                        if (handleStackIntercept(stack, ev)) {
                            param.result = true
                        }
                    }
                }
            )
            ok = true
        }.onFailure { XLog.e("hook stack onInterceptTouchEvent failed", it) }

        runCatching {
            XposedHelpers.findAndHookMethod(
                stackClass,
                "onTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val stack = param.thisObject ?: return
                        val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                        if (handleStackTouch(stack, ev)) {
                            param.result = true
                        }
                    }
                }
            )
            ok = true
        }.onFailure { XLog.e("hook stack onTouchEvent failed", it) }

        // Some builds only dispatch via ViewGroup path on the stack; keep this armed even when
        // intercept/onTouch hooks are present so MuMu's notification panel delivers full streams.
        runCatching {
            XposedBridge.hookAllMethods(
                stackClass,
                "dispatchTouchEvent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val stack = param.thisObject ?: return
                        val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                        if (handleStackTouch(stack, ev)) {
                            param.result = true
                        }
                    }
                }
            )
        }.onSuccess {
            if (it.isNotEmpty()) {
                ok = true
                XLog.d("HookSystemUI: stack dispatchTouchEvent hooked x${it.size}")
            }
        }.onFailure { XLog.e("hook stack dispatchTouchEvent failed", it) }
        return ok
    }

    private fun hookShadeWindow(cl: ClassLoader): Boolean {
        val classNames = listOf(
            SHADE_WINDOW_CLASS,
            "com.android.systemui.statusbar.phone.NotificationShadeWindowView",
        )
        for (className in classNames) {
            val shadeClass = runCatching {
                XposedHelpers.findClass(className, cl)
            }.getOrNull() ?: continue
            val hooks = runCatching {
                XposedBridge.hookAllMethods(
                    shadeClass,
                    "dispatchTouchEvent",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                            val stack = notificationStackRef?.get() ?: return
                            if (handleStackTouch(stack, ev)) {
                                param.result = true
                            }
                        }
                    },
                )
            }.onFailure {
                XLog.e("HookSystemUI: shade root touch hook failed for $className", it)
            }.getOrNull().orEmpty()
            if (hooks.isNotEmpty()) {
                XLog.i("HookSystemUI: hooked shade root touch $className")
                return true
            }
        }
        XLog.d("HookSystemUI: shade root touch class unavailable")
        return false
    }

    /**
     * Secondary: still hook row in case a build delivers touch to rows first.
     */
    private fun hookRowFallback(cl: ClassLoader): Boolean {
        val rowClass = runCatching { XposedHelpers.findClass(ROW_CLASS, cl) }.getOrNull()
            ?: return false
        var ok = false
        runCatching {
            XposedBridge.hookAllMethods(
                rowClass,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val row = param.thisObject ?: return
                        mainHandler.post { updateMiniWindowBar(row) }
                    }
                },
            )
        }.onSuccess { ok = it.isNotEmpty() || ok }
            .onFailure { XLog.d("HookSystemUI: row attach fallback skipped: ${it.message}") }

        runCatching {
            XposedBridge.hookAllMethods(
                rowClass,
                "setActualHeight",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        updateMiniWindowBar(param.thisObject ?: return)
                    }
                },
            )
        }.onSuccess { ok = it.isNotEmpty() || ok }
            .onFailure { XLog.d("HookSystemUI: row actual-height hook skipped: ${it.message}") }

        runCatching {
            XposedBridge.hookAllMethods(
                rowClass,
                "dispatchTouchEvent",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val row = param.thisObject ?: return
                        val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                        if (handleRowTouch(row, ev)) {
                            param.result = true
                        }
                    }
                },
            )
        }.onSuccess { ok = it.isNotEmpty() || ok }
            .onFailure { XLog.d("HookSystemUI: row dispatch fallback skipped: ${it.message}") }

        runCatching {
            XposedHelpers.findAndHookMethod(
                rowClass,
                "onTouchEvent",
                MotionEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val row = param.thisObject ?: return
                        val ev = param.args.getOrNull(0) as? MotionEvent ?: return
                        // Only act if stack path did not already take ownership.
                        if (!tracking && !armed) {
                            // Seed active row so stack intercept can pick it up on next move.
                            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                                updateMiniWindowBar(row)
                                activeRow = row
                            }
                        }
                        if (handleRowTouch(row, ev)) {
                            param.result = true
                        }
                    }
                }
            )
            ok = true
        }.onFailure { XLog.d("HookSystemUI: row touch fallback skipped: ${it.message}") }
        return ok
    }

    private fun handleRowTouch(row: Any, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                updateMiniWindowBar(row)
                activeRow = row
                val targetPkg = extractLaunchTarget(row)?.packageName.orEmpty()
                val launchable = isLaunchablePkg(targetPkg)
                val barHit = isMiniBarHit(row, ev.rawX, ev.rawY)
                if (barHit || targetPkg.isNotBlank()) {
                    val view = row as? View
                    val loc = IntArray(2)
                    view?.getLocationOnScreen(loc)
                    XLog.d(
                        "HookSystemUI: row down pkg=$targetPkg launchable=$launchable " +
                            "barHit=$barHit raw=${ev.rawX},${ev.rawY} " +
                            "row=${loc[0]},${loc[1]} ${view?.width}x${view?.height}"
                    )
                }
                if (!launchable || !barHit) {
                    resetGesture()
                    return false
                }
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
                downX = ev.x
                downY = ev.y
                pointerId = ev.getPointerId(0)
                triggered = false
                abandoned = false
                tracking = true
                armed = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking || !armed || triggered || abandoned || activeRow !== row) return triggered
                velocityTracker?.addMovement(ev)
                val idx = ev.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
                val dy = ev.getY(idx) - downY
                val dx = ev.getX(idx) - downX
                val slop = touchSlop(row)
                if (dy < -slop) {
                    resetGesture()
                    return false
                }
                if (dy >= maxTriggerThresholdPx(row) && dy > abs(dx) * 1.1f) {
                    if (tryTriggerFreeform(row, reason = "ROW_DISTANCE")) {
                        triggered = true
                        tracking = false
                        return true
                    }
                }
                return dy > slop && abs(dy) > abs(dx)
            }
            MotionEvent.ACTION_UP -> {
                var consumed = triggered
                if (!triggered && !abandoned && tracking && armed && activeRow === row) {
                    val idx = ev.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
                    val dy = ev.getY(idx) - downY
                    val dx = ev.getX(idx) - downX
                    val vt = velocityTracker
                    vt?.addMovement(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.getYVelocity(pointerId) ?: 0f
                    if (vy > SPEED_VELOCITY_Y && dy > triggerThresholdPx(row) && dy > abs(dx)) {
                        if (tryTriggerFreeform(row, reason = "ROW_SPEED")) {
                            triggered = true
                            consumed = true
                        }
                    }
                }
                resetGesture()
                return consumed
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = triggered
                resetGesture()
                return consumed
            }
        }
        return triggered
    }

    private fun resetGesture() {
        velocityTracker?.recycle()
        velocityTracker = null
        tracking = false
        armed = false
        triggered = false
        abandoned = false
        activeRow = null
        pointerId = -1
    }

    private fun handleStackIntercept(stack: Any, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(stack, ev)
                return armed && activeRow != null
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking || triggered || abandoned) return triggered
                val idx = ev.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
                val dy = ev.getY(idx) - downY
                val dx = ev.getX(idx) - downX
                val slop = touchSlop(stack)
                // Xiaomi: commit intercept once clearly vertical-down past slop.
                if (dy > slop && abs(dy) > abs(dx)) {
                    ensureRow(stack, ev)
                    if (activeRow != null) {
                        armed = true
                        return true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!triggered) resetGesture()
            }
        }
        return triggered
    }

    private fun handleStackTouch(stack: Any, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(stack, ev)
                return armed && activeRow != null
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                abandoned = true
                tracking = false
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking || triggered || abandoned) return triggered
                velocityTracker?.addMovement(ev)

                val idx = ev.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
                val dy = ev.getY(idx) - downY
                val dx = ev.getX(idx) - downX
                val slop = touchSlop(stack)

                // Xiaomi: abandon if upward past touchSlop.
                if (dy < -slop) {
                    abandoned = true
                    tracking = false
                    return false
                }

                if (!armed && dy > slop && abs(dy) > abs(dx)) {
                    ensureRow(stack, ev)
                    if (activeRow != null) armed = true
                }
                if (!armed || activeRow == null) return false

                // DISTANCE path: dy > max_trigger_threshold (60dp).
                val maxTrigger = maxTriggerThresholdPx(stack)
                if (dy >= maxTrigger && dy > abs(dx) * 1.1f) {
                    if (tryTriggerFreeform(activeRow!!, reason = "DISTANCE")) {
                        triggered = true
                        tracking = false
                        return true
                    }
                }
                return armed // keep consuming once armed so stack does not scroll-steal
            }
            MotionEvent.ACTION_UP -> {
                var consumed = triggered
                if (!triggered && !abandoned && tracking && armed && activeRow != null) {
                    val idx = ev.findPointerIndex(pointerId).takeIf { it >= 0 } ?: 0
                    val dy = ev.getY(idx) - downY
                    val dx = ev.getX(idx) - downX
                    val vt = velocityTracker
                    vt?.addMovement(ev)
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.getYVelocity(pointerId) ?: 0f
                    val trigger = triggerThresholdPx(stack)
                    // SPEED path.
                    if (vy > SPEED_VELOCITY_Y && dy > trigger && dy > abs(dx)) {
                        if (tryTriggerFreeform(activeRow!!, reason = "SPEED")) {
                            triggered = true
                            consumed = true
                        }
                    }
                }
                resetGesture()
                return consumed
            }
            MotionEvent.ACTION_CANCEL -> {
                val consumed = triggered
                resetGesture()
                return consumed
            }
        }
        return false
    }

    private fun beginGesture(stack: Any, ev: MotionEvent) {
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(ev)
        downX = ev.x
        downY = ev.y
        pointerId = ev.getPointerId(0)
        triggered = false
        armed = false
        abandoned = false
        tracking = true
        activeRow = resolveRow(stack, ev.rawX, ev.rawY) ?: resolveRow(stack, ev.x, ev.y)
        activeRow?.let { updateMiniWindowBar(it) }
        val row = activeRow
        val pkg = row?.let { extractLaunchTarget(it)?.packageName }
        if (row == null || pkg == null || !isLaunchablePkg(pkg) ||
            !isMiniBarHit(row, ev.rawX, ev.rawY)
        ) {
            tracking = false
            activeRow = null
            return
        }
        // Consume from ACTION_DOWN so NotificationShade never gets a chance to steal the bar drag.
        armed = true
        XLog.d("HookSystemUI: stack bar down pkg=$pkg raw=${ev.rawX},${ev.rawY}")
    }

    private fun ensureRow(stack: Any, ev: MotionEvent) {
        if (activeRow != null) return
        activeRow = resolveRow(stack, ev.rawX, ev.rawY) ?: resolveRow(stack, ev.x, ev.y)
        activeRow?.let { updateMiniWindowBar(it) }
        val pkg = activeRow?.let { extractLaunchTarget(it)?.packageName }
        if (pkg != null && !isLaunchablePkg(pkg)) {
            activeRow = null
        } else if (activeRow != null && !isMiniBarHit(activeRow!!, ev.rawX, ev.rawY)) {
            activeRow = null
        }
    }

    private fun updateMiniWindowBar(row: Any) {
        val vg = row as? ViewGroup ?: return
        val pkg = extractLaunchTarget(row)?.packageName
        val shouldShow = pkg != null && isLaunchablePkg(pkg)
        val existing = vg.getTag(MINI_BAR_TAG) as? View
        if (!shouldShow) {
            existing?.visibility = View.GONE
            return
        }
        val d = vg.resources.displayMetrics.density
        val barWidth = (60.36f * d).toInt().coerceAtLeast(1)
        val barHeight = (3.64f * d).toInt().coerceAtLeast(2)
        val bottomMargin = (7.27f * d).toInt().coerceAtLeast(0)
        val bar = existing ?: View(vg.context).apply {
            tag = "hyper_freeform_mini_window_bar"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0x66000000)
                cornerRadius = 2f * d
            }
            val lp = FrameLayout.LayoutParams(
                barWidth,
                barHeight,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            )
            layoutParams = lp
            runCatching { vg.addView(this, lp) }
            vg.setTag(MINI_BAR_TAG, this)
        }
        (bar.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            lp.width = barWidth
            lp.height = barHeight
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin = (visibleRowHeight(vg) - barHeight - bottomMargin)
                .toInt()
                .coerceAtLeast(0)
            lp.bottomMargin = 0
            bar.layoutParams = lp
        }
        bar.visibility = View.VISIBLE
        bar.bringToFront()
    }

    private fun isMiniBarHit(row: Any, rawX: Float, rawY: Float): Boolean {
        val view = row as? View ?: return true
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val localX = rawX - loc[0]
        val localY = rawY - loc[1]
        val d = view.resources.displayMetrics.density
        val hitW = 112f * d
        val hitH = 40f * d
        val centerX = view.width / 2f
        val bottom = visibleRowHeight(view) - 1f
        val top = bottom - hitH
        return localX >= centerX - hitW / 2f &&
            localX <= centerX + hitW / 2f &&
            localY >= top &&
            localY <= bottom + 8f * d
    }

    private fun visibleRowHeight(view: View): Float {
        val actualHeight = runCatching {
            (XposedHelpers.callMethod(view, "getActualHeight") as? Number)?.toFloat()
        }.getOrNull() ?: runCatching {
            XposedHelpers.getIntField(view, "mActualHeight").toFloat()
        }.getOrNull()
        return actualHeight
            ?.takeIf { it > 0f }
            ?.coerceAtMost(view.height.toFloat())
            ?: view.height.toFloat()
    }

    private fun isLaunchablePkg(pkg: String): Boolean {
        return pkg.isNotBlank() &&
            !FreeformPolicy.isBlacklisted(pkg) &&
            pkg != "io.hyper.freeform" &&
            pkg != "android" &&
            pkg != "com.android.systemui"
    }

    private fun resolveRow(stack: Any, x: Float, y: Float): Any? {
        // Prefer Xiaomi/AOSP getChildAtRawPosition(rawX, rawY)
        runCatching {
            XposedHelpers.callMethod(stack, "getChildAtRawPosition", x, y)
        }.getOrNull()?.let { child ->
            findRow(child)?.let { return it }
        }
        // getChildAtPosition(x, y, true, true)
        runCatching {
            XposedHelpers.callMethod(
                stack,
                "getChildAtPosition",
                x,
                y,
                true,
                true,
            )
        }.getOrNull()?.let { child ->
            findRow(child)?.let { return it }
        }
        // Manual hit-test children
        val vg = stack as? android.view.ViewGroup ?: return null
        val loc = IntArray(2)
        vg.getLocationOnScreen(loc)
        val localX = x - loc[0]
        val localY = y - loc[1]
        for (i in vg.childCount - 1 downTo 0) {
            val child = vg.getChildAt(i) ?: continue
            if (child.visibility != View.VISIBLE) continue
            if (localX >= child.left && localX < child.right &&
                localY >= child.top && localY < child.bottom
            ) {
                findRow(child)?.let { return it }
            }
        }
        return null
    }

    private fun findRow(view: Any?): Any? {
        if (view == null) return null
        var cur: Any? = view
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.javaClass.name.contains("ExpandableNotificationRow")) return cur
            cur = (cur as? View)?.parent
            depth++
        }
        // Some wrappers: walk children one level
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val c = view.getChildAt(i)
                if (c.javaClass.name.contains("ExpandableNotificationRow")) return c
            }
        }
        return if (view.javaClass.name.contains("ExpandableNotificationRow")) view else null
    }

    private fun touchSlop(host: Any): Float {
        val view = host as? View
        val ctx = view?.context
        return if (ctx != null) {
            ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
        } else {
            24f
        }
    }

    private fun density(host: Any): Float {
        val view = host as? View
        return view?.resources?.displayMetrics?.density ?: 4f
    }

    private fun triggerThresholdPx(host: Any): Float = TRIGGER_THRESHOLD_DP * density(host)

    private fun maxTriggerThresholdPx(host: Any): Float = MAX_TRIGGER_THRESHOLD_DP * density(host)

    private fun isNotificationFreeformEnabled(host: Any): Boolean {
        val view = host as? View
        if (view != null) FreeformManagerClient.initialize(view.context)
        if (!FreeformManagerClient.isEnabled()) return false
        val cr = view?.context?.contentResolver ?: return true
        return runCatching {
            Settings.Global.getInt(cr, SETTINGS_NOTIFICATION_FREEFORM, 1) != 0
        }.getOrDefault(true)
    }

    private fun tryTriggerFreeform(row: Any, reason: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerUptime < 900L) return false
        if (!isNotificationFreeformEnabled(row)) {
            XLog.d("HookSystemUI: notification freeform disabled")
            return false
        }

        val target = extractLaunchTarget(row)
        if (target == null) {
            XLog.d("HookSystemUI: no package/component on row")
            return false
        }
        val pkg = target.packageName
        if (!isLaunchablePkg(pkg)) {
            XLog.d("HookSystemUI: skip non-launchable $pkg")
            return false
        }
        if (!FreeformManagerClient.isReady()) {
            XLog.e("HookSystemUI: hyper_freeform not ready")
            return false
        }

        lastTriggerUptime = now
        val component = target.component
        XLog.i(
            "HookSystemUI: notification freeform for $pkg" +
                (if (component != null) " component=$component" else "") +
                " reason=$reason exactPendingIntent=${target.pendingIntent != null} " +
                "(LaunchFreeFormByNotification path)"
        )

        mainHandler.post {
            runCatching {
                val activityPendingIntent = target.pendingIntent?.takeIf {
                    runCatching { it.isActivity }.getOrDefault(false)
                }
                val launched = if (activityPendingIntent != null) {
                    launchNotificationPendingIntent(row, target).also { delivered ->
                        if (delivered) dispatchNotificationDragSuccess(row)
                    }
                } else {
                    // A row without an activity PendingIntent keeps the legacy package/component
                    // fallback, but it is not reported as a notification click and is not removed.
                    if (component != null) {
                        FreeformManagerClient.startComponent(component, mini = false)
                    } else {
                        FreeformManagerClient.startPackage(pkg, mini = false)
                    }
                    true
                }
                // A cancelled activity PendingIntent must behave like a cancelled normal click:
                // do not replace it with a launcher intent and do not auto-dismiss its row.
                if (launched) {
                    FreeformManagerClient.collapseStatusBar()
                    collapseShadeLocal(row)
                }
            }.onFailure { XLog.e("HookSystemUI: start freeform failed for $pkg", it) }
        }
        return true
    }

    private fun collapseShadeLocal(row: Any) {
        val view = row as? View ?: return
        val ctx = view.context ?: return
        runCatching {
            val sbm = ctx.getSystemService("statusbar")
            val methods = sbm?.javaClass?.methods.orEmpty()
            val method = methods.firstOrNull {
                it.name == "collapsePanels" && it.parameterTypes.isEmpty()
            } ?: methods.firstOrNull {
                (it.name == "collapsePanels" || it.name == "animateCollapsePanels") &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            when (method?.parameterTypes?.size) {
                0 -> method.invoke(sbm)
                1 -> method.invoke(sbm, 0)
                else -> Unit
            }
        }
    }

    private data class LaunchTarget(
        val packageName: String,
        val component: ComponentName? = null,
        val pendingIntent: PendingIntent? = null,
    )

    private fun extractLaunchTarget(row: Any): LaunchTarget? {
        val sbn = extractSbn(row)
        val sbnPkg = packageFromSbn(sbn) ?: extractPackage(row)

        val contentIntent = contentPendingIntent(sbn, row)
        val intent = pendingIntentToIntent(contentIntent)
        val component = intent?.component
            ?: resolveImplicitActivity(row, intent)
            ?: extractComponentFromExtras(sbn)
        val extrasTarget = extractTargetPackageFromExtras(sbn)

        // Xiaomi getMiniWindowTargetPkg: prefer content intent / component package.
        val pkg = component?.packageName
            ?: intent?.`package`
            ?: extrasTarget
            ?: sbnPkg
            ?: return null

        return LaunchTarget(
            packageName = pkg,
            component = component,
            pendingIntent = contentIntent,
        )
    }

    /** Send the original notification PendingIntent, changing only its launch window options. */
    private fun launchNotificationPendingIntent(row: Any, target: LaunchTarget): Boolean {
        val pendingIntent = target.pendingIntent ?: return false
        val context = (row as? View)?.context ?: return false
        val bounds = FreeformPolicy.defaultNormalBounds(context)
        val options = ActivityOptions.makeBasic()
        setNotificationLaunchWindowingMode(options, FreeformPolicy.WINDOWING_MODE_FREEFORM)
        setNotificationLaunchBounds(options, bounds)
        setNotificationPendingIntentBalMode(options)
        setNotificationLaunchDisplay(options, context.display.displayId)

        return try {
            pendingIntent.send(
                context,
                0,
                null,
                null,
                null,
                null,
                options.toBundle(),
            )
            val adopted = FreeformManagerClient.adoptPendingIntentLaunch(
                target.packageName,
                target.component,
                Rect(bounds),
                mini = false,
            )
            if (!adopted) {
                XLog.e(
                    "HookSystemUI: PendingIntent delivered but task adoption unavailable " +
                        "for ${target.packageName}",
                )
            }
            XLog.i(
                "HookSystemUI: delivered original notification PendingIntent " +
                    "pkg=${target.packageName} component=${target.component} bounds=$bounds",
            )
            true
        } catch (cancelled: PendingIntent.CanceledException) {
            XLog.e(
                "HookSystemUI: notification PendingIntent cancelled for ${target.packageName}",
                cancelled,
            )
            false
        }
    }

    /**
     * SystemUI's own drag-success path sends click telemetry and runs FutureDismissal only when
     * Notification.FLAG_AUTO_CANCEL is present.  This is the same semantic split as a normal click:
     * auto-cancel rows disappear, ongoing/non-auto-cancel rows remain.
     */
    private fun dispatchNotificationDragSuccess(row: Any) {
        val adapter = runCatching {
            XposedHelpers.getObjectField(row, "mEntryAdapter")
        }.getOrNull() ?: run {
            XLog.e("HookSystemUI: notification entry adapter missing after PendingIntent launch")
            return
        }
        runCatching {
            XposedHelpers.callMethod(adapter, "onDragSuccess")
        }.onSuccess {
            val flags = extractSbn(row)?.let(::notificationFromSbn)?.flags ?: 0
            val autoCancel = flags and android.app.Notification.FLAG_AUTO_CANCEL != 0
            XLog.i("HookSystemUI: notification drag success autoCancel=$autoCancel")
        }.onFailure {
            XLog.e("HookSystemUI: notification drag success dispatch failed", it)
        }
    }

    private fun resolveImplicitActivity(row: Any, intent: Intent?): ComponentName? {
        val context = (row as? View)?.context ?: return null
        intent?.resolveActivity(context.packageManager)?.let { return it }
        return null
    }

    private fun setNotificationLaunchWindowingMode(options: ActivityOptions, mode: Int) {
        runCatching {
            XposedHelpers.callMethod(options, "setLaunchWindowingMode", mode)
        }.onFailure {
            runCatching {
                val field = ActivityOptions::class.java.getDeclaredField("mLaunchWindowingMode")
                field.isAccessible = true
                field.setInt(options, mode)
            }.onFailure { error ->
                XLog.e("HookSystemUI: set notification launch windowing mode failed", error)
            }
        }
    }

    private fun setNotificationLaunchBounds(options: ActivityOptions, bounds: Rect) {
        runCatching {
            XposedHelpers.callMethod(options, "setLaunchBounds", bounds)
        }.onFailure {
            XLog.e("HookSystemUI: set notification launch bounds failed", it)
        }
    }

    private fun setNotificationPendingIntentBalMode(options: ActivityOptions) {
        runCatching {
            XposedHelpers.callMethod(options, "setPendingIntentBackgroundActivityStartMode", 1)
        }.onFailure {
            XLog.d("HookSystemUI: PendingIntent BAL option unavailable: ${it.message}")
        }
    }

    private fun setNotificationLaunchDisplay(options: ActivityOptions, displayId: Int) {
        runCatching {
            XposedHelpers.callMethod(options, "setLaunchDisplayId", displayId)
        }.onFailure {
            XLog.d("HookSystemUI: notification display option unavailable: ${it.message}")
        }
    }

    private fun extractTargetPackageFromExtras(sbn: Any?): String? {
        if (sbn == null) return null
        val notification = notificationFromSbn(sbn) ?: return null
        val extras = runCatching {
            XposedHelpers.getObjectField(notification, "extras") as? android.os.Bundle
        }.getOrNull() ?: notification.extras ?: return null
        return extras.getString("hyper_freeform_target_pkg")
            ?.takeIf { it.isNotBlank() && it.contains('.') }
    }

    private fun extractSbn(row: Any): Any? {
        val adapter = runCatching {
            XposedHelpers.getObjectField(row, "mEntryAdapter")
        }.getOrNull()
        if (adapter != null) {
            runCatching { XposedHelpers.callMethod(adapter, "getSbn") }
                .getOrNull()
                ?.let { return it }
        }

        val entry = extractEntry(row)

        if (entry != null) {
            runCatching { XposedHelpers.callMethod(entry, "getSbn") }.getOrNull()?.let { return it }
            runCatching { XposedHelpers.getObjectField(entry, "mSbn") }.getOrNull()?.let { return it }
            runCatching { XposedHelpers.getObjectField(entry, "sbn") }.getOrNull()?.let { return it }
            findStatusBarNotificationField(entry)?.let { return it }
        }
        return null
    }

    private fun extractEntry(row: Any): Any? {
        runCatching { XposedHelpers.callMethod(row, "getEntry") }
            .getOrNull()
            ?.let { return it }
        runCatching { XposedHelpers.callMethod(row, "getEntryLegacy") }
            .getOrNull()
            ?.let { return it }
        runCatching { XposedHelpers.getObjectField(row, "mEntry") }
            .getOrNull()
            ?.let { return it }
        val adapter = runCatching {
            XposedHelpers.getObjectField(row, "mEntryAdapter")
        }.getOrNull()
        if (adapter != null) {
            runCatching { XposedHelpers.getObjectField(adapter, "entry") }
                .getOrNull()
                ?.let { return it }
        }
        return null
    }

    private fun findStatusBarNotificationField(host: Any): StatusBarNotification? {
        var type: Class<*>? = host.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (!StatusBarNotification::class.java.isAssignableFrom(field.type)) continue
                runCatching {
                    field.isAccessible = true
                    field.get(host) as? StatusBarNotification
                }.getOrNull()?.let { return it }
            }
            type = type.superclass
        }
        return null
    }

    private fun notificationFromSbn(sbn: Any): android.app.Notification? {
        (sbn as? StatusBarNotification)?.notification?.let { return it }
        runCatching {
            XposedHelpers.callMethod(sbn, "getNotification") as? android.app.Notification
        }.getOrNull()?.let { return it }
        return runCatching {
            XposedHelpers.getObjectField(sbn, "notification") as? android.app.Notification
        }.getOrNull()
    }

    private fun contentPendingIntent(sbn: Any?, row: Any): PendingIntent? {
        runCatching {
            val injector = XposedHelpers.callMethod(row, "getInjector")
            XposedHelpers.callMethod(injector, "getPendingIntent") as? PendingIntent
        }.getOrNull()?.let { return it }

        if (sbn == null) return null
        val notification = notificationFromSbn(sbn) ?: return null

        runCatching {
            XposedHelpers.getObjectField(notification, "contentIntent") as? PendingIntent
        }.getOrNull()?.let { return it }

        runCatching {
            XposedHelpers.getObjectField(notification, "fullScreenIntent") as? PendingIntent
        }.getOrNull()?.let { return it }

        return null
    }

    private fun pendingIntentToIntent(pi: PendingIntent?): Intent? {
        if (pi == null) return null
        runCatching {
            XposedHelpers.callMethod(pi, "getIntent") as? Intent
        }.getOrNull()?.let { return it }
        runCatching {
            val m = PendingIntent::class.java.getDeclaredMethod("getIntent")
            m.isAccessible = true
            m.invoke(pi) as? Intent
        }.getOrNull()?.let { return it }
        return null
    }

    private fun extractComponentFromExtras(sbn: Any?): ComponentName? {
        if (sbn == null) return null
        val notification = notificationFromSbn(sbn) ?: return null
        val extras = runCatching {
            XposedHelpers.getObjectField(notification, "extras") as? android.os.Bundle
        }.getOrNull() ?: return null

        runCatching {
            extras.getParcelable("android.intent.extra.INTENT", Intent::class.java)?.component
        }.getOrNull()?.let { return it }
        runCatching {
            @Suppress("DEPRECATION")
            (extras.getParcelable("android.intent.extra.INTENT") as? Intent)?.component
        }.getOrNull()?.let { return it }
        return null
    }

    fun extractPackage(row: Any): String? {
        packageFromSbn(extractSbn(row))?.let { return it }

        val entry = extractEntry(row)

        if (entry != null) {
            runCatching {
                val key = XposedHelpers.callMethod(entry, "getKey") as? String
                key?.split("|")?.getOrNull(1)
            }.getOrNull()?.let { if (it.contains('.')) return it }
        }
        return null
    }

    private fun packageFromSbn(sbn: Any?): String? {
        if (sbn == null) return null
        (sbn as? StatusBarNotification)?.packageName
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        runCatching { XposedHelpers.callMethod(sbn, "getPackageName") as? String }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        runCatching { XposedHelpers.getObjectField(sbn, "pkg") as? String }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        runCatching { XposedHelpers.callMethod(sbn, "getOpPkg") as? String }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it.contains('.') }
            ?.let { return it }
        return null
    }
}
