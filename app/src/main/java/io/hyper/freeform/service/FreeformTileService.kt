package io.hyper.freeform.service

import android.content.Intent
import android.service.quicksettings.TileService
import io.hyper.freeform.ui.AppPickerActivity

class FreeformTileService : TileService() {
    override fun onClick() {
        val intent = Intent(this, AppPickerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivityAndCollapse(intent)
    }
}
