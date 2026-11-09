package com.mvxxcb.reader.data.repository

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.data.datastore.ReaderSettingsDataStore
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.domain.repository.ReaderSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** ReaderSettingsRepository 实现（契约 02 §7）：reader_settings DataStore 门面。 */
class ReaderSettingsRepositoryImpl(
    private val dataStore: ReaderSettingsDataStore,
    private val logger: AppLogger,
) : ReaderSettingsRepository {

    companion object {
        private const val TAG = "ReaderSettingsRepo"
    }

    override val settings: Flow<ReaderSettings> = dataStore.settings

    override suspend fun current(): ReaderSettings = dataStore.current()

    override suspend fun update(
        transform: (ReaderSettings) -> ReaderSettings,
    ): AppResult<Unit> = try {
        dataStore.update(transform)
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "update 阅读设置失败", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DISK_IO, "reader settings update failed", e))
    }
}
