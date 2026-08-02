package io.hyper.freeform

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import io.hyper.freeform.service.FreeformManagerClient
import org.lsposed.hiddenapibypass.HiddenApiBypass

class FreeformApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FreeformManagerClient.initialize(this)
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                "freeform_fg",
                getString(R.string.fg_service_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}
