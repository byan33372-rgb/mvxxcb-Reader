package com.mvxxcb.reader.ui.components

// WP-INS（07 §6.1 / §5 组件清单 9）：Insets 策略纯函数。
// 全局规范：应用点收敛到 AdaptiveScaffold.kt 的 WindowAdaptiveLayout；
// 禁止写死 24dp/32dp 顶高/底高替代状态栏/导航栏；禁止重复 insets
// （Scaffold 已给 padding 的边，子项不得再 windowInsetsPadding / imePadding）。

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * 页面内容 insets（07 §6.1.2）：safeDrawing（状态栏+挖孔+导航栏，M3 默认已含 displayCutout）
 * 但**排除 ime**——软键盘由表单页 `Modifier.imePadding()` 单独顶起，
 * 避免与 Scaffold padding 叠加成两倍键盘高度（07 §6.1.3 禁重复 insets）。
 * 实现说明：foundation 提供 infix `WindowInsets.exclude`（需显式 import）。
 */
@Composable
fun adaptiveContentInsets(): WindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime)

/** PaddingValues 合并：各边取和（列表页 contentPadding = Scaffold padding + 列表间距） */
operator fun PaddingValues.plus(other: PaddingValues): PaddingValues = PaddingValues(
    start = calculateLeftPaddingValue(other),
    top = calculateTopPaddingValue(other),
    end = calculateRightPaddingValue(other),
    bottom = calculateBottomPaddingValue(other),
)

/**
 * 列表页专用（07 §6.1.2）：把 WindowAdaptiveLayout 给出的 scaffoldPadding 与页面水平/垂直
 * 间距合并为 LazyColumn contentPadding，列表内容可滚动穿越至栏位之下（滚动时视觉延伸），
 * 且不与容器 padding 叠加。
 */
fun listContentPadding(
    scaffoldPadding: PaddingValues,
    horizontal: Dp,
    vertical: Dp,
): PaddingValues = scaffoldPadding + PaddingValues(horizontal = horizontal, vertical = vertical)

private fun PaddingValues.calculateLeftPaddingValue(other: PaddingValues) =
    calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
        .plus(other.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))

private fun PaddingValues.calculateRightPaddingValue(other: PaddingValues) =
    calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
        .plus(other.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))

private fun PaddingValues.calculateTopPaddingValue(other: PaddingValues) =
    calculateTopPadding().plus(other.calculateTopPadding())

private fun PaddingValues.calculateBottomPaddingValue(other: PaddingValues) =
    calculateBottomPadding().plus(other.calculateBottomPadding())
