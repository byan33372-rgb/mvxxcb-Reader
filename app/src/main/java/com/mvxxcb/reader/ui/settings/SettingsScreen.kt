package com.mvxxcb.reader.ui.settings

// WP-U（04 §4）：我的（设置入口）。应用明暗三态 + Android 12+ 动态取色（接 AppPreferences）；
// 阅读设置 / 规则管理入口。真实 UiState。

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.model.ThemeMode
import com.mvxxcb.reader.presentation.settings.AppSettingsViewModel
import com.mvxxcb.reader.ui.components.LoadingState
import com.mvxxcb.reader.ui.theme.spacing@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenReaderSettings: () -> Unit,
    onOpenRuleManager: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: AppSettingsViewModel = viewModel(factory = AppSettingsViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()
    val isDynamicColorSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text("我的") }) }) { padding ->
        if (state.isLoading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@Scaffold
        }
        val preferences = state.preferences
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.spacing.screenH, vertical = MaterialTheme.spacing.screenV),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            // —— 外观 ——
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.DarkMode, contentDescription = null)
                        Spacer(Modifier.width(MaterialTheme.spacing.sm))
                        Text("外观", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(MaterialTheme.spacing.sm))
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.update { it.copy(themeMode = mode) } },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = preferences.themeMode == mode,
                                onClick = { viewModel.update { it.copy(themeMode = mode) } },
                            )
                            Text(
                                text = when (mode) {
                                    ThemeMode.SYSTEM -> "跟随系统"
                                    ThemeMode.LIGHT -> "浅色"
                                    ThemeMode.DARK -> "深色"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    if (isDynamicColorSupported) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.spacing.xs))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Palette, contentDescription = null)
                            Spacer(Modifier.width(MaterialTheme.spacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("动态取色", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Android 12+ 壁纸取色",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = preferences.dynamicColor,
                                onCheckedChange = { checked ->
                                    viewModel.update { it.copy(dynamicColor = checked) }
                                },
                            )
                        }
                    }
                }
            }

            // —— 入口 ——
            SettingsEntry(icon = Icons.AutoMirrored.Filled.MenuBook, title = "阅读设置", subtitle = "字体/主题/翻页模式/亮度") {
                onOpenReaderSettings()
            }
            SettingsEntry(icon = Icons.Filled.Rule, title = "规则管理", subtitle = "站点解析规则：新建/编辑/导入导出") {
                onOpenRuleManager()
            }
        }
    }
}

@Composable
private fun SettingsEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(MaterialTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
