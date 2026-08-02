package io.hyper.freeform.ui.screen

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.hyper.freeform.BuildConfig
import io.hyper.freeform.R
import io.hyper.freeform.data.Prefs
import io.hyper.freeform.provider.ModuleStatusProvider
import io.hyper.freeform.service.FreeformBridge
import io.hyper.freeform.service.FreeformManagerClient
import io.hyper.freeform.service.NotificationFreeformService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomeScreen(prefs: Prefs, onOpenSidebarApps: () -> Unit = {}) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val enabled by prefs.enabled.collectAsState(initial = true)
    val notificationFreeform by prefs.notificationFreeform.collectAsState(initial = true)
    val recentsFreeform by prefs.recentsFreeform.collectAsState(initial = true)
    // null means SharedPreferences has not emitted yet.  Do not publish the UI placeholder to
    // system_server: doing so briefly applies the default before the persisted value on cold start.
    val persistedDpiPercent by prefs.dpiPercent.collectAsState(initial = null)
    val dpiPercent = persistedDpiPercent ?: FreeformBridge.DEFAULT_DPI_PERCENT
    val persistedWindowWidthPercent by prefs.windowWidthPercent.collectAsState(initial = null)
    val persistedWindowHeightPercent by prefs.windowHeightPercent.collectAsState(initial = null)
    val windowWidthPercent = persistedWindowWidthPercent
        ?: FreeformBridge.DEFAULT_WINDOW_WIDTH_PERCENT
    val windowHeightPercent = persistedWindowHeightPercent
        ?: FreeformBridge.DEFAULT_WINDOW_HEIGHT_PERCENT
    val sidebarSide by prefs.sidebarSide.collectAsState(initial = 1)
    val sidebarApps by prefs.sidebarApps.collectAsState(initial = emptySet())
    val persistedSidebarShowAppNames by
        prefs.sidebarShowAppNames.collectAsState(initial = null)
    val sidebarShowAppNames = persistedSidebarShowAppNames ?: false

    val initiallyReady = FreeformManagerClient.isReady()
    var moduleActive by remember {
        mutableStateOf(ModuleStatusProvider.isModuleActive() || initiallyReady)
    }
    var serviceReady by remember { mutableStateOf(initiallyReady) }
    var openCount by remember { mutableIntStateOf(0) }
    var notificationAccess by remember { mutableStateOf(false) }
    var notificationGranted by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    BackHandler(enabled = showAbout) {
        showAbout = false
    }
    // Keep the selected page alive while About temporarily replaces the pager. Otherwise the
    // pager is recreated at page 0 and About's back button unexpectedly returns to Home.
    val pagerState = rememberPagerState(pageCount = { 2 })
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationGranted = granted }

    LaunchedEffect(sidebarSide) {
        while (!FreeformManagerClient.setSidebarSide(sidebarSide.coerceIn(0, 1))) delay(1000)
    }
    LaunchedEffect(sidebarApps) {
        val csv = sidebarApps.sorted().joinToString(",")
        while (!FreeformManagerClient.setSidebarApps(csv)) delay(1000)
    }
    LaunchedEffect(persistedSidebarShowAppNames) {
        val show = persistedSidebarShowAppNames ?: return@LaunchedEffect
        while (!FreeformManagerClient.setSidebarShowAppNames(show)) delay(1000)
    }
    LaunchedEffect(persistedDpiPercent) {
        // Preferences are available before LSPosed has necessarily published the system service.
        // Keep retrying so the displayed value and the live freeform density cannot drift apart.
        val percent = persistedDpiPercent
            ?.let(FreeformBridge::sanitizeDpiPercent)
            ?: return@LaunchedEffect
        // Gesture callbacks already use the direct fast path. A Binder call and especially the
        // compatibility broadcast only confirm that the command was submitted, not that
        // system_server has committed it. Keep reading the authoritative value until it matches.
        // This effect is keyed by the preference, so a newer slider value cancels an older retry
        // and stale gesture events can never win after the user's finger stops.
        delay(80)
        while (FreeformManagerClient.getGlobalDpiPercent() != percent) {
            val submitted = FreeformManagerClient.setGlobalDpiPercent(percent)
            delay(if (submitted) 80 else 1000)
        }
    }
    LaunchedEffect(persistedWindowWidthPercent, persistedWindowHeightPercent) {
        val width = persistedWindowWidthPercent ?: return@LaunchedEffect
        val height = persistedWindowHeightPercent ?: return@LaunchedEffect
        delay(80)
        if (FreeformManagerClient.getGlobalWindowWidthPercent() == width &&
            FreeformManagerClient.getGlobalWindowHeightPercent() == height
        ) return@LaunchedEffect
        while (!FreeformManagerClient.setGlobalWindowSizePercent(width, height)) delay(1000)
    }
    LaunchedEffect(Unit) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val listener = ComponentName(context, NotificationFreeformService::class.java)
        while (true) {
            val readyNow = FreeformManagerClient.isReady()
            serviceReady = readyNow
            // A current-boot system_server marker is stronger evidence than the self-process hook:
            // it can only be written after this module initialized its system hooks successfully.
            moduleActive = ModuleStatusProvider.isModuleActive() || readyNow
            openCount = FreeformManagerClient.openCount()
            notificationAccess = notificationManager.isNotificationListenerAccessGranted(listener)
            notificationGranted = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            delay(1200)
        }
    }

    if (showAbout) {
        AboutPage(
            onBack = { showAbout = false },
            onOpenUrl = uriHandler::openUri,
        )
        return
    }

    val selectedTab = pagerState.currentPage
    val scrollBehavior = MiuixScrollBehavior()
    val navigationItems = listOf(
        NavigationItem(stringResource(R.string.nav_home), MiuixIcons.Home),
        NavigationItem(stringResource(R.string.nav_settings), MiuixIcons.Settings),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(
                    if (selectedTab == 0) R.string.home_title else R.string.settings_title
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            NavigationBar {
                navigationItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        icon = item.icon,
                        label = item.label,
                    )
                }
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { page ->
            if (page == 0) {
                StatusPage(
                    moduleActive = moduleActive,
                    serviceReady = serviceReady,
                    openCount = openCount,
                    notificationAccess = notificationAccess,
                    notificationGranted = notificationGranted,
                    onRequestNotificationAccess = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= 33) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            } else {
                SettingsPage(
                    enabled = enabled,
                    notificationFreeform = notificationFreeform,
                    recentsFreeform = recentsFreeform,
                    dpiPercent = dpiPercent,
                    windowWidthPercent = windowWidthPercent,
                    windowHeightPercent = windowHeightPercent,
                    sidebarSide = sidebarSide,
                    sidebarShowAppNames = sidebarShowAppNames,
                    onEnabledChange = { checked ->
                        scope.launch {
                            prefs.setEnabled(checked)
                            FreeformManagerClient.setEnabled(checked)
                        }
                    },
                    onNotificationChange = { checked ->
                        scope.launch {
                            prefs.setNotificationFreeform(checked)
                            runCatching {
                                Settings.Global.putInt(
                                    context.contentResolver,
                                    "hyper_freeform_notification",
                                    if (checked) 1 else 0,
                                )
                            }
                        }
                    },
                    onRecentsChange = { checked ->
                        scope.launch {
                            prefs.setRecentsFreeform(checked)
                            runCatching {
                                Settings.Global.putInt(
                                    context.contentResolver,
                                    "hyper_freeform_recents",
                                    if (checked) 1 else 0,
                                )
                            }
                        }
                    },
                    onDpiChange = { value ->
                        val clamped = FreeformBridge.sanitizeDpiPercent(value)
                        // Submit immediately and in callback order. Persisting below drives the
                        // acknowledgement/retry effect, but must not delay the live update.
                        FreeformManagerClient.setGlobalDpiPercent(clamped)
                        scope.launch {
                            prefs.setDpiPercent(clamped)
                        }
                    },
                    onWindowWidthChange = { value ->
                        scope.launch {
                            val clamped = FreeformBridge.sanitizeWindowSizePercent(value)
                            prefs.setWindowWidthPercent(clamped)
                            FreeformManagerClient.setGlobalWindowSizePercent(
                                clamped,
                                windowHeightPercent,
                            )
                        }
                    },
                    onWindowHeightChange = { value ->
                        scope.launch {
                            val clamped = FreeformBridge.sanitizeWindowSizePercent(value)
                            prefs.setWindowHeightPercent(clamped)
                            FreeformManagerClient.setGlobalWindowSizePercent(
                                windowWidthPercent,
                                clamped,
                            )
                        }
                    },
                    onSidebarSideChange = { side ->
                        scope.launch {
                            prefs.setSidebarSide(side)
                            FreeformManagerClient.setSidebarSide(side)
                        }
                    },
                    onSidebarShowAppNamesChange = { show ->
                        scope.launch {
                            prefs.setSidebarShowAppNames(show)
                        }
                    },
                    onOpenSidebarApps = onOpenSidebarApps,
                    onOpenAbout = { showAbout = true },
                    scrollBehavior = scrollBehavior,
                )
            }
        }
    }
}

