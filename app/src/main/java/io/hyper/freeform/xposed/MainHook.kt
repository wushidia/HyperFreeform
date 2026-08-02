package io.hyper.freeform.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.hyper.freeform.xposed.hook.HookFramework
import io.hyper.freeform.xposed.hook.HookLauncher
import io.hyper.freeform.xposed.hook.HookMyself
import io.hyper.freeform.xposed.hook.HookSystem
import io.hyper.freeform.xposed.hook.HookSystemUI
import io.hyper.freeform.xposed.utils.XLog

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "android" -> {
                XLog.d("Init system_server hooks")
                HookFramework.init(lpparam)
                HookSystem.init(lpparam)
            }
            "com.android.systemui" -> {
                XLog.d("Init SystemUI hooks")
                HookSystemUI.init(lpparam)
            }
            "io.hyper.freeform" -> {
                HookMyself.init(lpparam)
            }
            // Lawnchair / AOSP Quickstep foreground app swipe-up entry.
            "app.lawnchair", "com.android.launcher3" -> {
                XLog.d("Init launcher/recents hooks for ${lpparam.packageName}")
                HookLauncher.init(lpparam)
            }
        }
    }
}
