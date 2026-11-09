package com.mvxxcb.reader.ui.components

// WP-U（04 §4）：错误渲染组件 —— ReaderError → 用户文案映射（契约 02 §10 全表）。
// key 已由 WP-M 按 §10 全表生成（res/values/strings.xml error_*）；
// HTTP_ERROR_STATUS 需带状态码参数 getString（契约要求）。
// 渲染统一走本文件，禁止各屏自造错误文案（01 §7）。

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mvxxcb.reader.R
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.security.BlockedReason
import com.mvxxcb.reader.ui.theme.spacing

/** ReaderError → strings.xml key 资源 id（契约 02 §10：key = 错误名 snake_case） */
fun ReaderError.messageResId(): Int = when (this) {
    is ReaderError.Network -> when (failure) {
        NetworkFailure.INVALID_URL -> R.string.error_network_invalid_url
        NetworkFailure.DNS_FAILURE -> R.string.error_network_dns_failure
        NetworkFailure.CONNECT_TIMEOUT -> R.string.error_network_connect_timeout
        NetworkFailure.READ_TIMEOUT -> R.string.error_network_read_timeout
        NetworkFailure.CONNECTION_REFUSED -> R.string.error_network_connection_refused
        NetworkFailure.TLS_ERROR -> R.string.error_network_tls_error
        NetworkFailure.HTTP_ERROR_STATUS -> R.string.error_network_http_error_status
        NetworkFailure.TOO_MANY_REDIRECTS -> R.string.error_network_too_many_redirects
        NetworkFailure.REDIRECT_LOOP -> R.string.error_network_redirect_loop
        NetworkFailure.RESPONSE_TOO_LARGE -> R.string.error_network_response_too_large
        NetworkFailure.RATE_LIMITED -> R.string.error_network_rate_limited
        NetworkFailure.EMPTY_RESPONSE -> R.string.error_network_empty_response
        NetworkFailure.CANCELLED -> R.string.error_network_cancelled
        NetworkFailure.OFFLINE -> R.string.error_network_offline
    }

    is ReaderError.Parse -> when (failure) {
        ParseFailure.UNSUPPORTED_STRUCTURE -> R.string.error_parse_unsupported_structure
        ParseFailure.DETAIL_INCOMPLETE -> R.string.error_parse_detail_incomplete
        ParseFailure.CATALOG_EMPTY -> R.string.error_parse_catalog_empty
        ParseFailure.CATALOG_PAGINATION_FAILED -> R.string.error_parse_catalog_pagination_failed
        ParseFailure.CONTENT_EMPTY -> R.string.error_parse_content_empty
        ParseFailure.CONTENT_PAGINATION_FAILED -> R.string.error_parse_content_pagination_failed
        ParseFailure.PAGINATION_LOOP_DETECTED -> R.string.error_parse_pagination_loop_detected
        ParseFailure.ENCODING_FAILED -> R.string.error_parse_encoding_failed
        ParseFailure.DYNAMIC_PAGE_REQUIRED -> R.string.error_parse_dynamic_page_required
        ParseFailure.LOW_CONFIDENCE -> R.string.error_parse_low_confidence
        ParseFailure.DUPLICATE_CHAPTER_CONFLICT -> R.string.error_parse_duplicate_chapter_conflict
        ParseFailure.TIMEOUT_BUDGET_EXCEEDED -> R.string.error_parse_timeout_budget_exceeded
        ParseFailure.CATALOG_UNRESOLVED -> R.string.error_parse_catalog_unresolved
    }

    is ReaderError.Security -> when (reason) {
        BlockedReason.NON_HTTP_SCHEME -> R.string.error_security_non_http_scheme
        BlockedReason.INVALID_HOST -> R.string.error_security_invalid_host
        BlockedReason.MALFORMED_URL -> R.string.error_security_malformed_url
        BlockedReason.LOOPBACK -> R.string.error_security_loopback
        BlockedReason.PRIVATE_ADDRESS -> R.string.error_security_private_address
        BlockedReason.LINK_LOCAL -> R.string.error_security_link_local
        BlockedReason.RESERVED_ADDRESS -> R.string.error_security_reserved_address
        BlockedReason.METADATA_ENDPOINT -> R.string.error_security_metadata_endpoint
        BlockedReason.REDIRECT_LOOP -> R.string.error_security_redirect_loop
        BlockedReason.REDIRECT_LIMIT_EXCEEDED -> R.string.error_security_redirect_limit_exceeded
    }

    is ReaderError.Storage -> when (failure) {
        StorageFailure.DB_ERROR -> R.string.error_storage_db_error
        StorageFailure.DISK_IO -> R.string.error_storage_disk_io
        StorageFailure.DISK_FULL -> R.string.error_storage_disk_full
        StorageFailure.CACHE_CORRUPT -> R.string.error_storage_cache_corrupt
        StorageFailure.TXT_WRITE_FAILED -> R.string.error_storage_txt_write_failed
    }

    is ReaderError.State -> when (failure) {
        StateFailure.BOOK_NOT_FOUND -> R.string.error_state_book_not_found
        StateFailure.CHAPTER_NOT_FOUND -> R.string.error_state_chapter_not_found
        StateFailure.CHAPTER_NOT_CACHED -> R.string.error_state_chapter_not_cached
        StateFailure.INVALID_ARGUMENT -> R.string.error_state_invalid_argument
        StateFailure.CRAWL_TASK_NOT_FOUND -> R.string.error_state_crawl_task_not_found
        StateFailure.INVALID_TASK_STATE -> R.string.error_state_invalid_task_state
        StateFailure.LOCAL_BOOK_NOT_FOUND -> R.string.error_state_local_book_not_found
    }

    is ReaderError.Unknown -> R.string.error_unknown
}

/** ReaderError → 用户文案（HTTP_ERROR_STATUS 带状态码参数） */
fun ReaderError.toUserMessage(context: Context): String = when (this) {
    is ReaderError.Network ->
        if (failure == NetworkFailure.HTTP_ERROR_STATUS) {
            context.getString(R.string.error_network_http_error_status, statusCode ?: 0)
        } else {
            context.getString(messageResId())
        }
    else -> context.getString(messageResId())
}

/** Compose 场景的 ReaderError 文案 */
@Composable
fun rememberErrorText(error: ReaderError): String {
    val context = LocalContext.current
    return error.toUserMessage(context)
}

/**
 * 错误文本（简单行内展示）：ReaderError → §10 映射文案。
 */
@Composable
fun ErrorText(
    error: ReaderError,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.error,
) {
    Text(text = rememberErrorText(error), modifier = modifier, color = color)
}

/**
 * 错误横幅：文案 + 可选重试/附加操作/关闭。
 */
@Composable
fun ErrorBanner(
    error: ReaderError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rememberErrorText(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        if (secondaryText != null && onSecondary != null) {
            OutlinedButton(onClick = onSecondary) { Text(secondaryText) }
        }
        if (onRetry != null) {
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
        if (onDismiss != null) {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_back)) }
        }
    }
}
