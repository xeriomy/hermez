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
    private val prefsRepository = AuthPrefsRepository(context)

    /**
     * Borrow the process-wide shared [HttpClient] from [SharedHttpClient].
     * Returns null if the user isn't logged in yet (no saved URL).
     *
     * Because this is the SAME client that [dev.hermes.core.auth.AuthRepository]
     * used for login, the auth cookie is already in the cookie jar — no
     * need to re-authenticate.
     */
    private fun client(): HttpClient? = SharedHttpClient.client(prefsRepository.getServerUrl())

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

    suspend fun refreshSessions(): Result<List<SessionEntity>> = runCatching {
        val client = client() ?: throw Exception("Not connected to a server. Log in first.")
        val response = client.get(ApiEndpoint.Sessions.path)
        if (!response.status.isSuccess()) {
            throw Exception(when (response.status.value) {
                401 -> "Session expired. Please log in again."
                403 -> "Forbidden — your account can't list sessions."
                404 -> "Server is reachable but /api/sessions was not found."
                in 500..599 -> "Server error (${response.status}). Check hermes-webui logs."
                else -> "Failed to load sessions (${response.status})."
            })
        }
        val sessions = response.body<SessionsResponse>().sessions
        val entities = sessions.map { it.toEntity() }
        db.sessionDao().insertSessions(entities)
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
        val entity = session.toEntity()
        db.sessionDao().insertSession(entity)
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
        val client = client() ?: return Result.failure(Exception("Not connected to a server. Log in first."))
        val response = client.post(ApiEndpoint.SessionPin.path) {
            contentType(ContentType.Application.Json)
            setBody(PinRequest(sessionId, pinned))
        }
        if (response.status.isSuccess()) {
            db.sessionDao().setPinned(sessionId, pinned)
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to pin: ${response.status}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun setArchived(sessionId: String, archived: Boolean): Result<Unit> = try {
        val client = client() ?: return Result.failure(Exception("Not connected to a server. Log in first."))
        val response = client.post(ApiEndpoint.SessionArchive.path) {
            contentType(ContentType.Application.Json)
            setBody(ArchiveRequest(sessionId, archived))
        }
        if (response.status.isSuccess()) {
            db.sessionDao().setArchived(sessionId, archived)
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to archive: ${response.status}"))
        }
    } catch (e: Exception) {
        Result.failure(e)
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
        val tool_calls: String? = null,
        val reasoning: String? = null,
        val attachments: String? = null,
        val metadata: String? = null
    ) {
        fun toEntity(sessionId: String): MessageEntity = MessageEntity(
            sessionId = sessionId,
            messageId = message_id,
            role = role,
            content = content,
            timestamp = (timestamp ?: 0.0).toLong(),
            model = model,
            provider = provider,
            toolCalls = tool_calls,
            reasoning = reasoning,
            attachments = attachments,
            metadata = metadata
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
}
