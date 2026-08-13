package io.hyper.freeform.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val InstallerStatusActive = Color(0xFF36D167)
private val InstallerStatusInactive = Color(0xFFD13636)

/** 状态语义色集中定义，避免屏幕内散落色值或再次读取系统深色状态。 */
object StatusColors {
    @Composable
    @ReadOnlyComposable
    fun runStateContainer(running: Boolean): Color = if (running) {
        when {
            MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
            LocalAppDarkMode.current -> Color(0xFF1A3825)
            else -> Color(0xFFDFFAE4)
        }
    } else {
        when {
            MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.errorContainer
            LocalAppDarkMode.current -> Color(0xFF381A1A)
            else -> Color(0xFFFAEEEE)
        }
    }

    @Composable
    @ReadOnlyComposable
    fun runStateContent(running: Boolean): Color = when {
        running && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.onSecondaryContainer
        !running && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.onErrorContainer
        else -> MiuixTheme.colorScheme.onSurface
    }

    @Composable
    @ReadOnlyComposable
    fun runStateIcon(running: Boolean): Color = when {
        running && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
        !running && MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.error.copy(alpha = 0.8f)
        running -> InstallerStatusActive
        else -> InstallerStatusInactive
    }
}
