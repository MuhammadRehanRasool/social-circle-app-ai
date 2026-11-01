package dev.braindump.splitter.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaCategory
import dev.braindump.splitter.domain.model.IdeaStatus
import dev.braindump.splitter.domain.model.RefineTurn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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
    /** The polished note as Markdown, without its title; null until Gemini Nano has written it. */
    @ColumnInfo(name = "body")
    val body: String? = null,
    /** [IdeaCategory] name; null until the idea is polished. */
    @ColumnInfo(name = "category")
    val category: String? = null,
    @ColumnInfo(name = "status")
    val status: IdeaStatus = IdeaStatus.PENDING,
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    /** The refine chat as a JSON list of [RefineTurn]; null until the idea is first refined. */
    @ColumnInfo(name = "refinements")
    val refinements: String? = null,
)

fun IdeaEntity.refineTurns(): List<RefineTurn> =
    refinements?.let { runCatching { Json.decodeFromString(refineTurnList, it) }.getOrNull() }.orEmpty()

fun IdeaEntity.withTurn(turn: RefineTurn): IdeaEntity =
    copy(refinements = Json.encodeToString(refineTurnList, refineTurns() + turn))

private val refineTurnList = ListSerializer(RefineTurn.serializer())

fun IdeaEntity.toDomain(): Idea = Idea(
    id = id,
    rawInput = rawInput,
    title = ideaTitle.orEmpty(),
    body = body.orEmpty(),
    category = IdeaCategory.parse(category),
    status = status,
    error = lastError,
    createdAt = createdAt,
    refinements = refineTurns(),
)
