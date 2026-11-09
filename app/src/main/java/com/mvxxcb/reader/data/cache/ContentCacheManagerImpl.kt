package com.mvxxcb.reader.data.cache

import com.mvxxcb.reader.common.coroutine.DispatcherProvider
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.data.db.dao.ChapterDao
import com.mvxxcb.reader.data.mapper.ContentBlockCodec
import com.mvxxcb.reader.domain.cache.CacheBatchEvent
import com.mvxxcb.reader.domain.cache.CacheBatchReport
import com.mvxxcb.reader.domain.cache.CacheStats
import com.mvxxcb.reader.domain.cache.ChapterFailure
import com.mvxxcb.reader.domain.cache.ChapterFetcher
import com.mvxxcb.reader.domain.cache.ContentCacheManager
import com.mvxxcb.reader.domain.cache.IntegrityReport
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.domain.repository.ChapterRepository
// ParseOutcome 为契约 02 §6.3 ChapterFetcher 签名的一部分（domain.cache 引用的契约类型），
// data 层仅做成功/失败分派，不 import parser 实现（依赖红线契约 02 §0.5）。
import com.mvxxcb.reader.parser.core.ParseOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * ContentCacheManager 实现（契约 02 §7.1）。
 *
 * - cacheChapters：用户触发的批量缓存事件流，事件顺序
 *   Started → (Progress → ChapterFinished)* → Completed；同书串行抓取（01 §5.8）；
 *   取消协作：cancelActiveBatch 取消批次协程，抓取/写库在挂起点即时中断。
 * - prefetchNeighborhood：centerIndex±radius 未缓存章节串行预取，失败静默（只记日志）。
 * - verifyBookIntegrity：对已缓存章节重算 contentHash 比对（03 §8），篡改/损坏报 corrupted。
 *
 * 一期进程内执行；二期切 WorkManager 持久任务（data/work 骨架，04 §3）。
 */