@Composable
private fun StatusPage(
    moduleActive: Boolean,
    serviceReady: Boolean,
    openCount: Int,
    notificationAccess: Boolean,
    notificationGranted: Boolean,
    onRequestNotificationAccess: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
) {
    val isRunning = moduleActive && serviceReady
    val statusTint = statusColor(isRunning)
    val statusContainer = statusContainerColor(isRunning)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(key = "status") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(156.dp),
                    colors = CardDefaults.defaultColors(color = statusContainer),
                    onClick = {},
                    pressFeedbackType = PressFeedbackType.Tilt,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .padding(end = 10.dp, bottom = 8.dp),
                            contentAlignment = androidx.compose.ui.Alignment.BottomEnd,
                        ) {
                            Icon(
                                modifier = Modifier.size(if (isRunning) 124.dp else 112.dp),
                                imageVector = if (isRunning) {
                                    MiuixIcons.Ok
                                } else {
                                    MiuixIcons.Close
                                },
                                tint = statusTint.copy(alpha = 0.86f),
                                contentDescription = null,
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (isRunning) R.string.home_running else R.string.home_stopped
                                ),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(
                                    if (isRunning) R.string.running_normally else R.string.needs_attention,
                                ),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Spacer(Modifier.height(36.dp))
                            Text(
                                text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusSummaryCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.home_module),
                        value = stringResource(
                            if (moduleActive) R.string.home_active else R.string.home_inactive
                        ),
                        ok = moduleActive,
                    )
                    StatusSummaryCard(
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.home_system_service),
                        value = stringResource(
                            if (serviceReady) R.string.home_ready else R.string.home_not_ready
                        ),
                        ok = serviceReady,
                    )
                }
            }
        }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.current_windows),
                    summary = stringResource(R.string.open_count_fmt, openCount),
                )
                BasicComponent(
                    title = stringResource(R.string.module_version),
                    summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                StatusRow(
                    title = stringResource(R.string.notification_listener_permission),
                    summary = stringResource(
                        if (notificationAccess) R.string.permission_granted
                        else R.string.permission_tap_to_grant,
                    ),
                    ok = notificationAccess,
                    onClick = onRequestNotificationAccess,
                )
                StatusRow(
                    title = stringResource(R.string.notification_permission),
                    summary = stringResource(
                        if (notificationGranted) R.string.permission_granted
                        else R.string.permission_tap_to_grant,
                    ),
                    ok = notificationGranted,
                    onClick = onRequestNotificationPermission,
                )
            }
        }
    }
}

