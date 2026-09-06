package io.hyper.freeform.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

val PageVerticalPadding = 12.dp

data class CardItem(val key: String, val content: @Composable ColumnScope.() -> Unit)

fun LazyListScope.groupedCardItems(keyPrefix: String, items: List<CardItem>) {
    itemsIndexed(items, key = { _, item -> "$keyPrefix:${item.key}" }) { index, item ->
        CardSegmentContainer(isFirst = index == 0, isLast = index == items.lastIndex) {
            item.content(this)
        }
    }
}

/** Consecutive lazy rows share one card outline without composing the entire group at once. */
@Composable
fun CardSegmentContainer(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.defaultColors()
    val top = if (isFirst) CardDefaults.CornerRadius else 0.dp
    val bottom = if (isLast) CardDefaults.CornerRadius else 0.dp
    val surface = if (isFirst || isLast) {
        Modifier.squircleSurface(colors.color, top, top, bottom, bottom)
    } else {
        Modifier.background(colors.color)
    }
    CompositionLocalProvider(LocalContentColor provides colors.contentColor) {
        Column(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).then(surface),
            content = content,
        )
    }
}

@Composable
fun SectionTitle(text: String, topPadding: Dp = 20.dp) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = topPadding, bottom = 8.dp),
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        fontSize = 14.sp,
    )
}
