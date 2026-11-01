package dev.sangatai.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface IdeaDao {

    @Query("SELECT * FROM ideas ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<IdeaEntity>>

    @Query("SELECT * FROM ideas WHERE id = :id")
    suspend fun getById(id: Long): IdeaEntity?

    @Query("SELECT id FROM ideas WHERE status = 'PENDING' ORDER BY created_at ASC, id ASC")
    suspend fun pendingIds(): List<Long>

    /** The user's own words, newest first: what the persona is learned from. Skips photo-only and one-word dumps. */
    @Query(
        "SELECT raw_input FROM ideas WHERE LENGTH(TRIM(raw_input)) >= :minChars " +
            "ORDER BY created_at DESC, id DESC LIMIT :limit"
    )
    suspend fun recentDumps(minChars: Int, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM ideas WHERE LENGTH(TRIM(raw_input)) >= :minChars")
    suspend fun countDumps(minChars: Int): Int

    @Insert
    suspend fun insert(idea: IdeaEntity): Long

    @Update
    suspend fun update(idea: IdeaEntity)

    @Query(
        "UPDATE ideas SET status = 'PENDING', attempts = 0, last_error = NULL, updated_at = :now " +
            "WHERE id = :id AND status = 'FAILED'"
    )
    suspend fun resetFailed(id: Long, now: Long): Int

    @Query("UPDATE ideas SET category = :category, updated_at = :now WHERE id = :id")
    suspend fun setCategory(id: Long, category: String, now: Long)

    @Query("DELETE FROM ideas WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM ideas ORDER BY id")
    suspend fun all(): List<IdeaEntity>

    @Query("DELETE FROM ideas")
    suspend fun deleteAll()
}
