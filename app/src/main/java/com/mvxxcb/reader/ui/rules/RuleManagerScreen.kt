package com.mvxxcb.reader.ui.rules

// WP-U（04 §4）：规则管理。规则列表（启用开关/删除确认）、新建/编辑入口、
// JSON 导出（复制到剪贴板）与导入（对话框粘贴 + 模式选择）、导入报告展示。

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.repository.RuleImportMode
import com.mvxxcb.reader.presentation.rules.RuleManagerEvent
import com.mvxxcb.reader.presentation.rules.RuleManagerViewModel
import com.mvxxcb.reader.ui.components.ConfirmDialog
import com.mvxxcb.reader.ui.components.EmptyState
import com.mvxxcb.reader.ui.components.ErrorBanner
import com.mvxxcb.reader.ui.components.LoadingState
import com.mvxxcb.reader.ui.components.WindowAdaptiveLayout
import com.mvxxcb.reader.ui.components.listContentPadding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mvxxcb.reader.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleManagerScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onEditRule: (domain: String) -> Unit,
    onCreateRule: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: RuleManagerViewModel = viewModel(factory = RuleManagerViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }

    // WP-INS（07 §6.2）：AdaptiveScaffold 接入；FAB/Snackbar 由 Scaffold 统一避让导航栏 insets
    WindowAdaptiveLayout(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("规则管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.exportAll() }) {
                        Icon(Icons.Filled.FileDownload, contentDescription = "导出全部规则")
                    }
                    IconButton(onClick = { showImportDialog = true }) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "导入规则")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateRule) {
                Icon(Icons.Filled.Add, contentDescription = "新建规则")
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading -> LoadingState(modifier = Modifier.padding(padding))

                state.error != null -> Column(modifier = Modifier.padding(padding)) {
                    ErrorBanner(
                        error = state.error!!,
                        onDismiss = { viewModel.consumeError() },
                        modifier = Modifier.padding(MaterialTheme.spacing.md),
                    )
                }

                state.rules.isEmpty() -> EmptyState(
                    title = "还没有站点规则",
                    description = "导入解析失败时，可针对站点新建规则兜底",
                    actionText = "新建规则",
                    onAction = onCreateRule,
                    modifier = Modifier.padding(padding),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = listContentPadding(
                        scaffoldPadding = padding,
                        horizontal = MaterialTheme.spacing.screenH,
                        vertical = MaterialTheme.spacing.screenV,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.cardGap),
                ) {
                    items(state.rules, key = { it.ruleId }) { rule ->
                        RuleCard(
                            domain = rule.domain,
                            ruleId = rule.ruleId,
                            version = rule.version,
                            enabled = rule.enabled,
                            updatedAt = rule.updatedAt,
                            onToggle = { viewModel.toggleEnabled(rule.ruleId) },
                            onClick = { onEditRule(rule.domain) },
                            onDelete = { pendingDelete = rule.ruleId },
                        )
                    }
                }
            }
        }
    }

    // 编辑入口事件：Saved → 返回；Exported → 复制到剪贴板
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is RuleManagerEvent.Saved -> snackbarHostState.showSnackbar("规则已保存")
                is RuleManagerEvent.Exported -> {
                    clipboard.setText(AnnotatedString(event.json))
                    snackbarHostState.showSnackbar("已复制 ${event.json.length} 字符的规则 JSON")
                }
            }
        }
    }

    // 导出/编辑说明：点击卡片进入编辑；导出经 topBar 按钮复制到剪贴板

    pendingDelete?.let { ruleId ->
        ConfirmDialog(
            title = "删除规则",
            text = "删除后该站点将回退到自动解析。",
            confirmText = "删除",
            onConfirm = {
                viewModel.deleteRule(ruleId)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }

    if (showImportDialog) {
        RuleImportDialog(
            onDismiss = { showImportDialog = false },
            onImport = { json, mode ->
                viewModel.importJson(json, mode)
                showImportDialog = false
            },
        )
    }

    // 导入报告展示
    state.importReport?.let { report ->
        LaunchedEffect(report) {
            val message = buildString {
                append("导入完成：成功 ${report.imported}，跳过 ${report.skipped}")
                if (report.invalid.isNotEmpty()) append("，无效 ${report.invalid.size}")
            }
            snackbarHostState.showSnackbar(message)
            viewModel.clearImportReport()
        }
    }
}

@Composable
private fun RuleCard(
    domain: String,
    ruleId: String,
    version: Int,
    enabled: Boolean,
    updatedAt: Long,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(domain, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$ruleId · v$version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除规则", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RuleImportDialog(
    onDismiss: () -> Unit,
    onImport: (json: String, mode: RuleImportMode) -> Unit,
) {
    var jsonText by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(RuleImportMode.MERGE_BY_ID) }
    val valid = com.mvxxcb.reader.presentation.rules.RuleManagerViewModel.isJsonObject(jsonText)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入规则 JSON") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                androidx.compose.material3.OutlinedTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    placeholder = { Text("粘贴规则 JSON（数组或单对象）") },
                    isError = jsonText.isNotBlank() && !valid,
                )
                Row {
                    androidx.compose.material3.FilterChip(
                        selected = mode == RuleImportMode.MERGE_BY_ID,
                        onClick = { mode = RuleImportMode.MERGE_BY_ID },
                        label = { Text("按 ID 合并") },
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.sm))
                    androidx.compose.material3.FilterChip(
                        selected = mode == RuleImportMode.OVERWRITE_ALL,
                        onClick = { mode = RuleImportMode.OVERWRITE_ALL },
                        label = { Text("全部覆盖") },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(jsonText, mode) },
                enabled = valid,
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
