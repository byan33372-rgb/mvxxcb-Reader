package com.mvxxcb.reader.ui.theme

// WP-U（04 §4）：M3 Token 体系 —— 间距。经 AppTheme 提供的 LocalSpacing 消费。
// 2.1 视觉升级（M1）：新增图标尺寸等级（iconXs..iconLg），替代各屏散落的 12/16/20/24dp 硬编码。

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
    val touchTarget: Dp = 48.dp, // 最小触控目标（无障碍 §4.4）
    // —— 图标尺寸等级（2.1：与字阶对应，行内小图标/列表尾图标/默认/强调） ——
    val iconXs: Dp = 12.dp,    // 行内点缀图标（时间/字数前缀）
    val iconSm: Dp = 16.dp,    // 列表 supporting 图标
    val iconMd: Dp = 20.dp,    // 工具栏内嵌进度/次要图标
    val iconLg: Dp = 24.dp,    // 默认图标（IconButton 内容）
)

val LocalSpacing = staticCompositionLocalOf { Spacing() }

/** 便捷访问：MaterialTheme.spacing.md（由 AppTheme 提供的 LocalSpacing 支撑） */
val MaterialTheme.spacing: Spacing
    @Composable get() = LocalSpacing.current
