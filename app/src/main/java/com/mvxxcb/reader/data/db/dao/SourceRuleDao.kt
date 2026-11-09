package com.mvxxcb.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mvxxcb.reader.data.db.entities.SourceRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceRuleDao {

    @Query("SELECT * FROM source_rules ORDER BY domain ASC")
    fun observeAll(): Flow<List<SourceRuleEntity>>

    @Query("SELECT * FROM source_rules ORDER BY domain ASC")
    suspend fun getAll(): List<SourceRuleEntity>

    @Query("SELECT * FROM source_rules WHERE domain = :domain LIMIT 1")
    suspend fun getByDomain(domain: String): SourceRuleEntity?

    @Query("SELECT * FROM source_rules WHERE domain = :domain AND enabled = 1 LIMIT 1")
    suspend fun getEnabledByDomain(domain: String): SourceRuleEntity?

    @Query("SELECT * FROM source_rules WHERE ruleId = :ruleId LIMIT 1")
    suspend fun getByRuleId(ruleId: String): SourceRuleEntity?

    @Upsert
    suspend fun upsert(rule: SourceRuleEntity)

    @Query("UPDATE source_rules SET enabled = :enabled, updatedAt = :now WHERE ruleId = :ruleId")
    suspend fun setEnabled(ruleId: String, enabled: Boolean, now: Long)

    @Query("DELETE FROM source_rules WHERE ruleId = :ruleId")
    suspend fun deleteByRuleId(ruleId: String)
}
