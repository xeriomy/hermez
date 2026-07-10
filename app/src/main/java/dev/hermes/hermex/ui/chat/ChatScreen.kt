package dev.hermes.hermex.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mikepenz.markdown.m3.Markdown
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.network.ChatStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    sessionRepository: SessionRepository,
    configRepository: dev.hermes.core.data.ConfigRepository,
    serverUrl: String,
    onBack: () -> Unit = {}
) {
    val chatViewModel: ChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                ChatViewModel(
                    sessionRepository = sessionRepository,
                    chatStream = ChatStream(serverUrl)
                )
            }
        }
    )

    val messages by chatViewModel.messages.collectAsStateWithLifecycle()
    val isStreaming by chatViewModel.isStreaming.collectAsStateWithLifecycle()
    val error by chatViewModel.error.collectAsStateWithLifecycle()
    val remainingToLoad by chatViewModel.remainingToLoad.collectAsStateWithLifecycle()
    val isInitialLoading by chatViewModel.isInitialLoading.collectAsStateWithLifecycle()

    // Config (models, workspaces, profiles)
    val models by configRepository.models.collectAsStateWithLifecycle()
    val workspaces by configRepository.workspaces.collectAsStateWithLifecycle()
    val profiles by configRepository.profiles.collectAsStateWithLifecycle()

    // Selected config state
    var selectedModel by remember { mutableStateOf<dev.hermes.core.data.ModelOption?>(null) }
    var selectedWorkspace by remember { mutableStateOf<String?>(null) }
    var selectedProfile by remember { mutableStateOf<String?>(null) }

    // Load config when the screen appears
    LaunchedEffect(serverUrl) {
        if (serverUrl.isNotEmpty()) {
            configRepository.loadConfig()
        }
    }

    var composerText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(sessionId) {
        chatViewModel.loadMessages(sessionId)
    }

    LaunchedEffect(messages.size, isStreaming) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Chat") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Initial loading spinner — shown only on first-ever load when
            // cache is empty AND we're fetching from server.
            if (isInitialLoading && messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            // "Load more" button — only shown if there are older messages to load
            if (remainingToLoad > 0 && !isInitialLoading) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = { chatViewModel.loadMoreMessages(sessionId) }) {
                            Text("Load more ($remainingToLoad)")
                        }
                    }
                }
            }

            items(messages) { msg ->
                MessageBubble(message = msg)
            }

            if (isStreaming) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }

        // Config picker row (model, workspace, profile)
        ConfigPickerRow(
            models = models,
            workspaces = workspaces,
            profiles = profiles,
            selectedModel = selectedModel,
            selectedWorkspace = selectedWorkspace,
            selectedProfile = selectedProfile,
            onModelSelected = { selectedModel = it },
            onWorkspaceSelected = { selectedWorkspace = it },
            onProfileSelected = { selectedProfile = it },
            enabled = !isStreaming
        )

        // Composer
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = composerText,
                    onValueChange = { composerText = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = { Text("Message") },
                    singleLine = false,
                    maxLines = 5,
                    enabled = !isStreaming,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (isStreaming) {
                    IconButton(onClick = { chatViewModel.stopStreaming() }) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop"
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            val text = composerText.trim()
                            if (text.isNotEmpty()) {
                                composerText = ""
                                chatViewModel.sendMessage(
                                    text = text,
                                    sessionId = sessionId,
                                    model = selectedModel?.id,
                                    provider = selectedModel?.provider,
                                    workspace = selectedWorkspace,
                                    profile = selectedProfile
                                )
                            }
                        },
                        enabled = composerText.trim().isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send"
                        )
                    }
                }
            }
            error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    style = androidx.compose.ui.text.TextStyle(textAlign = TextAlign.Start)
                )
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            if (isUser) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                val normalizedContent = normalizeMessageContent(message.content)
                Markdown(
                    content = normalizedContent,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

/**
 * Normalize message content for markdown rendering:
 *  1. Replace literal \n strings with actual newlines (old cached data)
 *  2. Detect raw JSON (starts with { or [ and is valid JSON) and wrap
 *     it in a ```json code block so it renders with syntax highlighting
 *     and horizontal scroll instead of being cut off at the right edge.
 */
fun normalizeMessageContent(content: String): String {
    // Step 1: unescape literal \n and \r
    var normalized = content
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
        .replace("\\r", "")
        .replace("\\t", "    ")

    // Step 2: if the entire content looks like raw JSON, wrap it in a
    // code block so it renders properly with horizontal scroll.
    val trimmed = normalized.trim()
    if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
        (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
        normalized = try {
            val json = kotlinx.serialization.json.Json { prettyPrint = true }
            val parsed = json.parseToJsonElement(trimmed)
            val pretty = json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                parsed
            )
            "```json\n$pretty\n```"
        } catch (_: Exception) {
            "```\n$trimmed\n```"
        }
    }

    return normalized
}

data class ChatMessage(
    val messageId: String,
    val role: String,
    val content: String,
    val timestamp: Long
)

/**
 * A horizontal row of dropdown pickers for model, workspace, and profile.
 * Shown above the composer. Each picker is only shown if the server has
 * options available for it.
 */
@Composable
fun ConfigPickerRow(
    models: List<dev.hermes.core.data.ModelOption>,
    workspaces: List<String>,
    profiles: List<String>,
    selectedModel: dev.hermes.core.data.ModelOption?,
    selectedWorkspace: String?,
    selectedProfile: String?,
    onModelSelected: (dev.hermes.core.data.ModelOption?) -> Unit,
    onWorkspaceSelected: (String?) -> Unit,
    onProfileSelected: (String?) -> Unit,
    enabled: Boolean
) {
    // Only show the row if at least one picker has options
    if (models.isEmpty() && workspaces.isEmpty() && profiles.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Model picker
        if (models.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                androidx.compose.material3.AssistChip(
                    onClick = { if (enabled) expanded = true },
                    label = {
                        Text(
                            text = selectedModel?.name ?: "Model",
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    },
                    enabled = enabled
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    if (selectedModel != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Default") },
                            onClick = {
                                onModelSelected(null)
                                expanded = false
                            }
                        )
                    }
                    models.forEach { model ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(model.toString()) },
                            onClick = {
                                onModelSelected(model)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        // Workspace picker
        if (workspaces.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                androidx.compose.material3.AssistChip(
                    onClick = { if (enabled) expanded = true },
                    label = {
                        Text(
                            text = selectedWorkspace ?: "Workspace",
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    },
                    enabled = enabled
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    if (selectedWorkspace != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Default") },
                            onClick = {
                                onWorkspaceSelected(null)
                                expanded = false
                            }
                        )
                    }
                    workspaces.forEach { ws ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(ws) },
                            onClick = {
                                onWorkspaceSelected(ws)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        // Profile picker
        if (profiles.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                androidx.compose.material3.AssistChip(
                    onClick = { if (enabled) expanded = true },
                    label = {
                        Text(
                            text = selectedProfile ?: "Profile",
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    },
                    enabled = enabled
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    if (selectedProfile != null) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Default") },
                            onClick = {
                                onProfileSelected(null)
                                expanded = false
                            }
                        )
                    }
                    profiles.forEach { p ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(p) },
                            onClick = {
                                onProfileSelected(p)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}
