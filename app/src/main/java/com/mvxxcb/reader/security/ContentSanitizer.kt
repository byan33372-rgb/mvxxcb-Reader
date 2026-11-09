package com.mvxxcb.reader.security

interface ContentSanitizer {
    /**
     * 正文片段净化：剥离 script/style/iframe/event 属性/危险协议链接；
     * 输出段落块序列（正文防 XSS，仅受控渲染）。
     */
    fun sanitizeContentFragment(html: String): SanitizedFragment
}
data class SanitizedFragment(val blocks: List<com.mvxxcb.reader.domain.model.ContentBlock>, val removedElementCount: Int)
