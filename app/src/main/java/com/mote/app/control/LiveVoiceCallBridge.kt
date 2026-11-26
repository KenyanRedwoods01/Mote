package com.mote.app.control

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.mote.app.ai.HybridAiRouter
import com.mote.app.service.LiveVoiceRelayUiState
import com.mote.app.service.SystemOverlayStateBus
import com.mote.app.service.VoiceRelayPhase
import com.mote.app.service.VoiceRelayTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Real-Time Continuous Voice Call Relay Bridge using Android's free built-in:
 * - [SpeechRecognizer] (Google Speech-to-Text) to listen continuously to speaker/mic audio
 * - [HybridAiRouter] (Gemini Flash-Lite) to generate concise real-time spoken responses
 * - [TextToSpeech] (Google Text-to-Speech) to speak responses aloud and automatically loop back to listening
 *
 * Publishes every state update, live RMS audio level, and transcript turn to [SystemOverlayStateBus]
 * so the Siri-like floating overlay renders live call relay audio waveforms over the Phone dialer or any app.
 */
class LiveVoiceCallBridge(
    context: Context,
    private val aiRouter: HybridAiRouter
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var ttsReady: Boolean = false

    @Volatile
    private var activeSession: Boolean = false

    @Volatile
    private var isPaused: Boolean = false

    @Volatile
    private var activeInstruction: String = DEFAULT_RELAY_INSTRUCTION

    private var activeReplyJob: Job? = null

    private val _state = MutableStateFlow(LiveVoiceRelayUiState())
    val state: StateFlow<LiveVoiceRelayUiState> = _state.asStateFlow()

    init {
        SystemOverlayStateBus.registerVoiceRelayControls(
            onStop = { stopRelay() },
            onTogglePause = { togglePause() }
        )
    }

    fun isRunning(): Boolean = activeSession

    fun startRelay(
        targetLabel: String = "Live Voice Relay",
        instruction: String? = null,
        openingGreeting: String? = null
    ): Pair<Boolean, String> {
        val hasMic = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            val msg = "Microphone permission is required for real-time Speech-to-Text voice relay."
            updateState {
                it.copy(
                    isActive = false,
                    phase = VoiceRelayPhase.IDLE,
                    errorMessage = msg,
                    statusNote = msg
                )
            }
            return false to msg
        }

        activeInstruction = instruction?.takeIf { it.isNotBlank() } ?: DEFAULT_RELAY_INSTRUCTION
        activeSession = true
        isPaused = false
        enableSpeakerphoneBestEffort(true)
        ensureTtsInitialized()
        SystemOverlayStateBus.ensureSystemOverlayRunning(appContext)

        updateState {
            LiveVoiceRelayUiState(
                isActive = true,
                phase = if (!openingGreeting.isNullOrBlank()) VoiceRelayPhase.SPEAKING else VoiceRelayPhase.LISTENING,
                targetLabel = targetLabel.ifBlank { "Live Voice Relay" },
                rmsLevel = 0.12f,
                statusNote = "Live STT ↔ AI ↔ TTS active (put call on speakerphone for caller audio)",
                errorMessage = null,
                turns = emptyList()
            )
        }

        if (!openingGreeting.isNullOrBlank()) {
            appendTurn("Mote Voice AI", openingGreeting)
            speakAndThenListen(openingGreeting)
        } else {
            scheduleStartListening(delayMs = 250L)
        }

        return true to "Started real-time Google STT ↔ AI ↔ TTS Voice Relay for '$targetLabel'. Put your call on speakerphone so Mote can listen and respond aloud in real time."
    }

    fun togglePause() {
        if (!activeSession) return
        isPaused = !isPaused
        if (isPaused) {
            stopRecognizerOnMain()
            mainHandler.post { runCatching { textToSpeech?.stop() } }
            updateState {
                it.copy(
                    phase = VoiceRelayPhase.PAUSED,
                    rmsLevel = 0f,
                    statusNote = "Voice relay paused — tap Resume to keep listening"
                )
            }
        } else {
            updateState {
                it.copy(
                    phase = VoiceRelayPhase.LISTENING,
                    statusNote = "Resumed real-time listening..."
                )
            }
            scheduleStartListening(delayMs = 180L)
        }
    }

    fun speakDirectText(text: String): Pair<Boolean, String> {
        val clean = text.trim()
        if (clean.isBlank()) return false to "Nothing to speak."
        ensureTtsInitialized()
        appendTurn("Mote Voice AI", clean)
        speakAndThenListen(clean, resumeListeningAfter = activeSession && !isPaused)
        return true to "Speaking aloud via Android Text-to-Speech: \"${clean.take(90)}\""
    }

    /**
     * Allows injecting a simulated/manual caller utterance (handy for testing or noisy environments)
     * which still runs the real Gemini AI reply + real Android TTS playback.
     */
    fun submitHeardUtterance(heardText: String) {
        val clean = heardText.trim()
        if (clean.isBlank()) return
        if (!activeSession) {
            activeSession = true
            isPaused = false
            ensureTtsInitialized()
            updateState {
                it.copy(
                    isActive = true,
                    phase = VoiceRelayPhase.THINKING,
                    errorMessage = null
                )
            }
        }
        handleTranscribedSpeech(clean)
    }

    fun stopRelay(): Pair<Boolean, String> {
        activeSession = false
        isPaused = false
        activeReplyJob?.cancel()
        activeReplyJob = null
        stopRecognizerOnMain()
        mainHandler.post { runCatching { textToSpeech?.stop() } }
        enableSpeakerphoneBestEffort(false)
        updateState {
            it.copy(
                isActive = false,
                phase = VoiceRelayPhase.IDLE,
                rmsLevel = 0f,
                partialTranscript = "",
                statusNote = "Voice call relay ended."
            )
        }
        return true to "Stopped live Voice Call Relay."
    }

    fun shutdown() {
        stopRelay()
        mainHandler.post {
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
            runCatching { textToSpeech?.shutdown() }
            textToSpeech = null
        }
    }

    private fun ensureTtsInitialized() {
        if (textToSpeech != null) return
        mainHandler.post {
            if (textToSpeech != null) return@post
            textToSpeech = TextToSpeech(appContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    textToSpeech?.language = Locale.getDefault()
                    textToSpeech?.setSpeechRate(1.03f)
                    textToSpeech?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            updateState {
                                it.copy(
                                    phase = VoiceRelayPhase.SPEAKING,
                                    rmsLevel = 0.65f,
                                    statusNote = "Speaking AI reply via Android TTS..."
                                )
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            val shouldResume = utteranceId?.startsWith("relay_loop_") == true && activeSession && !isPaused
                            if (shouldResume) {
                                scheduleStartListening(delayMs = 320L)
                            } else if (!activeSession) {
                                updateState { it.copy(phase = VoiceRelayPhase.IDLE, rmsLevel = 0f) }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (activeSession && !isPaused) {
                                scheduleStartListening(delayMs = 450L)
                            }
                        }
                    })
                }
            }
        }
    }

    private fun scheduleStartListening(delayMs: Long = 200L) {
        if (!activeSession || isPaused) return
        mainHandler.postDelayed({
            if (!activeSession || isPaused) return@postDelayed
            startRecognizerOnMain()
        }, delayMs)
    }

    private fun startRecognizerOnMain() {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            updateState {
                it.copy(
                    phase = VoiceRelayPhase.PAUSED,
                    errorMessage = "Google Speech Recognition service unavailable on this device.",
                    statusNote = "SpeechRecognizer unavailable — you can still type or tap Speak"
                )
            }
            return
        }

        try {
            speechRecognizer?.destroy()
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    updateState {
                        it.copy(
                            phase = VoiceRelayPhase.LISTENING,
                            rmsLevel = 0.15f,
                            partialTranscript = "",
                            errorMessage = null,
                            statusNote = "Listening to live audio (Google STT)..."
                        )
                    }
                }

                override fun onBeginningOfSpeech() {
                    updateState { it.copy(phase = VoiceRelayPhase.LISTENING, rmsLevel = 0.35f) }
                }

                override fun onRmsChanged(rmsdB: Float) {
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
                    updateState { it.copy(rmsLevel = normalized) }
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    updateState { it.copy(rmsLevel = 0.08f) }
                }

                override fun onError(error: Int) {
                    if (!activeSession || isPaused) return
                    val retryDelay = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 350L
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 900L
                        else -> 1100L
                    }
                    updateState {
                        it.copy(
                            phase = VoiceRelayPhase.LISTENING,
                            rmsLevel = 0.05f,
                            statusNote = "Listening continuously for speech..."
                        )
                    }
                    scheduleStartListening(delayMs = retryDelay)
                }

                override fun onResults(results: Bundle?) {
                    val spoken = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    if (!spoken.isNullOrBlank()) {
                        handleTranscribedSpeech(spoken)
                    } else if (activeSession && !isPaused) {
                        scheduleStartListening(delayMs = 300L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    if (!partial.isNullOrBlank()) {
                        updateState { it.copy(partialTranscript = partial) }
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
            }
            recognizer.startListening(intent)
        } catch (e: Exception) {
            updateState {
                it.copy(
                    errorMessage = e.localizedMessage ?: "Failed to start SpeechRecognizer",
                    statusNote = "Retrying voice listener..."
                )
            }
            scheduleStartListening(delayMs = 1200L)
        }
    }

    private fun stopRecognizerOnMain() {
        mainHandler.post {
            runCatching { speechRecognizer?.stopListening() }
            runCatching { speechRecognizer?.cancel() }
            runCatching { speechRecognizer?.destroy() }
            speechRecognizer = null
        }
    }

    private fun handleTranscribedSpeech(heardText: String) {
        stopRecognizerOnMain()
        appendTurn("Caller / Mic", heardText)
        updateState {
            it.copy(
                phase = VoiceRelayPhase.THINKING,
                partialTranscript = heardText,
                rmsLevel = 0.25f,
                statusNote = "Heard: \"${heardText.take(48)}\" · Generating AI reply..."
            )
        }

        activeReplyJob?.cancel()
        activeReplyJob = scope.launch {
            val historySnippet = _state.value.turns.takeLast(6).joinToString("\n") {
                "${it.speaker}: ${it.text}"
            }
            val reply = aiRouter.generateVoiceCallReply(
                heardText = heardText,
                conversationContext = historySnippet,
                personaInstruction = activeInstruction
            ).getOrElse {
                "Got it — could you repeat that briefly so I can help?"
            }

            if (!activeSession || isPaused) return@launch
            appendTurn("Mote Voice AI", reply)
            updateState {
                it.copy(
                    lastSpokenReply = reply,
                    partialTranscript = "",
                    phase = VoiceRelayPhase.SPEAKING,
                    statusNote = "Speaking: \"${reply.take(54)}\""
                )
            }
            speakAndThenListen(reply, resumeListeningAfter = true)
        }
    }

    private fun speakAndThenListen(text: String, resumeListeningAfter: Boolean = true) {
        scope.launch {
            // Wait briefly for TTS engine initialization on cold start
            var waited = 0
            while (!ttsReady && waited < 12) {
                delay(150L)
                waited++
            }
            mainHandler.post {
                val tts = textToSpeech
                if (tts != null && ttsReady) {
                    val utteranceId = if (resumeListeningAfter) {
                        "relay_loop_${UUID.randomUUID().toString().take(8)}"
                    } else {
                        "relay_once_${UUID.randomUUID().toString().take(8)}"
                    }
                    tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                } else if (resumeListeningAfter && activeSession && !isPaused) {
                    scheduleStartListening(delayMs = 500L)
                }
            }
        }
    }

    private fun appendTurn(speaker: String, text: String) {
        val turn = VoiceRelayTurn(
            id = "vt_${System.nanoTime()}",
            speaker = speaker,
            text = text
        )
        updateState {
            it.copy(turns = (it.turns + turn).takeLast(14))
        }
    }

    @Suppress("DEPRECATION")
    private fun enableSpeakerphoneBestEffort(enable: Boolean) {
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.isSpeakerphoneOn = enable
        } catch (_: Exception) {
            // Best-effort speakerphone hint
        }
    }

    private fun updateState(transform: (LiveVoiceRelayUiState) -> LiveVoiceRelayUiState) {
        val next = transform(_state.value)
        _state.value = next
        SystemOverlayStateBus.publishVoiceRelayState(next)
    }

    companion object {
        private const val DEFAULT_RELAY_INSTRUCTION =
            "You are Mote, a real-time hands-free voice call assistant speaking aloud on the user's behalf. " +
                "Reply naturally in 1 to 2 short spoken sentences with no markdown, no bullet points, and no emojis."
    }
}
