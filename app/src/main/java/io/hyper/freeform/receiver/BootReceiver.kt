package io.hyper.freeform.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.hyper.freeform.service.FreeformForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            FreeformForegroundService.start(context)
        }
    }
}
