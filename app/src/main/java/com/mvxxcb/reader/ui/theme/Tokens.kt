package com.mvxxcb.reader.ui.theme

// 2.1 视觉升级（M1）：Elevation 层级与组件状态 token。
// 值域对齐 M3 官方 elevation tokens（level0..level5），组件状态透明度对齐 M3 state layer 规范。
// 纯静态 token（与明暗主题无关），直接顶层常量引用；后续若需随主题变化再经 CompositionLocal 提升。

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 阴影层级 token（克制原则：UI 层默认只用到 Level0/Level1/Level2，避免大面积投影） */
object ElevationTokens {
    val Level0: Dp = 0.dp     // 平面卡片（M3 FilledCard 默认，靠 surfaceContainer 色阶分层）
    val Level1: Dp = 1.dp     // 贴面浮层
    val Level2: Dp = 3.dp     // 顶栏/底栏（阅读器工具栏）
    val Level3: Dp = 6.dp     // FAB / 对话框
    val Level4: Dp = 8.dp     // 菜单 / ModalSheet
}

/**
 * 组件状态 token（默认/按下/选中/禁用/加载）。
 * 说明：M3 组件默认已内建按下/禁用态；本 token 用于业务自绘状态（如目录当前章高亮、
 * 占位/骨架内容透明度）时统一取值，替代散落的魔法数。
 */
object StateTokens {
    const val PressedOverlay: Float = 0.12f      // 按下态覆盖层
    const val SelectedOverlay: Float = 0.30f     // 选中态容器透明度（当前章高亮等）
    const val DisabledContent: Float = 0.38f     // 禁用内容（M3 规范值；仅禁用态豁免对比度）
    const val LoadingContent: Float = 0.6f       // 加载/骨架占位内容
    const val SubtleDivider: Float = 0.25f       // 克制分隔线（阅读主题次级色上再降透明度）
}
