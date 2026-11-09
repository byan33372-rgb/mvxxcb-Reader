package com.mvxxcb.reader.domain.model

/**
 * 反向识别结果：任意章节/详情/目录 URL → 整本书定位（多路证据，带置信度）
 * （契约 v1.1 K8；设计 07 §K8 逐字冻结）。
 *
 * 红线：detailUrl/catalogUrl 禁止 URL 猜测合成；全部失败时
 * requiresCatalogHint=true（UI 必须提示用户补充目录页 URL）或 Failure(Parse.CATALOG_UNRESOLVED)。
 */
data class BookResolution(
    val normalizedUrl: String,
    val kind: PageKind,                     // 输入页型判定
    val detailUrl: String?,                 // 定位到的详情页（可空，禁止 URL 猜测合成）
    val catalogUrl: String?,                // 定位到的目录页
    val detail: ParsedBookDetail?,          // 经 pipeline.parseDetail 的输出
    val catalog: ParsedCatalog?,            // 经 pipeline.parseCatalog 的输出
    val chapters: List<ChapterRef>,         // = catalog.chapters（便捷冗余）
    val confidence: Confidence,             // 各路证据置信度取 min
    val requiresCatalogHint: Boolean,       // true=找不到目录，UI 必须提示用户补充目录页 URL
    val evidence: List<String>,             // 判定依据（面包屑/meta/JSON-LD/OG/规则/上一章链，供 UI 展示）
)
