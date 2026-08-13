package io.hyper.freeform.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.hyper.freeform.ui.utils.AppIconPainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 可见时在 IO 线程加载并复用 LRU 缓存，避免 PackageManager 图标解码阻塞组合线程。 */
@Composable
fun LauncherAppIcon(
    packageName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null, packageName, size) {
        value = withContext(Dispatchers.IO) {
            AppIconPainter.load(context, packageName, size.value.toInt())
        }
    }
    val image = remember(bitmap) { bitmap?.asImageBitmap() }

    Box(modifier = modifier.size(size)) {
        Crossfade(
            targetState = image,
            animationSpec = tween(durationMillis = 150),
            label = "appIconFade",
        ) { icon ->
            if (icon != null) {
                Image(
                    bitmap = icon,
                    contentDescription = null,
                    modifier = Modifier.size(size),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(size)
                        .squircleBackground(MiuixTheme.colorScheme.secondaryContainer, 8.dp),
                )
            }
        }
    }
}
