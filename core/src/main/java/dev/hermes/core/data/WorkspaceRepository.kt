package dev.hermes.core.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.hermes.core.auth.AuthPrefsRepository
import dev.hermes.core.network.ApiEndpoint
import dev.hermes.core.network.SharedHttpClient
import dev.hermes.core.network.friendlyError
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
 * Repository for browsing files on the server.
 *
 * Uses GET /api/list?session_id=...&path=... to list directory contents,
 * and GET /api/file?session_id=...&path=... to read file contents.
 *
 * The server requires a session_id for file access — files are scoped
 * to the session's workspace.
 */
class WorkspaceRepository(app: Application) : AndroidViewModel(app) {

    init { AuthPrefsRepository.init(app.applicationContext) }

    private fun client(): HttpClient? =
        SharedHttpClient.client(AuthPrefsRepository.getServerUrl())

    /**
     * List files in a directory on the server.
     *
     * @param sessionId The session ID (scopes file access to the session's workspace)
     * @param path The directory path to list. Use "." for root.
     * @return Result with a list of file entries + the resolved path
     */
    suspend fun listDirectory(sessionId: String, path: String): Result<DirectoryListing> = runCatching {
        val client = client() ?: throw Exception("Not connected to a server. Log in first.")
        val response = client.get(ApiEndpoint.WorkspaceList.path) {
            parameter("session_id", sessionId)
            parameter("path", path)
        }
        if (!response.status.isSuccess()) {
            throw Exception(when (response.status.value) {
                401 -> "Session expired. Please log in again."
                403 -> "Forbidden — no access to this workspace."
                404 -> "Directory not found."
                in 500..599 -> "Server error (${response.status})."
                else -> "Failed to list directory (${response.status})."
            })
        }
        val body = response.body<DirectoryListResponse>()
        DirectoryListing(
            entries = body.entries?.map { it.toEntry() } ?: emptyList(),
            path = body.path ?: path,
            workspace = body.workspace
        )
    }

    /**
     * Read the contents of a file on the server (text only).
     *
     * @param sessionId The session ID
     * @param path The file path to read
     * @return Result with the file contents as a string
     */
    suspend fun readFile(sessionId: String, path: String): Result<String> = runCatching {
        val client = client() ?: throw Exception("Not connected to a server. Log in first.")
        val response = client.get(ApiEndpoint.File.path) {
            parameter("session_id", sessionId)
            parameter("path", path)
        }
        if (!response.status.isSuccess()) {
            throw Exception(when (response.status.value) {
                401 -> "Session expired. Please log in again."
                403 -> "Forbidden — no access to this file."
                404 -> "File not found."
                in 500..599 -> "Server error (${response.status})."
                else -> "Failed to read file (${response.status})."
            })
        }
        // PERF-5 fix: cap preview at 1 MB to prevent OOM on large files.
        val contentLength = response.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_PREVIEW_SIZE) {
            throw Exception("File is too large to preview (${contentLength / 1024} KB). Max preview size is ${MAX_PREVIEW_SIZE / 1024} KB.")
        }
        val text = response.bodyAsText()
        if (text.length > MAX_PREVIEW_SIZE) {
            throw Exception("File is too large to preview. Max preview size is ${MAX_PREVIEW_SIZE / 1024} KB.")
        }
        text
    }

    companion object {
        private const val MAX_PREVIEW_SIZE = 1_048_576L  // 1 MB
    }

    // --- DTOs ---

    @Serializable
    private data class DirectoryListResponse(
        val entries: List<EntryDto>? = null,
        val path: String? = null,
        val workspace: String? = null,
        val error: String? = null
    )

    @Serializable
    private data class EntryDto(
        val name: String? = null,
        val path: String? = null,
        val type: String? = null,
        val size: Long? = null,
        val modified: Double? = null,
        val is_directory: Boolean? = null,
        val is_dir: Boolean? = null
    ) {
        fun toEntry() = FileEntry(
            name = name ?: path ?: "unknown",
            path = path ?: name ?: "",
            type = type,
            size = size,
            modified = modified,
            isDirectory = is_directory ?: is_dir ?: (type == "dir" || type == "directory")
        )
    }
}

/**
 * A file or directory entry in a directory listing.
 */
data class FileEntry(
    val name: String,
    val path: String,
    val type: String?,
    val size: Long?,
    val modified: Double?,
    val isDirectory: Boolean
) {
    val isBrowsableDirectory: Boolean
        get() = isDirectory || type == "dir" || type == "directory"
}

/**
 * Result of listing a directory.
 */
data class DirectoryListing(
    val entries: List<FileEntry>,
    val path: String,
    val workspace: String?
)
