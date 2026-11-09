package com.mvxxcb.reader.ui.components

// WP-INS（07 §6.1 / §5 组件清单 9，命名 WindowAdaptiveLayout）：统一 Scaffold 封装。
//  - contentWindowInsets = safeDrawing（阅读器除外，本组件不用于 ReaderScreen）；
//    ime 由表单页 Modifier.imePadding() 单独顶起（见 InsetsPolicy.adaptiveContentInsets，禁重复 insets）；
//  - 内容区 maxWidth 600dp 居中（07 §6.1.4 横竖屏与平板；阅读页满宽不接本组件）；
//  - 禁写死 24dp/32dp 顶高/底高替代系统栏 insets；子项不得再对已给边做 insetsPadding。

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 07 §6.1.4：非阅读页内容区最大宽度（平板/横屏居中，不做独立布局文件） */
val AdaptiveContentMaxWidth: Dp = 600.dp

/**
 * 统一自适应脚手架：各普通页面（书架/导入/详情/目录/设置/阅读设置/规则两页）以此替代
 * 自建 Scaffold，保证 edge-to-edge 下内容始终位于 safeDrawing 安全区内。
 *
 * @param content 接收 Scaffold innerPadding（已含 safeDrawing insets 与栏位高度）；
 *                列表页请转成 LazyColumn contentPadding（[listContentPadding]），
 *                表单页在内容 modifier 上追加 `Modifier.imePadding()`（软键盘顶起）。
 */
@Composable
fun WindowAdaptiveLayout(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    contentWindowInsets: WindowInsets = adaptiveContentInsets(),
    content: @Composable (innerPadding: androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
        contentWindowInsets = contentWindowInsets,
    ) { innerPadding ->
        // 平板/横屏内容限宽居中；小屏自然满宽（widthIn(max) 不改变小屏行为）
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = AdaptiveContentMaxWidth)
                    .fillMaxSize(),
            ) {
                content(innerPadding)
            }
        }
    }
}
