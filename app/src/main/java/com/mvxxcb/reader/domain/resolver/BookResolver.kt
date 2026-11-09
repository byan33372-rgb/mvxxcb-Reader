package com.mvxxcb.reader.domain.resolver

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.model.BookResolution

/**
 * 反向识别器：章节/详情/目录 URL → 整本书定位（契约 v1.1 K8；接口骨架 WP-M0，实现 WP-R）。
 *
 * 六种输入统一入口：详情页/目录页/首章/中间章/末章/复杂参数章节页。
 * 实现层：service/DefaultBookResolver.kt；解析必走 ParserPipeline（红线），静态路径无 WebView；
 * 禁止从 URL 字符串推测书名/目录地址、禁止无边界枚举同域链接。
 */
interface BookResolver {

    /** catalogHint 非空时优先按提示 URL 解析目录（用户补充回路，requiresCatalogHint 回） */
    suspend fun resolve(rawUrl: String, catalogHint: String? = null): AppResult<BookResolution>
}
