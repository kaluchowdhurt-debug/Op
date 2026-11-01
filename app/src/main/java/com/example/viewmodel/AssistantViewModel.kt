package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.ChatMessageEntity
import com.example.data.local.MemoryEntity
import com.example.data.repository.AssistantRepository
import com.example.model.Robot3DState
import com.example.model.RobotEmotion
import com.example.model.RobotGesture
import com.example.util.UniversalDeviceAiClient
import com.example.util.hasValidAppCheckToken
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "AssistantViewModel"

sealed interface AssistantUiState {
    data object Idle : AssistantUiState
    data object Thinking : AssistantUiState
    data class Streaming(val partialText: String, val emotion: RobotEmotion) : AssistantUiState
    data class Error(val message: String, val lastPrompt: String) : AssistantUiState
}

class AssistantViewModel(
    private val repository: AssistantRepository
) : ViewModel() {

    // Lazy initialization per Firebase AI Logic guidelines
    private val fastModel by lazy {
        Firebase.ai.generativeModel("gemini-3.5-flash-lite")
    }

    private val primaryModel by lazy {
        Firebase.ai.generativeModel("gemini-3.8-flash")
    }

    val messages: StateFlow<List<ChatMessageEntity>> = repository.allMessages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val memories: StateFlow<List<MemoryEntity>> = repository.allMemories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _robotState = MutableStateFlow(Robot3DState())
    val robotState: StateFlow<Robot3DState> = _robotState.asStateFlow()

    private val _uiState = MutableStateFlow<AssistantUiState>(AssistantUiState.Idle)
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val _liveTranscript = MutableStateFlow("")
    val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

    private val _lastSpokenReply = MutableStateFlow("")
    val lastSpokenReply: StateFlow<String> = _lastSpokenReply.asStateFlow()

    private var gestureResetJob: Job? = null
    private var activeGenerationJob: Job? = null
    private var hasGreetedThisSession = false
    private var bypassAppCheckOnExternalDevice = false

    init {
        viewModelScope.launch {
            repository.deleteSeededWelcomeMessages()
        }
        triggerTemporaryGesture(RobotGesture.WAVE)
    }

    fun triggerInitialRobotGreetingIfNeeded(onSpeak: (String) -> Unit) {
        if (hasGreetedThisSession) return
        hasGreetedThisSession = true
        val greeting = "Hello! Main taiyar hoon, aap mujhse kuch bhi pooch sakte hain ya koi bhi command de sakte hain."
        _lastSpokenReply.value = greeting
        _robotState.update {
            it.copy(emotion = RobotEmotion.HAPPY, activeGesture = RobotGesture.WAVE)
        }
        triggerTemporaryGesture(RobotGesture.WAVE)
        onSpeak(greeting)
    }

    fun recordDeviceCommandResult(
        userCommand: String,
        assistantReply: String,
        onSpeakReply: ((text: String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            if (userCommand.isNotBlank()) {
                repository.insertMessage(
                    ChatMessageEntity(
                        role = "USER",
                        content = userCommand.trim(),
                        emotionTag = RobotEmotion.LISTENING.name,
                        gestureTag = RobotGesture.NONE.name
                    )
                )
            }
            completeAssistantReply(assistantReply, onSpeakReply)
        }
    }

    fun sendMessage(
        rawPrompt: String,
        insertUserMessage: Boolean = true,
        onSpeakReply: ((text: String) -> Unit)? = null
    ) {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty()) return

        _liveTranscript.value = ""
        activeGenerationJob?.cancel()

        activeGenerationJob = viewModelScope.launch {
            val recentHistory = repository.allMessages.first().takeLast(8)

            // Check if the user pasted an API key (AIzaSy..., gsk_..., sk-...)
            val extractedKey = UniversalDeviceAiClient.extractUserProvidedApiKey(prompt)
            if (extractedKey != null) {
                if (insertUserMessage) {
                    val maskedPrompt = prompt.replace(
                        extractedKey,
                        extractedKey.take(6) + "..." + extractedKey.takeLast(4)
                    )
                    repository.insertMessage(
                        ChatMessageEntity(
                            role = "USER",
                            content = maskedPrompt,
                            emotionTag = RobotEmotion.LISTENING.name,
                            gestureTag = RobotGesture.NONE.name
                        )
                    )
                }
                val existingMemories = repository.allMemories.first()
                existingMemories
                    .filter { it.category == "API_KEY" || it.fact.startsWith("API_KEY:") }
                    .forEach { repository.deleteMemoryById(it.id) }

                repository.insertMemory(
                    MemoryEntity(
                        fact = "API_KEY:$extractedKey",
                        category = "API_KEY"
                    )
                )
                val keyType = when {
                    extractedKey.startsWith("AIza") -> "Google Gemini"
                    extractedKey.startsWith("gsk_") -> "Groq Llama-3"
                    else -> "OpenAI"
                }
                val masked = extractedKey.take(6) + "..." + extractedKey.takeLast(4)
                val confirmation = "Aapki **$keyType API Key** (`$masked`) successfully save aur activate ho gayi hai! Ab aap jo bhi sawal poochenge, uska jawab seedhe aapke $keyType API se fast aayega."
                completeAssistantReply(confirmation, onSpeakReply)
                return@launch
            }

            if (insertUserMessage) {
                repository.insertMessage(
                    ChatMessageEntity(
                        role = "USER",
                        content = prompt,
                        emotionTag = RobotEmotion.LISTENING.name,
                        gestureTag = RobotGesture.NONE.name
                    )
                )
                autoExtractAndStoreMemory(prompt)
            }
            val savedMemories = repository.allMemories.first().take(20)
            val hasCustomApiKey = UniversalDeviceAiClient.resolveActiveApiKey(savedMemories) != null

            _uiState.value = AssistantUiState.Thinking
            _robotState.update {
                it.copy(
                    emotion = RobotEmotion.THINKING,
                    activeGesture = RobotGesture.THINK_TILT,
                    isListening = false
                )
            }

            try {
                val fullPrompt = buildConversationalPrompt(prompt, recentHistory, savedMemories)
                val accumulatedText = StringBuilder()
                var firstChunkReceived = false

                // Only attempt Firebase AI Logic if an App Check token is present (AI Studio emulator)
                // and the user hasn't overridden it with a custom direct API key.
                val shouldTryFirebaseAi = hasValidAppCheckToken &&
                    !bypassAppCheckOnExternalDevice &&
                    !hasCustomApiKey

                if (shouldTryFirebaseAi) {
                    val fastOk = tryGenerateStream(
                        model = fastModel,
                        prompt = fullPrompt,
                        timeoutMs = 5_500L,
                        accumulated = accumulatedText,
                        onError = { err ->
                            val msg = err.message.orEmpty().lowercase()
                            if (msg.contains("app check") || msg.contains("403") || msg.contains("invalid")) {
                                bypassAppCheckOnExternalDevice = true
                            }
                        },
                        onChunk = { currentText ->
                            val emotion = inferEmotionFromText(currentText)
                            if (!firstChunkReceived) {
                                firstChunkReceived = true
                                val gesture = inferGestureFromText(currentText)
                                _robotState.update {
                                    it.copy(emotion = emotion, activeGesture = gesture)
                                }
                            }
                            _lastSpokenReply.value = currentText
                            _uiState.value = AssistantUiState.Streaming(
                                partialText = currentText,
                                emotion = emotion
                            )
                        }
                    )

                    if (!fastOk && accumulatedText.isBlank() && !bypassAppCheckOnExternalDevice) {
                        tryGenerateStream(
                            model = primaryModel,
                            prompt = fullPrompt,
                            timeoutMs = 5_500L,
                            accumulated = accumulatedText,
                            onError = { err ->
                                val msg = err.message.orEmpty().lowercase()
                                if (msg.contains("app check") || msg.contains("403") || msg.contains("invalid")) {
                                    bypassAppCheckOnExternalDevice = true
                                }
                            },
                            onChunk = { currentText ->
                                val emotion = inferEmotionFromText(currentText)
                                if (!firstChunkReceived) {
                                    firstChunkReceived = true
                                    val gesture = inferGestureFromText(currentText)
                                    _robotState.update {
                                        it.copy(emotion = emotion, activeGesture = gesture)
                                    }
                                }
                                _lastSpokenReply.value = currentText
                                _uiState.value = AssistantUiState.Streaming(
                                    partialText = currentText,
                                    emotion = emotion
                                )
                            }
                        )
                    }
                }

                val finalReply = if (accumulatedText.isNotBlank()) {
                    accumulatedText.toString().trim()
                } else {
                    UniversalDeviceAiClient.generateUniversalReply(
                        userPrompt = prompt,
                        recentHistory = recentHistory,
                        savedMemories = savedMemories
                    )
                }

                completeAssistantReply(finalReply, onSpeakReply)
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    val universalReply = UniversalDeviceAiClient.generateUniversalReply(
                        userPrompt = prompt,
                        recentHistory = recentHistory,
                        savedMemories = savedMemories
                    )
                    completeAssistantReply(universalReply, onSpeakReply)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                Log.w(TAG, "Switching to UniversalDeviceAiClient for all-device compatibility: ${e.localizedMessage}")
                val universalReply = UniversalDeviceAiClient.generateUniversalReply(
                    userPrompt = prompt,
                    recentHistory = recentHistory,
                    savedMemories = savedMemories
                )
                completeAssistantReply(universalReply, onSpeakReply)
            }
        }
    }

    fun retryLastMessage(onSpeakReply: ((text: String) -> Unit)? = null) {
        val currentState = _uiState.value
        if (currentState is AssistantUiState.Error) {
            sendMessage(
                rawPrompt = currentState.lastPrompt,
                insertUserMessage = false,
                onSpeakReply = onSpeakReply
            )
        }
    }

    fun dismissError() {
        if (_uiState.value is AssistantUiState.Error) {
            _uiState.value = AssistantUiState.Idle
        }
    }

    fun clearChatHistory() {
        activeGenerationJob?.cancel()
        viewModelScope.launch {
            repository.clearAllMessages()
            _uiState.value = AssistantUiState.Idle
            _lastSpokenReply.value = ""
        }
    }

    private suspend fun completeAssistantReply(
        finalReply: String,
        onSpeakReply: ((text: String) -> Unit)?
    ) {
        val finalEmotion = inferEmotionFromText(finalReply)
        val finalGesture = inferGestureFromText(finalReply)

        repository.insertMessage(
            ChatMessageEntity(
                role = "ASSISTANT",
                content = finalReply,
                emotionTag = finalEmotion.name,
                gestureTag = finalGesture.name
            )
        )

        _lastSpokenReply.value = finalReply
        _uiState.value = AssistantUiState.Idle
        _robotState.update {
            it.copy(
                emotion = finalEmotion,
                activeGesture = finalGesture
            )
        }
        triggerTemporaryGesture(finalGesture)
        onSpeakReply?.invoke(finalReply)
    }

    private suspend fun tryGenerateStream(
        model: com.google.firebase.ai.GenerativeModel,
        prompt: String,
        timeoutMs: Long,
        accumulated: StringBuilder,
        onError: (Exception) -> Unit,
        onChunk: (String) -> Unit
    ): Boolean {
        return try {
            withTimeout(timeoutMs) {
                model.generateContentStream(prompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        accumulated.append(piece)
                        onChunk(accumulated.toString())
                    }
                }
            }
            accumulated.isNotBlank()
        } catch (e: TimeoutCancellationException) {
            onError(e)
            accumulated.isNotBlank()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Stream attempt error: ${e.localizedMessage}", e)
            onError(e)
            accumulated.isNotBlank()
        }
    }

    private suspend fun autoExtractAndStoreMemory(prompt: String) {
        val lower = prompt.lowercase()
        val shouldRemember = lower.contains("mera naam") ||
            lower.contains("my name is") ||
            lower.contains("yaad rakh") ||
            lower.contains("remember") ||
            lower.contains("mujhe ") && lower.contains("pasand") ||
            lower.contains("i like ") ||
            lower.contains("mera birthday") ||
            lower.contains("main ") && (lower.contains("rehta") || lower.contains("rehti"))

        if (shouldRemember && prompt.length in 5..220) {
            val existing = repository.allMemories.first()
            if (existing.none { it.fact.equals(prompt, ignoreCase = true) }) {
                repository.insertMemory(
                    MemoryEntity(
                        fact = prompt,
                        category = if (lower.contains("pasand") || lower.contains("like")) "PREFERENCE" else "PERSONAL"
                    )
                )
            }
        }
    }

    private fun buildConversationalPrompt(
        userPrompt: String,
        recentHistory: List<ChatMessageEntity>,
        savedMemories: List<MemoryEntity>
    ): String {
        val memoryBlock = if (savedMemories.isEmpty()) {
            ""
        } else {
            "Saved User Memories (Always remember these facts about the user):\n" +
                savedMemories.joinToString("\n") { "- ${it.fact}" } + "\n\n"
        }

        val historyBlock = if (recentHistory.isEmpty()) {
            ""
        } else {
            "Recent conversation:\n" + recentHistory.joinToString("\n") {
                "${if (it.role == "USER") "User" else "Assistant"}: ${it.content}"
            } + "\n\n"
        }

        return "${memoryBlock}${historyBlock}You are NovaBot 3D, a smart, helpful, and friendly AI assistant and coding helper with persistent memory. " +
            "Answer the user's question accurately, directly, and naturally in the same language or script they used (Hindi, Hinglish, or English). " +
            "When asked programming or coding questions, provide clear, well-structured code and explanations in any requested programming language (such as Python, Kotlin, Java, JavaScript, HTML/CSS, C++, or SQL), while strictly following safety guidelines and declining any requests for malware, exploits, or harmful actions. " +
            "Never repeat or echo the user's question back without answering it.\n" +
            "User: $userPrompt\nAssistant:"
    }

    private fun inferEmotionFromText(text: String): RobotEmotion {
        val lower = text.lowercase()
        return when {
            lower.contains("!") || lower.contains("badhiya") || lower.contains("swagat") -> RobotEmotion.EXCITED
            lower.contains("?") || lower.contains("kya") -> RobotEmotion.CURIOUS
            else -> RobotEmotion.HAPPY
        }
    }

    private fun inferGestureFromText(text: String): RobotGesture {
        val lower = text.lowercase()
        return when {
            lower.startsWith("hello") || lower.startsWith("hi") || lower.startsWith("namaste") -> RobotGesture.WAVE
            lower.contains("!") -> RobotGesture.BOUNCE
            lower.contains("?") -> RobotGesture.THINK_TILT
            else -> RobotGesture.NOD
        }
    }

    fun triggerTemporaryGesture(gesture: RobotGesture) {
        gestureResetJob?.cancel()
        _robotState.update { it.copy(activeGesture = gesture) }
        if (gesture != RobotGesture.NONE && gesture.durationMs > 0L) {
            gestureResetJob = viewModelScope.launch {
                delay(gesture.durationMs)
                _robotState.update { it.copy(activeGesture = RobotGesture.NONE) }
            }
        }
    }

    fun onCameraOrbit(deltaYaw: Float, deltaPitch: Float) {
        _robotState.update { current ->
            val newYaw = (current.cameraYaw + deltaYaw) % 360f
            val newPitch = (current.cameraPitch + deltaPitch).coerceIn(-38f, 38f)
            current.copy(cameraYaw = newYaw, cameraPitch = newPitch)
        }
    }

    fun resetCameraView() {
        _robotState.update {
            it.copy(cameraYaw = 16f, cameraPitch = -8f, cameraZoom = 1.0f)
        }
        triggerTemporaryGesture(RobotGesture.WAVE)
    }

    fun onPokeRobot(onSpeak: (String) -> Unit) {
        val nextCount = _robotState.value.pokeCount + 1
        val quips = listOf(
            "Haan boliye, main sun raha hoon! Aap kya poochna chahte hain?",
            "Main aapka 3D assistant hoon, kuch bhi puchiye!",
            "Boliye, aaj main aapki kya help karoon?"
        )
        val chosenQuip = quips[nextCount % quips.size]
        _lastSpokenReply.value = chosenQuip
        _robotState.update {
            it.copy(
                emotion = RobotEmotion.HAPPY,
                pokeCount = nextCount
            )
        }
        triggerTemporaryGesture(RobotGesture.WAVE)
        onSpeak(chosenQuip)
    }

    fun onListeningStateChanged(listening: Boolean) {
        _robotState.update {
            it.copy(
                isListening = listening,
                emotion = if (listening) RobotEmotion.LISTENING else it.emotion,
                activeGesture = if (listening) RobotGesture.SCAN else it.activeGesture
            )
        }
        if (!listening) {
            _liveTranscript.value = ""
        }
    }

    fun onSpeakingStateChanged(speaking: Boolean) {
        _robotState.update {
            it.copy(
                isSpeaking = speaking,
                emotion = if (speaking) RobotEmotion.SPEAKING else RobotEmotion.HAPPY,
                audioAmplitude = if (speaking) 0.78f else 0.12f
            )
        }
    }

    fun onAudioLevelChanged(level: Float) {
        _robotState.update { it.copy(audioAmplitude = level) }
    }

    fun onPartialTranscript(text: String) {
        _liveTranscript.value = text
    }

    class Factory(private val repository: AssistantRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AssistantViewModel(repository) as T
        }
    }
}
