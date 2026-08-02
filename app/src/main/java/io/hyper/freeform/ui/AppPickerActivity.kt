package io.hyper.freeform.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.hyper.freeform.ui.screen.AppPickerScreen
import io.hyper.freeform.ui.theme.HyperFreeformTheme

class AppPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HyperFreeformTheme {
                AppPickerScreen(onBack = { finish() })
            }
        }
    }
}
