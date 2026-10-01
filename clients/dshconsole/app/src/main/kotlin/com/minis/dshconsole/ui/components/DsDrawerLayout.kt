package com.minis.dshconsole.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.theme.DshTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * 侧栏抽屉 —— 复刻 DeepSeek 原包的切边栏动画
 *
 * 对照真机录屏（1000052245）逐帧还原：
 *   ① 主内容作为一整块【带圆角的白色面板】向右平移，位移量 = 抽屉宽 × 进度；
 *   ② 左边缘两角大圆角 ≈28dp，【全程恒定】不随进度缩放；
 *   ③ 抽屉宽度 ≈屏宽 82%；
 *   ④ **没有蒙层** —— 抽屉自始至终是清晰白底，不压暗；
 *      主内容左边缘只有一道细投影作为接缝；
 *   ⑤ 平移 **同时向左边缘轻微收缩**（scale 1 → 0.92，锚点 = 左边缘中点）。
 *   ⑥ 弹簧参数取自原包 smali（zz7.smali 常量表）：
 *      spring(dampingRatio = 1.0f, stiffness = 1400f)
 *
 * ★ 稳定性（修闪退）★
 * 上一版把「拖拽」和「open 状态变化」两条路都指向同一个 Animatable：
 * 手势里 launch { snapTo }、同时 LaunchedEffect 里 animateTo，
 * 两边互相取消，CancellationException 冒到 composition 直接闪退。
 * 现在：
 *   · 加 dragging 标志，拖拽期间 LaunchedEffect 不接管动画；
 *   · 拖拽更新只走一条协程路径，并且 runCatching 兜底；
 *   · 所有 animateTo 都 catch 掉 CancellationException；
 *   · drawerPx 为 0 时直接跳过，避免除零。
 */

/** 内容被推开时向左边缘收缩的比例（满开时 1 - 0.08 = 0.92） */
private const val ContentScaleAmount = 0.08f

/** 抽屉宽度占屏宽的比例（截图实测 ≈82%） */
private const val DrawerWidthFraction = 0.82f

/** 主内容被推开时的左圆角（录屏实测约 28dp） */
private val ContentCorner = 28.dp

/** 主内容左边缘的投影（录屏里抽屉与内容之间有一道细接缝） */
private val ContentShadow = 8.dp

private val OpenSpec = spring<Float>(dampingRatio = 1.0f, stiffness = 1400f)
private val CloseSpec = tween<Float>(durationMillis = 220)

/** 关闭阈值：超过 50% 就吸附到打开 */
private const val SettleThreshold = 0.5f

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
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // 拖拽中：由手势独占 progress，LaunchedEffect 不参与
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(open, dragging) {
        if (dragging) return@LaunchedEffect
        runCatching {
            progress.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = if (open) OpenSpec else CloseSpec,
            )
        }.getOrElse { e ->
            if (e !is CancellationException) throw e
        }
    }

    // 切到侧栏时收起输入法并清焦点（否则键盘会浮在抽屉上方）
    LaunchedEffect(open) {
        if (open) {
            runCatching { focusManager.clearFocus(force = true) }
            runCatching { keyboard?.hide() }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = maxWidth * DrawerWidthFraction
        val drawerPx = with(density) { drawerWidth.toPx() }.coerceAtLeast(1f)

        // ---------------- 底层：抽屉
        Box(
            Modifier
                .width(drawerWidth)
                .fillMaxHeight()
                // ★ 遮挡修复 ★
                // 子列表/标题行比抽屉宽时不会溢出到右侧内容下面
                // （截图里 session-d7f0f4d0-… 那几行被内容面板盖住就是这个原因）
                .clipToBounds()
        ) {
            drawerContent()
        }

        // ---------------- 上层：主内容（右移 + 左圆角 + 左侧投影 + 可拖拽）
        val p = progress.value
        // ★ 缩放：内容被推开时向左边缘收缩 ★
        // 之前我判断成「纯平移不缩放」是错的 —— 逐帧看，
        // 内容在右移的同时还向【左边缘】收缩，这才是「卡片被推远」的层次感。
        val contentScale = 1f - ContentScaleAmount * p
        // ★ 圆角恒定，不随进度缩放 ★
        // 对照原版逐帧：内容只移开 24% 时圆角依然很大；
        // 我原来写 corner = ContentCorner * p，动画刚开始时半径接近 0（看着像直角），
        // 这是与原版最明显的差异。
        val corner = ContentCorner

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = contentScale
                    scaleY = contentScale
                    // 锚点在左边缘中点：内容向左侧（靠近抽屉的那一边）收缩
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .offset { IntOffset((drawerPx * p).roundToInt(), 0) }
                .shadow(
                    elevation = ContentShadow * p,   // 投影随进度淡入，圆角保持恒定
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
                .pointerInput(drawerPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragCancel = { dragging = false },
                        onDragEnd = {
                            dragging = false
                            val settleOpen = progress.value >= SettleThreshold
                            runCatching {
                                if (settleOpen) onOpen() else onClose()
                            }
                        },
                        onHorizontalDrag = { change, delta ->
                            change.consume()
                            val next = (progress.value + delta / drawerPx).coerceIn(0f, 1f)
                            scope.launch {
                                runCatching { progress.snapTo(next) }
                            }
                        },
                    )
                }
        ) {
            content()

            // 打开时露出的部分：点一下关抽屉。
            // 纯热区 —— 不要涟漪、不要按压动效。
            if (p > 0.99f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onClose,
                        )
                )
            }
        }
    }
}
