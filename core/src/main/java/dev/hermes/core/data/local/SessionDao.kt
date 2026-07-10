package dev.hermes.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<SessionEntity>)

    @Query("SELECT * FROM sessions WHERE archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun getActiveSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE archived = 1 ORDER BY updatedAt DESC")
    fun getArchivedSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): SessionEntity?

    @Query("SELECT * FROM sessions WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun getSessionsByProject(projectId: String): Flow<List<SessionEntity>>

    @Query("DELETE FROM sessions WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("UPDATE sessions SET archived = :archived WHERE sessionId = :sessionId")
    suspend fun setArchived(sessionId: String, archived: Boolean)

    @Query("UPDATE sessions SET pinned = :pinned WHERE sessionId = :sessionId")
    suspend fun setPinned(sessionId: String, pinned: Boolean)

    @Query("UPDATE sessions SET projectId = :projectId WHERE sessionId = :sessionId")
    suspend fun moveSession(sessionId: String, projectId: String?)

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()
}