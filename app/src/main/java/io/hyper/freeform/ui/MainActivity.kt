package io.hyper.freeform.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import io.hyper.freeform.data.Prefs
import io.hyper.freeform.service.FreeformForegroundService
import io.hyper.freeform.ui.screen.HomeScreen
import io.hyper.freeform.ui.screen.SidebarAppsScreen
import io.hyper.freeform.ui.theme.HyperFreeformTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FreeformForegroundService.start(this)
        val prefs = Prefs(this)
        setContent {
            NavigationEventDispatcherProvider {
                HyperFreeformTheme {
                    HomeScreen(
                        prefs = prefs,
                        onOpenSidebarApps = {
                            startActivity(
                                android.content.Intent(this, SidebarAppsActivity::class.java)
                            )
                        },
                    )
                }
            }
        }
    }
}

/** Simple host so the sidebar app-picker is a separate back-stack entry. */
class SidebarAppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        setContent {
            NavigationEventDispatcherProvider {
                HyperFreeformTheme {
                    SidebarAppsScreen(prefs = prefs, onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun NavigationEventDispatcherProvider(content: @Composable () -> Unit) {
    val owner = rememberNavigationEventDispatcherOwner(parent = null)
    CompositionLocalProvider(
        LocalNavigationEventDispatcherOwner provides owner,
        content = content,
    )
}
