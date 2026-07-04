package dev.hermes.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "sessions")
@Serializable
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val archived: Boolean,
    val projectId: String?,
    val workspace: String?,
    val model: String?,
    val modelProvider: String?,
    val profile: String?,
    val messageCount: Int,
    val lastMessagePreview: String?
)