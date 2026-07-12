package dev.hermes.core.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val sessionId: String,
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