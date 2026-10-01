package com.minis.dshconsole.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DshTheme

/*
 * 助手消息底部的操作栏 —— 对应 DeepSeek 原包
 *   ui/pages/chat/message/AssistantChatMessageFooter.kt
 *   user/UserMessageActionView.kt（用户消息侧的同款操作）
 *   components/button/AppIconButton.kt（按钮本体）
 *
 * 截图实测布局（1000052250）：
 *   左组：复制 · 👍 · 👎 · 朗读 · 分享        （等距，间距约 24dp）
 *   右侧：重新生成                            （贴右边缘）
 *   图标 24dp，颜色为次级灰 #8F9094，无底色，触区 44dp
 */

@Composable
fun AssistantChatMessageFooter(
    modifier: Modifier = Modifier,
    liked: Boolean = false,
    disliked: Boolean = false,
    onCopy: () -> Unit = {},
    onLike: () -> Unit = {},
    onDislike: () -> Unit = {},
    onReadAloud: () -> Unit = {},
    onShare: () -> Unit = {},
    onRegenerate: () -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = DsSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FooterAction(androidx.compose.material.icons.Icons.Filled.ContentCopy, "复制", onCopy)
        Spacer(Modifier.size(12.dp))
        FooterAction(
            Icons.Filled.ThumbUp,
            "有帮助",
            onLike,
            active = liked,
        )
        Spacer(Modifier.size(12.dp))
        FooterAction(
            Icons.Filled.ThumbDown,
            "没帮助",
            onDislike,
            active = disliked,
        )
        Spacer(Modifier.size(12.dp))
        FooterAction(Icons.AutoMirrored.Filled.VolumeUp, "朗读", onReadAloud)
        Spacer(Modifier.size(12.dp))
        FooterAction(Icons.AutoMirrored.Filled.Reply, "分享", onShare)

        Spacer(Modifier.weight(1f))
        FooterAction(Icons.Filled.Refresh, "重新生成", onRegenerate)
    }
}

@Composable
private fun FooterAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val tint: Color = if (active) DshTheme.p.brand else DshTheme.p.textSecondary
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** 用户消息的操作栏（原包 UserMessageActionView.kt）：只有复制 / 重新生成 */
@Composable
fun UserMessageActionView(
    modifier: Modifier = Modifier,
    onCopy: () -> Unit = {},
    onRegenerate: () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FooterAction(Icons.Filled.ContentCopy, "复制", onCopy)
        Spacer(Modifier.size(12.dp))
        FooterAction(Icons.Filled.Refresh, "重新生成", onRegenerate)
    }
}
