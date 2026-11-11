package com.mvxxcb.reader.ui.theme

// WP-U（04 §4）：M3 Token 体系 —— 颜色。明暗两套 ColorScheme。
// 2.1 视觉升级（M1）：补全 surfaceContainer/inverse/surfaceDim·Bright 角色（避免 M3 紫灰基线默认值
// 渗入墨青色板），新增成功/警告语义色（ExtendedColors，见 Theme.kt）。
// 对比度基线：正文 onSurface/onBackground ≥ 4.5:1（WCAG AA），辅助文字 onSurfaceVariant ≥ 4.5:1。

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

// —— 状态语义色板：成功（绿）/ 警告（琥珀，与 tertiary 同源以便动态取色降级时观感一致） ——
val SuccessGreen10 = Color(0xFF00210E)
val SuccessGreen40 = Color(0xFF1E6C41)
val SuccessGreen80 = Color(0xFF84DA9D)
val SuccessGreen90 = Color(0xFFB0F0BE)

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
    // —— 表面容器层级（2.1 补全：卡片/面板/底栏容器的墨青中性面，替代 M3 紫灰基线） ——
    surfaceDim = Color(0xFFD8DADF),
    surfaceBright = Color(0xFFFDFCFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFF1F3F5),
    surfaceContainerHigh = Color(0xFFEBEEF1),
    surfaceContainerHighest = Color(0xFFE5E8EB),
    // —— 反色角色（Snackbar/Toast 类浮层） ——
    inverseSurface = Color(0xFF2E3134),
    inverseOnSurface = Color(0xFFEFF1F2),
    inversePrimary = BluePrimary80,
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
    // —— 表面容器层级（2.1 补全，暗色由深到浅递进） ——
    surfaceDim = Color(0xFF111416),
    surfaceBright = Color(0xFF3B3E41),
    surfaceContainerLowest = Color(0xFF0C0F11),
    surfaceContainerLow = Color(0xFF191D20),
    surfaceContainer = Color(0xFF1D2124),
    surfaceContainerHigh = Color(0xFF282B2F),
    surfaceContainerHighest = Color(0xFF333639),
    // —— 反色角色 ——
    inverseSurface = Color(0xFFE1E2E5),
    inverseOnSurface = Color(0xFF2E3134),
    inversePrimary = BluePrimary40,
)
