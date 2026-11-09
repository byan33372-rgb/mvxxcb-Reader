package com.mvxxcb.reader.data.db

import androidx.room.TypeConverter

/**
 * Room 类型转换（数据模型 03 §2.6）：
 * 仅 Boolean↔INTEGER；Long/Double 原生即可；content 为纯 JSON 字符串，无嵌套对象列。
 */
class Converters {

    @TypeConverter
    fun booleanToInt(value: Boolean?): Int =
        when (value) {
            null -> 0
            true -> 1
            false -> 0
        }

    @TypeConverter
    fun intToBoolean(value: Int?): Boolean = value != null && value != 0
}
