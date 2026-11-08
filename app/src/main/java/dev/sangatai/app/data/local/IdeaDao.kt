package dev.sangatai.app.data.local

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Old notes: read for export, counted for Settings, and deleted. Nothing new is written. */
@Dao
interface IdeaDao {

    @Query("SELECT COUNT(*) FROM ideas")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM ideas ORDER BY id")
    suspend fun all(): List<IdeaEntity>

    @Query("DELETE FROM ideas")
    suspend fun deleteAll()
}
