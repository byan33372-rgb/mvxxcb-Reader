package com.mvxxcb.reader.data.datastore

import com.mvxxcb.reader.common.log.AppLogger

/**
 * 枚举字符串解析（数据模型 03 §4）：
 * 解析失败回退默认值并记 WARN，禁止崩溃。
 * 值为 null 表示该 key 从未写入（正常缺省，不打 WARN）。
 */
internal object EnumParser {

    const val TAG = "SettingsDataStore"

    inline fun <reified T : Enum<T>> parseOr(
        raw: String?,
        keyName: String,
        fallback: T,
        logger: AppLogger,
    ): T {
        if (raw == null) return fallback
        val matched = enumValues<T>().firstOrNull { it.name == raw }
        if (matched == null) {
            logger.w(TAG, "非法枚举值，回退默认：key=$keyName value=$raw default=${fallback.name}")
            return fallback
        }
        return matched
    }
}
