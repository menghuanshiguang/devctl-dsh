package com.minis.dshconsole.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/*
 * 对应 DeepSeek 原包的 com.deepseek.chat.ui.theme.DeepSeekTheme (Theme.kt)
 *
 * 原包 Theme.kt 结构（从源码位置字符串还原）：
 *    :28  DeepSeekTheme(…)                 主题入口 composable
 *    :38  DeepSeekTheme.<anonymous>        内部 lambda
 *    :49  DeepSeekTheme.<anonymous>.<anonymous>
 *    :56  ForceDark(…)                     强制暗色（跟随系统/跟随设置/强制）
 *    :64  DeepSeekTheme.colorSchemeV2      取色方案（v2）
 *    :92  fallback.fallbackMaterialColorScheme   拿不到远端主题时的回退色板
 */

/** 亮色方案：M3 baseline 打底，品牌蓝覆盖 primary 家族与窗口底 */
private val LightScheme: ColorScheme = lightColorScheme(
    primary = DeepSeekColors.BrandBlueLight,
    onPrimary = M3Baseline.white,
    primaryContainer = DeepSeekColors.BrandBlueContainerLight,
    onPrimaryContainer = DeepSeekColors.OnBrandBlueContainerLight,
    inversePrimary = M3Baseline.inversePrimary,

    secondary = M3Baseline.secondary,
    onSecondary = M3Baseline.onSecondary,
    secondaryContainer = M3Baseline.secondaryContainer,
    onSecondaryContainer = M3Baseline.onSecondaryContainer,

    tertiary = M3Baseline.tertiary,
    onTertiary = M3Baseline.onTertiary,
    tertiaryContainer = M3Baseline.tertiaryContainer,
    onTertiaryContainer = M3Baseline.onTertiaryContainer,

    background = DeepSeekColors.BgLight,
    onBackground = M3Baseline.onSurface,
    surface = DeepSeekColors.BgLight,
    onSurface = M3Baseline.onSurface,
    surfaceVariant = M3Baseline.surfaceVariant,
    onSurfaceVariant = M3Baseline.onSurfaceVariant,

    surfaceDim = M3Baseline.surfaceDim,
    surfaceBright = M3Baseline.surfaceBright,
    surfaceContainerLowest = M3Baseline.surfaceContainerLowest,
    surfaceContainerLow = M3Baseline.surfaceContainerLow,
    surfaceContainer = M3Baseline.surfaceContainer,
    surfaceContainerHigh = M3Baseline.surfaceContainerHigh,
    surfaceContainerHighest = M3Baseline.surfaceContainerHighest,

    inverseSurface = M3Baseline.inverseSurface,
    inverseOnSurface = M3Baseline.inverseOnSurface,

    error = M3Baseline.error,
    onError = M3Baseline.onError,
    errorContainer = M3Baseline.errorContainer,
    onErrorContainer = M3Baseline.onErrorContainer,

    outline = M3Baseline.outline,
    outlineVariant = M3Baseline.outlineVariant,
    scrim = M3Baseline.scrim,
)

/** 暗色方案：primary 换成 values-night 的 #507BF2，窗口底 #0F0F0F */
private val DarkScheme: ColorScheme = darkColorScheme(
    primary = DeepSeekColors.BrandBlueDark,
    onPrimary = M3Baseline.white,
    primaryContainer = DeepSeekColors.BrandBlueContainerDark,
    onPrimaryContainer = DeepSeekColors.OnBrandBlueContainerDark,
    inversePrimary = Color(0xFFB4C5FF),

    background = DeepSeekColors.BgDark,
    onBackground = Color(0xFFE6E1E5),
    surface = DeepSeekColors.BgDark,
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),

    surfaceDim = Color(0xFF0F0F0F),
    surfaceBright = Color(0xFF39393B),
    surfaceContainerLowest = Color(0xFF0A0A0C),
    surfaceContainerLow = Color(0xFF161618),
    surfaceContainer = Color(0xFF1B1B1D),
    surfaceContainerHigh = Color(0xFF262628),
    surfaceContainerHighest = Color(0xFF313133),

    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF313033),

    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),

    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    scrim = Color(0xFF000000),
)

/** DSH 客户端自己的扩展令牌（气泡、思考行、代码块等 M3 没覆盖的） */
data class DshExtendedColors(
    val userBubble: Color,
    val onUserBubble: Color,
    val assistantBubble: Color,
    val traceBackground: Color,
    val codeBackground: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val accentViolet: Color,
)

private val LocalDshColors = staticCompositionLocalOf<DshExtendedColors> {
    error("DshColors 未提供")
}

object DshTheme {
    val colors: DshExtendedColors
        @Composable get() = LocalDshColors.current
}

@Composable
fun DshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val ext = DshExtendedColors(
        userBubble = if (darkTheme) DeepSeekColors.UserBubbleDark else DeepSeekColors.UserBubbleLight,
        onUserBubble = Color.White,
        assistantBubble = if (darkTheme) DeepSeekColors.AssistantBubbleDark else DeepSeekColors.AssistantBubbleLight,
        traceBackground = if (darkTheme) DeepSeekColors.TraceBgDark else DeepSeekColors.TraceBgLight,
        codeBackground = if (darkTheme) DeepSeekColors.CodeBgDark else DeepSeekColors.CodeBgLight,
        success = DeepSeekColors.Green,
        warning = DeepSeekColors.Amber,
        danger = DeepSeekColors.Red,
        accentViolet = DeepSeekColors.Violet,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            @Suppress("DEPRECATION")
            window.statusBarColor = scheme.surface.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = scheme.surface.toArgb()
        }
    }

    CompositionLocalProvider(LocalDshColors provides ext) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
