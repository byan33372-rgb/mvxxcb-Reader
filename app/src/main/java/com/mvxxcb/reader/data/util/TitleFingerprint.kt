package com.mvxxcb.reader.data.util

import java.util.Locale

/**
 * 标题指纹（数据模型 03 §7.2）。
 *
 * 主 Agent 裁定：指纹由 ChapterRepositoryImpl.replaceCatalog 写入时计算
 * （parser 的 ChapterRef 只带 title）；指纹 = SHA-256(normalize(title)) 前 32 hex。
 * 只用于同书内匹配，不跨书。
 *
 * normalize 步骤（03 §7.2）：
 *  1. 去首尾空白；连续空白折叠为单个空格
 *  2. 全角字符→半角（数字/字母/标点/空格）
 *  3. 去常见装饰：书名号引号、章节标记词（"正文"、"VIP章节"、"最新章节"）
 *  4. 统一小写（拉丁字母）
 *  5. 消除"第N章/第N节/Chapter N"中的编号空白差异；
 *     最终移除全部空白（步骤 5 的超集，确定性更强："第 12 章"与"第12章"一致）
 */
object TitleFingerprint {

    // 章节标记装饰词（03 §7.2 第 3 步）
    private val DECOR_WORDS = listOf("最新章节", "VIP章节", "正文")

    // 书名号/引号类装饰字符
    private val DECOR_CHARS = "《》「」『』〔〕【】“”‘’\"'"

    fun normalize(title: String): String {
        var s = title.trim()
        if (s.isEmpty()) return ""
        // 1. 折叠空白
        s = s.replace(Regex("\\s+"), " ")
        // 2. 全角→半角（含全角空格）
        s = fullWidthToHalfWidth(s)
        // 3. 去装饰
        for (word in DECOR_WORDS) s = s.replace(word, "")
        for (ch in DECOR_CHARS) s = s.replace(ch.toString(), "")
        // 4. 统一小写（Locale.ROOT 防止区域敏感大小写）
        s = s.lowercase(Locale.ROOT)
        // 5. 编号空白差异归一
        s = s.replace(Regex("第\\s*(\\d+)\\s*([章回节卷])"), "第$1$2")
        s = s.replace(Regex("chapter\\s*(\\d+)"), "chapter$1")
        // 最终移除全部空白（确定性的空白归一）
        s = s.replace(" ", "")
        return s
    }

    /** SHA-256 前 32 hex（03 §7.2：冲突率可接受） */
    fun of(title: String): String =
        if (normalize(title).isEmpty()) "" else Sha256.hexOf(normalize(title)).take(32)

    private fun fullWidthToHalfWidth(text: String): String = buildString(text.length) {
        for (c in text) {
            when {
                c == '\u3000' -> append(' ')                              // 全角空格
                c.code in 0xFF01..0xFF5E -> append((c.code - 0xFEE0).toChar()) // 全角 ASCII 区 → 半角字符
                else -> append(c)
            }
        }
    }
}
