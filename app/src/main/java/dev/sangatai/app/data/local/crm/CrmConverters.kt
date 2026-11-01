package dev.sangatai.app.data.local.crm

import androidx.room.TypeConverter
import dev.sangatai.app.data.crm.VectorMath
import dev.sangatai.app.domain.model.CaptureStatus
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * JSON columns and the float32 vector BLOB. A corrupt cell reads as empty instead of crashing
 * the list it's in; the raw text it came from is still in `captures` and `interaction_logs`.
 */
class CrmConverters {

    @TypeConverter
    fun vectorToBlob(value: FloatArray?): ByteArray? = value?.let(VectorMath::toBytes)

    @TypeConverter
    fun blobToVector(value: ByteArray?): FloatArray? = value?.let(VectorMath::fromBytes)

    @TypeConverter
    fun tagsToJson(value: TagMap): String = CrmJson.encodeTags(value)

    @TypeConverter
    fun jsonToTags(value: String): TagMap = CrmJson.decodeTags(value)

    @TypeConverter
    fun setToJson(value: StringSet): String = CrmJson.encodeSet(value)

    @TypeConverter
    fun jsonToSet(value: String): StringSet = CrmJson.decodeSet(value)

    @TypeConverter
    fun relationToJson(value: Relation): String = CrmJson.json.encodeToString(Relation.serializer(), value)

    @TypeConverter
    fun jsonToRelation(value: String): Relation =
        runCatching { CrmJson.json.decodeFromString(Relation.serializer(), value) }.getOrDefault(Relation())

    @TypeConverter
    fun statusToString(value: CaptureStatus): String = value.name

    @TypeConverter
    fun stringToStatus(value: String): CaptureStatus =
        CaptureStatus.entries.firstOrNull { it.name == value } ?: CaptureStatus.PENDING
}

/** The one JSON configuration for CRM columns, also used by export. */
object CrmJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val tagMapSerializer = MapSerializer(String.serializer(), TagStat.serializer())
    private val stringSetSerializer = SetSerializer(String.serializer())

    fun encodeTags(value: TagMap): String = json.encodeToString(tagMapSerializer, value.tags)

    fun decodeTags(value: String): TagMap =
        TagMap(runCatching { json.decodeFromString(tagMapSerializer, value) }.getOrDefault(emptyMap()))

    fun encodeSet(value: StringSet): String = json.encodeToString(stringSetSerializer, value.values)

    fun decodeSet(value: String): StringSet =
        StringSet(runCatching { json.decodeFromString(stringSetSerializer, value) }.getOrDefault(emptySet()))
}
