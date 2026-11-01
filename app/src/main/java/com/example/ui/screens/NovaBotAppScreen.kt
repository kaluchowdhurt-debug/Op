package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.ChatMessageEntity
import com.example.ui.components.ChatMessageBubble
import com.example.ui.components.ErrorBannerCard
import com.example.ui.components.Robot3DCanvas
import com.example.util.DeviceCommandAction
import com.example.util.DeviceCommandController
import com.example.util.VoiceAssistantManager
import com.example.viewmodel.AssistantUiState
import com.example.viewmodel.AssistantViewModel

@Composable
fun NovaBotAppScreen(
    viewModel: AssistantViewModel
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val robotState by viewModel.robotState.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val liveTranscript by viewModel.liveTranscript.collectAsStateWithLifecycle()
    val lastSpokenReply by viewModel.lastSpokenReply.collectAsStateWithLifecycle()

    // false = Full-Screen 3D Robot (automatic continuous human-like voice conversation)
    // true = Full-Screen Text Chat (when user taps text box to type)
    var isTextChatMode by rememberSaveable { mutableStateOf(false) }
    var textInput by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled = isTextChatMode) {
        focusManager.clearFocus()
        isTextChatMode = false
    }

    var voiceManagerRef by remember { mutableStateOf<VoiceAssistantManager?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && !isTextChatMode) {
            voiceManagerRef?.autoListenAfterSpeech = true
            voiceManagerRef?.startListening()
        }
    }

    val executeCommandOrSendToAi: (String) -> Unit = { rawInput ->
        val clean = rawInput.trim()
        if (clean.isNotEmpty()) {
            when (val action = DeviceCommandController.evaluateCommand(context, clean)) {
                is DeviceCommandAction.ImmediateSpokenResult -> {
                    viewModel.recordDeviceCommandResult(
                        userCommand = clean,
                        assistantReply = action.spokenMessage,
                        onSpeakReply = { voiceManagerRef?.speak(it) }
                    )
                }
                is DeviceCommandAction.ExitSession -> {
                    focusManager.clearFocus()
                    isTextChatMode = false
                    viewModel.recordDeviceCommandResult(
                        userCommand = clean,
                        assistantReply = action.spokenMessage,
                        onSpeakReply = { voiceManagerRef?.speak(it) }
                    )
                }
                is DeviceCommandAction.None -> {
                    viewModel.sendMessage(
                        rawPrompt = clean,
                        onSpeakReply = { reply ->
                            voiceManagerRef?.speak(reply)
                        }
                    )
                }
            }
        }
    }

    val voiceManager = remember(context) {
        var managerRef: VoiceAssistantManager? = null
        val created = VoiceAssistantManager(
            context = context,
            onTtsReady = {
                viewModel.triggerInitialRobotGreetingIfNeeded { greeting ->
                    managerRef?.speak(greeting)
                }
            },
            onPartialTranscript = { partial -> viewModel.onPartialTranscript(partial) },
            onFinalTranscript = { finalSpoken ->
                executeCommandOrSendToAi(finalSpoken)
            },
            onListeningStateChanged = { listening -> viewModel.onListeningStateChanged(listening) },
            onAudioLevelChanged = { rms -> viewModel.onAudioLevelChanged(rms) },
            onSpeakingStateChanged = { speaking -> viewModel.onSpeakingStateChanged(speaking) },
            onSpeakingCompleted = {},
            onVoiceError = {
                if (!isTextChatMode) {
                    managerRef?.startListening()
                }
            }
        )
        managerRef = created
        voiceManagerRef = created
        created
    }

    LaunchedEffect(isTextChatMode) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        voiceManager.autoListenAfterSpeech = !isTextChatMode && hasPerm
        if (!isTextChatMode && hasPerm && !robotState.isSpeaking) {
            voiceManager.startListening()
        }
    }

    DisposableEffect(voiceManager) {
        onDispose {
            voiceManager.release()
        }
    }

    LaunchedEffect(Unit) {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val handleSendTextPrompt: (String) -> Unit = { promptText ->
        val clean = promptText.trim()
        if (clean.isNotEmpty()) {
            textInput = ""
            isTextChatMode = true
            voiceManager.autoListenAfterSpeech = false
            voiceManager.stopListening()
            voiceManager.stopSpeaking()
            executeCommandOrSendToAi(clean)
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, uiState, isTextChatMode) {
        if (isTextChatMode && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // 3D ROBOT VIEW
                this@Column.AnimatedVisibility(
                    visible = !isTextChatMode,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.surface,
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                    ) {
                        Robot3DCanvas(
                            state = robotState,
                            onCameraOrbit = { dYaw, dPitch -> viewModel.onCameraOrbit(dYaw, dPitch) },
                            onPokeRobot = {
                                if (robotState.touchHapticsEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                viewModel.onPokeRobot { quip ->
                                    voiceManager.speak(quip)
                                }
                            },
                            onResetCamera = {
                                if (robotState.touchHapticsEnabled) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                viewModel.resetCameraView()
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Spoken Subtitle at bottom of 3D stage
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (uiState is AssistantUiState.Error) {
                                ErrorBannerCard(
                                    errorMessage = (uiState as AssistantUiState.Error).message,
                                    onRetry = {
                                        viewModel.retryLastMessage(
                                            onSpeakReply = { reply -> voiceManager.speak(reply) }
                                        )
                                    },
                                    onDismiss = { viewModel.dismissError() }
                                )
                            } else {
                                val subtitleText = when {
                                    liveTranscript.isNotBlank() -> "\"$liveTranscript\""
                                    uiState is AssistantUiState.Thinking -> "Thinking…"
                                    uiState is AssistantUiState.Streaming -> (uiState as AssistantUiState.Streaming).partialText
                                    lastSpokenReply.isNotBlank() -> lastSpokenReply
                                    else -> ""
                                }

                                if (subtitleText.isNotBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                        shape = RoundedCornerShape(18.dp),
                                        modifier = Modifier.border(
                                            width = 1.dp,
                                            color = robotState.emotion.visorColor.copy(alpha = 0.45f),
                                            shape = RoundedCornerShape(18.dp)
                                        )
                                    ) {
                                        Text(
                                            text = subtitleText,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (liveTranscript.isNotBlank()) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            textAlign = TextAlign.Center,
                                            maxLines = 4,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // TEXT CHAT VIEW (3D Robot hides when chatting via text)
                this@Column.AnimatedVisibility(
                    visible = isTextChatMode,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    NovaBotChatContent(
                        messages = messages,
                        uiState = uiState,
                        inputText = textInput,
                        onInputTextChanged = { newValue ->
                            textInput = newValue
                        },
                        onSendPrompt = { promptText ->
                            handleSendTextPrompt(promptText)
                        },
                        onBackClick = {
                            focusManager.clearFocus()
                            isTextChatMode = false
                        },
                        onClearChat = {
                            viewModel.clearChatHistory()
                        },
                        onRetryError = {
                            viewModel.retryLastMessage(
                                onSpeakReply = { reply -> voiceManager.speak(reply) }
                            )
                        },
                        onDismissError = {
                            viewModel.dismissError()
                        },
                        onSpeakMessage = { content ->
                            voiceManager.speak(content)
                        },
                        showInputBar = false,
                        listState = listState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // CLEAN BOTTOM BAR: text input + Send button
            val isThinking = uiState is AssistantUiState.Thinking || uiState is AssistantUiState.Streaming
            NovaBotChatInputBar(
                inputText = textInput,
                isBusy = isThinking,
                onInputTextChanged = { newValue ->
                    textInput = newValue
                    if (newValue.isNotEmpty() && !isTextChatMode) {
                        isTextChatMode = true
                        voiceManager.autoListenAfterSpeech = false
                        voiceManager.stopListening()
                    }
                },
                onSendPrompt = { promptText ->
                    handleSendTextPrompt(promptText)
                },
                onInputFocused = {
                    if (!isTextChatMode) {
                        isTextChatMode = true
                        voiceManager.autoListenAfterSpeech = false
                        voiceManager.stopListening()
                    }
                }
            )
        }
    }
}
