package com.suseoaa.locationspoofer.ui.theme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color

// 基础主色调与中性色
// 深色调色板
val DarkBg = Color(0xFF0D1117)
val SurfaceDark = Color(0xFF161B22)
val SurfaceCardDark = Color(0xFF1C2333)
val SurfaceVariantDark = Color(0xFF21262D)
val DividerColorDark = Color(0xFF30363D)
val TextPrimaryDark = Color(0xFFE6EDF3)
val TextSecondaryDark = Color(0xFF8B949E)

// 浅色调色板
val LightBg = Color(0xFFF6F8FA)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceCardLight = Color(0xFFFFFFFF)
val SurfaceCardCustomLight = Color(0xFFF3F6F9)
val SurfaceVariantLight = Color(0xFFEEF2F6)
val DividerColorLight = Color(0xFFD0D7DE)
val TextPrimaryLight = Color(0xFF24292F)
val TextSecondaryLight = Color(0xFF57606A)

// 强调品牌色
val AccentBlue = Color(0xFF388BFD)
val AccentBlueContainerLight = Color(0xFFE8F1FF)
val AccentBlueContainerDark = Color(0xFF143058)
val AccentGreen = Color(0xFF2EA043)
val AccentOrange = Color(0xFFD29922)
val ErrorRed = Color(0xFFF85149)
val ErrorRedLight = Color(0xFFCF222E)
val ErrorContainerLight = Color(0xFFFFEBE9)
val ErrorContainerDark = Color(0xFF4C1817)

// 全局配色方案
val AppColorSchemeLight = lightColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = AccentBlueContainerLight,
    onPrimaryContainer = Color(0xFF0B4FB3),
    inversePrimary = Color(0xFF79B8FF),

    secondary = AccentGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6F6EC),
    onSecondaryContainer = Color(0xFF0E6221),

    tertiary = AccentOrange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF4D4),
    onTertiaryContainer = Color(0xFF734C00),

    background = LightBg,
    onBackground = TextPrimaryLight,

    surface = SurfaceCardLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondaryLight,
    surfaceTint = AccentBlue,
    inverseSurface = Color(0xFF24292F),
    inverseOnSurface = Color(0xFFF6F8FA),

    error = ErrorRedLight,
    onError = Color.White,
    errorContainer = ErrorContainerLight,
    onErrorContainer = Color(0xFF82071E),

    outline = DividerColorLight,
    outlineVariant = Color(0xFFE1E4E8),
    scrim = Color(0x99000000)
)

val AppColorSchemeDark = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = AccentBlueContainerDark,
    onPrimaryContainer = Color(0xFFC8E1FF),
    inversePrimary = Color(0xFF0052CC),

    secondary = AccentGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF163E21),
    onSecondaryContainer = Color(0xFF9AE6B4),

    tertiary = AccentOrange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF4C3800),
    onTertiaryContainer = Color(0xFFFFDF9E),

    background = DarkBg,
    onBackground = TextPrimaryDark,

    surface = SurfaceCardDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    surfaceTint = AccentBlue,
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF0D1117),

    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorContainerDark,
    onErrorContainer = Color(0xFFFFD1D0),

    outline = DividerColorDark,
    outlineVariant = Color(0xFF21262D),
    scrim = Color(0xCC000000)
)

object AppColors {
    fun textSecondary(isDark: Boolean) = if (isDark) TextSecondaryDark else TextSecondaryLight
    fun surface(isDark: Boolean) = if (isDark) SurfaceDark else SurfaceLight
    fun cardBackground(isDark: Boolean) = if (isDark) SurfaceCardDark else SurfaceCardCustomLight
    fun topBarBackground(isDark: Boolean) = if (isDark) SurfaceDark else SurfaceCardCustomLight
    fun background(isDark: Boolean) = if (isDark) DarkBg else LightBg
}

/**
 * 彻底取消点击水波纹/高亮特效的全局 Modifier 扩展
 */
fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}


