package com.mvxxcb.reader.domain.service

sealed interface ImportStep {
    data class PhaseChanged(val phase: ImportPhase) : ImportStep
    data class Finished(val outcome: ImportOutcome) : ImportStep
}
enum class ImportPhase { IDLE, CLASSIFYING, PARSING_DETAIL, PARSING_CATALOG, PARSING_CONTENT, SAVING, SUCCESS, FAILED }

sealed interface ImportOutcome {
    data class Success(
        val bookId: Long,
        val detail: com.mvxxcb.reader.domain.model.ParsedBookDetail?,
        val catalogSize: Int?,                  // null = 未解析目录
        val confidence: com.mvxxcb.reader.domain.model.Confidence,
        val warnings: List<String>,
        val createdTemporary: Boolean,          // 仅解析到正文 → 临时书
        val mergedExisting: Boolean,            // 命中书架已有书（按规范化 URL 去重）
    ) : ImportOutcome
    data class Failure(
        val error: com.mvxxcb.reader.domain.error.ReaderError,
        val classification: com.mvxxcb.reader.domain.model.UrlClassification?,
        val partialDetail: com.mvxxcb.reader.domain.model.ParsedBookDetail?,   // 已拿到的部分字段（如实展示，可空）
        val recovery: List<RecoveryAction>,
    ) : ImportOutcome
}

enum class RecoveryAction { RETRY, EDIT_RULE, OPEN_CATALOG_IF_EXISTS, READ_AS_TEMPORARY }
// MANUAL_SELECT(手动选区) 为二期动作，一期不在枚举中（UI 层用 EDIT_RULE 兜底）

interface ImportService {
    suspend fun classify(rawUrl: String): com.mvxxcb.reader.common.result.AppResult<com.mvxxcb.reader.domain.model.UrlClassification>
    /** 完整导入流：classify → detail → catalog → 去重 → 入库；进度经 Flow 逐步上报；可取消 */
    fun importFromUrl(rawUrl: String): kotlinx.coroutines.flow.Flow<ImportStep>
    /** 章节页临时阅读：尽力回溯所属书与目录；失败仍返回单章内容建临时书 */
    suspend fun parseForTemporaryRead(rawUrl: String): TempReadOutcome
}

sealed interface TempReadOutcome {
    data class Ready(
        val bookId: Long,
        val chapterIndex: Int,
        val backtracked: Boolean,               // 是否成功回溯到书/目录
        val confidence: com.mvxxcb.reader.domain.model.Confidence,
    ) : TempReadOutcome
    data class Failed(val error: com.mvxxcb.reader.domain.error.ReaderError) : TempReadOutcome
}
