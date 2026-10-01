package com.minis.dshconsole.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 尺寸与排版令牌 —— 全部由真机截图量出（1216×2640，按 3x 密度换算）
 *
 *   卡片左右边距   56 px → 18 dp
 *   卡片圆角       ~39 px → 12 dp
 *   列表行高       167 px → 56 dp
 *   行标题字高     52 px → 17 sp
 *   欢迎语字高     65 px → 22 sp
 *   胶囊高         111 px → 36 dp
 *   搜索框高       162 px → 52 dp
 *
 * 排版阶梯对应原包各 AppXxxDefaults.textStyle
 * （AppButton.kt / AppAlertDialogV2.kt / ContextMenu.kt / AppSelectableChip.kt …）。
 */

object DsType {
    /** 页面大标题（“设置”） */
    val pageTitle = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)

    /** 空态问候语（“嗨！今天想聊些什么？”）—— 截图量得 65px/3 ≈ 22sp */
    val greeting = TextStyle(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)

    /** 分组标签（“账户 / 应用 / 语音 / 关于”） */
    val sectionLabel = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal)

    /** 列表行标题（“账号管理”）—— 截图量得 52px/3 ≈ 17sp */
    val rowTitle = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal)

    /** 列表行值（“2.6.1(279)”） */
    val rowValue = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)

    /** 列表行副标题 */
    val rowSubtitle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

    /** 会话列表条目标题 */
    val sessionTitle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)

    /** 搜索框占位 */
    val searchPlaceholder = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)

    /** 胶囊文字 */
    val chip = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)

    /** 正文（Markdown body） */
    val body = TextStyle(fontSize = 15.sp, lineHeight = 24.sp)
    val bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)

    /** 代码 */
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 19.sp)

    /** 过程行（思考 / 工具） */
    val trace = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
}

val DsTypography = Typography(
    titleLarge = DsType.pageTitle,
    bodyLarge = DsType.body,
    bodyMedium = DsType.bodySmall,
    labelLarge = DsType.rowValue,
)

val DsShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 具体圆角 */
object DsRadius {
    /** 分组卡片 —— 截图量得 ~39px/3 ≈ 12dp */
    val card = 12.dp

    /** 大卡片 / 底部弹层 */
    val sheet = 20.dp

    /** 搜索框、输入框 */
    val pill = 26.dp

    /** 消息气泡 */
    val bubble = 18.dp
    val bubbleTail = 6.dp

    /** 代码块 */
    val code = 12.dp
}

/** 间距 —— 与截图量的像素一一对应 */
object DsSpacing {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp

    /** 页面左右边距 —— 截图量得 56px/3 ≈ 18dp */
    val screenH: Dp = 18.dp

    /** 卡片内行内容左右内边距 */
    val cardInset: Dp = 18.dp

    /** 列表行高 —— 截图量得 167px/3 ≈ 56dp */
    val rowHeight: Dp = 56.dp

    /** 行内图标尺寸（截图量得 51px 字形 ≈ 20dp 图标） */
    val rowIcon: Dp = 20.dp

    /** 图标与文字间距（截图量得 62px/3 ≈ 20dp） */
    val iconGap: Dp = 20.dp

    /** 分组标签上下留白 */
    val labelTop: Dp = 8.dp
    val labelBottom: Dp = 8.dp

    /** 卡片之间的竖直间距 */
    val cardGap: Dp = 20.dp

    /** 搜索框高 —— 截图量得 162px/3 = 54dp → 取 52dp */
    val searchHeight: Dp = 52.dp

    /** 胶囊高 —— 截图量得 111px/3 = 37dp → 取 36dp */
    val chipHeight: Dp = 36.dp

    /** 最小触区 */
    val touch: Dp = 44.dp
}

/** 动画时长 */
object DsMotion {
    const val fast = 120
    const val normal = 220
    const val slow = 320
    const val shimmer = 1200
}
