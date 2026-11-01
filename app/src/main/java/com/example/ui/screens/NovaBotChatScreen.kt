package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.ChatMessageEntity
import com.example.ui.components.ChatMessageBubble
import com.example.ui.components.ErrorBannerCard
import com.example.viewmodel.AssistantUiState
import com.example.viewmodel.AssistantViewModel

/**
 * Standalone Jetpack Compose screen providing a full chat interface for interacting
 * with the NovaBot AI, including a scrollable message list and a bottom input bar.
 */
@Composable
fun NovaBotChatScreen(
    viewModel: AssistantViewModel,
    onBackClick: (() -> Unit)? = null,
    onSpeakMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var inputText by rememberSaveable { mutableStateOf("") }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        NovaBotChatContent(
            messages = messages,
            uiState = uiState,
            inputText = inputText,
            onInputTextChanged = { inputText = it },
            onSendPrompt = { prompt ->
                val clean = prompt.trim()
                if (clean.isNotEmpty()) {
                    inputText = ""
                    viewModel.sendMessage(
                        rawPrompt = clean,
                        onSpeakReply = { reply -> onSpeakMessage(reply) }
                    )
                }
            },
            onBackClick = onBackClick,
            onClearChat = { viewModel.clearChatHistory() },
            onRetryError = {
                viewModel.retryLastMessage(onSpeakReply = { reply -> onSpeakMessage(reply) })
            },
            onDismissError = { viewModel.dismissError() },
            onSpeakMessage = onSpeakMessage,
            showInputBar = true,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

/**
 * Reusable stateless chat content with a top header, scrollable message list,
 * empty-state starter suggestions, streaming/thinking indicators, and optional input bar.
 */
@Composable
fun NovaBotChatContent(
    messages: List<ChatMessageEntity>,
    uiState: AssistantUiState,
    inputText: String,
    onInputTextChanged: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
    onInputFocused: () -> Unit = {},
    onBackClick: (() -> Unit)? = null,
    onClearChat: () -> Unit = {},
    onRetryError: () -> Unit = {},
    onDismissError: () -> Unit = {},
    onSpeakMessage: (String) -> Unit = {},
    showInputBar: Boolean = true,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    val isBusy = uiState is AssistantUiState.Thinking || uiState is AssistantUiState.Streaming

    LaunchedEffect(
        messages.size,
        (uiState as? AssistantUiState.Streaming)?.partialText?.length,
        uiState is AssistantUiState.Thinking
    ) {
        val totalItems = messages.size +
            (if (uiState is AssistantUiState.Streaming) 1 else 0) +
            (if (uiState is AssistantUiState.Thinking) 1 else 0) +
            (if (uiState is AssistantUiState.Error) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("novabot_chat_screen")
    ) {
        // Chat Header Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    if (onBackClick != null) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier.testTag("back_to_3d_robot_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back_to_3d_robot),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    } else {
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = stringResource(R.string.chat_screen_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when (uiState) {
                                is AssistantUiState.Thinking -> "Thinking…"
                                is AssistantUiState.Streaming -> "Responding…"
                                is AssistantUiState.Error -> "Connection check needed"
                                is AssistantUiState.Idle -> "Online • Ready to help"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (messages.isNotEmpty()) {
                    IconButton(
                        onClick = onClearChat,
                        modifier = Modifier.testTag("clear_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.cd_clear_chat),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Scrollable Message List or Empty State
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty() && uiState is AssistantUiState.Idle) {
                NovaBotEmptyChatPlaceholder(
                    onSelectSuggestion = { suggestion ->
                        onSendPrompt(suggestion)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp)
                        .testTag("chat_message_list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatMessageBubble(
                            message = msg,
                            onSpeakMessage = onSpeakMessage
                        )
                    }

                    if (uiState is AssistantUiState.Streaming) {
                        item(key = "streaming_message") {
                            ChatMessageBubble(
                                message = ChatMessageEntity(
                                    id = -1L,
                                    role = "ASSISTANT",
                                    content = uiState.partialText,
                                    emotionTag = uiState.emotion.name
                                ),
                                onSpeakMessage = {}
                            )
                        }
                    }

                    if (uiState is AssistantUiState.Thinking) {
                        item(key = "thinking_indicator") {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.testTag("ai_thinking_indicator")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "NovaBot is thinking…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (uiState is AssistantUiState.Error) {
                        item(key = "error_banner") {
                            ErrorBannerCard(
                                errorMessage = uiState.message,
                                onRetry = onRetryError,
                                onDismiss = onDismissError
                            )
                        }
                    }
                }
            }
        }

        // Bottom Input Bar (when rendered standalone)
        if (showInputBar) {
            NovaBotChatInputBar(
                inputText = inputText,
                isBusy = isBusy,
                onInputTextChanged = onInputTextChanged,
                onSendPrompt = onSendPrompt,
                onInputFocused = onInputFocused
            )
        }
    }
}

@Composable
fun NovaBotEmptyChatPlaceholder(
    onSelectSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val suggestions = listOf(
        stringResource(R.string.prompt_suggestion_1),
        stringResource(R.string.prompt_suggestion_2),
        stringResource(R.string.prompt_suggestion_3)
    )

    Column(
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag("empty_chat_placeholder"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.empty_chat_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = stringResource(R.string.empty_chat_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(20.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            suggestions.forEachIndexed { index, suggestion ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .clickable { onSelectSuggestion(suggestion) }
                        .testTag("chat_suggestion_chip_$index")
                ) {
                    Text(
                        text = suggestion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun NovaBotChatInputBar(
    inputText: String,
    isBusy: Boolean,
    onInputTextChanged: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
    onInputFocused: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val canSend = inputText.isNotBlank() && !isBusy

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputTextChanged,
            placeholder = {
                Text(
                    text = stringResource(R.string.hint_ask_novabot),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(26.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (canSend) {
                        onSendPrompt(inputText)
                    }
                }
            ),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focusState ->
                    if (focusState.isFocused) {
                        onInputFocused()
                    }
                }
                .testTag("chat_text_input")
        )

        IconButton(
            onClick = {
                if (canSend) {
                    onSendPrompt(inputText)
                }
            },
            enabled = canSend,
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(
                    if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
                .testTag("send_message_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = stringResource(R.string.cd_send_message),
                tint = if (canSend) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
