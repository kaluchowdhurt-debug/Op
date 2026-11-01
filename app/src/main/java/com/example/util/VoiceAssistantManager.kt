package com.example.util

import android.content.Context
import android.content.Intent
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
import android.util.Log
import java.util.Locale
import java.util.UUID

private const val TAG = "VoiceAssistantManager"

class VoiceAssistantManager(
    private val context: Context,
    private val onTtsReady: () -> Unit,
    private val onPartialTranscript: (String) -> Unit,
    private val onFinalTranscript: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit,
    private val onAudioLevelChanged: (Float) -> Unit,
    private val onSpeakingStateChanged: (Boolean) -> Unit,
    private val onSpeakingCompleted: () -> Unit,
    private val onVoiceError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private var pendingSpeechText: String? = null
    var autoListenAfterSpeech: Boolean = true

    init {
        initTextToSpeech()
    }

    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val tts = textToSpeech ?: return@TextToSpeech

                    // Set clear speech audio attributes so voice plays smoothly on STREAM_MUSIC
                    try {
                        val audioAttrs = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts.setAudioAttributes(audioAttrs)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set AudioAttributes on TTS", e)
                    }

                    // Configure language with safe fallback
                    val defaultLoc = Locale.getDefault()
                    val res = tts.setLanguage(defaultLoc)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.setLanguage(Locale.US)
                    }

                    tts.setSpeechRate(0.98f)
                    tts.setPitch(1.0f)
                    isTtsInitialized = true

                    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            mainHandler.post { onSpeakingStateChanged(true) }
                        }

                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                onSpeakingStateChanged(false)
                                onSpeakingCompleted()
                                if (autoListenAfterSpeech) {
                                    mainHandler.postDelayed({
                                        if (autoListenAfterSpeech) {
                                            startListening()
                                        }
                                    }, 400L)
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            mainHandler.post { onSpeakingStateChanged(false) }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            mainHandler.post { onSpeakingStateChanged(false) }
                        }
                    })

                    mainHandler.post {
                        onTtsReady()
                        pendingSpeechText?.let { queued ->
                            pendingSpeechText = null
                            speak(queued)
                        }
                    }
                } else {
                    Log.w(TAG, "TextToSpeech init status=$status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e)
        }
    }

    fun startListening() {
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    onVoiceError("Voice recognition is not supported on this device.")
                    return@post
                }

                // Stop any active speech first
                if (textToSpeech?.isSpeaking == true) {
                    textToSpeech?.stop()
                    onSpeakingStateChanged(false)
                }

                // Always recreate a clean SpeechRecognizer instance to prevent ERROR_RECOGNIZER_BUSY
                destroyRecognizerInternal()

                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            onListeningStateChanged(true)
                        }

                        override fun onBeginningOfSpeech() {
                            onListeningStateChanged(true)
                        }

                        override fun onRmsChanged(rmsdB: Float) {
                            val normalized = ((rmsdB + 2f) / 10f).coerceIn(0.10f, 1.0f)
                            onAudioLevelChanged(normalized)
                        }

                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            onListeningStateChanged(false)
                        }

                        override fun onError(error: Int) {
                            onListeningStateChanged(false)
                            destroyRecognizerInternal()
                            if (autoListenAfterSpeech && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                                mainHandler.postDelayed({
                                    if (autoListenAfterSpeech) {
                                        startListening()
                                    }
                                }, 600L)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            onListeningStateChanged(false)
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val spokenText = matches?.firstOrNull()?.trim().orEmpty()
                            destroyRecognizerInternal()
                            if (spokenText.isNotEmpty()) {
                                onFinalTranscript(spokenText)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                                .orEmpty()
                            if (partial.isNotEmpty()) {
                                onPartialTranscript(partial)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                speechRecognizer?.startListening(intent)
                onListeningStateChanged(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start speech recognition", e)
                onListeningStateChanged(false)
            }
        }
    }

    private fun destroyRecognizerInternal() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        } finally {
            speechRecognizer = null
        }
    }

    fun stopListening() {
        mainHandler.post {
            destroyRecognizerInternal()
            onListeningStateChanged(false)
        }
    }

    fun speak(text: String, speechRate: Float = 0.98f, pitch: Float = 1.0f) {
        val cleanText = text
            .replace("*", "")
            .replace("#", "")
            .replace("`", "")
            .trim()
        if (cleanText.isEmpty()) return

        if (!isTtsInitialized) {
            pendingSpeechText = cleanText
            return
        }

        mainHandler.post {
            try {
                // Release microphone completely so Android routes full audio to speaker smoothly
                destroyRecognizerInternal()
                onListeningStateChanged(false)

                val tts = textToSpeech ?: return@post
                tts.setSpeechRate(speechRate.coerceIn(0.85f, 1.2f))
                tts.setPitch(pitch.coerceIn(0.85f, 1.15f))

                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                }
                val utteranceId = UUID.randomUUID().toString()
                tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to speak text via TTS", e)
                onSpeakingStateChanged(false)
            }
        }
    }

    fun stopSpeaking() {
        mainHandler.post {
            pendingSpeechText = null
            try {
                if (textToSpeech?.isSpeaking == true) {
                    textToSpeech?.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping TTS", e)
            } finally {
                onSpeakingStateChanged(false)
            }
        }
    }

    fun release() {
        autoListenAfterSpeech = false
        pendingSpeechText = null
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizerInternal()
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TextToSpeech", e)
        }
    }
}
