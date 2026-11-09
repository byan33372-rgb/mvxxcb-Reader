package com.mvxxcb.reader.domain.repository

interface ReaderSettingsRepository {
    val settings: kotlinx.coroutines.flow.Flow<com.mvxxcb.reader.domain.model.ReaderSettings>
    suspend fun current(): com.mvxxcb.reader.domain.model.ReaderSettings
    suspend fun update(transform: (com.mvxxcb.reader.domain.model.ReaderSettings) -> com.mvxxcb.reader.domain.model.ReaderSettings):
        com.mvxxcb.reader.common.result.AppResult<Unit>
}
