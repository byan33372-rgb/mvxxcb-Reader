package com.mvxxcb.reader.common.result

import com.mvxxcb.reader.domain.error.ReaderError

sealed interface AppResult<out T> {
    data class Success<out T>(val value: T) : AppResult<T>
    data class Failure(val error: com.mvxxcb.reader.domain.error.ReaderError) : AppResult<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): ReaderError? = (this as? Failure)?.error
    fun <R> map(transform: (T) -> R): AppResult<R> =
        when (this) { is Success -> Success(transform(value)); is Failure -> this }
    fun onSuccess(block: (T) -> Unit): AppResult<T> { if (this is Success) block(value); return this }
    fun onFailure(block: (ReaderError) -> Unit): AppResult<T> { if (this is Failure) block(error); return this }
}
