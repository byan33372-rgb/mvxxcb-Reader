package com.mvxxcb.reader.presentation.importing

// 实现归属 WP-U（04 §4）。契约依据：02 §8.3。
// 导入主流程：input → importFromUrl Flow 逐步上报 → 结果预览（空字段如实展示，禁止伪造）。
// 剪贴板仅前台显式触发（readClipboard），禁止后台静默读取（纪律 + 01 隐私最小化）。
//
// 构造注记：契约签名为 ImportViewModel(importService)。为在 VM 内实现 readClipboard()
// （需系统 ClipboardManager），追加可选第二构造参数 clipboardReader（默认 null = 不可用），
// 契约调用形态 ImportViewModel(importService) 不变，不影响既有调用点与测试。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.UrlClassification
import com.mvxxcb.reader.domain.service.ImportPhase
import com.mvxxcb.reader.domain.service.ImportService
import com.mvxxcb.reader.domain.service.ImportStep
import com.mvxxcb.reader.domain.service.RecoveryAction
import com.mvxxcb.reader.domain.service.TempReadOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportUiState(
    val phase: ImportPhase = ImportPhase.IDLE,
    val inputText: String = "",
    val clipboardSuggestion: String? = null,     // 前台点击「读剪贴板」后检测到的链接
    val classification: UrlClassification? = null,
    val detail: ParsedBookDetail? = null,   // 含空字段，禁止伪造
    val catalogSize: Int? = null,
    val confidenceWarning: Boolean = false,      // 低置信度警示
    val error: ReaderError? = null,
    val recovery: List<RecoveryAction> = emptyList(),
    val mergedExistingBookId: Long? = null,      // 命中书架已有书
)

sealed interface ImportEvent {
    data class NavigateToBook(val bookId: Long) : ImportEvent
    data class NavigateToReader(val bookId: Long, val chapterIndex: Int) : ImportEvent
    data class NavigateToCatalog(val bookId: Long) : ImportEvent
}

/** 剪贴板文本提供者（前台显式触发时由 Screen/Factory 注入） */
fun interface ClipboardTextReader {
    fun read(): String?
}

