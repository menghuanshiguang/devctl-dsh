package com.minis.dshconsole.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.R
import com.minis.dshconsole.ui.DsStr
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DsType
import com.minis.dshconsole.ui.theme.DshTheme

/*
 * 空会话欢迎页 —— 对应 DeepSeek 原包
 *   ui/pages/chat/session/views/ChatWelcome.kt
 *     :115 ChatWelcome
 *     :243 WelcomeMessageWithLogo
 *   ui/pages/chat/session/views/welcome/ChatWelcomeLogo.kt
 *     :20  ChatWelcomeInlineLogo
 *     :42  ChatWelcomeLargeLogo
 *
 * Logo 直接用原包自己的矢量资源 res/drawable/chat_welcome_logo.xml
 * （43×32 viewport，单 path，填充 #ff426efe —— 与本包 R.color.primary 一致），
 * 这里原样搬过来，不再自己画。
 *
 * 尺寸：截图实测鲸鱼宽 138px / 3x ≈ 46dp。
 */

/** Logo 宽度（截图实测 46dp） */
private val LogoWidth = 46.dp

/** logo 与问候语间距（原文案间距 32dp） */
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
            ChatWelcomeLargeLogo()
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
 * 对应 ChatWelcomeLogo.kt:42 ChatWelcomeLargeLogo
 * 就一个呼吸动效包着原包的矢量图。
 */
@Composable
fun ChatWelcomeLargeLogo(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "welcomeLogo")
    val breath by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
        label = "breath",
    )
    Image(
        painter = painterResource(R.drawable.chat_welcome_logo),
        contentDescription = null,
        modifier = modifier
            .width(LogoWidth)
            .scale(breath),
    )
}

/** 对应 ChatWelcomeLogo.kt:20 ChatWelcomeInlineLogo —— 行内小号 logo */
@Composable
fun ChatWelcomeInlineLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.chat_welcome_logo),
        contentDescription = null,
        modifier = modifier.width(24.dp),
    )
}

/** 竖版完整品牌标识（对应原包 drawable-anydpi-v24/ic_logo_deepseek_vertical.xml） */
@Composable
fun DeepSeekVerticalLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_logo_deepseek_vertical),
        contentDescription = null,
        modifier = modifier.width(120.dp),
    )
}
