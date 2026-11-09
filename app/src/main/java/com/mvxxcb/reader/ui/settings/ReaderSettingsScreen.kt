package com.mvxxcb.reader.ui.settings

// WP-U（04 §4）：阅读设置。全部 ReaderSettings 字段（契约 02 §2.4），即时保存；
// 五套阅读主题选择带预览；变更由阅读器侧触发重排版并按 inChapterFraction 恢复位置。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.model.BackgroundTheme
import com.mvxxcb.reader.domain.model.BrightnessMode
import com.mvxxcb.reader.domain.model.ReaderFontFamily
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.domain.model.ReadingMode
import com.mvxxcb.reader.domain.model.ScreenOrientation
import com.mvxxcb.reader.presentation.settings.ReaderSettingsViewModel
import com.mvxxcb.reader.reader.theme.ReaderThemes
import com.mvxxcb.reader.ui.components.LoadingState
import com.mvxxcb.reader.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ReaderSettingsViewModel = viewModel(
        factory = ReaderSettingsViewModel.factory(container),
    )
    val state by viewModel.uiState.collectAsState()
    val settings = state.settings

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("阅读设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (state.isLoading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.spacing.screenH, vertical = MaterialTheme.spacing.screenV),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.lg),
        ) {
            // —— 预览行 ——
            ThemePreview(settings)

            // —— 字体 ——
            SectionTitle("字体")
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                ReaderFontFamily.entries.forEach { family ->
                    FilterChip(
                        selected = settings.fontFamily == family,
                        onClick = { viewModel.update { it.copy(fontFamily = family) } },
                        label = { Text(familyLabel(family)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Switch(checked = settings.boldText, onCheckedChange = { v -> viewModel.update { it.copy(boldText = v) } })
                Text("粗体显示", modifier = Modifier.padding(start = MaterialTheme.spacing.sm))
            }

            // —— 字号 14..28 ——
            SliderSetting(
                title = "字号（${settings.fontSizeSp.toInt()}sp）",
                value = settings.fontSizeSp,
                valueRange = 14f..28f,
                steps = 13,
                onValueChange = { v -> viewModel.update { it.copy(fontSizeSp = v) } },
            )
            // —— 行距 1.2..2.4 ——
            SliderSetting(
                title = "行距（${"%.1f".format(settings.lineHeightMultiplier)}x）",
                value = settings.lineHeightMultiplier,
                valueRange = 1.2f..2.4f,
                steps = 11,
                onValueChange = { v -> viewModel.update { it.copy(lineHeightMultiplier = v) } },
            )
            // —— 段间距 0..24 ——
            SliderSetting(
                title = "段间距（${settings.paragraphSpacingDp.toInt()}dp）",
                value = settings.paragraphSpacingDp,
                valueRange = 0f..24f,
                steps = 23,
                onValueChange = { v -> viewModel.update { it.copy(paragraphSpacingDp = v) } },
            )
            // —— 页边距 0..48 ——
            SliderSetting(
                title = "页边距（${settings.horizontalPaddingDp}dp）",
                value = settings.horizontalPaddingDp.toFloat(),
                valueRange = 0f..48f,
                steps = 47,
                onValueChange = { v -> viewModel.update { it.copy(horizontalPaddingDp = v.toInt()) } },
            )

            // —— 阅读主题 ——
            SectionTitle("阅读主题")
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                listOf(BackgroundTheme.WHITE, BackgroundTheme.CREAM, BackgroundTheme.EYE_CARE_GREEN)
                    .forEach { theme -> ThemeSwatch(theme, settings, viewModel) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                listOf(BackgroundTheme.DARK_GRAY, BackgroundTheme.BLACK)
                    .forEach { theme -> ThemeSwatch(theme, settings, viewModel) }
            }

            // —— 翻页模式 ——
            SectionTitle("翻页模式")
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                FilterChip(
                    selected = settings.readingMode == ReadingMode.SCROLL,
                    onClick = { viewModel.update { it.copy(readingMode = ReadingMode.SCROLL) } },
                    label = { Text("上下滚动") },
                )
                FilterChip(
                    selected = settings.readingMode == ReadingMode.PAGE_SIMULATED,
                    onClick = { viewModel.update { it.copy(readingMode = ReadingMode.PAGE_SIMULATED) } },
                    label = { Text("仿真翻页") },
                )
            }

            // —— 亮度 ——
            SectionTitle("亮度")
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                FilterChip(
                    selected = settings.brightnessMode == BrightnessMode.SYSTEM,
                    onClick = { viewModel.update { it.copy(brightnessMode = BrightnessMode.SYSTEM) } },
                    label = { Text("跟随系统") },
                )
                FilterChip(
                    selected = settings.brightnessMode == BrightnessMode.CUSTOM,
                    onClick = { viewModel.update { it.copy(brightnessMode = BrightnessMode.CUSTOM) } },
                    label = { Text("自定义") },
                )
            }
            if (settings.brightnessMode == BrightnessMode.CUSTOM) {
                SliderSetting(
                    title = "屏幕亮度（${(settings.brightness * 100).toInt()}%）",
                    value = settings.brightness,
                    valueRange = 0.05f..1f,
                    onValueChange = { v -> viewModel.update { it.copy(brightness = v) } },
                )
            }

            // —— 屏幕方向 ——
            SectionTitle("屏幕方向")
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                ScreenOrientation.entries.forEach { orientation ->
                    FilterChip(
                        selected = settings.screenOrientation == orientation,
                        onClick = { viewModel.update { it.copy(screenOrientation = orientation) } },
                        label = { Text(orientationLabel(orientation)) },
                    )
                }
            }

            // —— 开关组 ——
            SwitchRow("自动保存阅读进度", settings.autoSaveProgress) {
                viewModel.update { it.copy(autoSaveProgress = !it.autoSaveProgress) }
            }
            SwitchRow("屏幕常亮", settings.keepScreenOn) {
                viewModel.update { it.copy(keepScreenOn = !settings.keepScreenOn) }
            }
            SwitchRow("自动预取下一章", settings.autoPrefetchNext) {
                viewModel.update { it.copy(autoPrefetchNext = !settings.autoPrefetchNext) }
            }

            OutlinedButton(onClick = { viewModel.resetToDefault() }) { Text("恢复默认设置") }
        }
    }
}

