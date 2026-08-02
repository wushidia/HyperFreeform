package io.hyper.freeform.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.hyper.freeform.R
import io.hyper.freeform.service.FreeformManagerClient
import io.hyper.freeform.ui.SidebarAppsActivity

class StartFreeformReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val i = intent ?: return
        when (i.action) {
            ACTION_OPEN_SIDEBAR_APPS -> {
                context.startActivity(
                    Intent(context, SidebarAppsActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                )
                return
            }
            ACTION_POST_DEMO_NOTIFICATION -> {
                postDemoNotification(context, i)
                return
            }
            else -> {
                val pkg = i.getStringExtra("package")
                    ?: i.getStringExtra("pkg")
                    ?: return
                val mini = i.getBooleanExtra("mini", false)
                FreeformManagerClient.startPackage(pkg, mini)
            }
        }
    }

    /**
     * Post a swipeable notification whose contentIntent targets a real app
     * (Xiaomi-like notification freeform E2E / demo).
     *
     * adb shell am broadcast -a io.hyper.freeform.POST_DEMO_NOTIFICATION \
     *   --es package <any.launchable.package>
     */
    private fun postDemoNotification(context: Context, intent: Intent) {
        // Resolve target dynamically — never hardcode a package name.
        val requestedPkg = intent.getStringExtra("package")
            ?: intent.getStringExtra("pkg")
            ?: firstLauncherPackage(context)
            ?: return
        val title = intent.getStringExtra("title") ?: "自由窗口演示"
        val text = intent.getStringExtra("text") ?: "向下滑动此通知以小窗打开"
        val notificationId = intent.getIntExtra("notification_id", DEMO_NOTIFICATION_ID)

        val requestedAction = intent.getStringExtra("intent_action")?.takeIf { it.isNotBlank() }
        val requestedComponent = ComponentName.unflattenFromString(
            intent.getStringExtra("intent_component").orEmpty(),
        )
        val launch = if (requestedAction != null || requestedComponent != null) {
            Intent(requestedAction ?: Intent.ACTION_MAIN).apply {
                component = requestedComponent
                intent.getStringExtra("intent_data")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { data = Uri.parse(it) }
                intent.getStringExtra("intent_extra_key")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { key -> putExtra(key, intent.getStringExtra("intent_extra_value")) }
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(requestedPkg) ?: return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolvedComponent = launch.component
            ?: launch.resolveActivity(context.packageManager)
        val targetPkg = resolvedComponent?.packageName
            ?: launch.`package`
            ?: requestedPkg

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = intent.getStringExtra("channel_id")
            ?.takeIf { it.isNotBlank() }
            ?: DEMO_CHANNEL_ID
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "自由窗口演示",
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }

        val extras = android.os.Bundle().apply {
            // Fallback for SystemUI when PendingIntent.getIntent is restricted.
            putString("hyper_freeform_target_pkg", targetPkg)
            putBoolean(
                "hyper_freeform_keep_heads_up",
                intent.getBooleanExtra("keep_heads_up", true),
            )
            putString("android.substName", "自由窗口")
        }
        val n = Notification.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(intent.getBooleanExtra("auto_cancel", true))
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .addExtras(extras)
            .build()
        nm.notify(
            notificationId,
            n,
        )
    }


    /** First launchable app package from PackageManager (no hardcoded names). */
    private fun firstLauncherPackage(context: Context): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { it.activityInfo?.packageName }
            .firstOrNull { it != context.packageName }
    }

    companion object {
        const val ACTION_OPEN_SIDEBAR_APPS = "io.hyper.freeform.OPEN_SIDEBAR_APPS"
        const val ACTION_POST_DEMO_NOTIFICATION = "io.hyper.freeform.POST_DEMO_NOTIFICATION"
        const val DEMO_NOTIFICATION_ID = 0x4846 // HF
        private const val DEMO_CHANNEL_ID = "hyper_freeform_demo"
    }
}
