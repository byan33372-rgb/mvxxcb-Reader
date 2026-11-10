package com.mvxxcb.reader.di

import android.content.Context
import androidx.room.Room
import com.mvxxcb.reader.common.coroutine.DefaultDispatcherProvider
import com.mvxxcb.reader.common.coroutine.DispatcherProvider
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.log.LogcatLogger
import com.mvxxcb.reader.common.time.SystemTimeProvider
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.data.cache.ContentCacheManagerImpl
import com.mvxxcb.reader.data.datastore.AppPreferencesDataStore
import com.mvxxcb.reader.data.datastore.ReaderSettingsDataStore
import com.mvxxcb.reader.data.db.AppDatabase
import com.mvxxcb.reader.data.repository.AppPreferencesRepositoryImpl
import com.mvxxcb.reader.data.repository.BookRepositoryImpl
import com.mvxxcb.reader.data.repository.BookmarkRepositoryImpl
import com.mvxxcb.reader.data.repository.CatalogMerger
import com.mvxxcb.reader.data.repository.ChapterRepositoryImpl
import com.mvxxcb.reader.data.repository.ReaderSettingsRepositoryImpl
import com.mvxxcb.reader.data.repository.ReadingProgressRepositoryImpl
import com.mvxxcb.reader.data.repository.SourceRuleRepositoryImpl
import com.mvxxcb.reader.domain.cache.ChapterFetcher
import com.mvxxcb.reader.domain.cache.ContentCacheManager
import com.mvxxcb.reader.domain.repository.AppPreferencesRepository
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.BookmarkRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import com.mvxxcb.reader.domain.repository.ReaderSettingsRepository
import com.mvxxcb.reader.domain.repository.ReadingProgressRepository
import com.mvxxcb.reader.domain.repository.SourceRuleRepository
import com.mvxxcb.reader.domain.rule.RuleJsonValidator
import com.mvxxcb.reader.domain.service.ImportService
import com.mvxxcb.reader.domain.service.ReadingService
import com.mvxxcb.reader.parser.core.ParseRequest
import com.mvxxcb.reader.parser.core.UrlClassifier
import com.mvxxcb.reader.parser.core.UrlClassifierImpl
import com.mvxxcb.reader.parser.pipeline.ParserPipeline
import com.mvxxcb.reader.parser.pipeline.ParserPipelineImpl
import com.mvxxcb.reader.parser.rule.RuleEngine
import com.mvxxcb.reader.parser.rule.RuleEngineImpl
import com.mvxxcb.reader.reader.engine.TextPager
import com.mvxxcb.reader.reader.engine.TextPagerImpl
import com.mvxxcb.reader.security.ContentSanitizer
import com.mvxxcb.reader.security.ContentSanitizerImpl
import com.mvxxcb.reader.security.NetworkLimits
import com.mvxxcb.reader.security.SecureHttpClient
import com.mvxxcb.reader.security.SecureHttpClientImpl
import com.mvxxcb.reader.security.UrlSecurityValidator
import com.mvxxcb.reader.security.UrlSecurityValidatorImpl
import com.mvxxcb.reader.service.DefaultImportService
import com.mvxxcb.reader.service.DefaultReadingService
import com.mvxxcb.reader.service.RuleJsonValidatorImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 手动 DI 容器（01 §8）—— 主 Agent 所有。v1：全量装配（阶段 5 整合）。
 *
 * 装配顺序（01 §8）：validator → sanitizer → http → DB/DataStore → repos → ruleEngine
 * → pipeline → classifier → fetcher 桥接 → cacheManager → 领域服务 → textPager。
 * 全部单例（by lazy）；基础件可整体注入替换（契约 02 §11.1）。
 * ViewModel 一律经 companion fun factory(container, args…) 由此取依赖（契约 02 §0.6）。
 */
class AppContainer(context: Context) {

    /** 应用级 Context（不持有 Activity 引用） */
    val appContext: Context = context.applicationContext

    // ———— 基础件 ————

    val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
    val timeProvider: TimeProvider = SystemTimeProvider()

    /** 日志环形缓冲（2.0 WP-DIAG）：LogcatLogger 具体类型以暴露 snapshotRecent 诊断缓冲 */
    val logBuffer: LogcatLogger = LogcatLogger()

