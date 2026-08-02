package io.hyper.freeform.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import io.hyper.freeform.xposed.policy.FreeformPolicy

/**
 * Secondary notification freeform entry (app-process listener).
 *
 * Primary Xiaomi-like path is SystemUI [io.hyper.freeform.xposed.hook.HookSystemUI]
 * (ExpandableNotificationRow swipe → hyper_freeform). This listener remains as a
 * helper for explicit openFromNotification calls / future actions.
 */
class NotificationFreeformService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Passive: do not auto-open on post. Swipe entry is handled in SystemUI.
    }

    fun openFromNotification(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (FreeformPolicy.isBlacklisted(pkg)) return
        try {
            // MULTIPLE_TASK on server: do not wrongly reuse existing same-package task.
            FreeformManagerClient.startPackage(pkg, mini = false)
            FreeformManagerClient.collapseStatusBar()
        } catch (t: Throwable) {
            Log.e("NotifFreeform", "open failed", t)
        }
    }
}
