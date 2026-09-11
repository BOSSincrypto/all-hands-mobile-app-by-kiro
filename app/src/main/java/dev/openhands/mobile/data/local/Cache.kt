package dev.openhands.mobile.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Offline cache. Deliberately holds only non-sensitive display metadata: no tokens,
 * no session keys, no file contents.
 */
@Entity(tableName = "conversations")
data class CachedConversation(
    @PrimaryKey val id: String,
    val title: String?,
    val repository: String?,
    val branch: String?,
    val model: String?,
    val sandboxStatus: String,
    val executionStatus: String?,
    val updatedAt: String?,
    val costUsd: Double?,
    /** Millis of local insert, used for stable ordering when the server omits timestamps. */
    val cachedAt: Long,
)

@Entity(tableName = "events")
data class CachedEvent(
    @PrimaryKey val id: String,
    val conversationId: String,
    val kind: String,
    val source: String?,
    val timestamp: String?,
    val toolName: String?,
    val summary: String?,
    val body: String,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY COALESCE(updatedAt, '') DESC, cachedAt DESC")
    fun observeAll(): Flow<List<CachedConversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeOne(id: String): Flow<CachedConversation?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<CachedConversation>)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun clear()
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events WHERE conversationId = :id ORDER BY COALESCE(timestamp, '') ASC")
    fun observeFor(id: String): Flow<List<CachedEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<CachedEvent>)

    /**
     * Caps stored history per conversation so a long-running agent cannot grow the
     * database without bound.
     */
    @Query(
        """
        DELETE FROM events WHERE conversationId = :id AND id NOT IN (
            SELECT id FROM events WHERE conversationId = :id
            ORDER BY COALESCE(timestamp, '') DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(id: String, keep: Int)

    @Query("DELETE FROM events")
    suspend fun clear()
}

@Database(
    entities = [CachedConversation::class, CachedEvent::class],
    version = 1,
    exportSchema = false,
)
abstract class OpenHandsDatabase : RoomDatabase() {
    abstract fun conversations(): ConversationDao
    abstract fun events(): EventDao
}
