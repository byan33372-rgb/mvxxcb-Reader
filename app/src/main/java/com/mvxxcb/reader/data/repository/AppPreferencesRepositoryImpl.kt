package com.mvxxcb.reader.data.repository

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.data.datastore.AppPreferencesDataStore
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.AppPreferences
import com.mvxxcb.reader.domain.repository.AppPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/** AppPreferencesRepository 实现（契约 02 §7）：app_preferences DataStore 门面。 */
class AppPreferencesRepositoryImpl(
    private val dataStore: AppPreferencesDataStore,
    private val logger: AppLogger,
) : AppPreferencesRepository {

    companion object {
        private const val TAG = "AppPreferencesRepo"
    }

    override val preferences: Flow<AppPreferences> = dataStore.preferences

    override suspend fun update(
        transform: (AppPreferences) -> AppPreferences,
    ): AppResult<Unit> = try {
        dataStore.update(transform)
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "update 应用偏好失败", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DISK_IO, "app preferences update failed", e))
    }
}
