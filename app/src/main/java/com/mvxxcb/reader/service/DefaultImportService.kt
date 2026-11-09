package com.mvxxcb.reader.service

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.ChapterMeta
import com.mvxxcb.reader.domain.model.ChapterRef
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.PageKind
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ParsedCatalog
import com.mvxxcb.reader.domain.model.UrlClassification
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import com.mvxxcb.reader.domain.service.ImportOutcome
import com.mvxxcb.reader.domain.service.ImportPhase
import com.mvxxcb.reader.domain.service.ImportService
import com.mvxxcb.reader.domain.service.ImportStep
import com.mvxxcb.reader.domain.service.RecoveryAction
import com.mvxxcb.reader.domain.service.TempReadOutcome
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import com.mvxxcb.reader.parser.core.UrlClassifier
import com.mvxxcb.reader.parser.pipeline.ParserPipeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 导入编排实现（契约 02 §6.1；装配层组合 parser+data）。
 *
 * 编排语义以 WP-Q 集成测试（ImportFlowIntegrationTest 的参考实现）为 C1 验收基线：
 * classify → detail → catalog → 去重入库，逐相位上报、可取消；
 * 章节页（PageKind.CHAPTER）直达：仅解析正文 → 临时书（并持久化单章内容供直接阅读，
 * 参考实现未持久化属已知缺口，本实现补齐且不破坏任何断言）。
 */
