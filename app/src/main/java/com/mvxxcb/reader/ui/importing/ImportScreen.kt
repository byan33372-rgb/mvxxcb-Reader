package com.mvxxcb.reader.ui.importing

// WP-U（04 §4）：发现·导入。真实 UiState；输入 + 显式粘贴按钮（禁止后台读剪贴板）；
// 系统分享进入（onSharedText）；ImportPhase 进度；结果预览含空字段如实展示；
// 低置信度警示；错误 + 恢复按钮（RETRY / EDIT_RULE / READ_AS_TEMPORARY）；mergedExistingBookId 提示。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.PageKind
import com.mvxxcb.reader.domain.service.ImportPhase
import com.mvxxcb.reader.domain.service.RecoveryAction
import com.mvxxcb.reader.presentation.importing.ImportEvent
import com.mvxxcb.reader.presentation.importing.ImportViewModel
import com.mvxxcb.reader.ui.components.ErrorBanner
import com.mvxxcb.reader.ui.components.InlineLoadingBar
import com.mvxxcb.reader.ui.components.WindowAdaptiveLayout
import java.net.URI
import com.mvxxcb.reader.ui.theme.extendedColors
import com.mvxxcb.reader.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImportScreen(
    container: AppContainer,
    onOpenRules: (domain: String?) -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenReader: (bookId: Long, chapterIndex: Int) -> Unit,
    initialSharedText: String? = null,
    modifier: Modifier = Modifier,
) {
    val viewModel: ImportViewModel = viewModel(factory = ImportViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()

    // 系统分享进入（MainActivity 转发 intent 文本）
    LaunchedEffect(initialSharedText) {
        if (!initialSharedText.isNullOrBlank()) viewModel.onSharedText(initialSharedText)
    }

    // WP-INS（07 §6.2）：AdaptiveScaffold + imePadding（软键盘顶起输入框；scaffold 已排除 ime，无重复 insets）
    WindowAdaptiveLayout(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("发现·导入") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.spacing.screenH, vertical = MaterialTheme.spacing.screenV),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            // —— 输入区 ——（2.1 视觉：medium 圆角让主输入框更醒目，全 App 文本框统一）
            OutlinedTextField(
                value = state.inputText,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("小说网址") },
                placeholder = { Text("粘贴书籍详情页 / 目录页 / 章节页链接") },
                shape = MaterialTheme.shapes.medium,
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                OutlinedButton(onClick = { viewModel.readClipboard() }) {
                    Icon(Icons.Filled.ContentPaste, contentDescription = null)
                    Spacer(Modifier.width(MaterialTheme.spacing.xs))
                    Text("粘贴")
                }
                Button(onClick = { viewModel.submit() }, enabled = state.inputText.isNotBlank()) {
                    Text(if (state.phase == ImportPhase.IDLE) "开始解析" else "重新解析")
                }
                if (state.phase != ImportPhase.IDLE && state.phase != ImportPhase.SUCCESS && state.phase != ImportPhase.FAILED) {
                    OutlinedButton(onClick = { viewModel.cancel() }) { Text("取消") }
                }
                if (state.inputText.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearInput() }) { Text("清空") }
                }
            }

            // 剪贴板建议（显式触发后出现）
            state.clipboardSuggestion?.let { suggestion ->
                AssistChip(
                    onClick = { viewModel.acceptClipboardSuggestion() },
                    label = { Text("检测到链接：${suggestion.take(48)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }

            // —— 进度区 ——
            when (state.phase) {
                ImportPhase.CLASSIFYING -> InlineLoadingBar(text = "正在识别网址类型…")
                ImportPhase.PARSING_DETAIL -> InlineLoadingBar(text = "正在解析书籍详情…")
                ImportPhase.PARSING_CATALOG -> InlineLoadingBar(text = "正在解析章节目录…")
                ImportPhase.PARSING_CONTENT -> InlineLoadingBar(text = "正在解析正文…")
                ImportPhase.SAVING -> InlineLoadingBar(text = "正在保存…")
                else -> Unit
            }

            // —— 分类结果 ——
            state.classification?.let { classification ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(MaterialTheme.spacing.md),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                    ) {
                        Text(
                            "网址类型：${kindLabel(classification.kind)}（置信度：${confidenceLabel(classification.confidence)}）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (classification.matchedRuleId != null) {
                            Text(
                                "命中规则：${classification.matchedRuleId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (classification.notes.isNotEmpty()) {
                            Text(
                                classification.notes.joinToString("\n"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // —— 低置信度警示 ——（2.1 视觉：warning 语义色 token，替代 tertiary 复用）
            if (state.confidenceWarning) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.extendedColors.warningContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(MaterialTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.extendedColors.onWarningContainer,
                        )
                        Spacer(Modifier.width(MaterialTheme.spacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "识别结果可信度较低，字段可能不完整",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.extendedColors.onWarningContainer,
                            )
                            TextButton(onClick = {
                                val domain = state.classification?.normalizedUrl?.let { hostOf(it) }
                                onOpenRules(domain)
                            }) { Text("编辑规则") }
                        }
                    }
                }
            }

            // —— 结果预览（含空字段如实展示；禁止伪造） ——
            state.detail?.let { detail ->
                ResultPreviewCard(detail = detail, catalogSize = state.catalogSize)
            }

            // —— mergedExistingBookId 提示 ——
            if (state.mergedExistingBookId != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(MaterialTheme.spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("书架中已有此书（按网址去重合并）", style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = { viewModel.openExistingBook() }) { Text("打开") }
                    }
                }
            }

            // —— 错误 + 恢复按钮（契约 02 §10） ——
            if (state.error != null && state.phase == ImportPhase.FAILED) {
                ErrorBanner(
                    error = state.error!!,
                    onRetry = if (state.recovery.contains(RecoveryAction.RETRY)) {
                        { viewModel.retry() }
                    } else {
                        null
                    },
                )
                val recovery = state.recovery
                // 2.1（M1）：FlowRow —— 恢复按钮多时换行，不再横向溢出
                FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                    if (recovery.contains(RecoveryAction.EDIT_RULE)) {
                        OutlinedButton(onClick = {
                            val domain = state.classification?.normalizedUrl?.let { hostOf(it) }
                            onOpenRules(domain)
                        }) { Text("编辑规则") }
                    }
                    if (recovery.contains(RecoveryAction.READ_AS_TEMPORARY)) {
                        OutlinedButton(onClick = { viewModel.readAsTemporary() }) { Text("临时阅读") }
                    }
                    // OPEN_CATALOG_IF_EXISTS：失败结果未携带 bookId，无法定位目录 —— 一期不渲染，
                    // 由「书架已有此书」提示兜底（详见汇报偏差项）。
                }
            }
        }
    }

    // —— 一次性导航事件（契约 02 §8.3：成功跳详情 / 临时阅读跳阅读器） ——
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ImportEvent.NavigateToBook -> onOpenBook(event.bookId)
                is ImportEvent.NavigateToReader -> onOpenReader(event.bookId, event.chapterIndex)
                is ImportEvent.NavigateToCatalog -> onOpenBook(event.bookId)
            }
        }
    }
}

