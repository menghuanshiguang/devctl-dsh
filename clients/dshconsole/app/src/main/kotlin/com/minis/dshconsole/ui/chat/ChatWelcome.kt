package com.minis.dshconsole.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.DsStr
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DsType
import com.minis.dshconsole.ui.theme.DshTheme

/*
 * 空会话欢迎页 —— 对应 DeepSeek 原包
 *   ui/pages/chat/ChatWelcome.kt
 *   ui/pages/chat/ChatWelcomeLogo.kt
 *
 * 截图实测：品牌蓝 logo（#416EFD）居中，下方 22sp 粗体问候语；
 * smali 常量表 cp2.smali 给出 logo 尺寸 80dp、文案与 logo 间距 32dp。
 */

/** Logo 尺寸（原包 ChatWelcome.kt 常量表：80dp） */
private val LogoSize = 80.dp

/** logo 与问候语间距（原包常量表：32dp） */
private val LogoGap = 32.dp

@Composable
fun ChatWelcome(
    modifier: Modifier = Modifier,
    greetingIndex: Int = 0,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = DsSpacing.screenH),
        ) {
            ChatWelcomeLogo()
            Spacer(Modifier.height(LogoGap))
            Text(
                DsStr.greeting[greetingIndex % DsStr.greeting.size],
                style = DsType.greeting,
                color = DshTheme.p.textPrimary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 鲸鱼 Logo —— 原包 ChatWelcomeLogo.kt。
 * 这里用路径画一个品牌蓝的鲸鱼轮廓，避免引入位图资源。
 */
@Composable
fun ChatWelcomeLogo(
    modifier: Modifier = Modifier,
    brand: Color = DshTheme.p.brand,
) {
    // 轻微呼吸动效（原包此处也带一个缓慢的缩放动画）
    val transition = rememberInfiniteTransition(label = "welcomeLogo")
    val breath by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
        label = "breath",
    )

    Box(
        modifier
            .size(LogoSize)
            .scale(breath)
            .clip(CircleShape)
            .background(brand),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(LogoSize * 0.62f)) {
            drawWhale(brand = Color.White)
        }
    }
}

/** 用两个椭圆 + 一条尾鳍拼出鲸鱼剪影 */
private fun DrawScope.drawWhale(brand: Color) {
    val w = size.width
    val h = size.height

    // 身体
    val body = Path().apply {
        moveTo(w * 0.08f, h * 0.42f)
        cubicTo(w * 0.08f, h * 0.10f, w * 0.72f, h * 0.06f, w * 0.80f, h * 0.40f)
        cubicTo(w * 0.86f, h * 0.66f, w * 0.60f, h * 0.90f, w * 0.34f, h * 0.88f)
        cubicTo(w * 0.18f, h * 0.86f, w * 0.08f, h * 0.68f, w * 0.08f, h * 0.42f)
        close()
    }
    drawPath(body, Color.White)

    // 尾鳍
    val tail = Path().apply {
        moveTo(w * 0.74f, h * 0.22f)
        lineTo(w * 0.98f, h * 0.04f)
        lineTo(w * 0.92f, h * 0.40f)
        close()
    }
    drawPath(tail, Color.White)

    // 眼睛（挖空）
    drawCircle(
        color = brand,
        radius = w * 0.055f,
        center = Offset(w * 0.34f, h * 0.44f),
    )
}
