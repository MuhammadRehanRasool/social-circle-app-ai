package dev.sangatai.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Notes from the app's note-taking days. Nothing creates them any more; the table is kept so
 * notes already on the phone aren't destroyed by an update. They're in every export, and
 * Settings can delete them for good.
 */
@Entity(
    tableName = "ideas",
    indices = [Index(value = ["created_at"]), Index(value = ["status"])],
)
data class IdeaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "raw_input")
    val rawInput: String,
    @ColumnInfo(name = "idea_title")
    val ideaTitle: String? = null,
    @ColumnInfo(name = "body")
    val body: String? = null,
    @ColumnInfo(name = "category")
    val category: String? = null,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "refinements")
    val refinements: String? = null,
)
