package com.mvxxcb.reader.common.time

interface TimeProvider { fun nowMillis(): Long }
class SystemTimeProvider : TimeProvider {
    // 契约 02 §0.7：所有时间戳 = epoch 毫秒（UTC），由注入的 TimeProvider 产生
    override fun nowMillis(): Long = System.currentTimeMillis()
}
