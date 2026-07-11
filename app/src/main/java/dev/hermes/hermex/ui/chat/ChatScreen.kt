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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
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

    LaunchedEffect(messages.size, isStreaming) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
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

data class AttachmentItem(
    val uri: String,
    val serverPath: String,
    val fileName: String
)