@Composable
private fun SettingsPage(
    enabled: Boolean,
    notificationFreeform: Boolean,
    recentsFreeform: Boolean,
    dpiPercent: Int,
    windowWidthPercent: Int,
    windowHeightPercent: Int,
    sidebarSide: Int,
    sidebarShowAppNames: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onNotificationChange: (Boolean) -> Unit,
    onRecentsChange: (Boolean) -> Unit,
    onDpiChange: (Int) -> Unit,
    onWindowWidthChange: (Int) -> Unit,
    onWindowHeightChange: (Int) -> Unit,
    onSidebarSideChange: (Int) -> Unit,
    onSidebarShowAppNamesChange: (Boolean) -> Unit,
    onOpenSidebarApps: () -> Unit,
    onOpenAbout: () -> Unit,
    scrollBehavior: top.yukonga.miuix.kmp.basic.ScrollBehavior,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SmallTitle(text = stringResource(R.string.freeform_settings_section))
            CardSection {
                SwitchRow(R.string.enable_freeform, R.string.enable_freeform_summary, enabled, onEnabledChange)
                SwitchRow(
                    R.string.notification_freeform,
                    R.string.notification_freeform_summary,
                    notificationFreeform,
                    onNotificationChange,
                )
                SwitchRow(
                    R.string.recents_freeform,
                    R.string.recents_freeform_summary,
                    recentsFreeform,
                    onRecentsChange,
                )
                DpiSliderRow(dpiPercent = dpiPercent, onDpiChange = onDpiChange)
                PercentSliderRow(
                    title = stringResource(R.string.freeform_window_width),
                    summary = stringResource(R.string.freeform_window_width_summary),
                    percent = windowWidthPercent,
                    onPercentChange = onWindowWidthChange,
                )
                PercentSliderRow(
                    title = stringResource(R.string.freeform_window_height),
                    summary = stringResource(R.string.freeform_window_height_summary),
                    percent = windowHeightPercent,
                    onPercentChange = onWindowHeightChange,
                )
            }
        }
        item {
            SmallTitle(text = stringResource(R.string.sidebar_title))
            CardSection {
                SidebarPositionRow(
                    sidebarSide = sidebarSide,
                    onSidebarSideChange = onSidebarSideChange,
                )
                SwitchRow(
                    R.string.sidebar_show_app_names,
                    R.string.sidebar_show_app_names_summary,
                    sidebarShowAppNames,
                    onSidebarShowAppNamesChange,
                )
                LinkRow(
                    title = stringResource(R.string.sidebar_apps_entry),
                    summary = stringResource(R.string.sidebar_apps_hint),
                    onClick = onOpenSidebarApps,
                )
            }
        }
        item {
            SmallTitle(text = stringResource(R.string.about_title))
            CardSection {
                LinkRow(
                    title = stringResource(R.string.about_title),
                    summary = stringResource(R.string.about_entry_summary),
                    onClick = onOpenAbout,
                )
            }
        }
    }
}

