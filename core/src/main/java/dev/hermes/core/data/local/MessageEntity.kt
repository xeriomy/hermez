package dev.hermes.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "messages")
@Serializable
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sessionId: String,
    val messageId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val model: String?,
    val provider: String?,
    val toolCalls: String?, // JSON serialized, nullable for future fields
    val reasoning: String?,
    val attachments: String?, // JSON serialized
    val metadata: String? // JSON serialized for unknown fields
)