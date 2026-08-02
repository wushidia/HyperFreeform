package io.hyper.freeform.xposed.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.hyper.freeform.xposed.utils.XLog

/**
 * Framework-level freeform enablement hooks (AOSP freeform path).
 */
object HookFramework {
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        forceSupportsFreeform(lpparam)
        forceResizable(lpparam)
    }

    private fun forceSupportsFreeform(lpparam: XC_LoadPackage.LoadPackageParam) {
        var installed = false
        runCatching {
            val wms = XposedHelpers.findClass(
                "com.android.server.wm.WindowManagerService",
                lpparam.classLoader
            )
            val field = XposedHelpers.findField(wms, "mSupportsFreeformWindowManagement")
            val constructorHooks = XposedBridge.hookAllConstructors(
                wms,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { field.setBoolean(param.thisObject, true) }
                    }
                }
            )
            installed = constructorHooks.isNotEmpty()

            runCatching {
                val featureHooks = XposedBridge.hookAllMethods(
                    wms,
                    "hasSystemFeature",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val feature = param.args.getOrNull(0) as? String ?: return
                            if (feature.contains("freeform", ignoreCase = true)) {
                                param.result = true
                            }
                        }
                    },
                )
                installed = featureHooks.isNotEmpty() || installed
            }
        }.onFailure { XLog.e("WMS freeform support hook failed", it) }

        runCatching {
            val atms = XposedHelpers.findClass(
                "com.android.server.wm.ActivityTaskManagerService",
                lpparam.classLoader
            )
            val field = XposedHelpers.findField(atms, "mSupportsFreeformWindowManagement")
            val hooks = XposedBridge.hookAllConstructors(
                atms,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        runCatching { field.setBoolean(param.thisObject, true) }
                    }
                },
            )
            installed = hooks.isNotEmpty() || installed
        }.onFailure { XLog.e("ATMS freeform support hook failed", it) }

        if (installed) {
            XLog.i("Framework freeform support hooks installed")
        } else {
            XLog.e("Framework freeform support hooks unavailable")
        }
    }

    private fun forceResizable(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val clazz = XposedHelpers.findClass(
                "com.android.server.wm.ActivityRecord",
                lpparam.classLoader,
            )
            var hooked = 0
            for (methodName in listOf("isResizeable", "supportsFreeform")) {
                hooked += XposedBridge.hookAllMethods(
                    clazz,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    },
                ).size
            }
            check(hooked > 0) { "no ActivityRecord resize hook points" }
            XLog.i("Framework activity resize hooks installed x$hooked")
        }.onFailure { XLog.e("Framework activity resize hooks failed", it) }
    }
}