    /** 既有全量引用点不变：logger 仍以 AppLogger 门面暴露 */
    val logger: AppLogger get() = logBuffer

    /** DataStore 等长生命周期协程作用域 */
    private val appScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    // ———— security 层（WP-S；生产装配不传 allowListForTest，无测试后门） ————

    val urlSecurityValidator: UrlSecurityValidator by lazy { UrlSecurityValidatorImpl() }

    val contentSanitizer: ContentSanitizer by lazy { ContentSanitizerImpl() }

    /** 全应用唯一 OkHttpClient（内部持有，外部不可见；契约 02 §4.2） */
    val secureHttpClient: SecureHttpClient by lazy {
        SecureHttpClientImpl(urlSecurityValidator, NetworkLimits.DEFAULT, timeProvider, dispatchers, logger)
    }

    // ———— data 层（WP-D） ————

    private val database: AppDatabase by lazy {
        AppDatabase.build(appContext) // WP-M（T-D4）：统一构建点，迁移经 AppMigrations 注册表挂载
    }

    private val catalogMerger: CatalogMerger by lazy {
        CatalogMerger(database, database.chapterDao(), database.bookDao(), logger)
    }

    private val readerSettingsDataStore: ReaderSettingsDataStore by lazy {
        ReaderSettingsDataStore(appContext, logger, appScope)
    }

    private val appPreferencesDataStore: AppPreferencesDataStore by lazy {
        AppPreferencesDataStore(appContext, logger, appScope)
    }

    val bookRepository: BookRepository by lazy {
        BookRepositoryImpl(database, database.bookDao(), catalogMerger, timeProvider, logger)
    }

    val chapterRepository: ChapterRepository by lazy {
        ChapterRepositoryImpl(database, database.chapterDao(), catalogMerger, timeProvider, logger)
    }

    val readingProgressRepository: com.mvxxcb.reader.domain.repository.ReadingProgressRepository by lazy {
        ReadingProgressRepositoryImpl(database.readingProgressDao(), timeProvider, logger)
    }

    val bookmarkRepository: BookmarkRepository by lazy {
        BookmarkRepositoryImpl(database.bookmarkDao(), timeProvider, logger)
    }

    val sourceRuleRepository: SourceRuleRepository by lazy {
        SourceRuleRepositoryImpl(database.sourceRuleDao(), timeProvider, logger)
    }

    val readerSettingsRepository: ReaderSettingsRepository by lazy {
        ReaderSettingsRepositoryImpl(readerSettingsDataStore, logger)
    }

    val appPreferencesRepository: AppPreferencesRepository by lazy {
        AppPreferencesRepositoryImpl(appPreferencesDataStore, logger)
    }

    // ———— parser 层（WP-P；v1.0.3：RuleEngineImpl 双参 / Pipeline 7 参） ————

    val ruleEngine: RuleEngine by lazy { RuleEngineImpl(sourceRuleRepository, contentSanitizer) }

    val parserPipeline: ParserPipeline by lazy {
        ParserPipelineImpl(secureHttpClient, ruleEngine, sourceRuleRepository, contentSanitizer, dispatchers, timeProvider, logger)
    }

    val urlClassifier: UrlClassifier by lazy { UrlClassifierImpl(secureHttpClient, ruleEngine, dispatchers, logger) }

    // ———— 解耦桥接（装配层） ————

    /** data → parser 唯一通道（契约 02 §6.3）：ParserPipeline::parseChapterContent */
    val chapterFetcher: ChapterFetcher by lazy {
        ChapterFetcher { url -> parserPipeline.parseChapterContent(ParseRequest(url)) }
    }

    val contentCacheManager: ContentCacheManager by lazy {
        ContentCacheManagerImpl(chapterRepository, database.chapterDao(), chapterFetcher, dispatchers, logger)
    }

    // ———— 领域服务（装配层组合 parser+data） ————

    val importService: ImportService by lazy {
        DefaultImportService(
            urlClassifier = urlClassifier,
            pipeline = parserPipeline,
            bookRepository = bookRepository,
            chapterRepository = chapterRepository,
            logger = logger,
            bookResolver = bookResolver,
        )
    }

