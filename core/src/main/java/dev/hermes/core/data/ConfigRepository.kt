package dev.hermes.core.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.hermes.core.auth.AuthPrefsRepository
import dev.hermes.core.network.ApiEndpoint
import dev.hermes.core.network.SharedHttpClient
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Fetches and caches the server's configuration: available models,
 * workspaces, and profiles. Used by the chat composer to show pickers.
 *
 * Extends [AndroidViewModel] so it can be created by the default Compose
 * `viewModel()` factory and scoped to the Activity.
 */
class ConfigRepository(app: Application) : AndroidViewModel(app) {

    private val prefsRepository = AuthPrefsRepository(app.applicationContext)

    private val _models = MutableStateFlow<List<ModelOption>>(emptyList())
    val models: StateFlow<List<ModelOption>> = _models.asStateFlow()

    private val _workspaces = MutableStateFlow<List<String>>(emptyList())
    val workspaces: StateFlow<List<String>> = _workspaces.asStateFlow()

    private val _profiles = MutableStateFlow<List<String>>(emptyList())
    val profiles: StateFlow<List<String>> = _profiles.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private fun client(): HttpClient? =
        SharedHttpClient.client(prefsRepository.getServerUrl())

    /**
     * Fetch models, workspaces, and profiles from the server in one call.
     * Safe to call multiple times — only fetches once unless forceRefresh.
     */
    suspend fun loadConfig(forceRefresh: Boolean = false) {
        if (!forceRefresh && (_models.value.isNotEmpty() || _workspaces.value.isNotEmpty())) {
            return
        }
        _isLoading.value = true
        try {
            val c = client() ?: return

            // Fetch models
            try {
                val response = c.get(ApiEndpoint.Models.path)
                if (response.status.isSuccess()) {
                    val body = response.body<ModelsResponse>()
                    _models.value = body.models?.map { it.toOption() } ?: emptyList()
                }
            } catch (_: Exception) { /* models are optional */ }

            // Fetch workspaces
            try {
                val response = c.get(ApiEndpoint.Workspaces.path)
                if (response.status.isSuccess()) {
                    val body = response.body<WorkspacesResponse>()
                    _workspaces.value = body.workspaces ?: emptyList()
                }
            } catch (_: Exception) { }

            // Fetch profiles
            try {
                val response = c.get(ApiEndpoint.Profiles.path)
                if (response.status.isSuccess()) {
                    val body = response.body<ProfilesResponse>()
                    _profiles.value = body.profiles ?: emptyList()
                }
            } catch (_: Exception) { }
        } finally {
            _isLoading.value = false
        }
    }

    // --- DTOs ---

    @Serializable
    private data class ModelsResponse(
        val models: List<ModelDto>? = null
    )

    @Serializable
    private data class ModelDto(
        val id: String? = null,
        val name: String? = null,
        val provider: String? = null
    ) {
        fun toOption() = ModelOption(
            id = id ?: name ?: "unknown",
            name = name ?: id ?: "unknown",
            provider = provider
        )
    }

    @Serializable
    private data class WorkspacesResponse(
        val workspaces: List<String>? = null
    )

    @Serializable
    private data class ProfilesResponse(
        val profiles: List<String>? = null
    )
}

/**
 * A model option shown in the composer picker.
 */
data class ModelOption(
    val id: String,
    val name: String,
    val provider: String?
) {
    override fun toString() = if (provider != null) "$name ($provider)" else name
}
