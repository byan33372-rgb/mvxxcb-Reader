package com.mvxxcb.reader.parser.core

import com.mvxxcb.reader.domain.model.Confidence

/**
 * 置信度显式排序（主 Agent 裁定 / QA 踩坑广播）：
 * 契约枚举 [Confidence] 的 ordinal 与置信度高低**相反**（HIGH=0, MEDIUM=1, LOW=2），
 * 任何实现禁止用 ordinal 比较置信度；统一使用本显式映射：
 *
 *   HIGH = 2 > MEDIUM = 1 > LOW = 0
 */
fun Confidence.rank(): Int = when (this) {
    Confidence.HIGH -> 2
    Confidence.MEDIUM -> 1
    Confidence.LOW -> 0
}
