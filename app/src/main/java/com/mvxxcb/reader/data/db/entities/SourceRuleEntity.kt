package com.mvxxcb.reader.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * source_rules 表（数据模型 03 §2.5，逐字段对齐）。
 *
 * 独立表，按域名生效，不与书关联；domain UNIQUE。
 * ruleJson 为完整规则 JSON（Schema 03 §5）；升级规则只增字段不得丢用户数据。
 */
@Entity(
    tableName = "source_rules",
    indices = [
        Index(value = ["domain"], unique = true),
    ],
)
data class SourceRuleEntity(
    // "slug.v1" 或 "manual.<domain>"
    @PrimaryKey val ruleId: String,
    val domain: String,
    // 规则升级递增
    @ColumnInfo(defaultValue = "1") val version: Int = 1,
    val ruleJson: String,
    @ColumnInfo(defaultValue = "1") val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
)
