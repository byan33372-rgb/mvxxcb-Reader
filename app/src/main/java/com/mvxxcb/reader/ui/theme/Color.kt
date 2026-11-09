package com.mvxxcb.reader.ui.theme

// WP-U（04 §4）：M3 Token 体系 —— 颜色。明暗两套 ColorScheme。

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// —— 品牌色板（墨青调，贴合阅读器气质） ——
val BluePrimary10 = Color(0xFF001E31)
val BluePrimary20 = Color(0xFF00344F)
val BluePrimary30 = Color(0xFF004B70)
val BluePrimary40 = Color(0xFF00639B)
val BluePrimary80 = Color(0xFF9ACBF5)
val BluePrimary90 = Color(0xFFCBE6FF)

val BlueGrey30 = Color(0xFF516069)
val BlueGrey80 = Color(0xFFB8C8D2)
val BlueGrey90 = Color(0xFFD4E4EE)

val TealSecondary40 = Color(0xFF4C635B)
val TealSecondary80 = Color(0xFFB3CCC1)
val TealSecondary90 = Color(0xFFCFE9DD)

val AmberTertiary40 = Color(0xFF7A5900)
val AmberTertiary80 = Color(0xFFF3BF3B)
val AmberTertiary90 = Color(0xFFFFE08C)

val ErrorRed40 = Color(0xFFBA1A1A)
val ErrorRed80 = Color(0xFFFFB4AB)
val ErrorRed90 = Color(0xFFFFDAD6)
val ErrorRed100 = Color(0xFFFFFFFF)

val NeutralGrey10 = Color(0xFF191C1F)
val NeutralGrey90 = Color(0xFFE1E2E5)
val NeutralGrey95 = Color(0xFFF1F0F4)

val LightAppColorScheme = lightColorScheme(
    primary = BluePrimary40,
    onPrimary = Color.White,
    primaryContainer = BluePrimary90,
    onPrimaryContainer = BluePrimary10,
    secondary = TealSecondary40,
    onSecondary = Color.White,
    secondaryContainer = TealSecondary90,
    onSecondaryContainer = Color(0xFF0A201A),
    tertiary = AmberTertiary40,
    onTertiary = Color.White,
    tertiaryContainer = AmberTertiary90,
    onTertiaryContainer = Color(0xFF261A00),
    error = ErrorRed40,
    onError = Color.White,
    errorContainer = ErrorRed90,
    onErrorContainer = Color(0xFF410002),
    background = NeutralGrey95,
    onBackground = NeutralGrey10,
    surface = Color(0xFFFDFCFF),
    onSurface = NeutralGrey10,
    surfaceVariant = BlueGrey90,
    onSurfaceVariant = BlueGrey30,
    outline = Color(0xFF6F797F),
    outlineVariant = Color(0xFFBFC8CE),
)

val DarkAppColorScheme = darkColorScheme(
    primary = BluePrimary80,
    onPrimary = BluePrimary20,
    primaryContainer = BluePrimary30,
    onPrimaryContainer = BluePrimary90,
    secondary = TealSecondary80,
    onSecondary = Color(0xFF1E352E),
    secondaryContainer = Color(0xFF354B44),
    onSecondaryContainer = TealSecondary90,
    tertiary = AmberTertiary80,
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5E4300),
    onTertiaryContainer = AmberTertiary90,
    error = ErrorRed80,
    onError = ErrorRed40,
    errorContainer = Color(0xFF93000A),
    onErrorContainer = ErrorRed90,
    background = NeutralGrey10,
    onBackground = NeutralGrey90,
    surface = Color(0xFF111416),
    onSurface = NeutralGrey90,
    surfaceVariant = Color(0xFF3F484D),
    onSurfaceVariant = BlueGrey80,
    outline = Color(0xFF89928F),
    outlineVariant = Color(0xFF3F484D),
)
