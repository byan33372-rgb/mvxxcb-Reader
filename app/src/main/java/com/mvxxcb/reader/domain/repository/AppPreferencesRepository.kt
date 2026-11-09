package com.mvxxcb.reader.domain.repository

interface AppPreferencesRepository {
    val preferences: kotlinx.coroutines.flow.Flow<com.mvxxcb.reader.domain.model.AppPreferences>
    suspend fun update(transform: (com.mvxxcb.reader.domain.model.AppPreferences) -> com.mvxxcb.reader.domain.model.AppPreferences):
        com.mvxxcb.reader.common.result.AppResult<Unit>
}
