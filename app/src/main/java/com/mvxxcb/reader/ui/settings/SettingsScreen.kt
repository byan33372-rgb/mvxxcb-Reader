package com.mvxxcb.reader.ui.settings

// WP-U（04 §4）：我的（设置入口）。应用明暗三态 + Android 12+ 动态取色（接 AppPreferences）；
// 阅读设置 / 规则管理入口。真实 UiState。
// 2.0 WP-DIAG：新增【反馈数据】入口（一键生成诊断反馈文件 → 系统分享面板 + 路径提示）。

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mvxxcb.reader.R
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.ThemeMode
import com.mvxxcb.reader.presentation.settings.AppSettingsViewModel
import com.mvxxcb.reader.ui.components.ErrorText
import com.mvxxcb.reader.ui.components.LoadingState
import com.mvxxcb.reader.ui.components.WindowAdaptiveLayout
import com.mvxxcb.reader.ui.theme.spacing
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onOpenReaderSettings: () -> Unit,
    onOpenRuleManager: () -> Unit,
    onOpenCrawlCenter: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: AppSettingsViewModel = viewModel(factory = AppSettingsViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()
    val isDynamicColorSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

    // —— 2.0 WP-DIAG：反馈数据生成本页局部状态 ——
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var generatingFeedback by remember { mutableStateOf(false) }
    var feedbackError by remember { mutableStateOf<ReaderError?>(null) }

    // WP-INS（07 §6.2）：AdaptiveScaffold 接入（"采集中心"入口属 2.0 WP-L，本页仅做 insets 适配）
    WindowAdaptiveLayout(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("我的") }) },
    ) { padding ->
        if (state.isLoading) {
            LoadingState(modifier = Modifier.padding(padding))
            return@WindowAdaptiveLayout
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
                        // 2.1 无障碍：整行可点击切换（触达 ≥48dp），Switch 自身不再单独消费点击
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.update { it.copy(dynamicColor = !preferences.dynamicColor) }
                                },
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
                                onCheckedChange = null,
                            )
                        }
                    }
                }
            }

            // —— 入口 ——
            // 2.0 WP-L（07 §6.2）：采集任务管理入口
            SettingsEntry(icon = Icons.Filled.CloudDownload, title = "采集中心", subtitle = "整本下载任务：进度/暂停/重试/TXT 导出") {
                onOpenCrawlCenter()
            }
            SettingsEntry(icon = Icons.AutoMirrored.Filled.MenuBook, title = "阅读设置", subtitle = "字体/主题/翻页模式/亮度") {
                onOpenReaderSettings()
            }
            SettingsEntry(icon = Icons.Filled.Rule, title = "规则管理", subtitle = "站点解析规则：新建/编辑/导入导出") {
                onOpenRuleManager()
            }

            // —— 2.0 WP-DIAG：反馈数据（一键生成 + 分享） ——
            FeedbackEntry(
                generating = generatingFeedback,
                error = feedbackError,
                onClick = {
                    feedbackError = null
                    scope.launch {
                        generatingFeedback = true
                        val result = container.diagnosticsService.generateFeedback()
                        generatingFeedback = false
                        when (result) {
                            is AppResult.Success -> {
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    result.value,
                                )
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                try {
                                    context.startActivity(
                                        Intent.createChooser(send, context.getString(R.string.feedback_share_chooser)),
                                    )
                                } catch (e: ActivityNotFoundException) {
                                    // 无分享目标（极简 ROM）：路径 Snackbar 已提示，可手动取文件
                                }
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.feedback_generated_path, result.value.absolutePath),
                                )
                            }

                            is AppResult.Failure -> feedbackError = result.error
                        }
                    }
                },
            )
        }
    }
}

/**
 * 2.0 WP-DIAG：反馈数据入口（生成中置 loading；失败行内 ErrorText）。
 * 反馈文件经 FileProvider 分享（ACTION_SEND text/plain EXTRA_STREAM），
 * 路径提示走 Snackbar（生成即保留在 filesDir/feedback/，支持手动提交历史文件）。
 */
@Composable
private fun FeedbackEntry(
    generating: Boolean,
    error: ReaderError?,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !generating, onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.BugReport, contentDescription = null)
                Spacer(Modifier.width(MaterialTheme.spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_feedback_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.settings_feedback_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (generating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
            if (generating) {
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                Text(
                    stringResource(R.string.feedback_generating),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (error != null) {
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                ErrorText(error = error)
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
