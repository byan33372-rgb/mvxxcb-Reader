package com.mvxxcb.reader.parser.core

// 契约 02 §5.2：解析管线请求/选项/输出模型（签名文件由 WP-M 生成；
// 解析实现与校验逻辑在 parser 包内由 WP-P 提供，见 04 §2）。

data class ParseRequest(
    val url: String,                        // 已规范化 URL
    val options: ParseOptions = ParseOptions.DEFAULT,
)

data class ParseOptions(
    val maxCatalogPages: Int = 20,
    val maxContentPages: Int = 10,
    val maxTotalRequests: Int = 30,
    val maxContentChars: Int = 200_000,
    val allowPaginationMerge: Boolean = true,
    val confidenceThreshold: com.mvxxcb.reader.domain.model.Confidence =
        com.mvxxcb.reader.domain.model.Confidence.MEDIUM,
) { companion object { val DEFAULT = ParseOptions() } }

sealed interface ParseOutcome<out T> {
    data class Success<T>(
        val data: T,
        val overallConfidence: com.mvxxcb.reader.domain.model.Confidence,
        val layerTrace: List<LayerTrace>,
        val warnings: List<String>,
    ) : ParseOutcome<T>
    data class Failure(
        val error: com.mvxxcb.reader.domain.error.ReaderError,
        val layerTrace: List<LayerTrace>,
    ) : ParseOutcome<Nothing>
}

data class LayerTrace(
    val layer: com.mvxxcb.reader.domain.model.ParseLayer,
    val outcome: LayerOutcome,
    val fieldsFilled: List<String> = emptyList(),
    val detail: String? = null,
)
enum class LayerOutcome { APPLIED, PARTIAL, SKIPPED_NO_RULE, FAILED, NOT_NEEDED }
