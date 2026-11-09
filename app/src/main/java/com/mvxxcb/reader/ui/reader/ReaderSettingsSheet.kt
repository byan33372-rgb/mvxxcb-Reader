package com.mvxxcb.reader.ui.reader

// WP-U（04 §4）：阅读器快捷设置面板。主题五选/字号步进/翻页模式/亮度；
// 变更经 ReaderViewModel.updateSettings 持久化 → 自动重排版恢复位置。
// 完整设置入口跳转 ReaderSettingsScreen。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mvxxcb.reader.R
import com.mvxxcb.reader.domain.model.BackgroundTheme
import com.mvxxcb.reader.domain.model.BrightnessMode
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.domain.model.ReadingMode
import com.mvxxcb.reader.reader.theme.ReaderThemes
import com.mvxxcb.reader.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    visible: Boolean,
    settings: ReaderSettings,
    onDismiss: () -> Unit,
    onSettingsChange: (transform: (ReaderSettings) -> ReaderSettings) -> Unit,
    onOpenFullSettings: () -> Unit,
) {
    if (!visible) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            // —— 阅读主题 ——
            Text("阅读主题", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                BackgroundTheme.entries.forEach { theme ->
                    val resolved = ReaderThemes.of(theme)
                    val selected = settings.backgroundTheme == theme
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(resolved.background), CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    onSettingsChange { it.copy(backgroundTheme = theme) }
                                },
                        )
                        Text(
                            themeLabel(theme),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // —— 字号 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("字号", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onSettingsChange { it.copy(fontSizeSp = (it.fontSizeSp - 1).coerceIn(14f, 28f)) } }) {
                    Text("A−")
                }
                Text("${settings.fontSizeSp.toInt()}", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { onSettingsChange { it.copy(fontSizeSp = (it.fontSizeSp + 1).coerceIn(14f, 28f)) } }) {
                    Text("A+")
                }
            }

            // —— 阅读模式（2.0：滚动/翻页/连续，07 §5） ——
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                Text("模式", style = MaterialTheme.typography.titleMedium)
                FilterChip(
                    selected = settings.readingMode == ReadingMode.SCROLL,
                    onClick = { onSettingsChange { it.copy(readingMode = ReadingMode.SCROLL) } },
                    label = { Text("滚动") },
                )
                FilterChip(
                    selected = settings.readingMode == ReadingMode.PAGE_SIMULATED,
                    onClick = { onSettingsChange { it.copy(readingMode = ReadingMode.PAGE_SIMULATED) } },
                    label = { Text("翻页") },
                )
                FilterChip(
                    selected = settings.readingMode == ReadingMode.CONTINUOUS,
                    onClick = { onSettingsChange { it.copy(readingMode = ReadingMode.CONTINUOUS) } },
                    label = { Text(stringResource(R.string.mode_continuous)) },
                )
            }

            // —— 亮度 ——
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("亮度", style = MaterialTheme.typography.titleMedium)
                FilterChip(
                    selected = settings.brightnessMode == BrightnessMode.SYSTEM,
                    onClick = { onSettingsChange { it.copy(brightnessMode = BrightnessMode.SYSTEM) } },
                    label = { Text("系统") },
                )
                FilterChip(
                    selected = settings.brightnessMode == BrightnessMode.CUSTOM,
                    onClick = { onSettingsChange { it.copy(brightnessMode = BrightnessMode.CUSTOM) } },
                    label = { Text("自定义") },
                )
            }
            if (settings.brightnessMode == BrightnessMode.CUSTOM) {
                Slider(
                    value = settings.brightness,
                    valueRange = 0.05f..1f,
                    onValueChange = { v -> onSettingsChange { it.copy(brightness = v) } },
                )
            }

            // —— 完整设置入口 ——
            TextButton(onClick = onOpenFullSettings) { Text("更多阅读设置 →") }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

private fun themeLabel(theme: BackgroundTheme): String = when (theme) {
    BackgroundTheme.WHITE -> "白"
    BackgroundTheme.CREAM -> "米"
    BackgroundTheme.EYE_CARE_GREEN -> "绿"
    BackgroundTheme.DARK_GRAY -> "灰"
    BackgroundTheme.BLACK -> "黑"
}
