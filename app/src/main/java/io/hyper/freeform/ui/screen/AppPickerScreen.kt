package io.hyper.freeform.ui.screen

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.hyper.freeform.R
import io.hyper.freeform.service.FreeformManagerClient
import io.hyper.freeform.ui.component.AdaptiveTopAppBar
import io.hyper.freeform.ui.component.CardSegmentContainer
import io.hyper.freeform.ui.component.LauncherAppIcon
import io.hyper.freeform.ui.component.PageVerticalPadding
import io.hyper.freeform.ui.component.SectionTitle
import io.hyper.freeform.ui.component.blur.BlurredBar
import io.hyper.freeform.ui.component.blur.rememberBlurBackdrop
import io.hyper.freeform.ui.util.horizontalCutoutPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Immutable
data class AppItem(
    val label: String,
    val packageName: String,
)

@Composable
fun AppPickerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = MiuixScrollBehavior()
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                .mapNotNull { result ->
                    val info = result.activityInfo?.applicationInfo ?: return@mapNotNull null
                    if (info.packageName == context.packageName) return@mapNotNull null
                    AppItem(
                        label = info.loadLabel(pm).toString(),
                        packageName = info.packageName,
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }

    val filtered = remember(query, apps) {
        if (query.isBlank()) apps else apps.filter { app ->
            app.label.contains(query, true) || app.packageName.contains(query, true)
        }
    }
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = stringResource(R.string.app_picker_title),
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
            item(key = "picker_hint") {
                SectionTitle(
                    text = stringResource(R.string.app_picker_count_fmt, filtered.size),
                    topPadding = 0.dp,
                )
            }
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    CardSegmentContainer(isFirst = true, isLast = true) {
                        BasicComponent(
                            title = stringResource(R.string.no_matching_apps),
                            summary = stringResource(R.string.app_picker_hint),
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = filtered,
                    key = { _, app -> app.packageName },
                    contentType = { _, _ -> "app" },
                ) { index, app ->
                    CardSegmentContainer(
                        isFirst = index == 0,
                        isLast = index == filtered.lastIndex,
                    ) {
                        BasicComponent(
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    FreeformManagerClient.startPackage(app.packageName, mini = false)
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    FreeformManagerClient.startPackage(app.packageName, mini = true)
                                },
                            ),
                            title = app.label,
                            summary = app.packageName,
                            startAction = {
                                LauncherAppIcon(
                                    packageName = app.packageName,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
