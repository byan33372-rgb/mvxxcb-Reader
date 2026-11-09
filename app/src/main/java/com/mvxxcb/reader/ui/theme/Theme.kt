package com.mvxxcb.reader.ui.theme

// WP-U（04 §4）：应用主题。明暗三态（SYSTEM/LIGHT/DARK）+ Android 12+ 动态取色，
// 由 SettingsScreen 经 AppSettingsViewModel 接 AppPreferences 后传入。
// MainActivity 集成调用形态（04 §1 占位注释约定）：
//   setContent { AppTheme(themeMode = …, dynamicColor = …) { AppNavHost(container) } }

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.mvxxcb.reader.domain.model.ThemeMode

/** 应用明暗判定（WP-INS：MainActivity 系统栏外观同步与 AppTheme 共用同口径，避免逻辑分叉） */
@Composable
fun rememberAppDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

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
    CompositionLocalProvider(LocalSpacing provides Spacing()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
