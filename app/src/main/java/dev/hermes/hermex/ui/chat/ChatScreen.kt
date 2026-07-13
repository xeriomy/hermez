package dev.hermes.hermex.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mikepenz.markdown.m3.Markdown
import dev.hermes.core.data.ChatMessage
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.data.ToolCallInfo
import dev.hermes.core.network.ChatStream
import dev.hermes.core.network.StreamState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    sessionRepository: SessionRepository,
    configRepository: dev.hermes.core.data.ConfigRepository,
    serverUrl: String,
    onBack: () -> Unit = {},
    onShowFiles: () -> Unit = {},
    onOpenDrawer: () -> Unit = {}
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

    // --- ChatViewModel state ---
    // CHAT-3 fix: allMessages = pendingMessages + persisted (deduped).
    // Pending user messages appear instantly (optimistic UI); on
    // stream completion, loadSession fetches the server's version and
    // the combiner dedups the pending copy by content+timestamp.
    val allMessages by chatViewModel.allMessages.collectAsStateWithLifecycle()
    val streamState by chatViewModel.streamState.collectAsStateWithLifecycle()
    val isStreaming by chatViewModel.isStreaming.collectAsStateWithLifecycle()
    val error by chatViewModel.error.collectAsStateWithLifecycle()
    val remainingToLoad by chatViewModel.remainingToLoad.collectAsStateWithLifecycle()
    val isInitialLoading by chatViewModel.isInitialLoading.collectAsStateWithLifecycle()

    // CHAT-3 fix: derive streaming-overlay values from streamState.
    // The streaming bubble stays visible during Completing (between
    // StreamEnd and loadSession returning) so there's no flicker.
    val streamingContent: String = when (streamState) {
        is StreamState.Streaming -> (streamState as StreamState.Streaming).content.toString()
        is StreamState.Completing -> {
            // Keep showing the last streaming content until the
            // persisted message arrives. The Streaming state's
            // StringBuilder is no longer accessible here (we're in
            // Completing now), so we fall back to whatever the UI
            // last rendered — Compose caches the value across
            // recomposition because it's read in a derived val.
            // In practice this means: the StreamingBubble keeps
            // rendering its previous content until allMessages
            // updates and the bubble disappears.
            ""
        }
        else -> ""
    }
    // During Completing, we want to keep showing the streaming bubble
    // even though streamingContent is empty above (we lost the
    // reference to the StringBuilder). To avoid flicker, we remember
    // the last non-empty streaming content.
    var lastStreamingContent by remember { mutableStateOf("") }
    if (streamingContent.isNotEmpty()) {
        lastStreamingContent = streamingContent
    }
    // Clear the cache when we leave the streaming/Completing states.
    if (streamState is StreamState.Idle || streamState is StreamState.Failed) {
        lastStreamingContent = ""
    }

    val streamingReasoning: String = (streamState as? StreamState.Streaming)?.reasoning?.toString() ?: ""
    val streamingTools: List<ToolCallInfo> = (streamState as? StreamState.Streaming)?.tools ?: emptyList()

    // CHAT-3 fix: show the streaming bubble while Streaming OR while
    // Completing (with cached content). The bubble disappears when
    // streamState goes to Idle — by then, allMessages has emitted the
    // persisted message and the MessageBubble has replaced it.
    val showStreamingBubble = streamState is StreamState.Streaming ||
        (streamState is StreamState.Completing && lastStreamingContent.isNotEmpty())

    // Config (models, workspaces, profiles)
    val models by configRepository.models.collectAsStateWithLifecycle()
    val workspaces by configRepository.workspaces.collectAsStateWithLifecycle()
    val profiles by configRepository.profiles.collectAsStateWithLifecycle()

    // Selected config state
    var selectedModel by remember { mutableStateOf<dev.hermes.core.data.ModelOption?>(null) }
    var selectedWorkspace by remember { mutableStateOf<String?>(null) }
    var selectedProfile by remember { mutableStateOf<String?>(null) }

    var showConfigSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Attachments — list of uploaded file paths (server paths)
    var attachments by remember { mutableStateOf<List<AttachmentItem>>(emptyList()) }
    var isUploading by remember { mutableStateOf(false) }

    // File picker launcher
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            isUploading = true
            scope.launch {
                uris.forEach { uri ->
                    val result = chatViewModel.uploadAttachment(sessionId, uri.toString())
                    result.onSuccess { path ->
                        val fileName = uri.lastPathSegment ?: path
                        attachments = attachments + AttachmentItem(
                            uri = uri.toString(),
                            serverPath = path,
                            fileName = fileName
                        )
                    }.onFailure { e ->
                        snackbarHostState.showSnackbar("Upload failed: ${e.message}")
                    }
                }
                isUploading = false
            }
        }
    }

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

    // CHAT-8 fix: smart auto-scroll. Only scroll to the bottom if the
    // user is already near the bottom (within 2 items of the end). If
    // they scrolled up to read older messages, don't yank them back
    // when a new token arrives or the stream completes.
    LaunchedEffect(allMessages.size, streamState) {
        if (allMessages.isEmpty()) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        val isNearBottom = lastVisible >= allMessages.size - 2
        if (isNearBottom) {
            // allMessages includes the streaming bubble at the end if
            // showStreamingBubble is true; scroll to the last item
            // (which is either the last message or the streaming bubble).
            val targetIndex = allMessages.size - 1
            listState.animateScrollToItem(targetIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text("Chat") },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Menu"
                    )
                }
            },
            actions = {
                // CHAT-9 fix: manual refresh action. Forces loadSession
                // regardless of whether the session is already loaded —
                // fetches new messages that arrived on the server while
                // the user was away.
                IconButton(
                    onClick = { chatViewModel.refresh(sessionId) },
                    enabled = !isStreaming
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh"
                    )
                }
                IconButton(onClick = onShowFiles) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Browse files"
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
            if (isInitialLoading && allMessages.isEmpty()) {
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

            // CHAT-3 fix: render allMessages (pending + persisted, deduped).
            // Pending messages appear instantly (optimistic UI); on stream
            // completion they're replaced by the server's version with a
            // real message_id.
            items(allMessages) { msg ->
                MessageBubble(message = msg)
            }

            // QUAL-5: Show reasoning block while streaming
            if (isStreaming && streamingReasoning.isNotEmpty()) {
                item {
                    ReasoningBubble(content = streamingReasoning)
                }
            }

            // QUAL-5: Show tool call cards while streaming
            if (isStreaming && streamingTools.isNotEmpty()) {
                items(streamingTools) { tool ->
                    ToolCallCard(tool = tool)
                }
            }

            // CHAT-3 fix: flicker-free transition. The streaming bubble
            // stays visible during the Completing state (between StreamEnd
            // and loadSession returning) so there's no gap where the
            // assistant message is nowhere on screen.
            //
            // PERF-1: while streaming, render as plain Text (no Markdown
            // re-parse). On stream completion, the full message appears in
            // allMessages via Room observer with full Markdown rendering.
            if (showStreamingBubble) {
                val contentToShow = streamingContent.ifEmpty { lastStreamingContent }
                if (contentToShow.isNotEmpty()) {
                    item {
                        StreamingBubble(content = contentToShow)
                    }
                } else {
                    // No tokens yet — show a spinner while we wait for the
                    // first token (the model is "thinking").
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
            } else if (streamState is StreamState.Starting) {
                // Starting state: chat.start is in flight, no stream_id yet.
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

        // Attachment preview chips (shown above composer when files are attached)
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEach { attachment ->
                    AssistChip(
                        onClick = {
                            attachments = attachments.filter { it != attachment }
                        },
                        label = { Text(attachment.fileName, fontSize = 11.sp, maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }

        // Composer — two-row layout (text on top, actions below) in a bordered surface
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                // Row 1: Text input
                OutlinedTextField(
                    value = composerText,
                    onValueChange = { composerText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Message…") },
                    singleLine = false,
                    maxLines = 5,
                    enabled = !isStreaming,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )

                // Row 2: Action bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // + button — opens a menu with Upload file + Message options
                    var plusMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { plusMenuExpanded = true },
                            enabled = !isStreaming && !isUploading,
                            modifier = Modifier.size(36.dp)
                        ) {
                            if (isUploading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "More options",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = plusMenuExpanded,
                            onDismissRequest = { plusMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Upload file") },
                                onClick = {
                                    plusMenuExpanded = false
                                    filePickerLauncher.launch("*/*")
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.AttachFile, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Message options") },
                                onClick = {
                                    plusMenuExpanded = false
                                    showConfigSheet = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Tune, contentDescription = null)
                                }
                            )
                        }
                    }

                    // Model chip
                    var modelMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        AssistChip(
                            onClick = { if (!isStreaming) modelMenuExpanded = true },
                            label = {
                                Text(
                                    text = selectedModel?.name ?: "Default",
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            },
                            enabled = !isStreaming
                        )
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Default") },
                                onClick = {
                                    selectedModel = null
                                    modelMenuExpanded = false
                                }
                            )
                            models.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.toString()) },
                                    onClick = {
                                        selectedModel = model
                                        modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Send / Stop
                    if (isStreaming) {
                        IconButton(
                            onClick = { chatViewModel.stopStreaming() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                val text = composerText.trim()
                                if (text.isNotEmpty()) {
                                    composerText = ""
                                    val filePaths = attachments.map { it.serverPath }
                                    attachments = emptyList()
                                    chatViewModel.sendMessage(
                                        text = text,
                                        sessionId = sessionId,
                                        model = selectedModel?.id,
                                        provider = selectedModel?.provider,
                                        workspace = selectedWorkspace,
                                        profile = selectedProfile,
                                        attachments = filePaths
                                    )
                                }
                            },
                            enabled = composerText.trim().isNotEmpty(),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp),
                                tint = if (composerText.trim().isNotEmpty())
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState) { data ->
            Snackbar(snackbarData = data)
        }
    }

    // Config bottom sheet (+ button → Message options)
}

/**
 * Streaming bubble — renders the partial assistant response as PLAIN TEXT
 * (no Markdown) while streaming. This is the PERF-1 fix: plain Text is
 * O(1) per token update, vs Markdown which re-parses the entire content
 * on every token. When streaming ends, the full message appears in the
 * messages list via Room observer with full Markdown rendering.
 */
/**
 * QUAL-5: Reasoning bubble — shows the agent's reasoning/thinking text
 * in a subtly different card (lower opacity, italic) while streaming.
 */
@Composable
fun ReasoningBubble(content: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Thinking…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * QUAL-5: Tool call card — shows a tool name + args + result (when
 * complete) as a compact card while the agent is working.
 */
@Composable
fun ToolCallCard(tool: ToolCallInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(0.85f).padding(horizontal = 4.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
                if (tool.result != null) {
                    Text(
                        text = "✓ Done",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "Running…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun StreamingBubble(content: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            Text(
                text = content,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
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
                // CHAT-11 fix: memoize so JSON parsing + pretty-printing
                // only runs when message.content changes, not on every
                // recomposition. Scrolling triggers recompositions →
                // without this, normalizeMessageContent (which parses
                // JSON and re-encodes it) runs on every message on
                // every scroll frame → stutter on long chats.
                val normalizedContent = remember(message.content) {
                    normalizeMessageContent(message.content)
                }
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

// ChatMessage and ToolCallInfo now live in dev.hermes.core.data
// (moved there so StreamState can reference them — see
// core/data/ChatMessage.kt for the full docstrings).

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

data class AttachmentItem(
    val uri: String,
    val serverPath: String,
    val fileName: String
)
