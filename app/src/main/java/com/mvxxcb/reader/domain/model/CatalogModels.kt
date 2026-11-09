package com.mvxxcb.reader.domain.model

// —— 跨层共用值类型（契约 02 §7 开头定义，与 §2 领域模型同包） ——

data class BookUpsertResult(val bookId: Long, val isNew: Boolean, val mergedExisting: Boolean)

data class CatalogMergeReport(
    val added: Int, val unchanged: Int, val updatedUrl: Int, val markedMissing: Int,
    val matchedRatio: Double,           // 新旧目录匹配率 0..1
    val detectedReversal: Boolean,
    val requiresReview: Boolean,        // matchedRatio < 0.6 → UI 需提示人工确认
)
