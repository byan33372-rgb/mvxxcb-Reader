package com.mvxxcb.reader.parser.core

interface UrlClassifier {
    /** 判定 详情页/目录页/章节页/未知；置信度低于 MEDIUM 时 kind 允许为 UNKNOWN */
    suspend fun classify(rawUrl: String): com.mvxxcb.reader.common.result.AppResult<com.mvxxcb.reader.domain.model.UrlClassification>
}
