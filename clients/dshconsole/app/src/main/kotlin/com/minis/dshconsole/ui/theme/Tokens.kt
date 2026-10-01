package com.minis.dshconsole.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 排版 / 形状 / 间距令牌
 *
 * 原包对应文件（从源码位置字符串还原）：
 *   com.deepseek.chat.ui.markdown.model.MarkdownTypography.kt
 *       :84  defaultBodyTextStyle
 *       :108 defaultBodySmallTextStyle
 *   com.deepseek.chat.ui.components.button.AppButton.kt
 *       :102 extraSmallTextStyle  :113 smallTextStyle  :124 mediumTextStyle
 *       :135 largeTextStyle       :235 textStyle
 *   com.deepseek.chat.ui.components.dialogv2.AppAlertDialogV2.kt
 *       :108 titleTextStyle  :121 messageTextStyle  :134 actionTextStyle
 *   com.deepseek.chat.ui.components.menu.ContextMenu.kt:430 textStyle
 *   com.deepseek.chat.ui.components.chip.AppSelectableChip.kt:33 textStyle
 *   com.deepseek.chat.ui.components.alert.AppAlert.kt:167/:178
 *   com.deepseek.chat.ui.components.banner.AppBanner.kt:109/:119
 *   com.deepseek.chat.ui.common.ComponentLocals.kt:33 ProvideContentColorTextStyle
 */

// ------------------------------------------------------------------ 排版

/** 组件级文本样式（对应原包各 AppXxxDefaults 里的 textStyle） */
object AppTypography {
    /** AppButton.kt:102 */
    val extraSmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    /** AppButton.kt:113 */
    val small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
    /** AppButton.kt:124 */
    val medium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    /** AppButton.kt:135 */
    val large = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)

    /** AppAlertDialogV2.kt:108 */
    val dialogTitle = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)
    /** AppAlertDialogV2.kt:121 */
    val dialogMessage = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    /** AppAlertDialogV2.kt:134 */
    val dialogAction = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

    /** AppAlert.kt:167 / :178 */
    val alertTitle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val alertDescription = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)

    /** AppBanner.kt:109 / :119 */
    val bannerTitle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val bannerDescription = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)

    /** ContextMenu.kt:430 */
    val menuItem = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)

    /** AppSelectableChip.kt:33 */
    val chip = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

    /** 顶栏标题（ChatPageTopBar.kt） */
    val topBarTitle = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)

    /** 正文 —— 对应 MarkdownTypography.kt:84 */
    val markdownBody = TextStyle(fontSize = 15.sp, lineHeight = 24.sp)
    /** 正文小号 —— 对应 MarkdownTypography.kt:108 */
    val markdownBodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)

    /** 代码块（MarkdownCodeBlockHeader.kt 配套正文） */
    val code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        lineHeight = 19.sp,
    )

    /** 思考行 / 工具行摘要 */
    val traceSummary = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
}

/** 对应 Material3 Typography，用于 MaterialTheme 内建控件 */
val DshTypography = Typography(
    titleLarge = AppTypography.topBarTitle,
    bodyLarge = AppTypography.markdownBody,
    bodyMedium = AppTypography.markdownBodySmall,
    labelLarge = AppTypography.medium,
)

// ------------------------------------------------------------------ 形状

val DshShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 界面里反复用到的具体圆角 */
object Radii {
    val card = 14.dp
    val bubble = 18.dp
    val bubbleTail = 6.dp
    val chip = 10.dp
    val pill = 22.dp
    val input = 22.dp
    val sheet = 20.dp
    val code = 12.dp
}

// ------------------------------------------------------------------ 间距

object Spacing {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp

    /** 屏幕左右安全边距 */
    val screenH: Dp = 16.dp

    /** 消息列表左右边距 */
    val messageH: Dp = 16.dp

    /** 相邻消息之间的竖向间距 */
    val messageGap: Dp = 16.dp

    /** 同一轮里过程行之间的间距（工具行之间 6dp） */
    val traceGap: Dp = 6.dp

    /** 过程组与正文之间 */
    val groupGap: Dp = 12.dp

    /** 最小可点区域 */
    val touch: Dp = 44.dp
}

/** 常用的动画时长（与 Compose 默认动画曲线配套） */
object Motion {
    const val fast = 120
    const val normal = 220
    const val slow = 320
    const val shimmer = 1200
}
