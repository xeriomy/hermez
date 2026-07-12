package dev.hermes.hermex.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.data.local.SessionEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    sessionRepository: SessionRepository,
    onSessionClick: (String) -> Unit = {},
    onShowArchived: () -> Unit = {},
    onShowSettings: () -> Unit = {},
    onOpenDrawer: () -> Unit = {}
) {
    val activeSessions by sessionRepository.getActiveSessions()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var isRefreshing by remember { mutableStateOf(false) }
    var isCreatingSession by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasAutoRefreshed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // State for session action dialogs
    var sessionToRename by remember { mutableStateOf<SessionEntity?>(null) }
    var sessionToDelete by remember { mutableStateOf<SessionEntity?>(null) }

    fun doRefresh() {
        if (!isRefreshing) {
            isRefreshing = true
            errorMessage = null
            scope.launch {
                val result = sessionRepository.refreshSessions()
                result.onFailure { e ->
                    errorMessage = e.message ?: "Failed to load sessions"
                }
                isRefreshing = false
            }
        }
    }

    fun createNewSession() {
        if (!isCreatingSession) {
            isCreatingSession = true
            scope.launch {
                val result = sessionRepository.createSession(
                    workspace = null,
                    model = null,
                    modelProvider = null,
                    profile = null
                )
                result.onSuccess { session ->
                    onSessionClick(session.sessionId)
                }.onFailure { e ->
                    errorMessage = e.message ?: "Failed to create session"
                }
                isCreatingSession = false
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        scope.launch {
            val result = sessionRepository.renameSession(sessionId, newTitle)
            result.onFailure { e ->
                errorMessage = e.message ?: "Failed to rename session"
            }
        }
    }

    fun deleteSession(sessionId: String) {
        scope.launch {
            val result = sessionRepository.deleteSession(sessionId)
            result.onFailure { e ->
                errorMessage = e.message ?: "Failed to delete session"
            }
        }
    }

    fun togglePin(session: SessionEntity) {
        scope.launch {
            val result = sessionRepository.setPinned(session.sessionId, !session.pinned)
            result.onFailure { e ->
                errorMessage = e.message ?: "Failed to pin session"
            }
        }
    }

    fun toggleArchive(session: SessionEntity) {
        scope.launch {
            val result = sessionRepository.setArchived(session.sessionId, !session.archived)
            result.onFailure { e ->
                errorMessage = e.message ?: "Failed to archive session"
            }
        }
    }

    // Auto-refresh ONCE when the screen first appears.
    LaunchedEffect(Unit) {
        if (!hasAutoRefreshed) {
            hasAutoRefreshed = true
            doRefresh()
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text("Sessions") },
            navigationIcon = {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            },
            actions = {
                IconButton(onClick = { doRefresh() }, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
                IconButton(onClick = { createNewSession() }, enabled = !isCreatingSession) {
                    if (isCreatingSession) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.Default.Add, contentDescription = "New session")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            )
        )

        // Error banner (shown when a refresh fails)
        errorMessage?.let { msg ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { doRefresh() }) {
                    Text("Retry")
                }
            }
        }

        if (activeSessions.isEmpty() && !isRefreshing) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("No sessions yet", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (errorMessage != null) "Couldn't load sessions from the server."
                        else "Tap + to start your first conversation.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { createNewSession() }, enabled = !isCreatingSession) {
                        if (isCreatingSession) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text("New Session")
                        }
                    }
                    if (errorMessage == null) {
                        TextButton(onClick = { doRefresh() }) {
                            Text("Refresh again")
                        }
                    }
                }
            }
        } else if (activeSessions.isEmpty() && isRefreshing) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(activeSessions) { session ->
                    SessionItem(
                        session = session,
                        onClick = { onSessionClick(session.sessionId) },
                        onRename = { sessionToRename = session },
                        onDelete = { sessionToDelete = session },
                        onTogglePin = { togglePin(session) },
                        onToggleArchive = { toggleArchive(session) }
                    )
                }
            }
        }
    }

    // Rename dialog
    sessionToRename?.let { session ->
        RenameSessionDialog(
            currentTitle = session.title ?: "",
            onConfirm = { newTitle ->
                renameSession(session.sessionId, newTitle)
                sessionToRename = null
            },
            onDismiss = { sessionToRename = null }
        )
    }

    // Delete confirmation dialog
    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            title = { Text("Delete session?") },
            text = {
                Text("Are you sure you want to delete \"${session.title ?: "Untitled"}\"? This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteSession(session.sessionId)
                        sessionToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { sessionToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionItem(
    session: SessionEntity,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleArchive: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.title ?: "Untitled",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (session.pinned) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                // Overflow menu (3 dots)
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More options",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (session.pinned) "Unpin" else "Pin") },
                            onClick = {
                                menuExpanded = false
                                onTogglePin()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.PushPin, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Edit, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (session.archived) "Unarchive" else "Archive") },
                            onClick = {
                                menuExpanded = false
                                onToggleArchive()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Archive, contentDescription = null)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                }
            }
            session.lastMessagePreview?.let { preview ->
                Text(
                    text = preview,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTimestamp(session.updatedAt),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                session.model?.let { model ->
                    Text(
                        text = model,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun RenameSessionDialog(
    currentTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var newTitle by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text("Session title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(newTitle.trim()) },
                enabled = newTitle.trim().isNotEmpty()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    return java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(date)
}
