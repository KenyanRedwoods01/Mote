package com.mote.app.ui.components

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

data class SpeechRecognitionState(
    val isListening: Boolean = false,
    val rmsLevel: Float = 0f,
    val errorText: String? = null
)

/**
 * In-place Google Speech Recognition API controller backed by Android's [SpeechRecognizer].
 * Streams partial dictation live into the caller's input field and cleans up native resources on dispose.
 */
class GoogleSpeechInputController(
    context: Context,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onStateChanged: (SpeechRecognitionState) -> Unit
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    fun isOnDeviceRecognizerAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext)

    fun startListening() {
        stopListening()
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onStateChanged(
                SpeechRecognitionState(
                    isListening = false,
                    errorText = "Google Speech Recognition service is not available."
                )
            )
            return
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onStateChanged(SpeechRecognitionState(isListening = true, rmsLevel = 0.2f))
            }

            override fun onBeginningOfSpeech() {
                onStateChanged(SpeechRecognitionState(isListening = true, rmsLevel = 0.4f))
            }

            override fun onRmsChanged(rmsdB: Float) {
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.1f, 1f)
                onStateChanged(SpeechRecognitionState(isListening = true, rmsLevel = normalized))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                onStateChanged(SpeechRecognitionState(isListening = false, rmsLevel = 0f))
            }

            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio capture error."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Network unavailable for speech recognition."
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected. Tap the mic to try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out."
                    else -> "Voice input stopped (code $error)."
                }
                onStateChanged(SpeechRecognitionState(isListening = false, rmsLevel = 0f, errorText = msg))
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                onStateChanged(SpeechRecognitionState(isListening = false, rmsLevel = 0f))
                if (!text.isNullOrBlank()) {
                    onFinalText(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                if (!partial.isNullOrBlank()) {
                    onPartialText(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        onStateChanged(SpeechRecognitionState(isListening = true, rmsLevel = 0.15f))
        sr.startListening(intent)
    }

    fun stopListening() {
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        onStateChanged(SpeechRecognitionState(isListening = false, rmsLevel = 0f))
    }

    fun release() {
        stopListening()
    }
}
