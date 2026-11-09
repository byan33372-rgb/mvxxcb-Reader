package com.mvxxcb.reader.parser.structured

import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField

/**
 * 层① 结构化提取的字段候选（parser 内部类型，不进 domain）。
 * value 为规范化前的原始提取值（封面等 URL 字段由调用方绝对化）。
 */
data class FieldCandidate(
    val field: DetailField,
    val value: String,
    val confidence: Confidence,
    val note: String? = null,
)