class DefaultImportService(
    private val urlClassifier: UrlClassifier,
    private val pipeline: ParserPipeline,
    private val bookRepository: BookRepository,
    private val chapterRepository: ChapterRepository,
    private val logger: AppLogger,
    // 2.0 整本回溯（契约 v1.1 K8）；可空参数保持 QA 集成测试构造兼容
    private val bookResolver: com.mvxxcb.reader.domain.resolver.BookResolver? = null,
) : ImportService {

    override suspend fun classify(rawUrl: String): AppResult<UrlClassification> =
        urlClassifier.classify(rawUrl)

    override fun importFromUrl(rawUrl: String): Flow<ImportStep> = flow {
        // —— Phase 1：classify ——
        emit(ImportStep.PhaseChanged(ImportPhase.CLASSIFYING))
        val classification = when (val result = urlClassifier.classify(rawUrl)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> {
                emit(ImportStep.PhaseChanged(ImportPhase.FAILED))
                emit(
                    ImportStep.Finished(
                        ImportOutcome.Failure(
                            error = result.error,
                            classification = null,
                            partialDetail = null,
                            recovery = recoveryFor(result.error),
                        )
                    )
                )
                return@flow
            }
        }

        // —— 章节页直达：2.0 先经 BookResolver 整本回溯（成功→完整书导入）；失败→保留一期临时书路径 ——
        if (classification.kind == PageKind.CHAPTER) {
            val resolver = bookResolver
            if (resolver != null) {
                when (val resolution = resolver.resolve(rawUrl)) {
                    is AppResult.Success -> {
                        val res = resolution.value
                        if (!res.requiresCatalogHint && res.detail != null && res.catalog != null) {
                            // 回溯成功：完整书导入（复用 resolver 已解析的详情/目录，避免重复抓取）
                            emit(ImportStep.PhaseChanged(ImportPhase.SAVING))
                            val upsert = when (val result = bookRepository.upsertFromImport(
                                res.detail, res.normalizedUrl, res.catalog, isTemporary = false,
                            )) {
                                is AppResult.Success -> result.value
                                is AppResult.Failure -> {
                                    emit(ImportStep.PhaseChanged(ImportPhase.FAILED))
                                    emit(ImportStep.Finished(
                                        ImportOutcome.Failure(result.error, classification, res.detail, recoveryFor(result.error))))
                                    return@flow
                                }
                            }
                            emit(ImportStep.PhaseChanged(ImportPhase.SUCCESS))
                            emit(ImportStep.Finished(ImportOutcome.Success(
                                bookId = upsert.bookId,
                                detail = res.detail,
                                catalogSize = res.catalog.chapters.size,
                                confidence = res.confidence,
                                warnings = res.evidence,
                                createdTemporary = false,
                                mergedExisting = upsert.mergedExisting,
                            )))
                            return@flow
                        }
                        // requiresCatalogHint / 回溯不完整 → 落入下方临时书路径（evidence 由 UI 层经分类信息提示补充目录 URL）
                    }
                    is AppResult.Failure -> {
                        // 回溯失败不终止：保底临时阅读（一期行为）
                        logger.w(TAG, "BookResolver 回溯失败，转临时书路径 rawUrl=$rawUrl")
                    }
                }
            }
            emit(ImportStep.PhaseChanged(ImportPhase.PARSING_CONTENT))
            val content = when (val outcome =
                pipeline.parseChapterContent(ParseRequest(classification.normalizedUrl))) {
                is ParseOutcome.Success -> outcome
                is ParseOutcome.Failure -> {
                    emit(ImportStep.PhaseChanged(ImportPhase.FAILED))
                    emit(ImportStep.Finished(ImportOutcome.Failure(outcome.error, classification, null, recoveryFor(outcome.error))))
                    return@flow
                }
            }
            emit(ImportStep.PhaseChanged(ImportPhase.SAVING))
            val upsert = when (val result =
                bookRepository.upsertFromImport(null, classification.normalizedUrl, null, isTemporary = true)) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> {
                    emit(ImportStep.PhaseChanged(ImportPhase.FAILED))
                    emit(ImportStep.Finished(ImportOutcome.Failure(result.error, classification, null, recoveryFor(result.error))))
                    return@flow
                }
            }
            // 持久化单章内容，保证「读作临时书」路径可直接进入阅读器
            persistSingleChapter(upsert.bookId, classification.normalizedUrl, content.data)?.let { error ->
                emit(ImportStep.PhaseChanged(ImportPhase.FAILED))
                emit(ImportStep.Finished(ImportOutcome.Failure(error, classification, null, recoveryFor(error))))
                return@flow
            }
            emit(ImportStep.PhaseChanged(ImportPhase.SUCCESS))
            emit(
                ImportStep.Finished(
                    ImportOutcome.Success(
                        bookId = upsert.bookId,
                        detail = null,
                        catalogSize = null,
                        confidence = minConfidence(classification.confidence, content.overallConfidence),
                        warnings = content.warnings,
                        createdTemporary = true,
                        mergedExisting = upsert.mergedExisting,
                    )
                )
            )
            return@flow
        }

        // —— Phase 2：detail ——
        emit(ImportStep.PhaseChanged(ImportPhase.PARSING_DETAIL))
        val detail = when (val outcome = pipeline.parseDetail(ParseRequest(classification.normalizedUrl))) {
            is ParseOutcome.Success -> outcome
            is ParseOutcome.Failure -> {
                emit(ImportStep.PhaseChanged(ImportPhase.FAILED))
                emit(ImportStep.Finished(ImportOutcome.Failure(outcome.error, classification, null, recoveryFor(outcome.error))))
                return@flow
            }
        }

        // —— Phase 3：catalog（目录页 URL = detail.catalogUrl，缺省用分类 URL） ——
        emit(ImportStep.PhaseChanged(ImportPhase.PARSING_CATALOG))
        val catalog = when (val outcome =
            pipeline.parseCatalog(ParseRequest(detail.data.catalogUrl ?: classification.normalizedUrl))) {
            is ParseOutcome.Success -> outcome
            is ParseOutcome.Failure -> {
                emit(ImportStep.PhaseChanged(ImportPhase.FAILED))
                emit(ImportStep.Finished(ImportOutcome.Failure(outcome.error, classification, detail.data, recoveryFor(outcome.error))))
                return@flow
            }
        }

        // —— Phase 4：去重入库（upsertFromImport 内部按规范化 URL 去重并写入目录） ——
        emit(ImportStep.PhaseChanged(ImportPhase.SAVING))
        val upsert = when (val result =
            bookRepository.upsertFromImport(detail.data, classification.normalizedUrl, catalog.data, isTemporary = false)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> {
                emit(ImportStep.PhaseChanged(ImportPhase.FAILED))
                emit(ImportStep.Finished(ImportOutcome.Failure(result.error, classification, detail.data, recoveryFor(result.error))))
                return@flow
            }
        }

        emit(ImportStep.PhaseChanged(ImportPhase.SUCCESS))
        emit(
            ImportStep.Finished(
                ImportOutcome.Success(
                    bookId = upsert.bookId,
                    detail = detail.data,
                    catalogSize = catalog.data.chapters.size,
                    confidence = minConfidence(detail.overallConfidence, catalog.overallConfidence),
                    warnings = detail.warnings + catalog.warnings,
                    createdTemporary = false,
                    mergedExisting = upsert.mergedExisting,
                )
            )
        )
    }

    override suspend fun parseForTemporaryRead(rawUrl: String): TempReadOutcome {
        val classification = when (val result = urlClassifier.classify(rawUrl)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return TempReadOutcome.Failed(result.error)
        }
        val content = when (val outcome = pipeline.parseChapterContent(ParseRequest(classification.normalizedUrl))) {
            is ParseOutcome.Success -> outcome
            is ParseOutcome.Failure -> return TempReadOutcome.Failed(outcome.error)
        }
        val upsert = when (val result =
            bookRepository.upsertFromImport(null, classification.normalizedUrl, null, isTemporary = true)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return TempReadOutcome.Failed(result.error)
        }
        // 持久化单章内容（参考实现缺口补齐：临时书必须可直接阅读）
        val chapterIndex = persistSingleChapter(upsert.bookId, classification.normalizedUrl, content.data)
            ?.let { return TempReadOutcome.Failed(it) }
            ?: chapterIndexOf(upsert.bookId, classification.normalizedUrl)
        return TempReadOutcome.Ready(
            bookId = upsert.bookId,
            chapterIndex = chapterIndex,
            backtracked = false, // 目录回溯语义（REG-IMP-08）一期不做，明确标注
            confidence = minConfidence(classification.confidence, content.overallConfidence),
        )
    }

    /**
     * 单章持久化（契约 v1.0.3 §13.3）：书内已有该 URL 章节 → 直接存内容；否则 appendChapter
     * 追加为末章（幂等、不触碰既有章节的卷标题/进度关联），再存内容。
     * 返回 null 表示成功，否则返回失败错误。
     */
    private suspend fun persistSingleChapter(
        bookId: Long,
        normalizedUrl: String,
        content: com.mvxxcb.reader.domain.model.ParsedChapterContent,
    ): ReaderError? {
        var chapter: com.mvxxcb.reader.domain.model.Chapter? = chapterRepository.getChapterByUrl(bookId, normalizedUrl)
        if (chapter == null) {
            when (val r = chapterRepository.appendChapter(
                bookId,
                ChapterRef(order = 0, title = content.title?.takeIf { it.isNotBlank() } ?: "未命名章节", url = normalizedUrl),
            )) {
                is AppResult.Failure -> return r.error
                is AppResult.Success -> Unit
            }
            chapter = chapterRepository.getChapterByUrl(bookId, normalizedUrl)
                ?: return ReaderError.State(StateFailure.CHAPTER_NOT_FOUND, debugDetail = "单章目录写入后未找到章节")
        }
        return when (val r = chapterRepository.saveContent(chapter.id, content)) {
            is AppResult.Failure -> r.error
            is AppResult.Success -> null
        }.also { if (it != null) logger.w(TAG, "单章内容持久化失败 bookId=$bookId（SEC-6-1：日志不落 URL）") }
    }

    private suspend fun chapterIndexOf(bookId: Long, normalizedUrl: String): Int =
        chapterRepository.getChapterByUrl(bookId, normalizedUrl)?.chapterIndex ?: 0

    /** 恢复动作映射（02 §10 参考推导，与 QA 参考实现一致） */
    private fun recoveryFor(error: ReaderError): List<RecoveryAction> = when (error) {
        is ReaderError.Network -> listOf(RecoveryAction.RETRY)
        is ReaderError.Parse -> when (error.failure) {
            ParseFailure.DYNAMIC_PAGE_REQUIRED -> emptyList()
            ParseFailure.DUPLICATE_CHAPTER_CONFLICT -> listOf(RecoveryAction.OPEN_CATALOG_IF_EXISTS)
            else -> listOf(RecoveryAction.RETRY, RecoveryAction.EDIT_RULE)
        }
        is ReaderError.Security -> emptyList()
        is ReaderError.Storage -> listOf(RecoveryAction.RETRY)
        is ReaderError.State -> emptyList()
        is ReaderError.Unknown -> listOf(RecoveryAction.RETRY)
    }

    private fun rank(c: Confidence): Int = when (c) {
        Confidence.LOW -> 0
        Confidence.MEDIUM -> 1
        Confidence.HIGH -> 2
    }

    private fun minConfidence(a: Confidence, b: Confidence): Confidence =
        if (rank(a) <= rank(b)) a else b

    private companion object {
        const val TAG = "DefaultImportSvc"
    }
}
