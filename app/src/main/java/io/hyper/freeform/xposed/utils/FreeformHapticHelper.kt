package io.hyper.freeform.xposed.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Xiaomi MiuiFreeformModeVibrateHelper port (lite).
 *
 * Real HyperOS prefers HapticFeedbackUtil / linear motor; on MuMu and
 * non-MIUI builds fall back to a short light pulse (100ms), matching
 * Xiaomi's non-linear path: vibrator.vibrate(100).
 */
object FreeformHapticHelper {
    private const val VIBRATE_LIGHT_MS = 100L

    /** One light pulse — mini threshold / bottom close-fullscreen / pin enter. */
    fun hapticLight(context: Context = SystemServices.systemContext) {
        runCatching {
            val vibrator = resolveVibrator(context) ?: return@runCatching
            if (!vibrator.hasVibrator()) return@runCatching
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        VIBRATE_LIGHT_MS,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(VIBRATE_LIGHT_MS)
            }
        }.onFailure { XLog.d("hapticLight failed: ${it.message}") }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(VibratorManager::class.java)
            mgr?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
