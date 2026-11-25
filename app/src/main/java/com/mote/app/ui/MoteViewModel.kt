package com.mote.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import com.mote.app.ai.ChatStreamEvent
import com.mote.app.ai.EngineUsed
import com.mote.app.ai.HybridAiRouter
import com.mote.app.ai.HybridGenUiResult
import com.mote.app.ai.InferenceMetrics
import com.mote.app.ai.LlmConfig
import com.mote.app.control.LanServerUiState
import com.mote.app.control.LocalLanApiServer
import com.mote.app.data.BotStore
import com.mote.app.data.ChatHistoryStore
import com.mote.app.data.DeviceHardwareSnapshot
import com.mote.app.data.LoadedLocalModelState
import com.mote.app.data.LocalModelDownloadStatus
import com.mote.app.data.LocalModelEntry
import com.mote.app.data.LocalModelManager
import com.mote.app.model.BotPersona
import com.mote.app.model.ChatMessage
import com.mote.app.model.ChatRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface BotsUiState {
    data object Loading : BotsUiState
    data class Success(val bots: List<BotPersona>) : BotsUiState
    data class Error(val message: String) : BotsUiState
}

sealed interface ChatHistoryUiState {
    data object Loading : ChatHistoryUiState
    data class Success(val messages: List<ChatMessage>) : ChatHistoryUiState
    data class Error(val message: String) : ChatHistoryUiState
}

sealed interface AiCardUiState {
    data object Idle : AiCardUiState
    data object Loading : AiCardUiState
    data class Success(val result: HybridGenUiResult) : AiCardUiState
    data class Error(val message: String) : AiCardUiState
}

sealed interface ChatStreamUiState {
    data object Idle : ChatStreamUiState
    data class Streaming(
        val partialText: String,
        val botName: String,
        val elapsedMs: Long
    ) : ChatStreamUiState
    data class Error(val message: String) : ChatStreamUiState
}

