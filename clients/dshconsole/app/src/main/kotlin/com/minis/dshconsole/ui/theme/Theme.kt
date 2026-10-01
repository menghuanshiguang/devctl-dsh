package com.minis.dshconsole.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/*
 * 对应 DeepSeek 原包 com.deepseek.chat.ui.theme.DeepSeekTheme (Theme.kt)
 *
 * 原包 Theme.kt 的结构（由 Compose 源码位置字符串还原）：
 *   :28  DeepSeekTheme(…)             主题入口
 *   :38  DeepSeekTheme.<anonymous>
 *   :49  DeepSeekTheme.<anonymous>.<anonymous>
 *   :56  ForceDark(…)                 强制暗色
 *   :64  DeepSeekTheme.colorSchemeV2  运行时色板（读 kx2）
 *   :92  fallback.fallbackMaterialColorScheme   拿不到远端主题时的回退
 *
 * 这里用同一套结构：MaterialTheme 承载 M3 内建控件，
 * 同时把实测出来的 DSH 语义令牌通过 CompositionLocal 下发。
 */

private val LightScheme = lightColorScheme(
    primary = DsColor.Brand,
    onPrimary = DsColor.White,
    primaryContainer = DsColor.BrandSoft,
    onPrimaryContainer = DsColor.BlueDarkest,

    secondary = DsColor.TextSecondary,
    onSecondary = DsColor.White,
    secondaryContainer = DsColor.Fill,
    onSecondaryContainer = DsColor.TextPrimary,

    tertiary = DsColor.BlueMid,
    onTertiary = DsColor.White,

    background = DsColor.BgCanvas,
    onBackground = DsColor.TextPrimary,
    surface = DsColor.Surface,
    onSurface = DsColor.TextPrimary,
    surfaceVariant = DsColor.Fill,
    onSurfaceVariant = DsColor.TextSecondary,

    surfaceContainerLowest = DsColor.White,
    surfaceContainerLow = DsColor.BgGrouped,
    surfaceContainer = DsColor.Fill,
    surfaceContainerHigh = DsColor.FillStrong,
    surfaceContainerHighest = DsColor.FillStrong,

    outline = DsColor.TextSecondary,
    outlineVariant = DsColor.Divider,
    scrim = Color(0x99000000),

    error = DsColor.Danger,
    onError = DsColor.White,
    errorContainer = DsColor.DangerContainer,
    onErrorContainer = DsColor.OnDangerContainer,
)

private val DarkScheme = darkColorScheme(
    primary = DsColor.BrandNight,
    onPrimary = DsColor.White,
    primaryContainer = DsColor.BrandBlueNightSoft,
    onPrimaryContainer = DsColor.BlueSoft2,

    background = DsColor.DarkBg,
    onBackground = DsColor.DarkTextPrimary,
    surface = DsColor.DarkSurface,
    onSurface = DsColor.DarkTextPrimary,
    surfaceVariant = DsColor.DarkSurfaceHigh,
    onSurfaceVariant = DsColor.DarkTextSecondary,

    surfaceContainerLowest = DsColor.DarkBg,
    surfaceContainerLow = DsColor.DarkSurface,
    surfaceContainer = DsColor.DarkFill,
    surfaceContainerHigh = DsColor.DarkSurfaceHigh,
    surfaceContainerHighest = Color(0xFF313133),

    outline = DsColor.DarkTextSecondary,
    outlineVariant = DsColor.DarkDivider,
    scrim = Color(0xCC000000),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LocalPalette = staticCompositionLocalOf { LightPalette }

/** 取 DSH 实测语义令牌 */
object DshTheme {
    val p: DshPalette
        @Composable get() = LocalPalette.current
}

@Composable
fun DshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val sysDensity = LocalDensity.current
    val palette = if (darkTheme) DarkPalette else LightPalette
    val scheme = if (darkTheme) DarkScheme else LightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.Transparent.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.Transparent.toArgb()
        }
    }

    CompositionLocalProvider(
        LocalPalette provides palette,
        // 字体大小设置：改 fontScale 会让所有 sp 同步缩放
        LocalDensity provides Density(sysDensity.density, fontScale),
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
