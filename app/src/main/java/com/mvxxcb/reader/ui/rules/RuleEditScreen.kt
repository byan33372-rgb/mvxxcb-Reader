package com.mvxxcb.reader.ui.rules

// WP-U（04 §4）：规则编辑。逐字段校验回显（fieldErrors）；保存成功 → 事件返回；
// 路由 ?domain={domain}：非空且存在规则 = 编辑，否则按该域名新建。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.presentation.rules.RuleManagerEvent
import com.mvxxcb.reader.presentation.rules.RuleManagerViewModel
import com.mvxxcb.reader.presentation.rules.SourceRuleDraft
import com.mvxxcb.reader.ui.components.ErrorBanner
import com.mvxxcb.reader.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditScreen(
    container: AppContainer,
    routeDomain: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: RuleManagerViewModel = viewModel(factory = RuleManagerViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 路由初始化：domain 非空 → 编辑（无规则回退新建）；空 → 新建
    LaunchedEffect(routeDomain) {
        if (state.editingDraft == null) {
            if (routeDomain.isNullOrBlank()) {
                viewModel.startCreate("")
            } else {
                viewModel.startEdit(routeDomain)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.editingDraft?.ruleId == null) "新建规则" else "编辑规则") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.cancelEdit()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        val draft = state.editingDraft
        if (draft == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(MaterialTheme.spacing.screenH),
                verticalArrangement = Arrangement.Center,
            ) {
                if (state.fieldErrors.isNotEmpty()) {
                    ErrorBanner(
                        error = com.mvxxcb.reader.domain.error.ReaderError.Unknown(
                            state.fieldErrors.values.firstOrNull(),
                        ),
                        onDismiss = onBack,
                    )
                }
                Text("正在加载规则…")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = MaterialTheme.spacing.screenH,
                    vertical = MaterialTheme.spacing.screenV,
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            if (state.error != null) {
                ErrorBanner(error = state.error!!, onDismiss = { viewModel.consumeError() })
            }

            // —— 基本信息 ——
            OutlinedTextField(
                value = draft.domain,
                onValueChange = { v -> viewModel.onDraftChange { it.copy(domain = v) } },
                label = { Text("站点域名 *") },
                placeholder = { Text("www.example-novel.com") },
                isError = state.fieldErrors.containsKey("domain"),
                supportingText = if (state.fieldErrors.containsKey("domain")) {
                    { Text(state.fieldErrors["domain"].orEmpty(), color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("启用该规则", modifier = Modifier.weight(1f))
                Switch(
                    checked = draft.enabled,
                    onCheckedChange = { v -> viewModel.onDraftChange { it.copy(enabled = v) } },
                )
            }
            OutlinedTextField(
                value = draft.encoding.orEmpty(),
                onValueChange = { v ->
                    viewModel.onDraftChange { it.copy(encoding = v.trim().takeIf { s -> s.isNotEmpty() }) }
                },
                label = { Text("站点编码（可选）") },
                placeholder = { Text("UTF-8 / GBK / GB18030 / BIG5") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                isError = state.fieldErrors.containsKey("encoding"),
                supportingText = if (state.fieldErrors.containsKey("encoding")) {
                    { Text(state.fieldErrors["encoding"].orEmpty(), color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // —— 详情选择器 ——
            SectionHeader("详情页选择器")
            SelectorField(draft, state.fieldErrors, "bookNameSelector", "书名选择器") {
                viewModel.onDraftChange { d -> d.copy(bookNameSelector = it) }
            }
            SelectorField(draft, state.fieldErrors, "authorSelector", "作者选择器") {
                viewModel.onDraftChange { d -> d.copy(authorSelector = it) }
            }
            SelectorField(draft, state.fieldErrors, "coverSelector", "封面选择器") {
                viewModel.onDraftChange { d -> d.copy(coverSelector = it) }
            }
            SelectorField(draft, state.fieldErrors, "descriptionSelector", "简介选择器") {
                viewModel.onDraftChange { d -> d.copy(descriptionSelector = it) }
            }

            // —— 目录选择器 ——
            SectionHeader("目录选择器")
            SelectorField(draft, state.fieldErrors, "catalogSelector", "目录列表选择器") {
                viewModel.onDraftChange { d -> d.copy(catalogSelector = it) }
            }
            SelectorField(draft, state.fieldErrors, "catalogNextPageSelector", "目录下一页选择器") {
                viewModel.onDraftChange { d -> d.copy(catalogNextPageSelector = it) }
            }
            SelectorField(draft, state.fieldErrors, "chapterTitleSelector", "章节标题选择器") {
                viewModel.onDraftChange { d -> d.copy(chapterTitleSelector = it) }
            }
            SelectorField(draft, state.fieldErrors, "chapterLinkSelector", "章节链接选择器") {
                viewModel.onDraftChange { d -> d.copy(chapterLinkSelector = it) }
            }

            // —— 正文选择器 ——
            SectionHeader("正文选择器")
            SelectorField(draft, state.fieldErrors, "contentSelector", "正文内容选择器") {
                viewModel.onDraftChange { d -> d.copy(contentSelector = it) }
            }
            SelectorField(draft, state.fieldErrors, "nextPageSelector", "正文下一页选择器") {
                viewModel.onDraftChange { d -> d.copy(nextPageSelector = it) }
            }
            SelectorField(draft, state.fieldErrors, "nextChapterSelector", "下一章链接选择器") {
                viewModel.onDraftChange { d -> d.copy(nextChapterSelector = it) }
            }
            SelectorField(draft, state.fieldErrors, "previousChapterSelector", "上一章链接选择器") {
                viewModel.onDraftChange { d -> d.copy(previousChapterSelector = it) }
            }
            Text(
                text = "选择器写法：CSS 简写默认取文本；加 @attr 后缀取属性，如 \"div.cover img@src\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // —— 清洗规则 ——
            SectionHeader("正文清洗规则（一行一条正则）")
            OutlinedTextField(
                value = draft.cleaningRulesText,
                onValueChange = { v -> viewModel.onDraftChange { it.copy(cleaningRulesText = v) } },
                label = { Text("正则列表") },
                isError = state.fieldErrors.containsKey("cleaningRulesText"),
                supportingText = if (state.fieldErrors.containsKey("cleaningRulesText")) {
                    { Text(state.fieldErrors["cleaningRulesText"].orEmpty(), color = MaterialTheme.colorScheme.error) }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            if (state.fieldErrors.containsKey("coverage")) {
                Text(
                    text = state.fieldErrors["coverage"].orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (state.fieldErrors.containsKey("ruleJson")) {
                Text(
                    text = state.fieldErrors["ruleJson"].orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // —— 操作 ——
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                Button(onClick = { viewModel.save() }, modifier = Modifier.weight(1f)) {
                    Text("保存")
                }
                OutlinedButton(onClick = {
                    viewModel.cancelEdit()
                    onBack()
                }) { Text("取消") }
            }
        }
    }

    // 保存成功 → 返回（契约 02 §8.7 RuleManagerEvent.Saved）
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is RuleManagerEvent.Saved -> {
                    snackbarHostState.showSnackbar("规则已保存")
                    onBack()
                }
                is RuleManagerEvent.Exported -> Unit
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
    )
}

/** 选择器输入框：fieldErrors 按 key 回显（逐字段校验，契约 02 §8.7 注） */
@Composable
private fun SelectorField(
    draft: SourceRuleDraft,
    fieldErrors: Map<String, String>,
    key: String,
    label: String,
    onValueChange: (String) -> Unit,
) {
    val value = when (key) {
        "bookNameSelector" -> draft.bookNameSelector
        "authorSelector" -> draft.authorSelector
        "coverSelector" -> draft.coverSelector
        "descriptionSelector" -> draft.descriptionSelector
        "catalogSelector" -> draft.catalogSelector
        "catalogNextPageSelector" -> draft.catalogNextPageSelector
        "chapterTitleSelector" -> draft.chapterTitleSelector
        "chapterLinkSelector" -> draft.chapterLinkSelector
        "contentSelector" -> draft.contentSelector
        "nextPageSelector" -> draft.nextPageSelector
        "nextChapterSelector" -> draft.nextChapterSelector
        "previousChapterSelector" -> draft.previousChapterSelector
        else -> ""
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = fieldErrors.containsKey(key),
        supportingText = if (fieldErrors.containsKey(key)) {
            { Text(fieldErrors[key].orEmpty(), color = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
