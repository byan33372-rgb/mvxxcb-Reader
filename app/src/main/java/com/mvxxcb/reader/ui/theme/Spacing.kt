package com.mvxxcb.reader.ui.theme

// WP-U（04 §4）：M3 Token 体系 —— 间距。经 AppTheme 提供的 LocalSpacing 消费。

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class Spacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val screenH: Dp = 16.dp,   // 屏幕水平内边距
    val screenV: Dp = 12.dp,   // 屏幕垂直内边距
    val cardGap: Dp = 12.dp,   // 列表卡片间距
    val touchTarget: Dp = 48.dp, // 最小触控目标
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

/** 便捷访问：MaterialTheme.spacing.md（由 AppTheme 提供的 LocalSpacing 支撑） */
val MaterialTheme.spacing: Spacing
    @Composable get() = LocalSpacing.current
