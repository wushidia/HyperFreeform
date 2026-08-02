package io.hyper.freeform.xposed.hook

import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.hyper.freeform.xposed.utils.XLog

object HookMyself {
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "io.hyper.freeform.provider.ModuleStatusProvider",
                lpparam.classLoader
            )
            val replacement = object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any = true
            }
            XposedHelpers.findAndHookMethod(clazz, "isModuleActive", replacement)
            // Kotlin callers dispatch through ModuleStatusProvider.Companion even though the method
            // also has an @JvmStatic bridge. Hook both so Compose and Java/provider callers agree.
            val companion = XposedHelpers.findClass(
                "io.hyper.freeform.provider.ModuleStatusProvider\$Companion",
                lpparam.classLoader,
            )
            XposedHelpers.findAndHookMethod(companion, "isModuleActive", replacement)
            XLog.d("HookMyself: isModuleActive static+companion -> true")
        } catch (t: Throwable) {
            XLog.e("HookMyself failed", t)
        }
    }
}
