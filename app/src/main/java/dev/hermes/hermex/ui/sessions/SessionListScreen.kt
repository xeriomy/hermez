package dev.hermes.hermex.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hermes.core.auth.AuthRepository
import dev.hermes.core.data.SessionRepository
import dev.hermes.core.data.local.SessionEntity
import dev.hermes.hermex.ui.navigation.HermesNavGraph
import kotlinx.coroutines.flow.collectAsStateWithLifecycle

@Composable
fun SessionListScreen(
    onSessionClick: (String) -> Unit = {},
    authRepository: AuthRepository = viewModel(),
    sessionRepository: SessionRepository = viewModel()
) {
    val context = LocalContext.current
    val serverUrl = authRepository.getServerUrl() ?: ""
    val activeSessions by sessionRepository.getActiveSessions().collectAsStateWithLifecycle(initialValue = emptyList())
    val isRefreshing by remember { mutableStateOf(false) }

    val refresh = {
        isRefreshing = true
        val scope = rememberCoroutineScope()
        scope.launch {
            sessionRepository.refreshSessions().onFailure { _ -> }
            isRefreshing = false
        }
    }

    TopAppBar(
        title = { Text("Sessions") },
        actions = {
            IconButton(onClick = refresh, enabled = !isRefreshing) {
                if (isRefreshing) CircularProgressIndicator(modifier = Modifier.size(24.dp))
                else Icon(androidx.compose.material.icons.Icons.Default.Refresh, contentDescription = "Refresh")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (activeSessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("No sessions yet", fontSize = 18.sp, fontWeight = FontWeight.Medium)
                    Text("Create a new session from the chat screen", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { onSessionClick("new") }) {
                        Text("New Session")
                    }
                }
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
                        onLongClick = { /* TODO: show context menu */ }
                    )
                }
            }
        }
    }
}

@Composable
fun SessionItem(
    session: SessionEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .fillMaxWidth(),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                Text(
                    text = session.title ?: "Untitled",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.overflow.TextOverflow.Ellipsis
                )
                if (session.pinned) {
                    Icon(androidx.compose.material.icons.Icons.Default.PushPin, contentDescription = "Pinned", tint = MaterialTheme.colorScheme.primary)
                }
            }
            session.lastMessagePreview?.let { preview ->
                Text(
                    text = preview,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.overflow.TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTimestamp(session.updatedAt),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (session.model != null) {
                    Text(
                        text = session.model!!,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val date = java.util.Date(timestamp)
    return java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(date)
}

@Composable
private fun rememberCoroutineScope() = androidx.compose.runtime.remember {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
}