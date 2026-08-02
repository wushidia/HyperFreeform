package io.hyper.freeform.xposed.shell

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.hyper.freeform.xposed.utils.XLog
import java.util.concurrent.atomic.AtomicReference

/**
 * Bridge to AOSP [SplitScreenController] living in **SystemUI process**.
 *
 * Xiaomi freeform→split is shell-side (MulWinSwitch → SoSc moveToStage).
 * system_server cannot hold the controller instance, so:
 *  - SystemUI hooks attach the controller here
 *  - system_server writes [SETTINGS_SPLIT_REQUEST]
 *  - SystemUI observer executes moveToStage
 */
object SplitScreenBridge {
    const val SETTINGS_SPLIT_REQUEST = "hyper_freeform_split_request"

    private val controller = AtomicReference<Any?>(null)
    @Volatile private var observerRegistered = false
    private var lastHandledToken: String? = null

    fun attach(splitScreenController: Any?) {
        if (splitScreenController == null) return
        if (controller.getAndSet(splitScreenController) === splitScreenController) return
        XLog.i("SplitScreenBridge attached ${splitScreenController.javaClass.name}")
    }

    fun isReady(): Boolean = controller.get() != null

    /** system_server → SystemUI request. value = "taskId:position:token" */
    fun requestFromServer(context: Context, taskId: Int, position: Int): Boolean {
        return runCatching {
            val token = System.currentTimeMillis().toString()
            val value = "$taskId:$position:$token"
            Settings.Global.putString(context.contentResolver, SETTINGS_SPLIT_REQUEST, value)
            XLog.i("SplitScreenBridge requestFromServer $value")
            true
        }.onFailure {
            XLog.e("SplitScreenBridge requestFromServer failed", it)
        }.getOrDefault(false)
    }

    fun registerObserver(context: Context) {
        if (observerRegistered) return
        val handler = Handler(Looper.getMainLooper())
        val uri = Settings.Global.getUriFor(SETTINGS_SPLIT_REQUEST)
        val appCtx = context.applicationContext ?: context
        runCatching {
            appCtx.contentResolver.registerContentObserver(
                uri,
                true,
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        handleRequest(appCtx)
                    }

                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        handleRequest(appCtx)
                    }
                }
            )
            observerRegistered = true
            // Catch any pending request + light poll (Settings IPC can race attach).
            handler.post { handleRequest(appCtx) }
            handler.postDelayed({ handleRequest(appCtx) }, 500L)
            handler.postDelayed({ handleRequest(appCtx) }, 1500L)
            XLog.i("SplitScreenBridge observer registered")
        }.onFailure {
            observerRegistered = false
            XLog.e("SplitScreenBridge observer register failed", it)
        }
    }

    private fun handleRequest(context: Context) {
        val raw = runCatching {
            Settings.Global.getString(context.contentResolver, SETTINGS_SPLIT_REQUEST)
        }.getOrNull() ?: return
        if (raw.isBlank()) return
        val parts = raw.split(":")
        if (parts.size < 2) return
        val taskId = parts[0].toIntOrNull() ?: return
        val position = parts[1].toIntOrNull() ?: 0
        val token = parts.getOrNull(2) ?: raw
        if (token == lastHandledToken) return
        if (taskId <= 0) return
        // Mark handled only after we attempt, to avoid dropping when controller not ready yet.
        val ready = isReady()
        if (!ready) {
            XLog.d("SplitScreenBridge handleRequest defer (controller not ready) raw=$raw")
            return
        }
        lastHandledToken = token
        val ok = moveToStage(taskId, position)
        XLog.i("SplitScreenBridge handleRequest task=$taskId pos=$position ok=$ok raw=$raw")
        // Clear request so it is not re-fired after reboot with stale token.
        runCatching {
            Settings.Global.putString(context.contentResolver, SETTINGS_SPLIT_REQUEST, "")
        }
    }

    /**
     * @param position 0=top/left, 1=bottom/right (AOSP SideStagePosition)
     */
    fun moveToStage(taskId: Int, position: Int): Boolean {
        val c = controller.get() ?: run {
            XLog.e("SplitScreenBridge.moveToStage: controller not ready")
            return false
        }
        return runCatching {
            val methods = c.javaClass.methods.filter { it.name == "moveToStage" }
            // Prefer AOSP: moveToStage(int taskId, int stagePosition, WindowContainerTransaction)
            val m = methods.firstOrNull { m ->
                val p = m.parameterTypes
                p.size == 3 && p[0] == Integer.TYPE && p[1] == Integer.TYPE
            } ?: methods.firstOrNull { m ->
                val p = m.parameterTypes
                p.size >= 2 && p[0] == Integer.TYPE && p[1] == Integer.TYPE
            } ?: run {
                XLog.e(
                    "SplitScreenBridge: moveToStage not found on ${c.javaClass.name} " +
                        methods.joinToString { it.parameterTypes.joinToString(prefix="(", postfix=")") { t -> t.simpleName } }
                )
                return false
            }
            val args = arrayOfNulls<Any>(m.parameterTypes.size)
            args[0] = taskId
            args[1] = position
            for (i in 2 until m.parameterTypes.size) {
                val pt = m.parameterTypes[i]
                args[i] = when {
                    pt == Integer.TYPE -> 0
                    pt == java.lang.Boolean.TYPE -> false
                    else -> runCatching { pt.getDeclaredConstructor().newInstance() }.getOrNull()
                }
            }
            m.invoke(c, *args)
            XLog.i(
                "SplitScreenBridge.moveToStage task=$taskId pos=$position ok " +
                    "sig=${m.parameterTypes.joinToString(prefix="(", postfix=")") { it.simpleName }}"
            )
            true
        }.onFailure {
            XLog.e("SplitScreenBridge.moveToStage failed task=$taskId", it)
        }.getOrDefault(false)
    }
}
