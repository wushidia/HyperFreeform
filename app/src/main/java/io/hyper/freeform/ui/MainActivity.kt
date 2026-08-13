package io.hyper.freeform.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import io.hyper.freeform.data.Prefs
import io.hyper.freeform.service.FreeformForegroundService
import io.hyper.freeform.ui.screen.AboutPage
import io.hyper.freeform.ui.screen.HomeScreen
import io.hyper.freeform.ui.screen.SidebarAppsScreen
import io.hyper.freeform.ui.theme.HyperFreeformTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FreeformForegroundService.start(this)
        val prefs = Prefs(this)
        setContent {
            NavigationEventDispatcherProvider {
                HyperFreeformTheme {
                    MainNavContent(
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

@Composable
private fun MainNavContent(
    prefs: Prefs,
    onOpenSidebarApps: () -> Unit,
) {
    var showAbout by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showAbout) { showAbout = false }

    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val uriHandler = LocalUriHandler.current
    val transition = updateTransition(targetState = showAbout, label = "aboutNavigation")
    val dimProgress by transition.animateFloat(
        transitionSpec = {
            tween(durationMillis = 500, easing = MiuixNavEasing)
        },
        label = "aboutNavigationDim",
    ) { if (it) 1f else 0f }

    AnimatedContent(
        targetState = showAbout,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { miuixAboutTransition(targetState, direction) },
        label = "aboutNavigation",
    ) { isAbout ->
        if (isAbout) {
            AboutPage(
                onBack = { showAbout = false },
                onOpenUrl = uriHandler::openUri,
            )
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                HomeScreen(
                    prefs = prefs,
                    onOpenSidebarApps = onOpenSidebarApps,
                    onOpenAbout = { showAbout = true },
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.5f * dimProgress }
                        .background(Color.Black),
                )
            }
        }
    }
}

private fun AnimatedContentTransitionScope<Boolean>.miuixAboutTransition(
    targetState: Boolean,
    direction: Int,
): ContentTransform {
    val offsetSpec = tween<IntOffset>(
        durationMillis = 500,
        easing = MiuixNavEasing,
    )
    val alphaSpec = tween<Float>(
        durationMillis = 500,
        easing = MiuixNavEasing,
    )

    return if (targetState) {
        val coveredExit = slideOutHorizontally(animationSpec = offsetSpec) { fullWidth ->
            -direction * fullWidth / 4
        } + fadeOut(animationSpec = alphaSpec, targetAlpha = 0.9f)

        slideInHorizontally(animationSpec = offsetSpec) { fullWidth ->
            direction * fullWidth
        }.togetherWith(coveredExit).apply {
            targetContentZIndex = 1f
        }
    } else {
        val coveredEnter = slideInHorizontally(animationSpec = offsetSpec) { fullWidth ->
            -direction * fullWidth / 4
        } + fadeIn(animationSpec = alphaSpec, initialAlpha = 0.9f)

        coveredEnter.togetherWith(
            slideOutHorizontally(animationSpec = offsetSpec) { fullWidth ->
                direction * fullWidth
            },
        ).apply {
            targetContentZIndex = -1f
        }
    }
}

/** Miuix NavTransitions.MiuixDefault 的 500ms programmatic 曲线。 */
private val MiuixNavEasing: Easing = NavSettleEasing(response = 0.8f, damping = 0.95f)

private class NavSettleEasing(
    response: Float,
    damping: Float,
) : Easing {
    private val r: Float
    private val w: Float
    private val c2: Float

    init {
        val omega = 2.0 * PI / response
        val k = omega * omega
        val c = damping * 4.0 * PI / response

        w = (sqrt(4.0 * k - c * c) / 2.0).toFloat()
        r = (-c / 2.0).toFloat()
        c2 = r / w
    }

    override fun transform(fraction: Float): Float {
        val t = fraction.toDouble()
        val decay = exp(r * t)
        return (decay * (-cos(w * t) + c2 * sin(w * t)) + 1.0).toFloat()
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
