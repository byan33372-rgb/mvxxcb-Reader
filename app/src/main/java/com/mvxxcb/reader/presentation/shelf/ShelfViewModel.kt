package com.mvxxcb.reader.presentation.shelf

// 实现归属 WP-U（04 §4）。契约依据：02 §8.2。
// 书架：观察 BookRepository.observeShelf（Room Flow，lastReadAt 倒序），
// 首帧后 isLoading=false；删除前由 Screen 弹确认，本 VM 只执行删除。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.domain.repository.BookRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ShelfUiState(
    val isLoading: Boolean = true,
    val books: List<Book> = emptyList(),
    val error: ReaderError? = null,
)

class ShelfViewModel(
    private val bookRepository: BookRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShelfUiState())

    /** 契约形态：StateFlow<ShelfUiState>（01 §5.2） */
    val uiState: StateFlow<ShelfUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    init {
        refresh()
    }

    /** 重新订阅书架流（Room Flow 自动更新；refresh 用于错误后手动重连） */
    fun refresh() {
        observeJob?.cancel()
        _uiState.update { it.copy(isLoading = it.books.isEmpty(), error = null) }
        observeJob = viewModelScope.launch {
            bookRepository.observeShelf()
                .catch { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = ReaderError.Unknown(cause = e))
                    }
                }
                .collect { books ->
                    _uiState.update { it.copy(isLoading = false, books = books, error = null) }
                }
        }
    }

    /** 删除书（级联章节/进度/书签）；确认对话框由 Screen 负责 */
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

    companion object {
        /** 手动 DI 创建入口（契约 02 §0.6） */
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ShelfViewModel(container.bookRepository) as T
            }
    }
}