class ImportViewModel(
    private val importService: ImportService,
    private val clipboardReader: ClipboardTextReader? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ImportUiState())
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ImportEvent>()
    val events: SharedFlow<ImportEvent> = _events.asSharedFlow()

    private var importJob: Job? = null
    private var lastSubmittedUrl: String? = null

    fun onInputChange(text: String) {
        _uiState.update {
            it.copy(
                inputText = text,
                // 用户手动输入视为放弃剪贴板建议
                clipboardSuggestion = if (text.isNotBlank()) null else it.clipboardSuggestion,
            )
        }
    }

    /** 系统分享进入：填充输入并可直接开始解析（不自动开始，留用户确认） */
    fun onSharedText(text: String) {
        _uiState.update { it.copy(inputText = text, clipboardSuggestion = null) }
    }

    /** 仅前台显式触发（禁止后台静默读取）：从剪贴板检测链接并给出建议 */
    fun readClipboard() {
        val raw = clipboardReader?.read() ?: return
        val url = extractUrl(raw)
        if (url != null) {
            _uiState.update { it.copy(clipboardSuggestion = url) }
        }
    }

    /** 采纳剪贴板建议：填入输入框并清除建议 */
    fun acceptClipboardSuggestion() {
        val suggestion = _uiState.value.clipboardSuggestion ?: return
        _uiState.update { it.copy(inputText = suggestion, clipboardSuggestion = null) }
    }

    /** 开始导入主流程 */
    fun submit() {
        val url = _uiState.value.inputText.trim()
        if (url.isEmpty()) return
        startImport(url)
    }

    fun retry() {
        val url = lastSubmittedUrl ?: _uiState.value.inputText.trim().takeIf { it.isNotEmpty() }
        if (url != null) startImport(url)
    }

    fun cancel() {
        importJob?.cancel()
        importJob = null
        _uiState.update {
            it.copy(phase = ImportPhase.IDLE, error = null, recovery = emptyList())
        }
    }

    /** mergedExistingBookId != null 时：打开书架已有书 */
    fun openExistingBook() {
        val bookId = _uiState.value.mergedExistingBookId ?: return
        viewModelScope.launch { _events.emit(ImportEvent.NavigateToBook(bookId)) }
    }

    /** RecoveryAction.READ_AS_TEMPORARY：章节页临时阅读 */
    fun readAsTemporary() {
        val url = _uiState.value.inputText.trim().ifEmpty { lastSubmittedUrl } ?: return
        viewModelScope.launch {
            when (val outcome = importService.parseForTemporaryRead(url)) {
                is TempReadOutcome.Ready -> {
                    _uiState.update { it.copy(phase = ImportPhase.SUCCESS, error = null) }
                    _events.emit(ImportEvent.NavigateToReader(outcome.bookId, outcome.chapterIndex))
                }
                is TempReadOutcome.Failed -> {
                    _uiState.update {
                        it.copy(
                            phase = ImportPhase.FAILED,
                            error = outcome.error,
                            recovery = listOf(RecoveryAction.RETRY),
                        )
                    }
                }
            }
        }
    }

    fun clearInput() {
        _uiState.update {
            it.copy(
                inputText = "",
                clipboardSuggestion = null,
                classification = null,
                detail = null,
                catalogSize = null,
                confidenceWarning = false,
                error = null,
                recovery = emptyList(),
                mergedExistingBookId = null,
            )
        }
    }

    // ———— 内部 ————

    private fun startImport(url: String) {
        importJob?.cancel()
        lastSubmittedUrl = url
        _uiState.update {
            it.copy(
                phase = ImportPhase.CLASSIFYING,
                error = null,
                recovery = emptyList(),
                classification = null,
                detail = null,
                catalogSize = null,
                confidenceWarning = false,
                mergedExistingBookId = null,
            )
        }
        importJob = viewModelScope.launch {
            importService.importFromUrl(url)
                .catch { e ->
                    _uiState.update {
                        it.copy(
                            phase = ImportPhase.FAILED,
                            error = ReaderError.Unknown(cause = e),
                            recovery = listOf(RecoveryAction.RETRY),
                        )
                    }
                }
                .collect { step ->
                    when (step) {
                        is ImportStep.PhaseChanged -> _uiState.update {
                            it.copy(phase = step.phase)
                        }

                        is ImportStep.Finished -> when (val outcome = step.outcome) {
                            is com.mvxxcb.reader.domain.service.ImportOutcome.Success -> {
                                _uiState.update {
                                    it.copy(
                                        phase = ImportPhase.SUCCESS,
                                        detail = outcome.detail,
                                        catalogSize = outcome.catalogSize,
                                        confidenceWarning =
                                            outcome.confidence == com.mvxxcb.reader.domain.model.Confidence.LOW,
                                        error = null,
                                        recovery = emptyList(),
                                        mergedExistingBookId =
                                            if (outcome.mergedExisting) outcome.bookId else null,
                                    )
                                }
                                // 导航约定：成功后经一次性事件跳转书详情；
                                // 结果预览（含空字段展示）保留在 UiState，返回导入页仍可见。
                                _events.emit(ImportEvent.NavigateToBook(outcome.bookId))
                            }

                            is com.mvxxcb.reader.domain.service.ImportOutcome.Failure -> {
                                _uiState.update {
                                    it.copy(
                                        phase = ImportPhase.FAILED,
                                        error = outcome.error,
                                        classification = outcome.classification ?: it.classification,
                                        detail = outcome.partialDetail,
                                        recovery = outcome.recovery,
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    /** 从任意文本中提取首个 http(s) 链接；无则返回原文本（若形似 URL） */
    private fun extractUrl(raw: String): String? {
        val regex = Regex("https?://\\S+")
        val match = regex.find(raw)
        return match?.value?.trimEnd(',', '，', ')', '）', '"', '\'')?.takeIf { it.isNotBlank() }
            ?: raw.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    companion object {
        /** 手动 DI 创建入口（契约 02 §0.6） */
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val reader = ClipboardTextReader {
                        val cm = container.appContext
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        val clip = cm?.primaryClip
                        val item = clip?.getItemAt(0)
                        item?.coerceToText(container.appContext)?.toString()
                    }
                    return ImportViewModel(container.importService, reader) as T
                }
            }
    }
}