@Composable
private fun ResultPreviewCard(detail: ParsedBookDetail, catalogSize: Int?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            Text("解析结果预览", style = MaterialTheme.typography.titleMedium)
            Row {
                // 2.1 视觉：无封面占位与书架/详情一致（surfaceVariant 底色 + 书本图标 + small 圆角）
                Box(
                    modifier = Modifier
                        .width(72.dp)
                        .aspectRatio(0.72f)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (detail.coverUrl.isNullOrBlank()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "无封面",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        AsyncImage(
                            model = detail.coverUrl,
                            contentDescription = "封面",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(MaterialTheme.spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    PreviewField("书名", detail.title, lowConfidence = detail.fieldConfidence[DetailField.TITLE] == Confidence.LOW)
                    PreviewField("作者", detail.author, lowConfidence = detail.fieldConfidence[DetailField.AUTHOR] == Confidence.LOW)
                    PreviewField("简介", detail.description, lowConfidence = detail.fieldConfidence[DetailField.DESCRIPTION] == Confidence.LOW)
                    Text(
                        text = catalogSize?.let { "目录：${it}章" } ?: "目录：未解析",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewField(label: String, value: String?, lowConfidence: Boolean) {
    val display = value?.takeIf { it.isNotBlank() } ?: "（未识别）"
    Row {
        Text(
            text = "$label：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = display,
            style = MaterialTheme.typography.bodySmall,
            color = if (value.isNullOrBlank() || lowConfidence) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun kindLabel(kind: PageKind): String = when (kind) {
    PageKind.BOOK_DETAIL -> "书籍详情页"
    PageKind.CATALOG -> "目录页"
    PageKind.CHAPTER -> "章节页"
    PageKind.UNKNOWN -> "未知页面"
}

private fun confidenceLabel(confidence: Confidence): String = when (confidence) {
    Confidence.HIGH -> "高"
    Confidence.MEDIUM -> "中"
    Confidence.LOW -> "低"
}

/** 从 URL 提取 host（规则编辑预填用；解析失败返回 null） */
internal fun hostOf(url: String): String? = runCatching { URI(url).host }.getOrNull()
