package com.mvxxcb.reader.presentation.shelf

// WP-U（04 §4）→ 2.0 WP-L（07 §6.2 / B3）五分组升级。
// 书架 = observeShelf（Room Flow，lastReadAt 倒序）× observeTasks（采集任务）×
// localBookRepository.observeForBook 按书合并（VM 层 combine + flatMapLatest）。
// 下载态判定为纯函数 ShelfGrouping（可单测，五种书形态→分组正确）；
// 删除前由 Screen 弹确认，本 VM 只执行删除。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.CrawlTask
import com.mvxxcb.reader.domain.model.LocalBook
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.CrawlTaskRepository
import com.mvxxcb.reader.domain.repository.LocalBookRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShelfUiState(
    val isLoading: Boolean = true,
    val books: List<Book> = emptyList(),
    /** 书 id → 下载态角标（null=在线书，无角标）；分组过滤与卡片角标共用 */
    val downloadBadges: Map<Long, ShelfDownloadBadge?> = emptyMap(),
    val error: ReaderError? = null,
)

/** 下载态角标（卡片展示；无角标 = 在线书） */
enum class ShelfDownloadBadge { DOWNLOADING, DOWNLOADED, PARTIAL, LOCAL }

/** 书架五分组过滤 chips（任务书冻结：全部/在线/下载中/已下载/本地导入） */
enum class ShelfGroup(val label: String) {
    ALL("全部"),
    ONLINE("在线"),
    DOWNLOADING("下载中"),
    DOWNLOADED("已下载"),
    LOCAL("本地导入"),
}

/**
 * 书架分组判定（纯函数，五种书形态→分组正确）。
 *
 * 判定语义（按优先级，任务书定义 + 实际数据形态取合理解释，已与「本地导入」判定冲突消解）：
 * 1. 下载中 = 存在非终态任务（WAITING/RUNNING/PAUSED；K9 同书非终态唯一）；
 * 2. 本地导入 = 从未发起采集（无任何任务记录）且 local_books 存在登记。
 *    （任务书原文：sourceType 本地（v2 列）或 local_books 存在且无爬取任务——域层 Book
 *    未暴露 sourceType（domain 冻结不越权），故按实际数据形态取 local_books 且无任务判定；
 *    该优先级消解了「已完整下载=local.isComplete=true」对本地导入书的误吞——本地导入书
 *    无任务记录，恒归本地导入，不受 isComplete 影响。）
 * 3. 已完整下载 = local_books.isComplete=true 或任务 COMPLETED；
 * 4. 部分下载 = 任务终态（FAILED/CANCELLED）但未完成（TXT 不完整/未生成）；
 * 5. 在线 = 无任务、无本地文件登记（仅在线阅读的书）。
 *
 * 分组映射：「部分下载」书归入「已下载」chip（五 chips 为冻结 UI），卡片角标
 * （已下载/部分下载）区分完整与部分，语义不丢失。
 */
object ShelfGrouping {

    private val NON_TERMINAL: Set<CrawlState> =
        setOf(CrawlState.WAITING, CrawlState.RUNNING, CrawlState.PAUSED)

    /** 单书下载态判定；null = 在线书（无角标） */
    fun badgeOf(tasks: List<CrawlTask>, local: LocalBook?): ShelfDownloadBadge? {
        // 1) 下载中：非终态任务优先（重新下载/断点续传中的书恒视为下载中）
        if (tasks.any { it.state in NON_TERMINAL }) return ShelfDownloadBadge.DOWNLOADING
        val latest = tasks.maxByOrNull { it.updatedAt }
        // 2) 本地导入：从未采集 + 有本地登记文件
        if (latest == null && local != null) return ShelfDownloadBadge.LOCAL
        // 3) 已完整下载：TXT 登记完整 或 任务 COMPLETED
        if (local?.isComplete == true || latest?.state == CrawlState.COMPLETED) {
            return ShelfDownloadBadge.DOWNLOADED
        }
        // 4) 部分下载：任务终态（FAILED/CANCELLED）但未完成（COMPLETED 已在 3 返回）
        if (latest != null) return ShelfDownloadBadge.PARTIAL
        // 5) 在线：无任务、无登记
        return null
    }

    /** 角标 → 过滤分组（部分下载归入「已下载」chip；卡片角标区分完整/部分） */
    fun groupOf(badge: ShelfDownloadBadge?): ShelfGroup = when (badge) {
        null -> ShelfGroup.ONLINE
        ShelfDownloadBadge.DOWNLOADING -> ShelfGroup.DOWNLOADING
        ShelfDownloadBadge.DOWNLOADED, ShelfDownloadBadge.PARTIAL -> ShelfGroup.DOWNLOADED
        ShelfDownloadBadge.LOCAL -> ShelfGroup.LOCAL
    }

    /** 全书架角标表（按书聚合任务与登记文件） */
    fun badgesOf(
        books: List<Book>,
        tasks: List<CrawlTask>,
        localByBook: Map<Long, LocalBook?>,
    ): Map<Long, ShelfDownloadBadge?> = books.associate { book ->
        val id = book.id
        id to badgeOf(tasks.filter { it.bookId == id }, localByBook[id])
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShelfViewModel(
    private val bookRepository: BookRepository,
    private val crawlTaskRepository: CrawlTaskRepository,
    private val localBookRepository: LocalBookRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShelfUiState())

    /** 契约形态：StateFlow<ShelfUiState>（01 §5.2） */
    val uiState: StateFlow<ShelfUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    init {
        refresh()
    }

    /** 重新订阅书架/任务/本地书三流（Room Flow 自动更新；refresh 用于错误后手动重连） */
    fun refresh() {
        observeJob?.cancel()
        _uiState.update { it.copy(isLoading = it.books.isEmpty(), error = null) }
        observeJob = viewModelScope.launch {
            bookRepository.observeShelf()
                .flatMapLatest { books ->
                    combine(
                        crawlTaskRepository.observeTasks(),
                        localBooksFlow(books),
                    ) { tasks, localByBook -> Triple(books, tasks, localByBook) }
                }
                .catch { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = ReaderError.Unknown(cause = e))
                    }
                }
                .collect { (books, tasks, localByBook) ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            books = books,
                            downloadBadges = ShelfGrouping.badgesOf(books, tasks, localByBook),
                            error = null,
                        )
                    }
                }
        }
    }

    /** 删除书（级联章节/进度/书签；2.0 v2 FK CASCADE 同时级联采集任务与 TXT 登记）；确认对话框由 Screen 负责 */
    fun removeBook(bookId: Long) {
        viewModelScope.launch {
            bookRepository.deleteBook(bookId)
                .onFailure { error ->
                    _uiState.update { it.copy(error = error) }
                }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 按书合并 local_books 登记流（observeForBook 无全表观察，书量级下逐书 combine） */
    private fun localBooksFlow(books: List<Book>) =
        if (books.isEmpty()) {
            flowOf(emptyMap<Long, LocalBook?>())
        } else {
            combine(books.map { book -> localBookRepository.observeForBook(book.id) }) { values ->
                books.mapIndexed { index, book -> book.id to values[index] }.toMap()
            }
        }

    companion object {
        /** 手动 DI 创建入口（契约 02 §0.6） */
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ShelfViewModel(
                        container.bookRepository,
                        container.crawlTaskRepository,
                        container.localBookRepository,
                    ) as T
            }
    }
}
