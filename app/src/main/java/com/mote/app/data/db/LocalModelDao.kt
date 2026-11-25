package com.mote.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalModelDao {

    @Query("SELECT * FROM local_models ORDER BY isImported ASC, approxSizeMb ASC")
    fun observeAllModels(): Flow<List<LocalModelEntity>>

    @Query("SELECT * FROM local_models")
    suspend fun getAllModelsOnce(): List<LocalModelEntity>

    @Query("SELECT * FROM local_models WHERE id = :modelId LIMIT 1")
    suspend fun getModelById(modelId: String): LocalModelEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissingModels(models: List<LocalModelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertModel(model: LocalModelEntity)

    @Query("SELECT * FROM local_models WHERE localFilePath IS NOT NULL AND localFilePath != ''")
    suspend fun getDownloadedModelsOnce(): List<LocalModelEntity>

    @Query(
        """
        UPDATE local_models
        SET localFilePath = :localFilePath,
            downloadedBytes = :fileSizeBytes,
            totalBytes = :fileSizeBytes,
            sha256Prefix = :sha256Prefix,
            downloadedAtEpochMs = :downloadedAtEpochMs,
            lastErrorMessage = NULL,
            remoteEtag = COALESCE(:remoteEtag, remoteEtag),
            remoteLastModified = COALESCE(:remoteLastModified, remoteLastModified),
            remoteContentLength = CASE WHEN :remoteContentLength > 0 THEN :remoteContentLength ELSE remoteContentLength END,
            updateAvailable = 0,
            updateStatusNote = NULL
        WHERE id = :modelId
        """
    )
    suspend fun markDownloadCompleted(
        modelId: String,
        localFilePath: String,
        fileSizeBytes: Long,
        sha256Prefix: String?,
        downloadedAtEpochMs: Long,
        remoteEtag: String? = null,
        remoteLastModified: String? = null,
        remoteContentLength: Long = 0L
    )

    @Query(
        """
        UPDATE local_models
        SET remoteEtag = :remoteEtag,
            remoteLastModified = :remoteLastModified,
            remoteContentLength = :remoteContentLength,
            lastUpdateCheckedEpochMs = :checkedAtEpochMs,
            updateAvailable = :updateAvailable,
            updateStatusNote = :updateStatusNote
        WHERE id = :modelId
        """
    )
    suspend fun recordUpdateCheck(
        modelId: String,
        remoteEtag: String?,
        remoteLastModified: String?,
        remoteContentLength: Long,
        checkedAtEpochMs: Long,
        updateAvailable: Boolean,
        updateStatusNote: String?
    )

    @Query(
        """
        UPDATE local_models
        SET lastErrorMessage = :errorMessage
        WHERE id = :modelId
        """
    )
    suspend fun markDownloadError(modelId: String, errorMessage: String?)

    @Query(
        """
        UPDATE local_models
        SET localFilePath = NULL,
            downloadedBytes = 0,
            totalBytes = 0,
            sha256Prefix = NULL,
            downloadedAtEpochMs = NULL,
            lastErrorMessage = NULL,
            remoteEtag = NULL,
            remoteLastModified = NULL,
            remoteContentLength = 0,
            updateAvailable = 0,
            updateStatusNote = NULL
        WHERE id = :modelId
        """
    )
    suspend fun clearDownloadRecord(modelId: String)

    @Query("UPDATE local_models SET lastUsedAtEpochMs = :epochMs WHERE id = :modelId")
    suspend fun touchLastUsed(modelId: String, epochMs: Long)

    @Query("DELETE FROM local_models WHERE id = :modelId")
    suspend fun deleteModelById(modelId: String)

    @Query("SELECT * FROM model_studio_prefs WHERE id = 1 LIMIT 1")
    fun observePrefs(): Flow<ModelStudioPrefsEntity?>

    @Query("SELECT * FROM model_studio_prefs WHERE id = 1 LIMIT 1")
    suspend fun getPrefsOnce(): ModelStudioPrefsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrefs(prefs: ModelStudioPrefsEntity)

    @Query("SELECT * FROM model_benchmarks ORDER BY benchmarkedAtEpochMs DESC")
    fun observeAllBenchmarks(): Flow<List<ModelBenchmarkEntity>>

    @Query("SELECT * FROM model_benchmarks WHERE modelId = :modelId ORDER BY benchmarkedAtEpochMs DESC LIMIT 10")
    fun observeBenchmarksForModel(modelId: String): Flow<List<ModelBenchmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBenchmark(benchmark: ModelBenchmarkEntity)

    @Query("DELETE FROM model_benchmarks WHERE modelId = :modelId")
    suspend fun deleteBenchmarksForModel(modelId: String)

    @Query("SELECT * FROM clock_alarms ORDER BY hour ASC, minute ASC")
    fun observeAllAlarms(): Flow<List<ClockAlarmEntity>>

    @Query("SELECT * FROM clock_alarms ORDER BY hour ASC, minute ASC")
    suspend fun getAllAlarmsOnce(): List<ClockAlarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlarm(alarm: ClockAlarmEntity)

    @Query("DELETE FROM clock_alarms WHERE id = :alarmId")
    suspend fun deleteAlarmById(alarmId: String)

    @Query("DELETE FROM clock_alarms")
    suspend fun deleteAllAlarms()

    @Query("SELECT * FROM calendar_events ORDER BY dateIso ASC, startTime ASC")
    fun observeAllCalendarEvents(): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events ORDER BY dateIso ASC, startTime ASC")
    suspend fun getAllCalendarEventsOnce(): List<CalendarEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCalendarEvent(event: CalendarEventEntity)

    @Query("DELETE FROM calendar_events WHERE id = :eventId")
    suspend fun deleteCalendarEventById(eventId: String)

    @Query("DELETE FROM calendar_events")
    suspend fun deleteAllCalendarEvents()

    @Query("SELECT * FROM tool_execution_logs ORDER BY timestampMs DESC LIMIT 200")
    fun observeAllToolLogs(): Flow<List<ToolExecutionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertToolLog(entry: ToolExecutionLogEntity)

    @Query("DELETE FROM tool_execution_logs")
    suspend fun clearAllToolLogs()
}