    val readingService: ReadingService by lazy {
        DefaultReadingService(
            bookRepository = bookRepository,
            chapterRepository = chapterRepository,
            pipeline = parserPipeline,
            timeProvider = timeProvider,
            logger = logger,
            crawlTaskRepository = crawlTaskRepository,
            crawlScheduler = crawlTaskScheduler,
            txtExporter = txtExporter,
            localBookRepository = localBookRepository,
            txtPathBuilder = txtPathBuilder,
        )
    }

    // ———— 其他门面 ————

    val ruleJsonValidator: RuleJsonValidator by lazy { RuleJsonValidatorImpl(ruleEngine) }

    // ———— 阅读引擎（WP-U） ————

    val textPager: TextPager by lazy { TextPagerImpl() }

    // ———— 2.0 契约 v1.1 骨架（WP-M0）：新 DAO 即时可用；新接口实现由 B2/B3 批次接入 ————

    val crawlTaskDao: com.mvxxcb.reader.data.db.dao.CrawlTaskDao by lazy { database.crawlTaskDao() }

    val localBookDao: com.mvxxcb.reader.data.db.dao.LocalBookDao by lazy { database.localBookDao() }

    // 2.0 整本识别（WP-R 批次交付；静态回溯链 K8）
    val bookResolver: com.mvxxcb.reader.domain.resolver.BookResolver by lazy {
        com.mvxxcb.reader.parser.resolver.BookResolverImpl(
            secureHttpClient = secureHttpClient,
            pipeline = parserPipeline,
            classifier = urlClassifier,
            dispatchers = dispatchers,
            timeProvider = timeProvider,
            logger = logger,
        )
    }

    // ———— 2.0 采集/本地书（主 Agent 整合批次接线，实现方 WP-C/WP-L） ————

    val crawlTaskRepository: com.mvxxcb.reader.domain.repository.CrawlTaskRepository by lazy {
        com.mvxxcb.reader.data.repository.CrawlTaskRepositoryImpl(crawlTaskDao, timeProvider, logger)
    }

    val localBookRepository: com.mvxxcb.reader.domain.repository.LocalBookRepository by lazy {
        com.mvxxcb.reader.data.repository.LocalBookRepositoryImpl(localBookDao, timeProvider, logger)
    }

    val txtExporter: com.mvxxcb.reader.domain.export.TxtExporter by lazy {
        com.mvxxcb.reader.data.export.TxtExporterImpl(bookRepository, chapterRepository, dispatchers, timeProvider, logger)
    }

    val crawlTaskScheduler: com.mvxxcb.reader.data.work.CrawlTaskScheduler by lazy {
        com.mvxxcb.reader.data.work.CrawlTaskScheduler(appContext, logger)
    }

    /** TXT 输出路径构造（应用受控目录 filesDir/txt/<bookId>/；文件名清洗复用 TxtFilePaths） */
    val txtPathBuilder: (bookId: Long, title: String) -> String = { bookId, title ->
        val safeName = com.mvxxcb.reader.data.export.TxtFilePaths.sanitizeFileName(title)
            .ifBlank { "book-$bookId" }
        java.io.File(java.io.File(appContext.filesDir, "txt/$bookId"), "$safeName.txt").absolutePath
    }

    // ———— 诊断反馈（2.0 WP-DIAG）：监察日志缓冲 + 一键反馈文件 ————

    val diagnosticsService: com.mvxxcb.reader.service.DiagnosticsService by lazy {
        com.mvxxcb.reader.service.DiagnosticsService(
            appContext = appContext,
            database = database,
            readerSettingsRepository = readerSettingsRepository,
            crawlTaskRepository = crawlTaskRepository,
            localBookRepository = localBookRepository,
            logBuffer = logBuffer,
            timeProvider = timeProvider,
            logger = logger,
        )
    }

    /** 应用启动对账（07 风险#7）：重启后对齐 WAITING/RUNNING 采集任务与 WorkManager 队列 */
    fun onAppStart() {
        appScope.launch {
            runCatching {
                crawlTaskScheduler.reconcileOnAppStart(crawlTaskRepository)
            }.onFailure { logger.w("AppContainer", "采集任务启动对账失败（忽略）", it) }
        }
    }
}
