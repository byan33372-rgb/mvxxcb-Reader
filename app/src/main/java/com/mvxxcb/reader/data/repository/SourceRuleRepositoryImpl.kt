package com.mvxxcb.reader.data.repository

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.data.db.dao.SourceRuleDao
import com.mvxxcb.reader.data.db.entities.SourceRuleEntity
import com.mvxxcb.reader.data.mapper.toDomain
import com.mvxxcb.reader.data.mapper.toEntity
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.SourceRule
import com.mvxxcb.reader.domain.repository.RuleImportMode
import com.mvxxcb.reader.domain.repository.RuleImportReport
import com.mvxxcb.reader.domain.repository.SourceRuleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SourceRuleRepository 实现（契约 02 §7）。
 *
 * exportAll：规则 JSON 数组（Schema 同 03 §5，enabled 字段与列同步）。
 * importRules：MERGE_BY_ID 按 ruleId 幂等（同 id 且版本不更高 → 跳过）；OVERWRITE_ALL 全量覆盖。
 * 契约注：调用方（规则编辑 UI）须先经 RuleJsonValidator 校验后才调用 upsertRule。
 */
class SourceRuleRepositoryImpl(
    private val ruleDao: SourceRuleDao,
    private val timeProvider: TimeProvider,
    private val logger: AppLogger,
) : SourceRuleRepository {

    companion object {
        private const val TAG = "SourceRuleRepository"
        private val json = Json { ignoreUnknownKeys = true }
    }

    override fun observeAll(): Flow<List<SourceRule>> =
        ruleDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getEnabledRule(domain: String): SourceRule? =
        ruleDao.getEnabledByDomain(domain)?.toDomain()

    override suspend fun getRuleByDomain(domain: String): SourceRule? =
        ruleDao.getByDomain(domain)?.toDomain()

    override suspend fun upsertRule(rule: SourceRule): AppResult<Unit> = try {
        val now = timeProvider.nowMillis()
        // 同域名既有规则按"版本最高者生效"原则保护：仅当新规则版本 ≥ 既有版本才覆盖
        val existing = ruleDao.getByDomain(rule.domain)
        if (existing != null && rule.version < existing.version) {
            logger.w(TAG, "upsertRule 拒绝低版本覆盖 domain=${rule.domain} " +
                "old=v${existing.version} new=v${rule.version}")
            AppResult.Failure(
                ReaderError.State(
                    com.mvxxcb.reader.domain.error.StateFailure.INVALID_ARGUMENT,
                    "rule version ${rule.version} < existing ${existing.version}",
                )
            )
        } else {
            val entity = rule.toEntity().copy(
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            ruleDao.upsert(entity)
            AppResult.Success(Unit)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "upsertRule 失败 ruleId=${rule.ruleId}", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "upsertRule failed", e))
    }

    override suspend fun setEnabled(ruleId: String, enabled: Boolean): AppResult<Unit> = try {
        val now = timeProvider.nowMillis()
        ruleDao.setEnabled(ruleId, enabled, now)
        // 同步 ruleJson 内的 enabled 字段，保证导出一致（Schema 03 §5）
        ruleDao.getByRuleId(ruleId)?.let { entity ->
            val synced = syncEnabledInRuleJson(entity, enabled)
            if (synced != entity.ruleJson) {
                ruleDao.upsert(entity.copy(ruleJson = synced))
            }
        }
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "setEnabled 失败 ruleId=$ruleId", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "setEnabled failed", e))
    }

    override suspend fun deleteRule(ruleId: String): AppResult<Unit> = try {
        ruleDao.deleteByRuleId(ruleId)
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "deleteRule 失败 ruleId=$ruleId", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "deleteRule failed", e))
    }

    override suspend fun exportAll(): String {
        val rules = ruleDao.getAll()
        val array = buildString {
            append('[')
            rules.forEachIndexed { index, entity ->
                if (index > 0) append(',')
                append(entity.ruleJson.ifBlank { fallbackJsonOf(entity) })
            }
            append(']')
        }
        // 规范化校验：任一规则 JSON 损坏则用兜底对象替换该元素（导出不崩溃）
        return runCatching { json.parseToJsonElement(array).toString() }
            .getOrElse {
                logger.w(TAG, "exportAll 存在损坏规则 JSON，使用兜底字段导出")
                rules.joinToString(prefix = "[", postfix = "]") { fallbackJsonOf(it) }
            }
    }

    override suspend fun importRules(
        jsonText: String,
        mode: RuleImportMode,
    ): AppResult<RuleImportReport> = try {
        val now = timeProvider.nowMillis()
        val element = runCatching { json.parseToJsonElement(jsonText) }.getOrNull()
            ?: return AppResult.Failure(
                ReaderError.State(
                    com.mvxxcb.reader.domain.error.StateFailure.INVALID_ARGUMENT,
                    "importRules: 非法 JSON",
                )
            )
        val items = runCatching { element.jsonArray }.getOrElse {
            return AppResult.Failure(
                ReaderError.State(
                    com.mvxxcb.reader.domain.error.StateFailure.INVALID_ARGUMENT,
                    "importRules: 期望 JSON 数组",
                )
            )
        }

        var imported = 0
        var skipped = 0
        val invalid = mutableListOf<String>()
        for ((index, item) in items.withIndex()) {
            val obj = item as? JsonObject
            val ruleId = obj?.get("ruleId")?.jsonPrimitive?.contentOrNullSafe()
            val domain = obj?.get("domain")?.jsonPrimitive?.contentOrNullSafe()
            val version = obj?.get("version")?.jsonPrimitive?.intOrNull
            if (obj == null || ruleId.isNullOrBlank() || domain.isNullOrBlank() || version == null) {
                invalid += "index:$index"
                continue
            }
            val existing = ruleDao.getByRuleId(ruleId)
            if (mode == RuleImportMode.MERGE_BY_ID && existing != null && version <= existing.version) {
                // 幂等合并：同 id 且不更高版本 → 跳过
                skipped++
                continue
            }
            val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
            ruleDao.upsert(
                SourceRuleEntity(
                    ruleId = ruleId,
                    domain = domain,
                    version = version,
                    ruleJson = obj.toString(),
                    enabled = enabled,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            )
            imported++
        }
        AppResult.Success(RuleImportReport(imported, skipped, invalid))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "importRules 失败", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "importRules failed", e))
    }

    // ———— 内部 ————

    private fun fallbackJsonOf(entity: SourceRuleEntity): String =
        JsonObject(
            mapOf(
                "ruleId" to kotlinx.serialization.json.JsonPrimitive(entity.ruleId),
                "domain" to kotlinx.serialization.json.JsonPrimitive(entity.domain),
                "version" to kotlinx.serialization.json.JsonPrimitive(entity.version),
                "enabled" to kotlinx.serialization.json.JsonPrimitive(entity.enabled),
            )
        ).toString()

    /** 将 ruleJson 顶层 enabled 字段同步为列值；解析失败返回原文本（不损坏数据） */
    private fun syncEnabledInRuleJson(entity: SourceRuleEntity, enabled: Boolean): String =
        runCatching {
            val obj = json.parseToJsonElement(entity.ruleJson).jsonObject
            JsonObject(obj + ("enabled" to kotlinx.serialization.json.JsonPrimitive(enabled)))
                .toString()
        }.getOrElse { entity.ruleJson }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { content }.getOrNull()
}
