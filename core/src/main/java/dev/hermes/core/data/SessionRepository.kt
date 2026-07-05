package dev.hermes.core.data

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.hermes.core.data.local.AppDatabase
import dev.hermes.core.data.local.MessageEntity
import dev.hermes.core.data.local.SessionEntity
import dev.hermes.core.network.ApiEndpoint
import dev.hermes.core.network.HttpClientProvider
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class SessionRepository(application: Application, private val serverUrl: String) : ViewModel() {
    private val context = application.applicationContext
    private val db = AppDatabase.getInstance(context)
    private val client = HttpClientProvider.create(serverUrl)

    fun getActiveSessions(): Flow<List<SessionEntity>> = db.sessionDao().getActiveSessions()
    fun getArchivedSessions(): Flow<List<SessionEntity>> = db.sessionDao().getArchivedSessions()
    fun getSessionsByProject(projectId: String): Flow<List<SessionEntity>> = db.sessionDao().getSessionsByProject(projectId)

    fun getMessages(sessionId: String, limit: Int = 50): Flow<List<MessageEntity>> = db.messageDao().getMessages(sessionId, limit)

    suspend fun refreshSessions(): Result<List<SessionEntity>> = runCatching {
        val response = client.get(ApiEndpoint.Sessions.path)
        if (!response.status.isSuccess()) throw Exception("Failed to refresh sessions: ${response.status}")
        val sessions = response.body<SessionsResponse>().sessions
        val entities = sessions.map { it.toEntity() }
        db.sessionDao().insertSessions(entities)
        entities
    }

    suspend fun loadSession(sessionId: String, msgLimit: Int = 50): Result<SessionEntity> = runCatching {
        val response = client.get("${ApiEndpoint.SessionDetail.path}?session_id=$sessionId&messages=1&msg_limit=$msgLimit")
        if (!response.status.isSuccess()) throw Exception("Failed to load session: ${response.status}")
        val session = response.body<SessionDetailResponse>().session ?: throw Exception("Session not found")
        val entity = session.toEntity()
        db.sessionDao().insertSession(entity)
        session.messages?.forEach { msg ->
            db.messageDao().insertMessage(msg.toEntity(sessionId))
        }
        entity
    }

    suspend fun createSession(workspace: String?, model: String?, modelProvider: String?, profile: String?): Result<SessionEntity> = try {
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
        Result.failure(e)
    }

    suspend fun renameSession(sessionId: String, title: String): Result<Unit> = try {
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

    class Factory(private val application: Application, private val serverUrl: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionRepository(application, serverUrl) as T
    }

    // DTOs
    @Serializable
    private data class SessionsResponse(val sessions: List<SessionDto>)

    @Serializable
    private data class SessionDto(
        val session_id: String,
        val title: String?,
        val created_at: Long,
        val updated_at: Long,
        val pinned: Boolean,
        val archived: Boolean,
        val project_id: String?,
        val workspace: String?,
        val model: String?,
        val model_provider: String?,
        val profile: String?,
        val message_count: Int,
        val last_message_preview: String?
    ) {
        fun toEntity(): SessionEntity = SessionEntity(
            sessionId = session_id,
            title = title,
            createdAt = created_at,
            updatedAt = updated_at,
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
        val session_id: String,
        val title: String?,
        val created_at: Long,
        val updated_at: Long,
        val pinned: Boolean,
        val archived: Boolean,
        val project_id: String?,
        val workspace: String?,
        val model: String?,
        val model_provider: String?,
        val profile: String?,
        val message_count: Int,
        val last_message_preview: String?,
        val messages: List<MessageDto>?
    ) {
        fun toEntity(): SessionEntity = SessionEntity(
            sessionId = session_id,
            title = title,
            createdAt = created_at,
            updatedAt = updated_at,
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
        val message_id: String,
        val role: String,
        val content: String,
        val timestamp: Long,
        val model: String?,
        val provider: String?,
        val tool_calls: String?,
        val reasoning: String?,
        val attachments: String?,
        val metadata: String?
    ) {
        fun toEntity(sessionId: String): MessageEntity = MessageEntity(
            sessionId = sessionId,
            messageId = message_id,
            role = role,
            content = content,
            timestamp = timestamp,
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
