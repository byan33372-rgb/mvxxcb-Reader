package com.mvxxcb.reader.presentation.rules

// 实现归属 WP-U（04 §4）。契约依据：02 §8.7（RuleManagerViewModel）。
// 规则管理：列表（observeAll）+ 编辑草稿（SourceRuleDraft）+ 逐字段校验回显 fieldErrors。
// 校验：本地逐字段预检（domain/编码/选择器长度/清洗行）→ RuleJsonValidator 权威校验；
// 校验失败不入库（契约注释）。
// ruleId 约定（契约 02 §2.3 / docs 03 §5 示例）：新建人工规则 = "manual.<domain>"，
// 编辑保留原 ruleId；ruleId 派生自 domain。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.SourceRule
import com.mvxxcb.reader.domain.repository.RuleImportMode
import com.mvxxcb.reader.domain.repository.RuleImportReport
import com.mvxxcb.reader.domain.repository.SourceRuleRepository
import com.mvxxcb.reader.domain.rule.RuleJsonValidator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class RuleManagerUiState(
    val isLoading: Boolean = true,
    val rules: List<SourceRule> = emptyList(),
    val editingDraft: SourceRuleDraft? = null,
    // 契约 02 §8.7 原文初始化为 emptyList()，与声明类型 Map<String, String> 不符（疑似笔误）；
    // 骨架按声明类型以 emptyMap() 生成，待主 Agent 知会架构师修订契约后确认。
    val fieldErrors: Map<String, String> = emptyMap(),
    val importReport: RuleImportReport? = null,
    val error: ReaderError? = null,
)

sealed interface RuleManagerEvent {
    data object Saved : RuleManagerEvent
    data class Exported(val json: String) : RuleManagerEvent
}

/** 规则编辑表单（契约 02 §8.7 逐字：仅含选择器字段，逐字段校验回显） */
data class SourceRuleDraft(
    val ruleId: String? = null,                  // null = 新建
    val domain: String = "",
    val enabled: Boolean = true,
    val encoding: String? = null,
    val bookNameSelector: String = "", val authorSelector: String = "",
    val coverSelector: String = "", val descriptionSelector: String = "",
    val catalogSelector: String = "", val catalogNextPageSelector: String = "",
    val chapterTitleSelector: String = "", val chapterLinkSelector: String = "",
    val contentSelector: String = "", val nextPageSelector: String = "",
    val nextChapterSelector: String = "", val previousChapterSelector: String = "",
    val cleaningRulesText: String = "",          // 一行一条 regex
)

