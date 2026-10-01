package com.minis.dshconsole.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.theme.DshTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * 侧栏抽屉 —— 复刻 DeepSeek 原包的切边栏动画
 *
 * 对照真机逐帧截图（1000052239 → 1000052244）还原出的行为：
 *   ① 抽屉从左侧滑入，**不是**单纯的蒙层淡入；
 *   ② 主内容整体**向右平移**（位移量 = 抽屉宽度 × 进度），
 *      并在**左侧两角**加上大圆角（topStart / bottomStart），
 *      看起来像一张卡片被推开；
 *   ③ 抽屉宽度约为屏宽的 82%（截图量得右边缘在 x≈750/920）；
 *   ④ **没有蒙层** —— 录屏逐帧看，抽屉自始至终是清晰白底，不压暗；
*      主内容左边缘有一道细投影作为接缝。
 *
 * 实现：三层 —— 抽屉（底层）/ 蒙层 / 主内容（上层，位移 + 圆角）。
 * 进度用 Animatable 驱动，支持从左边缘拖拽。
 */

/** 抽屉宽度占屏宽的比例（截图实测 ≈82%） */
private const val DrawerWidthFraction = 0.82f

/** 主内容左边缘的投影（录屏里抽屉与内容之间有一道细接缝） */
private val ContentShadow = 8.dp

/** 主内容被推开时的左圆角（录屏实测约 28dp） */
private val ContentCorner = 28.dp

/** 抽屉落定所用的弹簧 */
private val OpenSpec = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
private val CloseSpec = tween<Float>(durationMillis = 240)

@Composable
fun DsDrawerLayout(
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    LaunchedEffect(open) {
        if (open) progress.animateTo(1f, OpenSpec) else progress.animateTo(0f, CloseSpec)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = maxWidth * DrawerWidthFraction
        val drawerPx = with(density) { drawerWidth.toPx() }
        var totalDrag by remember { mutableFloatStateOf(0f) }

        // ---------------- 底层：抽屉
        Box(
            Modifier
                .width(drawerWidth)
                .fillMaxHeight()
        ) {
            drawerContent()
        }

        // ---------------- 上层：主内容（右移 + 左圆角 + 可拖拽）
        val corner = ContentCorner * progress.value
        Box(
            Modifier
                .fillMaxSize()
                .offsetX { (drawerPx * progress.value).roundToInt() }
                .shadow(
                    elevation = ContentShadow * progress.value,
                    shape = RoundedCornerShape(topStart = corner, bottomStart = corner),
                    clip = false,
                )
                .clip(
                    RoundedCornerShape(
                        topStart = corner,
                        bottomStart = corner,
                        topEnd = 0.dp,
                        bottomEnd = 0.dp,
                    )
                )
                .background(DshTheme.p.bg)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val target = if (progress.value > 0.5f) 1f else 0f
                                totalDrag = 0f
                                if (target == 1f && !open) onOpen() else if (target == 0f && open) onClose()
                                progress.animateTo(target, OpenSpec)
                            }
                        },
                        onHorizontalDrag = { _, delta ->
                            scope.launch {
                                val next = (progress.value + delta / drawerPx).coerceIn(0f, 1f)
                                progress.snapTo(next)
                            }
                        },
                    )
                }
        ) {
            content()
            // 打开状态下点右侧露出的部分可关闭
            if (progress.value > 0.99f) {
                Box(Modifier.fillMaxSize().clickable(onClick = onClose))
            }
        }
    }
}

/** offset { IntOffset } 的简写 */
private fun Modifier.offsetX(block: () -> Int): Modifier =
    this.offset { IntOffset(block(), 0) }
