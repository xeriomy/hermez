package dev.hermes.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    // Subquery gets the NEWEST N messages (DESC), outer query re-sorts
    // ascending for chronological display. Without this, ORDER BY ASC LIMIT
    // returns the OLDEST N — wrong for chat (you want recent messages).
    @Query("SELECT * FROM (SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit) ORDER BY timestamp ASC")
    fun getMessages(sessionId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND timestamp < :beforeTimestamp ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getMessagesBefore(sessionId: String, beforeTimestamp: Long, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun getMessageCount(sessionId: String): Int

    /**
     * Reactive count of cached messages for [sessionId].
     *
     * Used by the new ChatViewModel (chat rewrite §5.3) to compute
     * `remainingToLoad` as a Flow — `remaining = total - visibleCount`.
     * When a new message is inserted (e.g. by `loadSession` after a
     * stream completes), this Flow re-emits and the "Load more (N)"
     * button updates automatically.
     *
     * Replaces the old pattern of calling `getMessageCount` (a suspend
     * one-shot) after every cache write, which was racy and required
     * manual `_remainingToLoad.value = ...` updates scattered across
     * the ViewModel.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    fun getMessageCountFlow(sessionId: String): Flow<Int>

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesForSession(sessionId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    suspend fun deleteMessage(messageId: String)
}