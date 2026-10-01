package com.minis.dshconsole.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * DeepSeek 客户端配色令牌 —— 全部来自**实测**，不是推测
 * ============================================================================
 * 两个来源互相印证：
 *
 * ① 从 APK 里逐层还原（docs/extract_palette.py）
 *      rz7 原始色值表 → mx2/ix2 语义调色板 → rx2/ema → fma(=Theme.kt)
 *      kx2 = 运行时的语义色板（蓝种子 Material3 方案）
 *      res/values/colors.xml        primary=#426EFE  splash=#FFFFFF
 *      res/values-night/colors.xml  primary=#507BF2  splash=#0F0F0F
 *
 * ② 从真机截图逐像素采样（1216×2640，3x 密度）
 *      主文字        #0F0F0F   （18227 px）
 *      次级文字      #8F9094   （ 6958 px）
 *      占位文字      #B9BABC   （ 4544 px）
 *      页面底(列表)  #FFFFFF
 *      页面底(设置)  #F8F8F8
 *      卡片底        #FFFFFF
 *      品牌蓝        #416EFD   ≈ 资源里的 #426EFE（JPEG 误差）
 *      胶囊底        #ECF2FE
 *      搜索框底      #F5F5F5
 *      分割线        #EDEDED
 *
 * 结论：这是**中性白灰 + 单一品牌蓝点缀**的体系，
 *      不是 Material3 默认的紫色 baseline（那只是拿不到远端主题时的 fallback）。
 * ============================================================================
 */

object DsColor {

    // ---------------------------------------------------------- 品牌
    /** 亮色品牌蓝 —— values/colors.xml 的 R.color.primary（截图像素 #416EFD 印证） */
    val Brand = Color(0xFF426EFE)

    /** 暗色品牌蓝 —— values-night/colors.xml */
    val BrandNight = Color(0xFF507BF2)

    /** 胶囊/标签的淡蓝底（截图实测 #ECF2FE） */
    val BrandSoft = Color(0xFFECF2FE)

    /** 来自 kx2 色板的深/浅蓝（Material3 蓝种子方案） */
    val BlueDark = Color(0xFF004CA7)
    val BlueDarkest = Color(0xFF002E6A)
    val BlueMid = Color(0xFF2170E4)
    val BlueSoft2 = Color(0xFFE6ECFF)
    val BlueBright = Color(0xFF005AC2)

    /** 暗色下的淡蓝底（品牌蓝低透明度铺在 #0F0F0F 上的效果） */
    val BrandBlueNightSoft = Color(0xFF1B2A4A)

    // ---------------------------------------------------------- 中性
    /** 主文字（截图实测 #0F0F0F，占比最高） */
    val TextPrimary = Color(0xFF0F0F0F)

    /** 次级文字 / 分组标签 / 行值（截图实测 #8F9094） */
    val TextSecondary = Color(0xFF8F9094)

    /** 输入占位（截图实测 #B9BABC） */
    val TextPlaceholder = Color(0xFFB9BABC)

    /** 纯白 */
    val White = Color(0xFFFFFFFF)

    /** 列表/聊天页底色 */
    val BgCanvas = Color(0xFFFFFFFF)

    /** 设置页底色（靠卡片区分层级的页面） */
    val BgGrouped = Color(0xFFF8F8F8)

    /** 卡片 */
    val Surface = Color(0xFFFFFFFF)

    /** 搜索框 / 输入框填充 */
    val Fill = Color(0xFFF5F5F5)

    /** 分隔线 */
    val Divider = Color(0xFFEDEDED)

    /** 更深的填充（按压态 / 次级按钮） */
    val FillStrong = Color(0xFFE1E1E1)

    // ---------------------------------------------------------- 暗色
    val DarkBg = Color(0xFF0F0F0F)
    val DarkSurface = Color(0xFF171717)
    val DarkSurfaceHigh = Color(0xFF262628)
    val DarkFill = Color(0xFF1F1F1F)
    val DarkTextPrimary = Color(0xFFEDEDED)
    val DarkTextSecondary = Color(0xFF8C909F)
    val DarkDivider = Color(0xFF2A2A2A)

    // ---------------------------------------------------------- 语义
    /** 来自 kx2 的 error 家族 */
    val Danger = Color(0xFF93000A)
    val DangerContainer = Color(0xFFFFDAD6)
    val OnDangerContainer = Color(0xFF410002)

    val Success = Color(0xFF1B7740)
    val Warning = Color(0xFF8F6A00)
}

/** 明暗两套语义令牌，运行时按 dark 选择 */
data class DshPalette(
    val bg: Color,
    val surface: Color,
    val fill: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textPlaceholder: Color,
    val brand: Color,
    val brandSoft: Color,
    val onBrand: Color,
    val userBubble: Color,
    val onUserBubble: Color,
    val assistantBubble: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
)

val LightPalette = DshPalette(
    bg = DsColor.BgCanvas,
    surface = DsColor.Surface,
    fill = DsColor.Fill,
    divider = DsColor.Divider,
    textPrimary = DsColor.TextPrimary,
    textSecondary = DsColor.TextSecondary,
    textPlaceholder = DsColor.TextPlaceholder,
    brand = DsColor.Brand,
    brandSoft = DsColor.BrandSoft,
    onBrand = DsColor.White,
    userBubble = DsColor.Brand,
    onUserBubble = DsColor.White,
    assistantBubble = DsColor.Fill,
    danger = DsColor.Danger,
    success = DsColor.Success,
    warning = DsColor.Warning,
)

val DarkPalette = DshPalette(
    bg = DsColor.DarkBg,
    surface = DsColor.DarkSurface,
    fill = DsColor.DarkFill,
    divider = DsColor.DarkDivider,
    textPrimary = DsColor.DarkTextPrimary,
    textSecondary = DsColor.DarkTextSecondary,
    textPlaceholder = DsColor.DarkTextSecondary.copy(alpha = 0.7f),
    brand = DsColor.BrandNight,
    brandSoft = DsColor.BrandBlueNightSoft,
    onBrand = DsColor.White,
    userBubble = DsColor.BrandNight,
    onUserBubble = DsColor.White,
    assistantBubble = DsColor.DarkSurfaceHigh,
    danger = Color(0xFFFFB4AB),
    success = Color(0xFF7FD69A),
    warning = Color(0xFFF0C24B),
)