@Composable
private fun CardSection(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun StatusRow(
    title: String,
    summary: String,
    ok: Boolean,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        onClick = if (ok) null else onClick,
    )
}

@Composable
private fun StatusSummaryCard(
    modifier: Modifier,
    label: String,
    value: String,
    ok: Boolean,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        onClick = {},
        pressFeedbackType = PressFeedbackType.Sink,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = if (ok) statusColor(true) else MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun statusColor(running: Boolean): Color = if (running) {
    if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF81C784) else Color(0xFF4CAF50)
} else {
    if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFEF9A9A) else Color(0xFFE53935)
}

@Composable
private fun statusContainerColor(running: Boolean): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return if (running) {
        if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
    } else {
        if (dark) Color(0xFF3A2020) else Color(0xFFFDE8E8)
    }
}

@Composable
private fun SwitchRow(
    titleRes: Int,
    summaryRes: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    BasicComponent(
        title = stringResource(titleRes),
        summary = stringResource(summaryRes),
        endActions = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@Composable
private fun SidebarPositionRow(
    sidebarSide: Int,
    onSidebarSideChange: (Int) -> Unit,
) {
    WindowDropdownPreference(
        title = stringResource(R.string.sidebar_side),
        summary = stringResource(
            if (sidebarSide == 0) R.string.sidebar_side_summary_left
            else R.string.sidebar_side_summary_right,
        ),
        items = listOf(
            stringResource(R.string.sidebar_side_left),
            stringResource(R.string.sidebar_side_right),
        ),
        selectedIndex = sidebarSide.coerceIn(0, 1),
        onSelectedIndexChange = { onSidebarSideChange(it.coerceIn(0, 1)) },
    )
}

@Composable
private fun DpiSliderRow(
    dpiPercent: Int,
    onDpiChange: (Int) -> Unit,
) {
    PercentSliderRow(
        title = stringResource(R.string.freeform_dpi),
        summary = stringResource(R.string.freeform_dpi_summary),
        percent = dpiPercent,
        onPercentChange = onDpiChange,
    )
}

@Composable
private fun PercentSliderRow(
    title: String,
    summary: String,
    percent: Int,
    onPercentChange: (Int) -> Unit,
) {
    var sliderValue by remember(percent) {
        mutableFloatStateOf(percent.coerceIn(0, 100).toFloat())
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.main,
                )
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            }
            Text(
                text = "${sliderValue.roundToInt()}%",
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { value ->
                val level = value.roundToInt().coerceIn(0, 100)
                if (level == sliderValue.roundToInt()) return@Slider
                sliderValue = level.toFloat()
                onPercentChange(level)
            },
            valueRange = 0f..100f,
            steps = 99,
            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LinkRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        summary = summary,
        endActions = { Icon(MiuixIcons.ChevronForward, contentDescription = null) },
        onClick = onClick,
    )
}