@Composable
private fun ThemePreview(settings: ReaderSettings) {
    val theme = ReaderThemes.of(settings.backgroundTheme)
    val background = Color(theme.background)
    val textPrimary = Color(theme.textPrimary)
    val fontSize = settings.fontSizeSp.sp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background, MaterialTheme.shapes.medium)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .padding(settings.horizontalPaddingDp.dp),
    ) {
        Text(
            text = settings.chapterPreviewTitle(),
            style = MaterialTheme.typography.titleMedium.copy(
                color = textPrimary,
                fontSize = fontSize,
                fontFamily = fontFamilyOf(settings.fontFamily),
            ),
        )
        Text(
            text = "这是正文预览。设置变更立即生效，阅读器将按章内位置重新排版并恢复到当前位置。",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = textPrimary,
                fontSize = fontSize,
                lineHeight = fontSize * settings.lineHeightMultiplier,
                fontFamily = fontFamilyOf(settings.fontFamily),
                fontWeight = if (settings.boldText) androidx.compose.ui.text.font.FontWeight.Bold else null,
            ),
            modifier = Modifier.padding(top = settings.paragraphSpacingDp.dp),
        )
    }
}

private fun ReaderSettings.chapterPreviewTitle(): String = "第 3 章 预览"

private fun fontFamilyOf(family: ReaderFontFamily): androidx.compose.ui.text.font.FontFamily =
    when (family) {
        ReaderFontFamily.SYSTEM -> androidx.compose.ui.text.font.FontFamily.Default
        ReaderFontFamily.SANS -> androidx.compose.ui.text.font.FontFamily.SansSerif
        ReaderFontFamily.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
        ReaderFontFamily.MONOSPACE -> androidx.compose.ui.text.font.FontFamily.Monospace
    }

@Composable
private fun ThemeSwatch(
    theme: BackgroundTheme,
    settings: ReaderSettings,
    viewModel: ReaderSettingsViewModel,
) {
    val resolved = ReaderThemes.of(theme)
    val selected = settings.backgroundTheme == theme
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Color(resolved.background), CircleShape)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable { viewModel.update { it.copy(backgroundTheme = theme) } },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = themeLabel(theme),
            style = MaterialTheme.typography.labelMedium,
            color = Color(resolved.textPrimary),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SliderSetting(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value, valueRange = valueRange, steps = steps, onValueChange = onValueChange)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

private fun familyLabel(family: ReaderFontFamily): String = when (family) {
    ReaderFontFamily.SYSTEM -> "默认"
    ReaderFontFamily.SANS -> "黑体"
    ReaderFontFamily.SERIF -> "宋体"
    ReaderFontFamily.MONOSPACE -> "等宽"
}

private fun themeLabel(theme: BackgroundTheme): String = when (theme) {
    BackgroundTheme.WHITE -> "白"
    BackgroundTheme.CREAM -> "米"
    BackgroundTheme.EYE_CARE_GREEN -> "绿"
    BackgroundTheme.DARK_GRAY -> "灰"
    BackgroundTheme.BLACK -> "黑"
}

private fun orientationLabel(orientation: ScreenOrientation): String = when (orientation) {
    ScreenOrientation.SYSTEM -> "跟随系统"
    ScreenOrientation.PORTRAIT -> "竖屏"
    ScreenOrientation.LANDSCAPE -> "横屏"
}
