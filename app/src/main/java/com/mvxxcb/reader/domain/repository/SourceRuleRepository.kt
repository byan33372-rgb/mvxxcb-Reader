package com.mvxxcb.reader.domain.repository

interface SourceRuleRepository {
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<com.mvxxcb.reader.domain.model.SourceRule>>   // 按 domain 排序
    suspend fun getEnabledRule(domain: String): com.mvxxcb.reader.domain.model.SourceRule?
    suspend fun getRuleByDomain(domain: String): com.mvxxcb.reader.domain.model.SourceRule?
    /** upsert（调用方须先经 RuleJsonValidator 校验） */
    suspend fun upsertRule(rule: com.mvxxcb.reader.domain.model.SourceRule): com.mvxxcb.reader.common.result.AppResult<Unit>
    suspend fun setEnabled(ruleId: String, enabled: Boolean): com.mvxxcb.reader.common.result.AppResult<Unit>
    suspend fun deleteRule(ruleId: String): com.mvxxcb.reader.common.result.AppResult<Unit>
    suspend fun exportAll(): String                       // JSON 数组（Schema 同 03 §6）
    suspend fun importRules(json: String, mode: RuleImportMode): com.mvxxcb.reader.common.result.AppResult<RuleImportReport>
}

enum class RuleImportMode { MERGE_BY_ID, OVERWRITE_ALL }
data class RuleImportReport(val imported: Int, val skipped: Int, val invalid: List<String>)
