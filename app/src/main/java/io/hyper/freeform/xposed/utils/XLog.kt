package io.hyper.freeform.xposed.utils

import android.util.Log
import de.robv.android.xposed.XposedBridge

object XLog {
    private const val TAG = "HyperFreeform"

    fun d(msg: String) {
        Log.d(TAG, msg)
        runCatching { XposedBridge.log("$TAG: $msg") }
    }

    fun i(msg: String) {
        Log.i(TAG, msg)
        runCatching { XposedBridge.log("$TAG: $msg") }
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        runCatching {
            XposedBridge.log("$TAG: $msg")
            if (t != null) XposedBridge.log(t)
        }
    }
}
