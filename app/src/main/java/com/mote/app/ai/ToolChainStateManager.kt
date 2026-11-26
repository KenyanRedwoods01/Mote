package com.mote.app.ai

import com.mote.app.data.db.LocalModelDao
import com.mote.app.data.db.ToolChainStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Room-backed repository managing [ToolChainStateEntity] rows so sequential tool calls reliably
 * persist intermediate outputs (app launches, screenshot URIs, OCR text, dialed numbers, note IDs)
 * and pass resolved dependencies cleanly from Step N to Step N+1 — even across external app switches.
 */
class ToolChainStateManager(
    private val dao: LocalModelDao,
    private val scope: CoroutineScope
) {

    val recentStates: StateFlow<List<ToolChainStateEntity>> = dao.observeRecentToolChainStates()
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    suspend fun recordStepOutput(
        chainId: String,
        stepIndex: Int,
        toolName: String,
        outputKey: String,
        outputValue: String,
        dependencyKey: String? = null
    ): ToolChainStateEntity {
        val now = System.currentTimeMillis()
        val state = ToolChainStateEntity(
            stateId = "st_${chainId}_${stepIndex}_${outputKey}_${UUID.randomUUID().toString().take(4)}",
            chainId = chainId,
            stepIndex = stepIndex,
            toolName = toolName,
            outputKey = outputKey,
            outputValue = outputValue,
            dependencyKey = dependencyKey,
            consumedByStepIndex = null,
            status = "READY",
            createdAtEpochMs = now,
            updatedAtEpochMs = now
        )
        dao.upsertToolChainState(state)
        return state
    }

    /**
     * Resolves a dependency key (e.g., `lastOpenedApp`, `lastCaptureId`, `lastExtractedText`,
     * `lastSavedUri`, `lastDialedNumber`, `lastSearchQuery`) first from the active chain's persisted
     * Room rows, falling back to the latest globally persisted state if a follow-up turn continues
     * an earlier workflow.
     */
    suspend fun resolveDependency(
        chainId: String,
        outputKey: String,
        allowCrossChainFallback: Boolean = true
    ): ToolChainStateEntity? {
        val inChain = dao.getStatesForChainOnce(chainId)
            .lastOrNull { it.outputKey == outputKey && it.outputValue.isNotBlank() }
        if (inChain != null) return inChain
        if (!allowCrossChainFallback) return null
        return dao.getLatestStateByKey(outputKey)?.takeIf { it.outputValue.isNotBlank() }
    }

    suspend fun markConsumed(stateId: String, consumerStepIndex: Int) {
        dao.markToolChainStateConsumed(
            stateId = stateId,
            consumerStepIndex = consumerStepIndex,
            updatedAtEpochMs = System.currentTimeMillis()
        )
    }

    /**
     * Hydrates a fresh [ToolChainContext] from persisted Room state before a step executes so
     * intermediate outputs survive process/app handoffs (e.g. launching Chrome, Phone, or MediaProjection).
     */
    suspend fun hydrateContextForChain(chainId: String, context: ToolChainContext) {
        val rows = dao.getStatesForChainOnce(chainId)
        for (row in rows) {
            applyRowToContext(row, context)
        }
        // Also hydrate cross-turn fallback for keys still null so multi-turn follow-ups work seamlessly
        if (context.lastOpenedApp.isNullOrBlank()) {
            dao.getLatestStateByKey(KEY_LAST_OPENED_APP)?.let { context.lastOpenedApp = it.outputValue }
        }
        if (context.lastCaptureId.isNullOrBlank()) {
            dao.getLatestStateByKey(KEY_LAST_CAPTURE_ID)?.let { context.lastCaptureId = it.outputValue }
        }
        if (context.lastSavedUri.isNullOrBlank()) {
            dao.getLatestStateByKey(KEY_LAST_SAVED_URI)?.let { context.lastSavedUri = it.outputValue }
        }
        if (context.lastExtractedText.isNullOrBlank()) {
            dao.getLatestStateByKey(KEY_LAST_EXTRACTED_TEXT)?.let { context.lastExtractedText = it.outputValue }
        }
        if (context.lastDialedNumber.isNullOrBlank()) {
            dao.getLatestStateByKey(KEY_LAST_DIALED_NUMBER)?.let { context.lastDialedNumber = it.outputValue }
        }
    }

    suspend fun getChainStatesSnapshot(chainId: String): List<ToolChainStateEntity> {
        return dao.getStatesForChainOnce(chainId)
    }

    fun clearAllStates() {
        scope.launch { dao.clearToolChainStates() }
    }

    private fun applyRowToContext(row: ToolChainStateEntity, context: ToolChainContext) {
        when (row.outputKey) {
            KEY_LAST_OPENED_APP -> context.lastOpenedApp = row.outputValue
            KEY_LAST_CAPTURE_ID -> context.lastCaptureId = row.outputValue
            KEY_LAST_SAVED_URI -> context.lastSavedUri = row.outputValue
            KEY_LAST_EXTRACTED_TEXT -> context.lastExtractedText = row.outputValue
            KEY_LAST_NOTE_ID -> context.lastNoteId = row.outputValue
            KEY_LAST_DIALED_NUMBER -> context.lastDialedNumber = row.outputValue
            KEY_LAST_SEARCH_QUERY -> context.lastSearchQuery = row.outputValue
            KEY_LAST_ALARM_ID -> context.lastAlarmId = row.outputValue
            KEY_LAST_EVENT_ID -> context.lastEventId = row.outputValue
        }
        context.stepOutputs[row.outputKey] = row.outputValue
    }

    companion object {
        const val KEY_LAST_OPENED_APP = "lastOpenedApp"
        const val KEY_LAST_CAPTURE_ID = "lastCaptureId"
        const val KEY_LAST_SAVED_URI = "lastSavedUri"
        const val KEY_LAST_EXTRACTED_TEXT = "lastExtractedText"
        const val KEY_LAST_NOTE_ID = "lastNoteId"
        const val KEY_LAST_DIALED_NUMBER = "lastDialedNumber"
        const val KEY_LAST_SEARCH_QUERY = "lastSearchQuery"
        const val KEY_LAST_ALARM_ID = "lastAlarmId"
        const val KEY_LAST_EVENT_ID = "lastEventId"
    }
}
