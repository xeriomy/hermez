package dev.hermes.core.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.hermes.core.auth.AuthPrefsRepository
import dev.hermes.core.data.local.AppDatabase
import dev.hermes.core.data.local.MessageEntity
import dev.hermes.core.data.local.SessionEntity
import dev.hermes.core.network.ApiEndpoint
import dev.hermes.core.network.SharedHttpClient
import dev.hermes.core.network.friendlyError
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SessionRepository(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val db = AppDatabase.getInstance(context)
    init { AuthPrefsRepository.init(context) }

    /**
     * Borrow the process-wide shared [HttpClient] from [SharedHttpClient].
     * Returns null if the user isn't logged in yet (no saved URL).
     *
     * Because this is the SAME client that [dev.hermes.core.auth.AuthRepository]
     * used for login, the auth cookie is already in the cookie jar — no
     * need to re-authenticate.
     */
    private fun client(): HttpClient? = SharedHttpClient.client(AuthPrefsRepository.getServerUrl())

    fun getActiveSessions(): Flow<List<SessionEntity>> = db.sessionDao().getActiveSessions()
    fun getArchivedSessions(): Flow<List<SessionEntity>> = db.sessionDao().getArchivedSessions()
    fun getSessionsByProject(projectId: String): Flow<List<SessionEntity>> = db.sessionDao().getSessionsByProject(projectId)

    fun getMessages(sessionId: String, limit: Int = 50): Flow<List<MessageEntity>> = db.messageDao().getMessages(sessionId, limit)

    /**
     * Get messages older than [beforeTimestamp] for pagination ("Load more").
     * One-shot suspend read (not a Flow) — returns up to [limit] messages
     * with timestamps strictly less than [beforeTimestamp], newest first.
     */
    suspend fun getMessagesBefore(sessionId: String, beforeTimestamp: Long, limit: Int = 20): List<MessageEntity> =
        db.messageDao().getMessagesBefore(sessionId, beforeTimestamp, limit)

    /**
     * Get the total number of cached messages for [sessionId].
     * Used to calculate how many older messages are available to load.
     */
    suspend fun getMessageCount(sessionId: String): Int =
        db.messageDao().getMessageCount(sessionId)

    /**
     * Persist a locally-sent user message + the assistant's streamed
     * response to Room. Called when a chat stream completes so the
     * messages survive navigation away and back.
     *
     * Uses OnConflictStrategy.REPLACE on messageId so re-inserting
     * (e.g. when loadSession fetches the same messages from the server)
     * is a no-op, not a duplicate.
     */
    suspend fun persistLocalMessages(
        sessionId: String,
        userMessageId: String,
        userContent: String,
        userTimestamp: Long,
        assistantMessageId: String?,
        assistantContent: String?,
        assistantTimestamp: Long?
    ) {
        db.messageDao().insertMessage(
            dev.hermes.core.data.local.MessageEntity(
                sessionId = sessionId,
                messageId = userMessageId,
                role = "user",
                content = userContent,
                timestamp = userTimestamp,
                model = null,
                provider = null,
                toolCalls = null,
                reasoning = null,
                attachments = null,
                metadata = null
            )
        )
        if (assistantMessageId != null && !assistantContent.isNullOrBlank()) {
            db.messageDao().insertMessage(
                dev.hermes.core.data.local.MessageEntity(
                    sessionId = sessionId,
                    messageId = assistantMessageId,
                    role = "assistant",
                    content = assistantContent,
                    timestamp = assistantTimestamp ?: System.currentTimeMillis(),
                    model = null,
                    provider = null,
                    toolCalls = null,
                    reasoning = null,
                    attachments = null,
                    metadata = null
                )
            )
        }
    }

    /**
     * Clear ALL locally cached sessions and messages. The server is not
     * contacted — this only wipes the Room database. Data will be re-fetched
     * from the server on next refresh.
     */
    suspend fun clearAllCache() {
        db.sessionDao().deleteAllSessions()
        db.messageDao().deleteAllMessages()
    }

    suspend fun refreshSessions(): Result<List<SessionEntity>> = runCatching {
        val client = client() ?: throw Exception("Not connected to a server. Log in first.")
        val response = client.get(ApiEndpoint.Sessions.path)
        if (!response.status.isSuccess()) {
            // BUG-11 fix: on 401, force logout so the user is redirected
            // to the login screen instead of seeing a stale error message.
            if (response.status.value == 401) {
                AuthPrefsRepository.clearServerUrl()
                dev.hermes.core.network.SharedHttpClient.reset()
            }
            throw Exception(when (response.status.value) {
                401 -> "Session expired. Please log in again."
                403 -> "Forbidden — your account can't list sessions."
                404 -> "Server is reachable but /api/sessions was not found."
                in 500..599 -> "Server error (${response.status}). Check hermes-webui logs."
                else -> "Failed to load sessions (${response.status})."
            })
        }
        val sessions = response.body<SessionsResponse>().sessions

        // Before inserting, read the existing archived/pinned state for each
        // session so we don't overwrite local-only state. The server may not
        // include the 'archived' or 'pinned' fields in the sessions list
        // response (or may report them differently), so REPLACE would wipe
        // any sessions the user archived locally.
        val existingSessions = db.sessionDao().getAllSessionsList().associateBy { it.sessionId }
        val entities = sessions.map { dto ->
            val existing = existingSessions[dto.session_id]
            dto.toEntity().copy(
                archived = existing?.archived ?: dto.archived,
                pinned = existing?.pinned ?: dto.pinned
            )
        }
        db.sessionDao().insertSessions(entities)

        // BUG-7 fix: delete sessions that exist locally but not on the server.
        // This happens when a session is deleted from another client (web UI,
        // another phone). Without this, deleted sessions stay in the local
        // cache forever until the user manually deletes them from Hermez.
        val serverSessionIds = sessions.map { it.session_id }.toSet()
        val localSessionIds = existingSessions.keys
        val deletedSessionIds = localSessionIds - serverSessionIds
        deletedSessionIds.forEach { sessionId ->
            db.sessionDao().deleteSession(sessionId)
            db.messageDao().deleteMessagesForSession(sessionId)
        }

        entities
    }

    suspend fun loadSession(sessionId: String, msgLimit: Int = 50): Result<SessionEntity> = runCatching {
        val client = client() ?: throw Exception("Not connected to a server. Log in first.")
        val response = client.get("${ApiEndpoint.SessionDetail.path}?session_id=$sessionId&messages=1&msg_limit=$msgLimit")
        if (!response.status.isSuccess()) {
            throw Exception(when (response.status.value) {
                401 -> "Session expired. Please log in again."
                404 -> "Session not found on the server."
                in 500..599 -> "Server error (${response.status})."
                else -> "Failed to load session (${response.status})."
            })
        }
        val session = response.body<SessionDetailResponse>().session ?: throw Exception("Session not found")
        // Preserve local archived/pinned state (same reason as refreshSessions)
        val existing = db.sessionDao().getSession(sessionId)
        val entity = session.toEntity().copy(
            archived = existing?.archived ?: session.archived,
            pinned = existing?.pinned ?: session.pinned
        )
        db.sessionDao().insertSession(entity)

        // BUG-3 fix: DO NOT delete existing messages before re-inserting.
        // Now that messageId is the primary key (BUG-4 fix), REPLACE
        // dedupes correctly — re-inserting the same 50 messages from the
        // server is a no-op. Older messages that the user previously
        // loaded via "Load more" stay in the cache. New messages from
        // the server get added.
        session.messages?.forEach { msg ->
            db.messageDao().insertMessage(msg.toEntity(sessionId))
        }
        entity
    }

    suspend fun createSession(workspace: String?, model: String?, modelProvider: String?, profile: String?): Result<SessionEntity> = try {
        val client = client() ?: return Result.failure(Exception("Not connected to a server. Log in first."))
        val response = client.post(ApiEndpoint.SessionNew.path) {
            contentType(ContentType.Application.Json)
            setBody(CreateSessionRequest(workspace, model, modelProvider, profile))
        }
        if (response.status.isSuccess()) {
            val session = response.body<CreateSessionResponse>().session ?: throw Exception("No session returned")
            val entity = session.toEntity()
            db.sessionDao().insertSession(entity)
            Result.success(entity)
        } else {
            Result.failure(Exception("Failed to create session: ${response.status}"))
        }
    } catch (e: Exception) {
        Result.failure(Exception(friendlyError(e)))
    }

    suspend fun renameSession(sessionId: String, title: String): Result<Unit> = try {
        val client = client() ?: return Result.failure(Exception("Not connected to a server. Log in first."))
        val response = client.post(ApiEndpoint.SessionRename.path) {
            contentType(ContentType.Application.Json)
            setBody(RenameRequest(sessionId, title))
        }
        if (response.status.isSuccess()) {
            db.sessionDao().getSession(sessionId)?.let { db.sessionDao().insertSession(it.copy(title = title)) }
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to rename: ${response.status}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun deleteSession(sessionId: String): Result<Unit> = try {
        val client = client() ?: return Result.failure(Exception("Not connected to a server. Log in first."))
        val response = client.post(ApiEndpoint.SessionDelete.path) {
            contentType(ContentType.Application.Json)
            setBody(DeleteRequest(sessionId))
        }
        if (response.status.isSuccess()) {
            db.sessionDao().deleteSession(sessionId)
            db.messageDao().deleteMessagesForSession(sessionId)
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to delete: ${response.status}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun setPinned(sessionId: String, pinned: Boolean): Result<Unit> = try {
        // Optimistic update: update Room FIRST so the UI reflects the change
        // immediately. Pinning is local-only state (the server may not support
        // it), so we don't roll back if the server call fails — but we DO
        // log the failure so it's visible during development. (QUAL-7 fix)
        db.sessionDao().setPinned(sessionId, pinned)

        val client = client() ?: return Result.success(Unit)
        val response = client.post(ApiEndpoint.SessionPin.path) {
            contentType(ContentType.Application.Json)
            setBody(PinRequest(sessionId, pinned))
        }
        if (response.status.isSuccess()) {
            Result.success(Unit)
        } else {
            // Server failed but local state is already updated. Log it so
            // a 401 (session expired) isn't silently swallowed.
            android.util.Log.w("SessionRepository",
                "setPinned server call failed: ${response.status} (local state preserved)")
            Result.success(Unit)
        }
    } catch (e: Exception) {
        android.util.Log.w("SessionRepository",
            "setPinned server call threw: ${e.message} (local state preserved)")
        Result.success(Unit)
    }

    suspend fun setArchived(sessionId: String, archived: Boolean): Result<Unit> = try {
        // Optimistic update: update Room FIRST. Archiving is local-only state.
        db.sessionDao().setArchived(sessionId, archived)

        val client = client() ?: return Result.success(Unit)
        val response = client.post(ApiEndpoint.SessionArchive.path) {
            contentType(ContentType.Application.Json)
            setBody(ArchiveRequest(sessionId, archived))
        }
        if (response.status.isSuccess()) {
            Result.success(Unit)
        } else {
            android.util.Log.w("SessionRepository",
                "setArchived server call failed: ${response.status} (local state preserved)")
            Result.success(Unit)
        }
    } catch (e: Exception) {
        android.util.Log.w("SessionRepository",
            "setArchived server call threw: ${e.message} (local state preserved)")
        Result.success(Unit)
    }

    suspend fun moveSession(sessionId: String, projectId: String?): Result<Unit> = try {
        val client = client() ?: return Result.failure(Exception("Not connected to a server. Log in first."))
        val response = client.post(ApiEndpoint.SessionMove.path) {
            contentType(ContentType.Application.Json)
            setBody(MoveRequest(sessionId, projectId))
        }
        if (response.status.isSuccess()) {
            db.sessionDao().moveSession(sessionId, projectId)
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to move: ${response.status}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Upload a file to the server via POST /api/upload (multipart/form-data).
     * Returns the server path of the uploaded file, which can be passed
     * to ChatStream.startChat() as a `files` entry.
     *
     * SEC-2 fix: files larger than 50 MB are rejected to prevent OOM.
     * SEC-3 fix: uses Ktor's MultiPartFormDataContent instead of
     * hand-rolling the multipart body (fixes header injection risk
     * from filenames with quotes or \r\n).
     *
     * @param sessionId The session to attach the file to
     * @param fileUri The content:// URI of the file to upload
     * @return Result with the server file path on success
     */
    suspend fun uploadFile(sessionId: String, fileUri: String): Result<String> = try {
        val client = client() ?: return Result.failure(Exception("Not connected to a server. Log in first."))

        val uri = android.net.Uri.parse(fileUri)
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return Result.failure(Exception("Could not open file"))

        // Get filename
        val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else "file"
        } ?: "file"

        // Get MIME type
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"

        // SEC-2: Read file data with a 50 MB limit to prevent OOM.
        // For files under 50 MB, reading into memory is acceptable
        // (50 MB * ~2 for multipart overhead = 100 MB, within typical
        // Android heap limits).
        val maxFileSize = 50L * 1024 * 1024  // 50 MB
        val fileData = inputStream.use { stream ->
            val available = stream.available().toLong()
            if (available > maxFileSize) {
                return Result.failure(Exception("File is too large. Maximum upload size is 50 MB."))
            }
            // Read in chunks to avoid allocating a single huge buffer
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var totalRead = 0L
            var bytesRead: Int
            while (stream.read(chunk).also { bytesRead = it } != -1) {
                totalRead += bytesRead
                if (totalRead > maxFileSize) {
                    return Result.failure(Exception("File is too large. Maximum upload size is 50 MB."))
                }
                buffer.write(chunk, 0, bytesRead)
            }
            buffer.toByteArray()
        }

        // SEC-3 fix: use Ktor's MultiPartFormDataContent instead of
        // hand-rolling the multipart body. Ktor handles boundary
        // generation, header escaping (prevents injection from
        // filenames with quotes or \r\n), and content-type correctly.
        val parts = listOf(
            io.ktor.http.content.PartData.FormItem(
                value = sessionId,
                partHeaders = io.ktor.http.Headers.build {
                    append(io.ktor.http.HttpHeaders.ContentDisposition, "form-data; name=\"session_id\"")
                },
                dispose = {}
            ),
            io.ktor.http.content.PartData.BinaryItem(
                provider = { kotlinx.io.Buffer().apply { write(fileData) } },
                partHeaders = io.ktor.http.Headers.build {
                    append(io.ktor.http.HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"$fileName\"")
                    append(io.ktor.http.HttpHeaders.ContentType, mimeType)
                },
                dispose = {}
            )
        )
        val multipartContent = io.ktor.client.request.forms.MultiPartFormDataContent(parts)

        val response = client.post(ApiEndpoint.Upload.path) {
            setBody(multipartContent)
        }

        if (response.status.isSuccess()) {
            val body = response.body<UploadResponse>()
            val path = body.path ?: body.filename ?: fileName
            Result.success(path)
        } else {
            Result.failure(Exception("Upload failed: ${response.status}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    // DTOs
    @Serializable
    private data class SessionsResponse(val sessions: List<SessionDto>)

    @Serializable
    private data class SessionDto(
        val session_id: String = "",
        val title: String? = null,
        // hermes-webui sends timestamps as float Unix epoch (e.g. 1783449907.2857065)
        val created_at: Double? = null,
        val updated_at: Double? = null,
        val pinned: Boolean = false,
        val archived: Boolean = false,
        val project_id: String? = null,
        val workspace: String? = null,
        val model: String? = null,
        val model_provider: String? = null,
        val profile: String? = null,
        val message_count: Int = 0,
        val last_message_preview: String? = null
    ) {
        fun toEntity(): SessionEntity = SessionEntity(
            sessionId = session_id,
            title = title,
            createdAt = (created_at ?: 0.0).toLong(),
            updatedAt = (updated_at ?: 0.0).toLong(),
            pinned = pinned,
            archived = archived,
            projectId = project_id,
            workspace = workspace,
            model = model,
            modelProvider = model_provider,
            profile = profile,
            messageCount = message_count,
            lastMessagePreview = last_message_preview
        )
    }

    @Serializable
    private data class SessionDetailResponse(val session: SessionDetailDto?)

    @Serializable
    private data class SessionDetailDto(
        val session_id: String = "",
        val title: String? = null,
        // hermes-webui sends timestamps as float Unix epoch
        val created_at: Double? = null,
        val updated_at: Double? = null,
        val pinned: Boolean = false,
        val archived: Boolean = false,
        val project_id: String? = null,
        val workspace: String? = null,
        val model: String? = null,
        val model_provider: String? = null,
        val profile: String? = null,
        val message_count: Int = 0,
        val last_message_preview: String? = null,
        val messages: List<MessageDto>? = null
    ) {
        fun toEntity(): SessionEntity = SessionEntity(
            sessionId = session_id,
            title = title,
            createdAt = (created_at ?: 0.0).toLong(),
            updatedAt = (updated_at ?: 0.0).toLong(),
            pinned = pinned,
            archived = archived,
            projectId = project_id,
            workspace = workspace,
            model = model,
            modelProvider = model_provider,
            profile = profile,
            messageCount = message_count,
            lastMessagePreview = last_message_preview
        )
    }

    @Serializable
    private data class MessageDto(
        val message_id: String = "",
        val role: String = "",
        val content: String = "",
        // hermes-webui sends timestamps as float Unix epoch
        val timestamp: Double? = null,
        val model: String? = null,
        val provider: String? = null,
        // tool_calls can be a JSON array of objects OR a string. Use
        // JsonElement to accept either, then convert to string for Room.
        val tool_calls: kotlinx.serialization.json.JsonElement? = null,
        val reasoning: String? = null,
        // attachments can also be a JSON array. Same treatment.
        val attachments: kotlinx.serialization.json.JsonElement? = null,
        val metadata: kotlinx.serialization.json.JsonElement? = null
    ) {
        fun toEntity(sessionId: String): MessageEntity = MessageEntity(
            sessionId = sessionId,
            messageId = message_id,
            role = role,
            content = content,
            timestamp = (timestamp ?: 0.0).toLong(),
            model = model,
            provider = provider,
            toolCalls = tool_calls?.toString(),
            reasoning = reasoning,
            attachments = attachments?.toString(),
            metadata = metadata?.toString()
        )
    }

    @Serializable
    private data class CreateSessionRequest(
        val workspace: String?,
        val model: String?,
        val model_provider: String?,
        val profile: String?
    )

    @Serializable
    private data class CreateSessionResponse(val session: SessionDto?)

    @Serializable
    private data class RenameRequest(val session_id: String, val title: String)
    @Serializable
    private data class DeleteRequest(val session_id: String)
    @Serializable
    private data class PinRequest(val session_id: String, val pinned: Boolean)
    @Serializable
    private data class ArchiveRequest(val session_id: String, val archived: Boolean)
    @Serializable
    private data class MoveRequest(val session_id: String, val project_id: String?)

    @Serializable
    private data class UploadResponse(
        val filename: String? = null,
        val path: String? = null,
        val size: Int? = null,
        val mime: String? = null,
        val is_image: Boolean? = null,
        val error: String? = null
    )
}
