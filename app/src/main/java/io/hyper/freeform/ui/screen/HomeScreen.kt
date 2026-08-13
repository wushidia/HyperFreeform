package io.hyper.freeform.ui.screen

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.hyper.freeform.BuildConfig
import io.hyper.freeform.R
import io.hyper.freeform.data.Prefs
import io.hyper.freeform.provider.ModuleStatusProvider
import io.hyper.freeform.service.FreeformBridge
import io.hyper.freeform.service.FreeformManagerClient
import io.hyper.freeform.service.NotificationFreeformService
import io.hyper.freeform.ui.component.AdaptiveTopAppBar
import io.hyper.freeform.ui.component.CardItem
import io.hyper.freeform.ui.component.PageVerticalPadding
import io.hyper.freeform.ui.component.SectionTitle
import io.hyper.freeform.ui.component.blur.BlurredBar
import io.hyper.freeform.ui.component.blur.rememberBlurBackdrop
import io.hyper.freeform.ui.component.groupedCardItems
import io.hyper.freeform.ui.theme.StatusColors
import io.hyper.freeform.ui.util.WideContentBox
import io.hyper.freeform.ui.util.rememberIsWideScreen
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
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun HomeScreen(
    prefs: Prefs,
    modifier: Modifier = Modifier,
    onOpenSidebarApps: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val enabled by prefs.enabled.collectAsStateWithLifecycle(initialValue = true)
    val notificationFreeform by prefs.notificationFreeform.collectAsStateWithLifecycle(initialValue = true)
    val recentsFreeform by prefs.recentsFreeform.collectAsStateWithLifecycle(initialValue = true)
    // null 表示 SharedPreferences 尚未发出首值，避免把占位值短暂写入 system_server。
    val persistedDpiPercent by prefs.dpiPercent.collectAsStateWithLifecycle(initialValue = null)
    val dpiPercent = persistedDpiPercent ?: FreeformBridge.DEFAULT_DPI_PERCENT
    val persistedWindowWidthPercent by prefs.windowWidthPercent.collectAsStateWithLifecycle(initialValue = null)
    val persistedWindowHeightPercent by prefs.windowHeightPercent.collectAsStateWithLifecycle(initialValue = null)
    val windowWidthPercent = persistedWindowWidthPercent ?: FreeformBridge.DEFAULT_WINDOW_WIDTH_PERCENT
    val windowHeightPercent = persistedWindowHeightPercent ?: FreeformBridge.DEFAULT_WINDOW_HEIGHT_PERCENT
    val sidebarSide by prefs.sidebarSide.collectAsStateWithLifecycle(initialValue = 1)
    val sidebarApps by prefs.sidebarApps.collectAsStateWithLifecycle(initialValue = emptySet())
    val persistedSidebarShowAppNames by
        prefs.sidebarShowAppNames.collectAsStateWithLifecycle(initialValue = null)
    val sidebarShowAppNames = persistedSidebarShowAppNames ?: false

    val initiallyReady = FreeformManagerClient.isReady()
    var moduleActive by remember {
        mutableStateOf(ModuleStatusProvider.isModuleActive() || initiallyReady)
    }
    var serviceReady by remember { mutableStateOf(initiallyReady) }
    var openCount by remember { mutableIntStateOf(0) }
    var notificationAccess by remember { mutableStateOf(false) }
    var notificationGranted by remember { mutableStateOf(false) }
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
        val percent = persistedDpiPercent
            ?.let(FreeformBridge::sanitizeDpiPercent)
            ?: return@LaunchedEffect
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
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            val listener = ComponentName(context, NotificationFreeformService::class.java)
            while (true) {
                val readyNow = FreeformManagerClient.isReady()
                serviceReady = readyNow
                moduleActive = ModuleStatusProvider.isModuleActive() || readyNow
                openCount = FreeformManagerClient.openCount()
                notificationAccess = notificationManager.isNotificationListenerAccessGranted(listener)
                notificationGranted = Build.VERSION.SDK_INT < 33 ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                delay(1200)
            }
        }
    }

    val selectedPage = pagerState.currentPage
    val navigationItems = listOf(
        NavigationItem(stringResource(R.string.nav_home), MiuixIcons.Home),
        NavigationItem(stringResource(R.string.nav_settings), MiuixIcons.Settings),
    )
    val pagerContent: @Composable (Modifier, Dp) -> Unit = { pagerModifier, bottomPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = pagerModifier,
            verticalAlignment = Alignment.Top,
        ) { page ->
            if (page == 0) {
                StatusPage(
                    bottomPadding = bottomPadding,
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
                )
            } else {
                SettingsPage(
                    bottomPadding = bottomPadding,
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
                        FreeformManagerClient.setGlobalDpiPercent(clamped)
                        scope.launch { prefs.setDpiPercent(clamped) }
                    },
                    onWindowWidthChange = { value ->
                        scope.launch {
                            val clamped = FreeformBridge.sanitizeWindowSizePercent(value)
                            prefs.setWindowWidthPercent(clamped)
                            FreeformManagerClient.setGlobalWindowSizePercent(clamped, windowHeightPercent)
                        }
                    },
                    onWindowHeightChange = { value ->
                        scope.launch {
                            val clamped = FreeformBridge.sanitizeWindowSizePercent(value)
                            prefs.setWindowHeightPercent(clamped)
                            FreeformManagerClient.setGlobalWindowSizePercent(windowWidthPercent, clamped)
                        }
                    },
                    onSidebarSideChange = { side ->
                        scope.launch {
                            prefs.setSidebarSide(side)
                            FreeformManagerClient.setSidebarSide(side)
                        }
                    },
                    onSidebarShowAppNamesChange = { show ->
                        scope.launch { prefs.setSidebarShowAppNames(show) }
                    },
                    onOpenSidebarApps = onOpenSidebarApps,
                    onOpenAbout = onOpenAbout,
                )
            }
        }
    }

    if (rememberIsWideScreen()) {
        Scaffold(modifier = modifier.fillMaxSize()) { _ ->
            Row(Modifier.fillMaxSize()) {
                NavigationRail(state = rememberNavigationRailState()) {
                    navigationItems.forEachIndexed { index, item ->
                        NavigationRailItem(
                            selected = selectedPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            icon = item.icon,
                            label = item.label,
                        )
                    }
                }
                pagerContent(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .consumeWindowInsets(
                            WindowInsets.displayCutout.union(WindowInsets.navigationBars)
                                .only(WindowInsetsSides.Start),
                        )
                        .windowInsetsPadding(
                            WindowInsets.systemBars.union(WindowInsets.displayCutout)
                                .only(WindowInsetsSides.End),
                        ),
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                )
            }
        }
    } else {
        val bottomBackdrop = rememberBlurBackdrop()
        val blurActive = bottomBackdrop != null
        val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
        Scaffold(
            modifier = modifier.fillMaxSize(),
            bottomBar = {
                BlurredBar(backdrop = bottomBackdrop, blurActive = blurActive) {
                    NavigationBar(color = barColor) {
                        navigationItems.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = selectedPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                icon = item.icon,
                                label = item.label,
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            pagerContent(
                Modifier
                    .fillMaxSize()
                    .then(if (bottomBackdrop != null) Modifier.layerBackdrop(bottomBackdrop) else Modifier),
                innerPadding.calculateBottomPadding(),
            )
        }
    }
}

@Composable
private fun StatusPage(
    bottomPadding: Dp,
    moduleActive: Boolean,
    serviceReady: Boolean,
    openCount: Int,
    notificationAccess: Boolean,
    notificationGranted: Boolean,
    onRequestNotificationAccess: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val isActive = moduleActive && serviceReady
    val statusContainer = StatusColors.runStateContainer(isActive)
    val statusContentColor = StatusColors.runStateContent(isActive)
    val statusDescriptionColor = statusContentColor.copy(alpha = 0.8f)
    val statusIcon = StatusColors.runStateIcon(isActive)
    val statusSummary = when {
        isActive -> R.string.home_status_active_summary
        !moduleActive -> R.string.home_status_module_inactive_summary
        else -> R.string.home_status_service_inactive_summary
    }
    val statusDetail = when {
        isActive -> R.string.home_status_detail_fmt to
            (R.string.home_system_service to R.string.home_ready)
        !moduleActive -> R.string.home_status_detail_fmt to
            (R.string.home_module to R.string.home_inactive)
        else -> R.string.home_status_detail_fmt to
            (R.string.home_system_service to R.string.home_not_ready)
    }
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.home_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        WideContentBox {
            sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    top = innerPadding.calculateTopPadding() + PageVerticalPadding,
                    end = sidePadding,
                    bottom = bottomPadding + PageVerticalPadding,
                ),
            ) {
                item(key = "status_overview") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth(),
                            colors = CardDefaults.defaultColors(color = statusContainer),
                            showIndication = true,
                            pressFeedbackType = PressFeedbackType.Tilt,
                        ) {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .offset(50.dp, 38.dp),
                                    contentAlignment = Alignment.BottomEnd,
                                ) {
                                    Icon(
                                        modifier = Modifier.size(170.dp),
                                        imageVector = if (isActive) {
                                            Icons.Rounded.CheckCircleOutline
                                        } else {
                                            Icons.Rounded.ErrorOutline
                                        },
                                        tint = statusIcon,
                                        contentDescription = null,
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                ) {
                                    Text(
                                        modifier = Modifier.fillMaxWidth(),
                                        text = stringResource(
                                            if (isActive) R.string.home_active else R.string.home_inactive,
                                        ),
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = statusContentColor,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        modifier = Modifier.fillMaxWidth(),
                                        text = stringResource(statusSummary),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = statusDescriptionColor,
                                    )
                                    Spacer(Modifier.height(36.dp))
                                    Text(
                                        modifier = Modifier.fillMaxWidth(),
                                        text = stringResource(
                                            statusDetail.first,
                                            stringResource(statusDetail.second.first),
                                            stringResource(statusDetail.second.second),
                                        ),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = statusDescriptionColor,
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            StatusSummaryCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                label = stringResource(R.string.home_module),
                                value = stringResource(
                                    if (moduleActive) R.string.home_active else R.string.home_inactive,
                                ),
                            )
                            StatusSummaryCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                label = stringResource(R.string.home_system_service),
                                value = stringResource(
                                    if (serviceReady) R.string.home_ready else R.string.home_not_ready,
                                ),
                            )
                        }
                    }
                }
                item(key = "runtime_title") {
                    SectionTitle(text = stringResource(R.string.runtime_info_title))
                }
                groupedCardItems(
                    keyPrefix = "runtime",
                    items = listOf(
                        CardItem("windows") {
                            BasicComponent(
                                title = stringResource(R.string.current_windows),
                                summary = stringResource(R.string.open_count_fmt, openCount),
                            )
                        },
                        CardItem("version") {
                            BasicComponent(
                                title = stringResource(R.string.module_version),
                                summary = stringResource(
                                    R.string.version_build_fmt,
                                    BuildConfig.VERSION_NAME,
                                    BuildConfig.VERSION_CODE,
                                ),
                            )
                        },
                        CardItem("notificationAccess") {
                            StatusRow(
                                title = stringResource(R.string.notification_listener_permission),
                                summary = stringResource(
                                    if (notificationAccess) R.string.permission_granted
                                    else R.string.permission_tap_to_grant,
                                ),
                                ok = notificationAccess,
                                onClick = onRequestNotificationAccess,
                            )
                        },
                        CardItem("notificationPermission") {
                            StatusRow(
                                title = stringResource(R.string.notification_permission),
                                summary = stringResource(
                                    if (notificationGranted) R.string.permission_granted
                                    else R.string.permission_tap_to_grant,
                                ),
                                ok = notificationGranted,
                                onClick = onRequestNotificationPermission,
                            )
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun SettingsPage(
    bottomPadding: Dp,
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
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.settings_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { innerPadding ->
        WideContentBox { sidePadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    top = innerPadding.calculateTopPadding() + PageVerticalPadding,
                    end = sidePadding,
                    bottom = bottomPadding + PageVerticalPadding,
                ),
            ) {
                item(key = "freeform_title") {
                    SectionTitle(
                        text = stringResource(R.string.freeform_settings_section),
                        topPadding = 0.dp,
                    )
                }
                groupedCardItems(
                    keyPrefix = "freeform",
                    items = listOf(
                        CardItem("enabled") {
                            SwitchPreference(
                                title = stringResource(R.string.enable_freeform),
                                summary = stringResource(R.string.enable_freeform_summary),
                                checked = enabled,
                                onCheckedChange = onEnabledChange,
                            )
                        },
                        CardItem("notification") {
                            SwitchPreference(
                                title = stringResource(R.string.notification_freeform),
                                summary = stringResource(R.string.notification_freeform_summary),
                                checked = notificationFreeform,
                                onCheckedChange = onNotificationChange,
                            )
                        },
                        CardItem("recents") {
                            SwitchPreference(
                                title = stringResource(R.string.recents_freeform),
                                summary = stringResource(R.string.recents_freeform_summary),
                                checked = recentsFreeform,
                                onCheckedChange = onRecentsChange,
                            )
                        },
                        CardItem("dpi") {
                            PercentSliderRow(
                                title = stringResource(R.string.freeform_dpi),
                                summary = stringResource(R.string.freeform_dpi_summary),
                                percent = dpiPercent,
                                onPercentChange = onDpiChange,
                            )
                        },
                        CardItem("width") {
                            PercentSliderRow(
                                title = stringResource(R.string.freeform_window_width),
                                summary = stringResource(R.string.freeform_window_width_summary),
                                percent = windowWidthPercent,
                                onPercentChange = onWindowWidthChange,
                            )
                        },
                        CardItem("height") {
                            PercentSliderRow(
                                title = stringResource(R.string.freeform_window_height),
                                summary = stringResource(R.string.freeform_window_height_summary),
                                percent = windowHeightPercent,
                                onPercentChange = onWindowHeightChange,
                            )
                        },
                    ),
                )
                item(key = "sidebar_title") {
                    SectionTitle(text = stringResource(R.string.sidebar_title))
                }
                groupedCardItems(
                    keyPrefix = "sidebar",
                    items = listOf(
                        CardItem("position") {
                            SidebarPositionRow(
                                sidebarSide = sidebarSide,
                                onSidebarSideChange = onSidebarSideChange,
                            )
                        },
                        CardItem("names") {
                            SwitchPreference(
                                title = stringResource(R.string.sidebar_show_app_names),
                                summary = stringResource(R.string.sidebar_show_app_names_summary),
                                checked = sidebarShowAppNames,
                                onCheckedChange = onSidebarShowAppNamesChange,
                            )
                        },
                        CardItem("apps") {
                            ArrowPreference(
                                title = stringResource(R.string.sidebar_apps_entry),
                                summary = stringResource(R.string.sidebar_apps_hint),
                                onClick = onOpenSidebarApps,
                            )
                        },
                    ),
                )
                item(key = "about_title") {
                    SectionTitle(text = stringResource(R.string.about_title))
                }
                groupedCardItems(
                    keyPrefix = "about",
                    items = listOf(
                        CardItem("entry") {
                            ArrowPreference(
                                title = stringResource(R.string.about_title),
                                summary = stringResource(R.string.about_entry_summary),
                                onClick = onOpenAbout,
                            )
                        },
                    ),
                )
            }
        }
    }
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
    modifier: Modifier = Modifier,
    label: String,
    value: String,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MiuixTheme.textStyles.main)
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            }
            Text(
                text = stringResource(R.string.percent_fmt, sliderValue.roundToInt()),
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { value ->
                val level = value.roundToInt().coerceIn(0, 100)
                if (level != sliderValue.roundToInt()) {
                    sliderValue = level.toFloat()
                    onPercentChange(level)
                }
            },
            valueRange = 0f..100f,
            steps = 99,
            hapticEffect = SliderDefaults.SliderHapticEffect.Step,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
