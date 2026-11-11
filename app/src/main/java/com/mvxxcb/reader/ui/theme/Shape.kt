package com.mvxxcb.reader.ui.theme

// WP-U（04 §4）：M3 Token 体系 —— 形状。

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// 2.1（M1）圆角体系用途约定：extraSmall=小状态角/输入框描边；small=封面/状态 chip/列表行高亮；
// medium=输入框/卡片内嵌元素；large=卡片/对话框；extraLarge=底部面板（M3 默认）。禁止屏内新建 RoundedCornerShape。

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