class ContentCacheManagerImpl(
    private val chapterRepository: ChapterRepository,
    private val chapterDao: ChapterDao,
    private val fetcher: ChapterFetcher,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger,
) : ContentCacheManager {

    companion object {
        private const val TAG = "ContentCacheManager"
    }

    /** bookId → 当前批次协程（channelFlow 作用域 Job） */
    private val activeBatches = ConcurrentHashMap<Long, Job>()

    override fun cacheChapters(bookId: Long, chapterIds: List<Long>): Flow<CacheBatchEvent> =
        channelFlow {
            val batchJob = currentCoroutineContext().job
            // 同书已有批次在跑：取消旧批次（同书串行原则，01 §5.8）
            activeBatches.put(bookId, batchJob)?.let { previous ->
                if (previous.isActive) {
                    logger.w(TAG, "同书重复批量缓存，取消前一批 bookId=$bookId")
                    previous.cancel()
                }
            }
            var done = 0
            var succeeded = 0
            val failures = mutableListOf<ChapterFailure>()

            try {
                send(CacheBatchEvent.Started(chapterIds.size))
                for (chapterId in chapterIds) {
                    // 每章开始前检查取消（01 §5.4 取消协作）
                    currentCoroutineContext().ensureActive()

                    val meta = chapterRepository.getChapterMeta(chapterId)
                    if (meta == null) {
                        val err = ReaderError.State(
                            StateFailure.CHAPTER_NOT_FOUND, "cacheChapters: chapterId=$chapterId"
                        )
                        failures += ChapterFailure(chapterId, err)
                        send(CacheBatchEvent.ChapterFinished(chapterId, false, err))
                        done++
                        continue
                    }

                    send(CacheBatchEvent.Progress(done, chapterIds.size, meta.title))

                    when (val outcome = fetcher.fetch(meta.url)) {
                        is ParseOutcome.Success -> {
                            val saved = chapterRepository.saveContent(chapterId, outcome.data)
                            val err = saved.errorOrNull()
                            if (err == null) {
                                succeeded++
                                send(CacheBatchEvent.ChapterFinished(chapterId, true, null))
                            } else {
                                failures += ChapterFailure(chapterId, err)
                                send(CacheBatchEvent.ChapterFinished(chapterId, false, err))
                            }
                        }

                        is ParseOutcome.Failure -> {
                            failures += ChapterFailure(chapterId, outcome.error)
                            send(CacheBatchEvent.ChapterFinished(chapterId, false, outcome.error))
                        }
                    }
                    done++
                }
                send(
                    CacheBatchEvent.Completed(
                        CacheBatchReport(
                            succeeded = succeeded,
                            failed = failures.size,
                            cancelled = false,
                            failures = failures.toList(),
                        )
                    )
                )
            } catch (e: CancellationException) {
                // 取消即完成：尽力送达带 cancelled=true 的收尾报告（收集者已退出则静默丢弃）
                trySend(
                    CacheBatchEvent.Completed(
                        CacheBatchReport(
                            succeeded = succeeded,
                            failed = failures.size,
                            cancelled = true,
                            failures = failures.toList(),
                        )
                    )
                )
                throw e
            } finally {
                activeBatches.remove(bookId, batchJob)
            }
        }

    override suspend fun prefetchNeighborhood(
        bookId: Long,
        centerChapterIndex: Int,
        radius: Int,
    ): AppResult<Int> = withContext(dispatchers.io) {
        try {
            val from = (centerChapterIndex - radius).coerceAtLeast(0)
            val to = centerChapterIndex + radius
            val targets = chapterDao.getUncachedBetween(bookId, from, to)
            var ok = 0
            // 串行抓取（同书串行原则，01 §5.8）；预取失败静默（01 §5.7）
            for (target in targets) {
                val outcome = fetcher.fetch(target.url)
                if (outcome is ParseOutcome.Success) {
                    val saved = chapterRepository.saveContent(target.id, outcome.data)
                    if (saved is AppResult.Success) ok++ else logPrefetchFailure(target.id, saved.errorOrNull())
                } else if (outcome is ParseOutcome.Failure) {
                    logPrefetchFailure(target.id, outcome.error)
                }
            }
            AppResult.Success(ok)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "prefetchNeighborhood 失败 bookId=$bookId", e)
            AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "prefetch failed", e))
        }
    }

    override fun observeCacheStats(bookId: Long): Flow<CacheStats> =
        combine(
            chapterDao.observeCachedCount(bookId),
            chapterDao.observeTotalCount(bookId),
            chapterDao.observeTotalChars(bookId),
        ) { cached, total, chars -> CacheStats(cachedCount = cached, totalCount = total, totalChars = chars) }

    override suspend fun clearBookCache(bookId: Long): AppResult<Unit> = try {
        chapterDao.clearBookCache(bookId)
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "clearBookCache 失败 bookId=$bookId", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "clearBookCache failed", e))
    }

    override suspend fun clearAllCaches(): AppResult<Unit> = try {
        chapterDao.clearAllCaches()
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "clearAllCaches 失败", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "clearAllCaches failed", e))
    }

    override suspend fun verifyBookIntegrity(bookId: Long): AppResult<IntegrityReport> =
        withContext(dispatchers.default) {
            try {
                val cached = chapterDao.getCachedChapters(bookId)
                val corrupted = mutableListOf<Long>()
                for (row in cached) {
                    val corruptedRow =
                        row.content == null || // isCached=1 却无正文 → 不一致
                            row.contentHash == null ||
                            recomputeHash(row.content) != row.contentHash
                    if (corruptedRow) corrupted += row.id
                }
                AppResult.Success(IntegrityReport(checked = cached.size, corruptedChapterIds = corrupted))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "verifyBookIntegrity 失败 bookId=$bookId", e)
                AppResult.Failure(ReaderError.Storage(StorageFailure.CACHE_CORRUPT, "verify failed", e))
            }
        }

    override suspend fun cancelActiveBatch(bookId: Long) {
        activeBatches.remove(bookId)?.cancel()
    }

    // ———— 内部 ————

    /** 重算 contentHash：正文块 JSON → 段落文本 \n 连接 → SHA-256（03 §8）；解析失败视为损坏 */
    private fun recomputeHash(contentJson: String): String? =
        ContentBlockCodec.blocksFromJson(contentJson)?.let { ContentBlockCodec.computeContentHash(it) }

    private fun logPrefetchFailure(chapterId: Long, error: ReaderError?) {
        logger.w(TAG, "预取失败（静默）chapterId=$chapterId error=${error?.let { it::class.simpleName }}")
    }
}
