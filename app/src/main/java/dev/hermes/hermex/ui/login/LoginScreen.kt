package dev.hermes.hermex.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hermes.core.auth.AuthRepository
import dev.hermes.core.auth.ConnectionProbeResult
import dev.hermes.core.auth.LoginResult
import kotlinx.coroutines.launch

/**
 * The first screen the user sees when not logged in. Lets them enter
 * their hermes-webui server URL and password, probe the connection
 * with `GET /health`, and log in with `POST /api/auth/login`.
 *
 * On successful login, [AuthRepository.authState] transitions to
 * `LoggedIn` and the host NavHost routes to the session list.
 *
 * Mirrors the iOS `OnboardingConnectPage` (without the multi-page
 * welcome pager — we'll add that polish later).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    authRepository: AuthRepository
) {
    // authRepository is passed from HermexApp so all screens share the
    // SAME instance. When login() sets authState = LoggedIn, HermexApp's
    // LaunchedEffect sees it and navigates to sessions.

    var serverUrl by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val canSubmit = serverUrl.trim().isNotEmpty() && !isTesting && !isConnecting

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to Hermes") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Header -----------------------------------------------------
            Text(
                text = "Hermex",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Control your self-hosted Hermes agent from your phone. " +
                    "Enter the URL of your hermes-webui server below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // --- Server URL field -------------------------------------------
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    // Clear stale status when the user edits
                    statusMessage = null
                    errorMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Server URL") },
                placeholder = { Text("https://hermes.yourdomain.com") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                )
            )

            // --- Password field --------------------------------------------
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") },
                placeholder = { Text("Server password (HERMES_WEBUI_PASSWORD)") },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                )
            )

            // --- Action buttons --------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        if (canSubmit) {
                            isTesting = true
                            statusMessage = null
                            errorMessage = null
                            scope.launch {
                                when (val result = authRepository.testConnection(serverUrl.trim())) {
                                    is ConnectionProbeResult.Ok -> {
                                        statusMessage = if (result.authEnabled) {
                                            "Connection ok. Password required."
                                        } else {
                                            "Connection ok. Password not required."
                                        }
                                    }
                                    is ConnectionProbeResult.Failed -> {
                                        errorMessage = result.message
                                    }
                                }
                                isTesting = false
                            }
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test")
                    }
                }

                Button(
                    onClick = {
                        if (canSubmit) {
                            isConnecting = true
                            statusMessage = null
                            errorMessage = null
                            scope.launch {
                                when (val result = authRepository.login(serverUrl.trim(), password)) {
                                    LoginResult.Success -> {
                                    // authState flips to LoggedIn → HermexApp's
                                    // LaunchedEffect navigates to sessions.
                                    }
                                    is LoginResult.Failed -> {
                                        errorMessage = result.message
                                    }
                                }
                                isConnecting = false
                            }
                        }
                    },
                    enabled = canSubmit,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connect")
                    }
                }
            }

            // --- Status / error banners ------------------------------------
            statusMessage?.let { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            errorMessage?.let { msg ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Help / hints ----------------------------------------------
            Text(
                text = "Need a server?\n" +
                    "Run hermes-webui on a machine you control, expose it via " +
                    "Cloudflare Tunnel or Tailscale (real HTTPS required), and " +
                    "set HERMES_WEBUI_PASSWORD on the server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
        }
    }
}
