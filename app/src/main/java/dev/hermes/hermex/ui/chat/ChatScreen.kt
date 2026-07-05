package dev.hermes.hermex.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hermes.core.network.ChatStream
import dev.hermes.core.network.ApiEndpoint
import dev.hermes.core.network.HttpClientProvider
import dev.hermes.hermex.ui.navigation.HermesNavGraph
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.http.*

@Composable
fun ChatScreen(
    sessionId: String,
    navController: androidx.navigation.compose.NavHostController = androidx.navigation.compose.rememberNavController(),
    chatViewModel: ChatViewModel = viewModel()
) {
    val messages by chatViewModel.messages.collectAsStateWithLifecycle()
    val isStreaming by chatViewModel.isStreaming.collectAsStateWithLifecycle()
    val newMessage by chatViewModel.newMessage.collectAsStateWithLifecycle()
    val error by chatViewModel.error.collectAsStateWithLifecycle()

    var composerText by remember { mutableStateOf("") }

    val sendMessage = {
        val text = composerText.trim()
        if (text.isEmpty() || isStreaming) return@sendMessage
        composerText = ""
        chatViewModel.sendMessage(text)
    }

    val stopStreaming = {
        chatViewModel.stopStreaming()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Chat") },
            navigationIcon = { IconButton(onClick = { navController.navigate("sessions") }) {
                Icon(imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack, contentDescription = "Back")
            }},
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            reverseLayout = true
        ) {
            items(messages.reversed()) { msg ->
                MessageBubble(message = msg)
            }

            if (isStreaming) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = composerText,
                    onValueChange = { composerText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    label = { Text("Message") },
                    singleLine = false,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                if (isStreaming) {
                    IconButton(onClick = stopStreaming) {
                        Icon(imageVector = androidx.compose.material.icons.Icons.Default.Stop, contentDescription = "Stop")
                    }
                } else {
                    IconButton(onClick = sendMessage, enabled = composerText.trim().isNotEmpty()) {
                        Icon(imageVector = androidx.compose.material.icons.Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, style = androidx.compose.ui.text.TextStyle(textAlign = androidx.compose.ui.text.style.TextAlign.Start))
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(vertical = 4.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
            )
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(16.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun rememberCoroutineScope() = androidx.compose.runtime.remember {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
}

data class ChatMessage(
    val messageId: String,
    val role: String,
    val content: String,
    val timestamp: Long
)