package com.mvxxcb.reader.ui.theme

// WP-U（04 §4）：应用主题。明暗三态（SYSTEM/LIGHT/DARK）+ Android 12+ 动态取色，
// 由 SettingsScreen 经 AppSettingsViewModel 接 AppPreferences 后传入。
// MainActivity 集成调用形态（04 §1 占位注释约定）：
//   setContent { AppTheme(themeMode = …, dynamicColor = …) { AppNavHost(container) } }
// 2.1 视觉升级（M1）：新增 ExtendedColors（成功/警告语义色，随明暗主题切换；
// 动态取色时回退到静态语义色，保证状态色语义稳定）。

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mvxxcb.reader.domain.model.ThemeMode

/** 应用明暗判定（WP-INS：MainActivity 系统栏外观同步与 AppTheme 共用同口径，避免逻辑分叉） */
@Composable
fun rememberAppDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * M3 ColorScheme 未覆盖的语义状态色（成功/警告），M1 token 化补充。
 * 对比度与 error 同基线：状态文字 ≥ 4.5:1（浅色 success/警告色在 surface 上，深色反之）。
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

val LightExtendedColors = ExtendedColors(
    success = SuccessGreen40,
    onSuccess = Color.White,
    successContainer = SuccessGreen90,
    onSuccessContainer = SuccessGreen10,
    warning = AmberTertiary40,
    onWarning = Color.White,
    warningContainer = AmberTertiary90,
    onWarningContainer = Color(0xFF261A00),
)

val DarkExtendedColors = ExtendedColors(
    success = SuccessGreen80,
    onSuccess = Color(0xFF00391D),
    successContainer = Color(0xFF005328),
    onSuccessContainer = SuccessGreen90,
    warning = AmberTertiary80,
    onWarning = Color(0xFF412D00),
    warningContainer = Color(0xFF5E4300),
    onWarningContainer = AmberTertiary90,
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtendedColors }

/** 便捷访问：MaterialTheme.extendedColors.success（AppTheme 提供的 LocalExtendedColors 支撑） */
val MaterialTheme.extendedColors: ExtendedColors
    @Composable get() = LocalExtendedColors.current

@Composable
fun AppTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = rememberAppDarkTheme(themeMode)
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkAppColorScheme
        else -> LightAppColorScheme
    }
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(
        LocalSpacing provides Spacing(),
        LocalExtendedColors provides extendedColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
