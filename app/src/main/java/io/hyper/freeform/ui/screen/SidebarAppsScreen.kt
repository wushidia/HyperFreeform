package io.hyper.freeform.ui.screen

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.hyper.freeform.R
import io.hyper.freeform.data.Prefs
import io.hyper.freeform.service.FreeformManagerClient
import io.hyper.freeform.ui.component.AdaptiveTopAppBar
import io.hyper.freeform.ui.component.CardSegmentContainer
import io.hyper.freeform.ui.component.LauncherAppIcon
import io.hyper.freeform.ui.component.PageVerticalPadding
import io.hyper.freeform.ui.component.SectionTitle
import io.hyper.freeform.ui.component.blur.BlurredBar
import io.hyper.freeform.ui.component.blur.rememberBlurBackdrop
import io.hyper.freeform.ui.util.horizontalCutoutPadding
import io.hyper.freeform.xposed.policy.FreeformPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Immutable
data class SidebarAppItem(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
)

@Composable
fun SidebarAppsScreen(
    prefs: Prefs,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<SidebarAppItem>>(emptyList()) }
    var showSystemApps by remember { mutableStateOf(false) }
    var openPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selected by prefs.sidebarApps.collectAsStateWithLifecycle(initialValue = emptySet())

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { result ->
                    val info = result.activityInfo?.applicationInfo ?: return@mapNotNull null
                    if (info.packageName == context.packageName) return@mapNotNull null
                    if (FreeformPolicy.isBlacklisted(info.packageName)) return@mapNotNull null
                    SidebarAppItem(
                        label = info.loadLabel(pm).toString(),
                        packageName = info.packageName,
                        isSystem = info.flags and
                            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    )
                }
                .distinctBy { it.packageName }
        }
    }
    LaunchedEffect(selected) {
        val csv = selected.sorted().joinToString(",")
        while (!FreeformManagerClient.setSidebarApps(csv)) delay(1000)
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                openPackages = FreeformManagerClient.openPackages()
                delay(1200)
            }
        }
    }

    fun togglePackage(packageName: String) {
        val next = selected.toMutableSet().apply {
            if (!add(packageName)) remove(packageName)
        }
        scope.launch {
            prefs.setSidebarApps(next)
            FreeformManagerClient.setSidebarApps(next.sorted().joinToString(","))
        }
    }

    val displayedApps = remember(query, apps, showSystemApps, selected, openPackages) {
        apps.filter { app ->
            (showSystemApps || !app.isSystem) &&
                (query.isBlank() || app.label.contains(query, true) ||
                    app.packageName.contains(query, true))
        }.sortedWith(
            compareByDescending<SidebarAppItem> { it.packageName in openPackages }
                .thenByDescending { it.packageName in selected }
                .thenBy { it.label.lowercase() },
        )
    }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.sidebar_apps_title),
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                    actions = {
                        WindowIconDropdownMenu(
                            DropdownEntry(
                                items = listOf(
                                    DropdownItem(
                                        text = stringResource(R.string.show_system_apps_user_only),
                                        selected = !showSystemApps,
                                        onClick = { showSystemApps = false },
                                    ),
                                    DropdownItem(
                                        text = stringResource(R.string.show_system_apps_all),
                                        selected = showSystemApps,
                                        onClick = { showSystemApps = true },
                                    ),
                                ),
                            ),
                        ) {
                            Icon(
                                imageVector = MiuixIcons.More,
                                contentDescription = stringResource(R.string.sort_by_show_system_app),
                            )
                        }
                    },
                    bottomContent = {
                        InputField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 8.dp),
                            query = query,
                            onQueryChange = { query = it },
                            label = stringResource(R.string.search_hint),
                            expanded = false,
                            onExpandedChange = {},
                            onSearch = {},
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + PageVerticalPadding,
                bottom = innerPadding.calculateBottomPadding() + PageVerticalPadding,
            ),
        ) {
            item(key = "apps_title") {
                SectionTitle(
                    text = stringResource(
                        R.string.sidebar_apps_count_fmt,
                        selected.size,
                        displayedApps.size,
                    ),
                    topPadding = 0.dp,
                )
            }
            if (displayedApps.isEmpty()) {
                item(key = "empty") {
                    CardSegmentContainer(isFirst = true, isLast = true) {
                        BasicComponent(
                            title = stringResource(R.string.no_matching_apps),
                            summary = stringResource(R.string.sidebar_apps_hint),
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = displayedApps,
                    key = { _, app -> app.packageName },
                    contentType = { _, _ -> "app" },
                ) { index, app ->
                    val isSelected = app.packageName in selected
                    CardSegmentContainer(
                        isFirst = index == 0,
                        isLast = index == displayedApps.lastIndex,
                    ) {
                        BasicComponent(
                            title = app.label,
                            summary = app.packageName,
                            startAction = {
                                LauncherAppIcon(
                                    packageName = app.packageName,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            },
                            endActions = {
                                Checkbox(
                                    state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                                    onClick = { togglePackage(app.packageName) },
                                )
                            },
                            onClick = { togglePackage(app.packageName) },
                        )
                    }
                }
            }
        }
    }
}
