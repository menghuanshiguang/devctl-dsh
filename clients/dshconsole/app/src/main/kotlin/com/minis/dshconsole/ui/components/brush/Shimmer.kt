package com.minis.dshconsole.ui.components.brush

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/*
 * 对应 DeepSeek 原包：
 *   com.deepseek.chat.ui.components.brush.rememberShimmerBrush
 *   com.deepseek.chat.ui.components.brush.rememberShimmerProgress
 *   com.deepseek.chat.ui.components.brush.shimmerBrush
 *
 * 流式输出时"正在生成"的那道扫光就是它。
 */

fun shimmerBrush(
    progress: Float,
    width: Float,
    base: Color,
    highlight: Color,
): Brush {
    // progress ∈ [0,1]，扫光整体从左侧外面滑到右侧外面
    val start = -width + progress * (3f * width)
    return Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(start, 0f),
        end = Offset(start + width, 0f),
    )
}

@Composable
fun rememberShimmerProgress(periodMillis: Int = 1200): State<Float> {
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMillis),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
}

/** 直接在盒子上叠一道扫光 */
@Composable
fun ShimmerOverlay(
    modifier: Modifier = Modifier,
    periodMillis: Int = 1200,
) {
    val progress by rememberShimmerProgress(periodMillis)
    val base = Color.Transparent
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val size = remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .onSizeChanged { size.value = it }
            .background(
                shimmerBrush(
                    progress = progress,
                    width = size.value.width.coerceAtLeast(1).toFloat(),
                    base = base,
                    highlight = highlight,
                )
            )
            .fillMaxSize()
    )
}
