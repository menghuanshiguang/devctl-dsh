package com.minis.dshconsole.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minis.dshconsole.ui.theme.DsColor
import com.minis.dshconsole.ui.theme.DsRadius
import com.minis.dshconsole.ui.theme.DsSpacing
import com.minis.dshconsole.ui.theme.DsType
import com.minis.dshconsole.ui.theme.DshTheme

/*
 * 组件库 —— 全部按真机截图实测的尺寸/配色实现
 *
 * 对应 DeepSeek 原包 com.deepseek.chat.ui.components.*
 *   AppTopBar.kt / AppButton.kt / AppIconButton.kt / SettingItem.kt
 *   AppSelectableChip.kt / AppSearchBar / AppCard
 */

/** 分组标签 —— 截图里“账户 / 应用 / 语音 / 关于”那一行 */
@Composable
fun DsSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = DsType.sectionLabel,
        color = DshTheme.p.textSecondary,
        modifier = modifier.padding(
            start = DsSpacing.screenH,
            end = DsSpacing.screenH,
            top = DsSpacing.labelTop,
            bottom = DsSpacing.labelBottom,
        ),
    )
}

/** 白色分组卡片 —— 截图里圆角 12dp、左右各留 18dp 的白块 */
@Composable
fun DsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.screenH)
            .clip(RoundedCornerShape(DsRadius.card))
            .background(DshTheme.p.surface),
        content = content,
    )
}

/**
 * 列表行 —— 截图实测布局：
 *   [左内边距 18dp][图标 20dp][间隔 20dp][标题 17sp][弹性][值 15sp 灰][chevron][右内边距]
 *   行高 56dp
 */
@Composable
fun DsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val p = DshTheme.p
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = DsSpacing.rowHeight)
            .padding(horizontal = DsSpacing.cardInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = p.textPrimary, modifier = Modifier.size(DsSpacing.rowIcon))
            Spacer(Modifier.width(DsSpacing.iconGap))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = DsType.rowTitle,
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = DsType.rowSubtitle,
                    color = p.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(DsSpacing.s2))
            Text(value, style = DsType.rowValue, color = p.textSecondary, maxLines = 1)
        }
        if (trailing != null) {
            Spacer(Modifier.width(DsSpacing.s1))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(DsSpacing.s2))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = p.textPlaceholder,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 卡片内的细分隔线 */
@Composable
fun DsRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = DsSpacing.cardInset + DsSpacing.rowIcon + DsSpacing.iconGap)
            .height(0.6.dp)
            .background(DshTheme.p.divider)
    )
}

/**
 * 搜索框 —— 截图实测：高 52dp、全圆角、底色 #F5F5F5、前置放大镜、占位 #8F9094
 */
@Composable
fun DsSearchBar(
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
) {
    val p = DshTheme.p
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpacing.screenH)
            .height(DsSpacing.searchHeight)
            .clip(RoundedCornerShape(DsRadius.pill))
            .background(p.fill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = DsSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = p.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(DsSpacing.s3))
        Text(placeholder, style = DsType.searchPlaceholder, color = p.textSecondary)
    }
}

/**
 * 胶囊 —— 截图里“深度思考 / 智能搜索”那种：
 * 高 36dp、底 #ECF2FE、文字与图标用品牌蓝
 */
@Composable
fun DsChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val p = DshTheme.p
    val bg = if (selected) p.brandSoft else p.fill
    val fg = if (selected) p.brand else p.textSecondary
    Row(
        modifier = modifier
            .height(DsSpacing.chipHeight)
            .clip(RoundedCornerShape(DsRadius.pill))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = DsSpacing.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = DsType.chip, color = fg)
    }
}

/** 圆形图标按钮 —— 截图右上角“⊕ / 语音”、左上角“≡”都是这个 */
@Composable
fun DsCircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = DsSpacing.touch,
    container: Color = Color.Transparent,
    tint: Color = Color.Unspecified,
) {
    Box(
        modifier = modifier
            // requiredSize：忽略父级约束，保证圆形按钮不会被 Row 挤扁
            .requiredSize(size)
            .clip(CircleShape)
            .background(container)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription,
            tint = if (tint == Color.Unspecified) DshTheme.p.textPrimary else tint,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * 顶栏 —— 截图实测：高 56dp，标题居中 17sp Semibold，
 * 返回键是 40dp 圆形浅灰底（设置页左上角那个）
 */
@Composable
fun DsTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    left: @Composable (() -> Unit)? = null,
    right: @Composable (() -> Unit)? = null,
    centerTitle: Boolean = false,
) {
    val p = DshTheme.p
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(p.bg)
            .padding(horizontal = DsSpacing.s2),
    ) {
        if (left != null) {
            Box(Modifier.align(Alignment.CenterStart)) { left() }
        }
        Column(
            Modifier
                .align(if (centerTitle) Alignment.Center else Alignment.CenterStart)
                .padding(start = if (centerTitle) 0.dp else 44.dp)
                .padding(horizontal = DsSpacing.s2),
            horizontalAlignment = if (centerTitle) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(title, style = DsType.pageTitle, color = p.textPrimary, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, style = DsType.rowSubtitle, color = p.textSecondary, maxLines = 1)
            }
        }
        if (right != null) {
            Row(
                Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpacing.s1),
            ) { right() }
        }
    }
}

/** 主按钮 —— 品牌蓝实心、全圆角、高 44dp */
@Composable
fun DsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    outlined: Boolean = false,
) {
    val p = DshTheme.p
    val bg = when {
        !enabled -> p.fill
        outlined -> Color.Transparent
        else -> p.brand
    }
    val fg = when {
        !enabled -> p.textPlaceholder
        outlined -> p.brand
        else -> p.onBrand
    }
    Box(
        modifier = modifier
            .height(DsSpacing.touch)
            .clip(RoundedCornerShape(DsRadius.pill))
            .background(bg)
            .then(
                if (outlined) Modifier.border(1.dp, p.divider, RoundedCornerShape(DsRadius.pill))
                else Modifier
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = DsSpacing.s5),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DsType.rowValue, color = fg)
    }
}

/** 底栏头像 + 用户名 —— 截图会话列表底部那一行 */
@Composable
fun DsBottomAccountRow(
    name: String,
    modifier: Modifier = Modifier,
    avatar: @Composable () -> Unit,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = DsSpacing.screenH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(DsColor.FillStrong)) { avatar() }
        Spacer(Modifier.width(DsSpacing.s3))
        Text(
            name,
            style = DsType.sessionTitle,
            color = DshTheme.p.textPrimary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}
