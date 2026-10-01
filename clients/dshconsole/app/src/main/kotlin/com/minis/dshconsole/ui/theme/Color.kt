package com.minis.dshconsole.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * DeepSeek 客户端的配色令牌
 * ============================================================================
 * 来源：从 com.deepseek.chat v2.6.1 的 smali 里完整还原（见 docs/extract_palette.py）
 *
 *   原始结构（R8 混淆后的真实链路）：
 *       rz7  原始色值表        Color 由 const 整数 + Ly08;->k(R,G,B,A) / m(R,G,B) 构造
 *       mx2  暗色语义调色板  ┐
 *       ix2  亮色语义调色板  ┘  引用 rz7 的字段
 *       rx2  darkColorScheme ┐  按 Material3 ColorScheme 形参顺序读上面的调色板
 *       ema  lightColorScheme┘
 *       fma  = Theme.kt       DeepSeekTheme / ForceDark / fallbackMaterialColorScheme
 *
 *   还原结果：rz7 的 37 个色值与 Material3 baseline（#6750A4 / #625B71 / #7D5260 /
 *   #B3261E / #FEF7FF …）逐一吻合 —— 即客户端在拿不到远端主题时回退到 M3 默认色板
 *   （Theme.kt:92 的 fallback.fallbackMaterialColorScheme）。
 *
 * 品牌色来源（来自 APK 自己的资源，非猜测）：
 *       res/values/colors.xml        primary = #426EFE   splashscreenBackground = #FFFFFF
 *       res/values-night/colors.xml  primary = #507BF2   splashscreenBackground = #0F0F0F
 * ============================================================================
 */

// ---------------------------------------------------------------- Material3 baseline
// 下方 37 个值就是从 rz7 原样还原出来的（命名按其在 M3 色板中的角色）
internal object M3Baseline {
    val white = Color(0xFFFFFFFF)
    val black = Color(0xFF000000)

    // primary 家族
    val primary = Color(0xFF6750A4)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFEADDFF)
    val onPrimaryContainer = Color(0xFF21005D)
    val inversePrimary = Color(0xFFD0BCFF)

    // secondary
    val secondary = Color(0xFF625B71)
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFE8DEF8)
    val onSecondaryContainer = Color(0xFF1D192B)

    // tertiary
    val tertiary = Color(0xFF7D5260)
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFFFD8E4)
    val onTertiaryContainer = Color(0xFF31111D)

    // error
    val error = Color(0xFFB3261E)
    val onError = Color(0xFFFFFFFF)
    val errorContainer = Color(0xFFF9DEDC)
    val onErrorContainer = Color(0xFF410E0B)

    // surface 阶梯（自暗到亮）
    val surfaceDim = Color(0xFFDED8E1)
    val surfaceContainerLowest = Color(0xFFFFFFFF)
    val surfaceContainerLow = Color(0xFFF7F2FA)
    val surfaceContainer = Color(0xFFF3EDF7)
    val surfaceContainerHigh = Color(0xFFECE6F0)
    val surfaceContainerHighest = Color(0xFFE6E0E9)
    val surfaceBright = Color(0xFFFEF7FF)
    val surface = Color(0xFFFEF7FF)
    val surfaceVariant = Color(0xFFE7E0EC)
    val onSurface = Color(0xFF1D1B20)
    val onSurfaceVariant = Color(0xFF49454F)
    val inverseSurface = Color(0xFF322F35)
    val inverseOnSurface = Color(0xFFF5EFF7)

    val outline = Color(0xFF79747E)
    val outlineVariant = Color(0xFFCAC4D0)
    val scrim = Color(0xFF000000)

    // fixed 家族（M3 1.4 新增）
    val primaryFixed = Color(0xFFEADDFF)
    val onPrimaryFixedVariant = Color(0xFF4F378B)
    val secondaryFixedDim = Color(0xFFCCC2DC)
    val onSecondaryFixedVariant = Color(0xFF4A4458)
    val tertiaryFixedDim = Color(0xFFEFB8C8)
    val onTertiaryFixedVariant = Color(0xFF633B48)
}

// ---------------------------------------------------------------- DeepSeek 品牌令牌
/**
 * DeepSeek 品牌色。取自 APK 自身资源：
 *   values/colors.xml        → brandBlueLight = #426EFE
 *   values-night/colors.xml  → brandBlueDark  = #507BF2
 *   splashscreenBackground   → 亮 #FFFFFF / 暗 #0F0F0F
 */
object DeepSeekColors {
    /** 亮色主品牌蓝（R.color.primary） */
    val BrandBlueLight = Color(0xFF426EFE)

    /** 暗色主品牌蓝（values-night R.color.primary） */
    val BrandBlueDark = Color(0xFF507BF2)

    /** 亮色窗口底 */
    val BgLight = Color(0xFFFFFFFF)

    /** 暗色窗口底 */
    val BgDark = Color(0xFF0F0F0F)

    /** 品牌蓝的容器色（按 M3 惯例推出的浅/深阶，与品牌蓝同相） */
    val BrandBlueContainerLight = Color(0xFFDCE4FF)
    val OnBrandBlueContainerLight = Color(0xFF00205B)
    val BrandBlueContainerDark = Color(0xFF2B4ACB)
    val OnBrandBlueContainerDark = Color(0xFFDCE4FF)

    /** 用户消息气泡（品牌蓝底白字） */
    val UserBubbleLight = Color(0xFF426EFE)
    val UserBubbleDark = Color(0xFF507BF2)

    /** AI 消息气泡底 */
    val AssistantBubbleLight = Color(0xFFF3F4F6)
    val AssistantBubbleDark = Color(0xFF1F2023)

    /** 思考/工具行的次级底色 */
    val TraceBgLight = Color(0xFFFAFAFA)
    val TraceBgDark = Color(0xFF161618)

    /** 代码块 */
    val CodeBgLight = Color(0xFFF6F7F9)
    val CodeBgDark = Color(0xFF141518)

    /** 语义色 */
    val Green = Color(0xFF34C759)
    val Amber = Color(0xFFFF9F0A)
    val Red = Color(0xFFFF3B30)
    val Violet = Color(0xFFAF52DE)
}