class RuleManagerViewModel(
    private val sourceRuleRepository: SourceRuleRepository,
    private val ruleJsonValidator: RuleJsonValidator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RuleManagerUiState())
    val uiState: StateFlow<RuleManagerUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<RuleManagerEvent>()
    val events: SharedFlow<RuleManagerEvent> = _events.asSharedFlow()

    /** 编辑中的原规则（保留 ruleId/version；草稿不携带版本字段） */
    private var editingRule: SourceRule? = null

    init {
        viewModelScope.launch {
            sourceRuleRepository.observeAll()
                .catch { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = ReaderError.Unknown(cause = e))
                    }
                }
                .collect { rules ->
                    _uiState.update { it.copy(isLoading = false, rules = rules) }
                }
        }
    }

    /** 新建规则草稿（domain 可由导入错误页带入预填） */
    fun startCreate(domain: String) {
        editingRule = null
        _uiState.update {
            it.copy(
                editingDraft = SourceRuleDraft(ruleId = null, domain = domain),
                fieldErrors = emptyMap(),
                importReport = null,
                error = null,
            )
        }
    }

    /** 编辑既有规则；该域名无规则时回退为新建（路由 ?domain= 双语义兜底） */
    fun startEdit(domain: String) {
        viewModelScope.launch {
            val rule = sourceRuleRepository.getRuleByDomain(domain)
            if (rule == null) {
                startCreate(domain)
                return@launch
            }
            editingRule = rule
            val draft = RuleJsonCodec.decode(rule)
            if (draft == null) {
                _uiState.update {
                    it.copy(
                        editingDraft = null,
                        fieldErrors = mapOf("ruleJson" to "规则 JSON 解析失败，无法编辑"),
                        error = ReaderError.Unknown("RuleJson decode failed for ${rule.ruleId}"),
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(editingDraft = draft, fieldErrors = emptyMap(), error = null)
            }
        }
    }

    fun onDraftChange(transform: (SourceRuleDraft) -> SourceRuleDraft) {
        _uiState.update { state ->
            val draft = state.editingDraft ?: return@update state
            state.copy(editingDraft = transform(draft), fieldErrors = emptyMap())
        }
    }

    /** 校验失败 → fieldErrors 回显，不入库 */
    fun save() {
        val draft = _uiState.value.editingDraft ?: return
        val fieldErrors = validateDraft(draft)
        if (fieldErrors.isNotEmpty()) {
            _uiState.update { it.copy(fieldErrors = fieldErrors) }
            return
        }
        val ruleJson = RuleJsonCodec.encode(draft)
        // 权威校验（委托 RuleEngine.parseRuleJson，契约 02 §5.3）
        val authoritative = ruleJsonValidator.validate(ruleJson)
        if (authoritative is com.mvxxcb.reader.common.result.AppResult.Failure) {
            val detail = authoritative.error.debugDetail ?: "规则 JSON 校验未通过"
            _uiState.update {
                it.copy(fieldErrors = mapOf("ruleJson" to detail))
            }
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existing = editingRule
            val rule = SourceRule(
                ruleId = existing?.ruleId ?: draft.ruleId ?: "manual.${draft.domain}",
                domain = draft.domain,
                version = existing?.version ?: 1,
                ruleJson = ruleJson,
                enabled = draft.enabled,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            when (val result = sourceRuleRepository.upsertRule(rule)) {
                is com.mvxxcb.reader.common.result.AppResult.Success -> {
                    editingRule = null
                    _uiState.update {
                        it.copy(editingDraft = null, fieldErrors = emptyMap(), error = null)
                    }
                    _events.emit(RuleManagerEvent.Saved)
                }
                is com.mvxxcb.reader.common.result.AppResult.Failure -> {
                    _uiState.update { it.copy(error = result.error) }
                }
            }
        }
    }

    fun toggleEnabled(ruleId: String) {
        val current = _uiState.value.rules.find { it.ruleId == ruleId }?.enabled ?: return
        viewModelScope.launch {
            sourceRuleRepository.setEnabled(ruleId, !current)
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            sourceRuleRepository.deleteRule(ruleId)
                .onFailure { error -> _uiState.update { it.copy(error = error) } }
        }
    }

    fun exportAll() {
        viewModelScope.launch {
            try {
                val exported = sourceRuleRepository.exportAll()
                _events.emit(RuleManagerEvent.Exported(exported))
            } catch (e: Exception) {
                _uiState.update { it.copy(error = ReaderError.Unknown(cause = e)) }
            }
        }
    }

    fun importJson(json: String, mode: RuleImportMode) {
        viewModelScope.launch {
            val report = sourceRuleRepository.importRules(json, mode)
            report.onSuccess { r ->
                _uiState.update { it.copy(importReport = r) }
            }.onFailure { error ->
                _uiState.update { it.copy(error = error) }
            }
        }
    }

    fun cancelEdit() {
        editingRule = null
        _uiState.update { it.copy(editingDraft = null, fieldErrors = emptyMap()) }
    }

    fun clearImportReport() {
        _uiState.update { it.copy(importReport = null) }
    }

    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    // ———— 逐字段校验（docs 03 §5 Schema 约束） ————

    internal fun validateDraft(draft: SourceRuleDraft): Map<String, String> {
        val errors = LinkedHashMap<String, String>()
        if (draft.domain.isBlank()) {
            errors["domain"] = "域名不能为空"
        } else if (!DOMAIN_REGEX.matches(draft.domain)) {
            errors["domain"] = "域名格式不正确（如 www.example-novel.com）"
        }
        draft.encoding?.takeIf { it.isNotBlank() }?.let { enc ->
            if (enc !in ALLOWED_ENCODINGS) {
                errors["encoding"] = "编码仅支持 UTF-8 / GBK / GB18030 / BIG5"
            }
        }
        forEachSelector(draft) { key, value ->
            if (value.length > SELECTOR_MAX_LENGTH) {
                errors[key] = "选择器长度超限（≤300 字符）"
            }
            if (value.contains('\n') || value.contains('\r')) {
                errors[key] = "选择器不能包含换行"
            }
        }
        // 清洗规则行校验
        draft.cleaningRulesText.split('\n').forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            if (trimmed.length > SELECTOR_MAX_LENGTH) {
                errors["cleaningRulesText"] = "第 ${index + 1} 条清洗规则过长（≤300 字符）"
            }
            runCatching { Regex(trimmed) }.onFailure {
                errors["cleaningRulesText"] = "第 ${index + 1} 条清洗规则不是合法正则"
            }
        }
        // 覆盖度警告：contentSelector 与 catalogSelector+chapterLinkSelector 至少其一（docs 03 §5）
        val hasContent = draft.contentSelector.isNotBlank()
        val hasCatalog = draft.catalogSelector.isNotBlank() || draft.chapterLinkSelector.isNotBlank()
        if (!hasContent && !hasCatalog) {
            errors["coverage"] = "该规则只能覆盖部分目标：正文与目录选择器均未填写"
        }
        return errors
    }

    private inline fun forEachSelector(
        draft: SourceRuleDraft,
        block: (key: String, value: String) -> Unit,
    ) {
        val selectors = linkedMapOf(
            "bookNameSelector" to draft.bookNameSelector,
            "authorSelector" to draft.authorSelector,
            "coverSelector" to draft.coverSelector,
            "descriptionSelector" to draft.descriptionSelector,
            "catalogSelector" to draft.catalogSelector,
            "catalogNextPageSelector" to draft.catalogNextPageSelector,
            "chapterTitleSelector" to draft.chapterTitleSelector,
            "chapterLinkSelector" to draft.chapterLinkSelector,
            "contentSelector" to draft.contentSelector,
            "nextPageSelector" to draft.nextPageSelector,
            "nextChapterSelector" to draft.nextChapterSelector,
            "previousChapterSelector" to draft.previousChapterSelector,
        )
        selectors.forEach { (key, value) -> if (value.isNotBlank()) block(key, value) }
    }

    companion object {
        private val DOMAIN_REGEX = Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")
        private const val SELECTOR_MAX_LENGTH = 300
        private val ALLOWED_ENCODINGS = setOf("UTF-8", "GBK", "GB18030", "BIG5")

        /** 手动 DI 创建入口（契约 02 §0.6） */
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RuleManagerViewModel(
                        container.sourceRuleRepository,
                        container.ruleJsonValidator,
                    ) as T
            }

        /** 规则 JSON 格式预检（导入对话框使用）：是否为合法 JSON 对象 */
        fun isJsonObject(jsonText: String): Boolean = runCatching {
            Json.parseToJsonElement(jsonText) is JsonObject
        }.getOrDefault(false) && jsonText.trimStart().startsWith("{")

        /** 规则 JSON 的 domain 字段预览（导入对话框展示用） */
        fun peekDomain(jsonText: String): String? = runCatching {
            ((Json.parseToJsonElement(jsonText) as? JsonObject)?.get("domain") as? JsonPrimitive)?.content
        }.getOrNull()
    }
}
