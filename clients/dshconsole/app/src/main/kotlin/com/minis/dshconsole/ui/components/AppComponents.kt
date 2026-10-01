package com.minis.dshconsole.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.theme.AppTypography
import com.minis.dshconsole.ui.theme.Radii
import com.minis.dshconsole.ui.theme.Spacing

/*
 * 基础组件 —— 对应 DeepSeek 原包 com.deepseek.chat.ui.components.*
 *   AppButton.kt / AppIconButton.kt / AppTextButton.kt / AppTopBar.kt
 *   AppBanner.kt / AppSelectableChip.kt / SettingItem.kt / AppCard
 */

// ------------------------------------------------------------------ 按钮

enum class AppButtonStyle { Primary, Secondary, Outlined, Text }

/**
 * 对应 AppButton.kt。
 * 尺寸阶梯与原包一致：extraSmall 11sp / small 13sp / medium 14sp / large 16sp
 */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AppButtonStyle = AppButtonStyle.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scheme = MaterialTheme.colorScheme

    val (bg, fg, border) = when (style) {
        AppButtonStyle.Primary -> Triple(scheme.primary, scheme.onPrimary, Color.Transparent)
        AppButtonStyle.Secondary -> Triple(scheme.secondaryContainer, scheme.onSecondaryContainer, Color.Transparent)
        AppButtonStyle.Outlined -> Triple(Color.Transparent, scheme.primary, scheme.outlineVariant)
        AppButtonStyle.Text -> Triple(Color.Transparent, scheme.primary, Color.Transparent)
    }
    val alpha = when {
        !enabled -> 0.38f
        pressed -> 0.80f
        else -> 1f
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(bg.copy(alpha = bg.alpha * alpha))
            .then(if (border != Color.Transparent) Modifier.border(1.dp, border, RoundedCornerShape(Radii.pill)) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .heightIn(min = Spacing.touch)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.s2))
        }
        Text(text, style = AppTypography.medium, color = fg)
    }
}

/** 对应 AppIconButton.kt —— 顶栏上的图标按钮，触区 44dp */
@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Box(
        modifier = modifier
            .size(Spacing.touch)
            .clip(RoundedCornerShape(Radii.pill))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

// ------------------------------------------------------------------ 卡片

/** 对应原包里的 AppCard / 设置分组卡片 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.card))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.s4),
        content = { content() },
    )
}

// ------------------------------------------------------------------ 列表项

/** 对应 SettingItem.kt */
@Composable
fun SettingItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.screenH, vertical = Spacing.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Spacing.s3))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AppTypography.markdownBody,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = AppTypography.markdownBodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.s3))
            trailing()
        }
    }
}

// ------------------------------------------------------------------ 横幅

/** 对应 AppBanner.kt */
@Composable
fun AppBanner(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.errorContainer,
    contentColor: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.chip))
            .background(containerColor)
            .padding(horizontal = Spacing.s3, vertical = Spacing.s3),
    ) {
        Text(title, style = AppTypography.bannerTitle, color = contentColor)
        if (description != null) {
            Spacer(Modifier.height(2.dp))
            Text(description, style = AppTypography.bannerDescription, color = contentColor.copy(alpha = 0.85f))
        }
    }
}

// ------------------------------------------------------------------ 胶囊

/** 对应 AppSelectableChip.kt */
@Composable
fun AppChip(
    text: String,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (selected) scheme.primary else scheme.surfaceContainerHigh
    val fg = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.s3, vertical = 7.dp),
    ) {
        Text(text, style = AppTypography.chip, color = fg)
    }
}

// ------------------------------------------------------------------ 顶栏

/**
 * 对应 AppTopBar.kt / ChatPageTopBar.kt
 * 左：菜单/返回  中：标题 + 副标题  右：操作区
 */
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    actions: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(scheme.surface)
                .heightIn(min = 56.dp)
                .padding(horizontal = Spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                onBack != null -> AppIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack,
                )
                else -> Spacer(Modifier.width(Spacing.s2))
            }

            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.s2),
            ) {
                Text(
                    title,
                    style = AppTypography.topBarTitle,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = AppTypography.markdownBodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (actions != null) actions() else Spacer(Modifier.width(Spacing.s2))
        }
    }
}
