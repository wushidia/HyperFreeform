package io.hyper.freeform.ui.screen

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.hyper.freeform.R
import io.hyper.freeform.data.Prefs
import io.hyper.freeform.service.FreeformManagerClient
import io.hyper.freeform.ui.utils.AppIconPainter
import io.hyper.freeform.xposed.policy.FreeformPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.WindowIconDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

data class SidebarAppItem(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
)

/** InstallerX-style scope picker adapted to the sidebar app selection. */
@Composable
fun SidebarAppsScreen(prefs: Prefs, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<SidebarAppItem>>(emptyList()) }
    var showSystemApps by remember { mutableStateOf(false) }
    var openPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    val selected by prefs.sidebarApps.collectAsState(initial = emptySet())

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
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

    LaunchedEffect(selected) {
        val csv = selected.sorted().joinToString(",")
        while (!FreeformManagerClient.setSidebarApps(csv)) delay(1000)
    }
    LaunchedEffect(Unit) {
        while (true) {
            openPackages = FreeformManagerClient.openPackages()
            delay(1200)
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

    val displayedApps = remember(
        query,
        apps,
        showSystemApps,
        selected,
        openPackages,
    ) {
        apps.filter {
            (showSystemApps || !it.isSystem) &&
                (query.isBlank() || it.label.contains(query, true) ||
                    it.packageName.contains(query, true))
        }
            .sortedWith(
                compareByDescending<SidebarAppItem> { it.packageName in openPackages }
                    .thenByDescending { it.packageName in selected }
                    .thenBy { it.label.lowercase() }
            )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = stringResource(R.string.sidebar_apps_title),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
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
                )
                InputField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    query = query,
                    onQueryChange = { query = it },
                    label = stringResource(R.string.search_hint),
                    expanded = false,
                    onExpandedChange = {},
                    onSearch = {},
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 8.dp,
                bottom = 24.dp,
            ),
        ) {
            itemsIndexed(
                items = displayedApps,
                key = { _, app -> app.packageName },
            ) { index, app ->
                val radius = CardDefaults.CornerRadius
                val shape = when {
                    displayedApps.size == 1 -> RoundedCornerShape(radius)
                    index == 0 -> RoundedCornerShape(
                        topStart = radius,
                        topEnd = radius,
                        bottomStart = 0.dp,
                        bottomEnd = 0.dp,
                    )
                    index == displayedApps.lastIndex -> RoundedCornerShape(
                        topStart = 0.dp,
                        topEnd = 0.dp,
                        bottomStart = radius,
                        bottomEnd = radius,
                    )
                    else -> RoundedCornerShape(0.dp)
                }
                val isSelected = app.packageName in selected
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .zIndex(-index.toFloat())
                        .fillMaxWidth()
                        .clip(shape)
                        .background(CardDefaults.defaultColors().color),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                togglePackage(app.packageName)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconPainter(app.packageName)?.let { bitmap ->
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                            )
                        } ?: Box(modifier = Modifier.size(40.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = app.label,
                                style = MiuixTheme.textStyles.title4,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = app.packageName,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                style = MiuixTheme.textStyles.body2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Switch(
                            checked = isSelected,
                            onCheckedChange = {
                                togglePackage(app.packageName)
                            },
                        )
                    }
                }
            }
            item { Spacer(modifier = Modifier.navigationBarsPadding()) }
        }
    }
}