class MoteViewModel(
    private val repo: BotStore,
    private val chatStore: ChatHistoryStore,
    private val router: HybridAiRouter,
    private val localModelManager: LocalModelManager
) : ViewModel() {

    val botsState: StateFlow<BotsUiState> = repo.observeBots()
        .map<List<BotPersona>, BotsUiState> { BotsUiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            emit(BotsUiState.Error(rootError.message ?: "Failed to load bot personas."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = BotsUiState.Loading
        )

    val chatHistoryState: StateFlow<ChatHistoryUiState> = chatStore.observeHistory()
        .map<List<ChatMessage>, ChatHistoryUiState> { ChatHistoryUiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            emit(ChatHistoryUiState.Error(rootError.message ?: "Failed to load chat history."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = ChatHistoryUiState.Loading
        )

    val localModels: StateFlow<List<LocalModelEntry>> = localModelManager.models
    val loadedLocalModel: StateFlow<LoadedLocalModelState?> = localModelManager.loadedModel
    val deviceHardware: StateFlow<DeviceHardwareSnapshot> = localModelManager.hardware

    private val _aiState = MutableStateFlow<AiCardUiState>(AiCardUiState.Idle)
    val aiState: StateFlow<AiCardUiState> = _aiState.asStateFlow()

    private val _chatStreamState = MutableStateFlow<ChatStreamUiState>(ChatStreamUiState.Idle)
    val chatStreamState: StateFlow<ChatStreamUiState> = _chatStreamState.asStateFlow()

    private val _lastMetrics = MutableStateFlow<InferenceMetrics?>(null)
    val lastMetrics: StateFlow<InferenceMetrics?> = _lastMetrics.asStateFlow()

    private val _llmConfig = MutableStateFlow(LlmConfig())
    val llmConfig: StateFlow<LlmConfig> = _llmConfig.asStateFlow()

    private val _selectedBotId = MutableStateFlow("focus")
    val selectedBotId: StateFlow<String> = _selectedBotId.asStateFlow()

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    private val lanServer = LocalLanApiServer {
        val list = mutableListOf(_llmConfig.value.modelName)
        localModelManager.loadedModel.value?.let { list.add("local/${it.modelId}") }
        localModelManager.models.value
            .filter { it.status == LocalModelDownloadStatus.DOWNLOADED }
            .forEach { entry ->
                val tag = "local/${entry.id}"
                if (tag !in list) list.add(tag)
            }
        list
    }
    val lanServerState: StateFlow<LanServerUiState> = lanServer.state

    private var activeStreamJob: Job? = null

    init {
        viewModelScope.launch {
            repo.seedDefaultBotsIfEmpty().onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to initialize starter bots."
            }
        }
    }

    fun selectBot(botId: String) {
        _selectedBotId.value = botId
    }

    fun updateModel(modelName: String) {
        _llmConfig.update { it.copy(modelName = modelName) }
    }

    fun updateTemperature(temperature: Float) {
        _llmConfig.update { it.copy(temperature = temperature.coerceIn(0f, 1.5f)) }
    }

    fun updateTopP(topP: Float) {
        _llmConfig.update { it.copy(topP = topP.coerceIn(0.1f, 1.0f)) }
    }

    fun updateMaxOutputTokens(tokens: Int) {
        _llmConfig.update { it.copy(maxOutputTokens = tokens.coerceIn(128, 4096)) }
    }

    fun updateContextWindowTurns(turns: Int) {
        _llmConfig.update { it.copy(contextWindowTurns = turns.coerceIn(2, 20)) }
    }

    fun toggleBot(bot: BotPersona) {
        viewModelScope.launch {
            repo.toggleBot(bot.id, bot.active).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to update bot status."
            }
        }
    }

    fun saveBotPersona(bot: BotPersona, isExisting: Boolean) {
        viewModelScope.launch {
            repo.upsertBot(bot, isExisting).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to save bot persona."
            }
        }
    }

    fun startModelDownload(modelId: String) = localModelManager.startDownload(modelId)

    fun cancelModelDownload(modelId: String) = localModelManager.cancelDownload(modelId)

    fun deleteLocalModel(modelId: String) = localModelManager.deleteModel(modelId)

    fun loadLocalModel(modelId: String) {
        viewModelScope.launch {
            localModelManager.loadModel(modelId).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to load GGUF model."
            }
        }
    }

    fun unloadLocalModel() = localModelManager.unloadModel()

    fun importLocalModel(uri: Uri) {
        viewModelScope.launch {
            localModelManager.importModelFromUri(uri).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to import model file."
            }
        }
    }

    fun refreshHardware() = localModelManager.refreshHardware()

    fun toggleLanServer() = lanServer.toggleServer()

    fun updateLanPort(port: Int) = lanServer.updatePort(port)

    fun toggleLanBearerToken(requireToken: Boolean) = lanServer.toggleRequireToken(requireToken)

    fun stopStreaming() {
        activeStreamJob?.cancel()
        activeStreamJob = null
        _chatStreamState.value = ChatStreamUiState.Idle
    }

    fun sendStreamingChatMessage(
        prompt: String,
        bot: BotPersona?,
        attachedBitmap: Bitmap? = null
    ) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty() && attachedBitmap == null) return
        val effectivePrompt = cleanPrompt.ifEmpty { "Describe and analyze this image." }
        val botId = bot?.id?.takeIf { it.isNotBlank() } ?: "focus"
        val botName = bot?.name?.takeIf { it.isNotBlank() } ?: "Mote"
        val systemPrompt = bot?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: "You are Mote, a crisp, helpful Android AI assistant."
        val priorHistory = (chatHistoryState.value as? ChatHistoryUiState.Success)?.messages.orEmpty()
        val displayPrompt = if (attachedBitmap != null) "[Image attached] $effectivePrompt" else effectivePrompt

        activeStreamJob?.cancel()
        activeStreamJob = viewModelScope.launch {
            _chatStreamState.value = ChatStreamUiState.Streaming(
                partialText = "",
                botName = botName,
                elapsedMs = 0L
            )
            chatStore.appendMessage(
                role = ChatRole.USER,
                botId = botId,
                botName = botName,
                text = displayPrompt,
                engineUsed = "NONE"
            ).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to save prompt to chat history."
            }

            try {
                var finalAnswer = ""
                router.streamChatMessage(
                    userPrompt = effectivePrompt,
                    botSystemPrompt = systemPrompt,
                    history = priorHistory,
                    config = _llmConfig.value,
                    attachedBitmap = attachedBitmap
                ).collect { event ->
                    when (event) {
                        is ChatStreamEvent.Delta -> {
                            _chatStreamState.value = ChatStreamUiState.Streaming(
                                partialText = event.accumulatedText,
                                botName = botName,
                                elapsedMs = event.elapsedMs
                            )
                        }
                        is ChatStreamEvent.Completed -> {
                            finalAnswer = event.fullText
                            _lastMetrics.value = event.metrics
                        }
                    }
                }

                if (finalAnswer.isNotBlank()) {
                    chatStore.appendMessage(
                        role = ChatRole.ASSISTANT,
                        botId = botId,
                        botName = botName,
                        text = finalAnswer,
                        engineUsed = EngineUsed.CLOUD_FLASH.name
                    ).onFailure { err ->
                        _actionError.value = err.localizedMessage ?: "Failed to save assistant reply."
                    }
                }
                _chatStreamState.value = ChatStreamUiState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _chatStreamState.value = ChatStreamUiState.Error(
                    e.localizedMessage ?: "Failed to generate response."
                )
            }
        }
    }

    fun generateFocusControlCard(
        userPrompt: String = "Focus mode: dim screen to 30%, enable DND, start 25 minute timer",
        botSystemPrompt: String = "Return compact Android Generative UI controls.",
        redactedScreenContext: String? = null,
        botId: String = "focus",
        botName: String = "Focus"
    ) {
        viewModelScope.launch {
            _aiState.value = AiCardUiState.Loading
            chatStore.appendMessage(
                role = ChatRole.USER,
                botId = botId,
                botName = botName,
                text = userPrompt,
                engineUsed = "NONE"
            ).onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to save prompt to chat history."
            }

            router.generateControlCard(
                userPrompt = userPrompt,
                botSystemPrompt = botSystemPrompt,
                redactedScreenContext = redactedScreenContext,
                isForeground = true,
                modelName = _llmConfig.value.modelName
            ).onSuccess { result ->
                _aiState.value = AiCardUiState.Success(result)
                val widgetNames = result.spec.widgets.joinToString(", ") { it.label }
                val summary = if (widgetNames.isNotBlank()) {
                    "${result.spec.title} — $widgetNames"
                } else {
                    result.spec.title
                }
                chatStore.appendMessage(
                    role = ChatRole.ASSISTANT,
                    botId = botId,
                    botName = botName,
                    text = summary,
                    engineUsed = result.engineUsed.name
                )
            }.onFailure { err ->
                _aiState.value = AiCardUiState.Error(
                    err.localizedMessage ?: "Failed to generate interactive UI."
                )
            }
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch {
            chatStore.clearHistory().onFailure { err ->
                _actionError.value = err.localizedMessage ?: "Failed to clear chat history."
            }
            _aiState.value = AiCardUiState.Idle
            _chatStreamState.value = ChatStreamUiState.Idle
            _lastMetrics.value = null
        }
    }

    fun clearActionError() {
        _actionError.value = null
    }

    fun clearChatStreamError() {
        if (_chatStreamState.value is ChatStreamUiState.Error) {
            _chatStreamState.value = ChatStreamUiState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        lanServer.stop()
    }

    class Factory(
        private val repo: BotStore,
        private val chatStore: ChatHistoryStore,
        private val router: HybridAiRouter,
        private val localModelManager: LocalModelManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MoteViewModel(repo, chatStore, router, localModelManager) as T
        }
    }
}
