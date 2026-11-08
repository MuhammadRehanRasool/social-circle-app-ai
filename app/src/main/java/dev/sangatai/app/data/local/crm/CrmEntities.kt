package dev.sangatai.app.data.local.crm

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.sangatai.app.domain.model.CaptureStatus

/**
 * A person in the user's network. Tags are open-ended: Gemini Nano invents them from rants,
 * the user can add or remove them, and nothing about a person is a fixed score or status.
 */
@Entity(
    tableName = "people",
    indices = [
        // Not unique: two different people can both be "Ali".
        Index(value = ["name_key"]),
        Index(value = ["last_interaction_at"]),
    ],
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    /** Display name; the longest form heard, unless the user renamed them ([nameLocked]). */
    @ColumnInfo(name = "name")
    val name: String,
    /** [dev.sangatai.app.data.crm.NameKey] of [name]. */
    @ColumnInfo(name = "name_key")
    val nameKey: String,
    /** Other name keys that resolved to this person ("ali" for "Ali Khan"). */
    @ColumnInfo(name = "aliases_json")
    val aliases: StringSet = StringSet(),
    @ColumnInfo(name = "dynamic_tags_json")
    val dynamicTags: TagMap = TagMap(),
    /** Tags the user removed; merges skip them so the AI can't quietly add them back. */
    @ColumnInfo(name = "suppressed_tags_json")
    val suppressedTags: StringSet = StringSet(),
    /** Unit-length embedding of the person's strongest tags; null until embedded. */
    @ColumnInfo(name = "embedding")
    val embedding: FloatArray? = null,
    /** Which model made [embedding]. A different model means it gets re-embedded. */
    @ColumnInfo(name = "embedding_model")
    val embeddingModel: String? = null,
    /** The user renamed them, so a longer spoken name doesn't overwrite it. */
    @ColumnInfo(name = "name_locked")
    val nameLocked: Boolean = false,
    /** Added by hand, so they're kept even with no interactions left. */
    @ColumnInfo(name = "created_by_user")
    val createdByUser: Boolean = false,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_interaction_at")
    val lastInteractionAt: Long,
    /**
     * Who they are to the user ("cousin", "boss"), as the user set it. Null means "whatever the
     * rants said last" (see [RelationFactEntity]); the user's word always wins.
     */
    @ColumnInfo(name = "relation_to_user")
    val relationToUser: String? = null,
) {
    // FloatArray has identity equality, which breaks data-class equals; rows are compared by version instead.
    override fun equals(other: Any?): Boolean =
        other is PersonEntity && other.id == id && other.updatedAt == updatedAt && other.name == name

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + updatedAt.hashCode()) + name.hashCode()
}

/** One rant as the user said it: saved before Gemini Nano runs, so nothing is lost if it can't. */
@Entity(
    tableName = "captures",
    indices = [Index(value = ["status"]), Index(value = ["created_at"])],
)
data class CaptureEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "raw_text")
    val rawText: String,
    @ColumnInfo(name = "status")
    val status: CaptureStatus = CaptureStatus.PENDING,
    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error")
    val lastError: String? = null,
    @ColumnInfo(name = "summary")
    val summary: String? = null,
    @ColumnInfo(name = "mention_count")
    val mentionCount: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "interaction_logs",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["capture_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["person_id", "timestamp"]), Index(value = ["capture_id"])],
)
data class InteractionLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "person_id")
    val personId: Long,
    /** The rant this came from. */
    @ColumnInfo(name = "capture_id")
    val captureId: Long?,
    /** When the user spoke, not when Gemini Nano got to it. */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "raw_text")
    val rawText: String,
    @ColumnInfo(name = "nano_summary")
    val nanoSummary: String?,
    /** The tags this interaction gave the person; the person's tags can be rebuilt from these. */
    @ColumnInfo(name = "tags_added_json")
    val tagsAdded: StringSet = StringSet(),
)

@Entity(
    tableName = "connections",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_a"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_b"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["person_a", "person_b"], unique = true), Index(value = ["person_b"])],
)
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "edge_id")
    val edgeId: Long = 0,
    /** Always the smaller id of the pair, so each pair has exactly one row. */
    @ColumnInfo(name = "person_a")
    val personA: Long,
    @ColumnInfo(name = "person_b")
    val personB: Long,
    @ColumnInfo(name = "relation_json")
    val relation: Relation = Relation(),
)

/**
 * A relationship the user said out loud, kept per rant so deleting the rant takes it back.
 * [personB] null means the relation is to the user ("Ali is my cousin"); otherwise it is between
 * two people ("Ali and Sara are married"), stored in either order.
 */
@Entity(
    tableName = "relation_facts",
    foreignKeys = [
        ForeignKey(
            entity = CaptureEntity::class,
            parentColumns = ["id"],
            childColumns = ["capture_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_a"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_b"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["capture_id"]), Index(value = ["person_a"]), Index(value = ["person_b"])],
)
data class RelationFactEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "capture_id")
    val captureId: Long,
    @ColumnInfo(name = "person_a")
    val personA: Long,
    @ColumnInfo(name = "person_b")
    val personB: Long?,
    @ColumnInfo(name = "label")
    val label: String,
    /** When the user spoke. */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
)

/** A person without the embedding blob, for lists. */
data class PersonRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "aliases_json") val aliases: StringSet,
    @ColumnInfo(name = "dynamic_tags_json") val dynamicTags: TagMap,
    @ColumnInfo(name = "suppressed_tags_json") val suppressedTags: StringSet,
    @ColumnInfo(name = "has_embedding") val hasEmbedding: Boolean,
    @ColumnInfo(name = "created_by_user") val createdByUser: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_interaction_at") val lastInteractionAt: Long,
    /** Set by the user. */
    @ColumnInfo(name = "relation_to_user") val relationToUser: String?,
    /** The newest relation to the user heard in a rant. */
    @ColumnInfo(name = "ai_relation") val aiRelation: String?,
    /** Interactions logged for them. */
    @ColumnInfo(name = "mention_count") val mentionCount: Int,
)

/** A relation fact seen from one person: who it's with (null = the user) and what it is. */
data class FactRow(
    @ColumnInfo(name = "other_id") val otherId: Long?,
    @ColumnInfo(name = "label") val label: String,
)

/** A rant with the names of the people it was saved to. */
data class CaptureRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "status") val status: CaptureStatus,
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "last_error") val lastError: String?,
    @ColumnInfo(name = "summary") val summary: String?,
    @ColumnInfo(name = "mention_count") val mentionCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** Names separated by [CaptureRow.NAME_SEPARATOR], or null when nobody was saved. */
    @ColumnInfo(name = "people_names") val peopleNames: String?,
) {
    companion object {
        /** Unit separator: can't appear in a cleaned name. */
        const val NAME_SEPARATOR = "\u001F"
    }
}

data class PersonVector(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "embedding") val embedding: FloatArray?,
)

/** Another person named in the same rants as a given one. */
data class CoMention(
    @ColumnInfo(name = "other_id") val otherId: Long,
    @ColumnInfo(name = "mentions") val mentions: Int,
    @ColumnInfo(name = "last_at") val lastAt: Long,
)

/** An edge seen from one side, with the other person's name. */
data class ConnectionRow(
    @ColumnInfo(name = "edge_id") val edgeId: Long,
    @ColumnInfo(name = "other_id") val otherId: Long,
    @ColumnInfo(name = "other_name") val otherName: String,
    @ColumnInfo(name = "relation_json") val relation: Relation,
)
