# Social graph / personal CRM: data, AI and repository layer (proposal v1)

Status: **approved (D1–D8) and implemented** in the data layer. No view-layer code yet.
Differences from this text in the code: `people` also has `suppressed_tags_json`, `name_locked` and `created_by_user`;
`interaction_logs.tags_added_json` lets tags be rebuilt when an interaction or rant is deleted; edges are always
recomputed from co-mentions instead of incremented; the embedder is MediaPipe `tasks-text` 1.2.0, with every API symbol
checked in the AAR; all prompts and numbers live in `CrmTuning` (user-editable). See README "Privacy and your data".

Written against the code as it is today: Kotlin 2.3.21, Hilt 2.58, Room 2.7.2 (KSP, `generateKotlin`),
ML Kit GenAI Prompt `1.0.0-beta4`, DB version 4 (`ideas` table), `AICoreManager` with model fallback.

---

## 0. Decisions this proposal makes (please confirm or overrule)

| # | Decision | Why |
|---|----------|-----|
| D1 | **Kotlin, not Java.** | The whole app is Kotlin + Hilt + KSP Room. ML Kit GenAI's Prompt API is coroutine-first (`suspend generateContent`, `Flow` download). Java would mean a second language, Java-friendly wrappers around the existing `AICoreManager`, and Room in Java mode. Nothing is gained. |
| D2 | **Compose for the UI phase, not RecyclerView + ChipGroup.** | The app is 100% Compose Material 3. `LazyRow` carousels and `FlowRow` + `FilterChip`/`AssistChip` are the Compose equivalents of horizontal RecyclerView and ChipGroup. Bringing in Views + Material Components would add a second UI stack. The widget still uses `RemoteViews`, as it must. |
| D3 | **Widget mic opens the existing transparent `CaptureActivity`, not a foreground service.** | AICore serves Gemini Nano only to the app that is in the foreground (an activity on screen). A foreground service gets `BACKGROUND_USE_BLOCKED`. On Android 14+, a `microphone`-type FGS started from a widget also hits the while-in-use limits. The transparent-activity pattern already works in this app (tile, widget, shortcuts). |
| D4 | **Add a `captures` table** (save-first queue) next to your 3 tables. | The raw rant has to be in the DB *before* Nano runs, because Nano can fail, defer or be swiped away. At that point we don't know who's in it yet, so it can't be an `InteractionLog` row. |
| D5 | **Keep the `ideas` table** (migration 4→5 only adds tables). | `fallbackToDestructiveMigration` would wipe existing notes. Old notes can be dropped in a later migration once the CRM replaces them. |
| D6 | **Embedder: Universal Sentence Encoder (MediaPipe Text Embedder), behind an interface.** EmbeddingGemma-300m is the drop-in upgrade. | USE is small enough to bundle in the APK. EmbeddingGemma retrieves better (it has separate query and document modes) but makes the APK much larger. A model-id column re-embeds everyone when the model is switched. See §2.4. |
| D7 | **Connections come from co-mentions** (2 people named in the same rant), not from a Nano field. | Your output schema `{mentioned, interaction_summary}` has no relations. Asking nano-v3 for more JSON raises its format-failure rate. `relationJSON` keeps a `labels` slot for later. |
| D8 | **Embed the tags, not the summaries.** | `interaction_summary` covers the whole rant, so it names other people. Embedding it would leak Sara's traits into Bilal's vector. |

---

## 1. Room layer

### 1.1 Entities

```kotlin
// data/local/crm/PersonEntity.kt
@Entity(
    tableName = "people",
    indices = [
        Index(value = ["name_key"]),             // not unique: two different "Ali"s are allowed
        Index(value = ["last_interaction_at"]),
    ],
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,                     // display name, longest form seen
    @ColumnInfo(name = "name_key") val nameKey: String,              // normalized: "sara khan"
    @ColumnInfo(name = "aliases_json") val aliases: StringSet = StringSet(),   // other name_keys that resolved here
    @ColumnInfo(name = "dynamic_tags_json") val dynamicTags: TagMap = TagMap(),
    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB) val embedding: FloatArray? = null,
    @ColumnInfo(name = "embedding_model") val embeddingModel: String? = null,  // e.g. "use-v1"; mismatch => re-embed
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_interaction_at") val lastInteractionAt: Long,
) {
    // FloatArray breaks data-class equality; compare by id + updatedAt instead.
    override fun equals(other: Any?) = other is PersonEntity && other.id == id && other.updatedAt == updatedAt
    override fun hashCode() = 31 * id.hashCode() + updatedAt.hashCode()
}

// data/local/crm/InteractionLogEntity.kt
@Entity(
    tableName = "interaction_logs",
    foreignKeys = [
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["person_id"],
            onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE),
        ForeignKey(entity = CaptureEntity::class, parentColumns = ["id"], childColumns = ["capture_id"],
            onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["person_id", "timestamp"]), Index(value = ["capture_id"])],
)
data class InteractionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "person_id") val personId: Long,
    @ColumnInfo(name = "capture_id") val captureId: Long?,       // the rant it came from; lets one rant be undone
    @ColumnInfo(name = "timestamp") val timestamp: Long,         // when the user spoke, not when Nano ran
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "nano_summary") val nanoSummary: String?,
    @ColumnInfo(name = "tags_added_json") val tagsAdded: StringSet = StringSet(), // what this rant contributed
)

// data/local/crm/ConnectionEntity.kt
@Entity(
    tableName = "connections",
    foreignKeys = [
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["person_a"],
            onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE),
        ForeignKey(entity = PersonEntity::class, parentColumns = ["id"], childColumns = ["person_b"],
            onDelete = ForeignKey.CASCADE, onUpdate = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["person_a", "person_b"], unique = true), Index(value = ["person_b"])],
)
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "edge_id") val edgeId: Long = 0,
    @ColumnInfo(name = "person_a") val personA: Long,   // invariant: personA < personB (enforced in repository)
    @ColumnInfo(name = "person_b") val personB: Long,
    @ColumnInfo(name = "relation_json") val relation: Relation = Relation(),
)

// data/local/crm/CaptureEntity.kt: save-first queue (D4)
@Entity(tableName = "captures", indices = [Index(value = ["status"]), Index(value = ["created_at"])])
data class CaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "raw_text") val rawText: String,
    @ColumnInfo(name = "status") val status: CaptureStatus = CaptureStatus.PENDING, // PENDING | DONE | FAILED
    @ColumnInfo(name = "attempts") val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "summary") val summary: String? = null,
    @ColumnInfo(name = "mention_count") val mentionCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
```

JSON value types (kotlinx.serialization, which the app already uses):

```kotlin
@Serializable data class TagStat(
    @SerialName("n") val count: Int,          // times the AI (or the user) asserted it
    @SerialName("f") val firstAt: Long,
    @SerialName("l") val lastAt: Long,
    @SerialName("u") val byUser: Boolean = false, // user-added tags are never evicted
)

/** Wrapper classes so Room sees one concrete type per converter (no generic Map/Set converters). */
@Serializable @JvmInline value class TagMap(val tags: Map<String, TagStat> = emptyMap())
@Serializable @JvmInline value class StringSet(val values: Set<String> = emptySet())

@Serializable data class Relation(
    @SerialName("co_mentions") val coMentions: Int = 0,
    @SerialName("last_at") val lastAt: Long = 0,
    @SerialName("labels") val labels: List<String> = emptyList(), // reserved: user- or Nano-supplied ("siblings")
)
```

> Room 2.7 with KSP handles `@JvmInline value class` columns badly in some versions. If CI complains,
> make `TagMap`/`StringSet` plain `data class`es. The converters stay the same.

On disk, `dynamic_tags_json` looks like this:
`{"car_expert":{"n":3,"f":1730400000000,"l":1730486400000},"high_trust":{"n":1,...}}`.
A shallow merge works key by key, as §4.2 shows.

### 1.2 TypeConverters

```kotlin
class CrmConverters {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // float[] <-> BLOB, little-endian float32. 100-d USE = 400 B/person; 768-d Gemma = 3 KB.
    @TypeConverter fun vectorToBlob(v: FloatArray?): ByteArray? = v?.let {
        ByteBuffer.allocate(it.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asFloatBuffer().put(it) }.array()
    }
    @TypeConverter fun blobToVector(b: ByteArray?): FloatArray? = b?.let {
        val fb = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(fb.remaining()).also(fb::get)
    }

    @TypeConverter fun tagsToJson(t: TagMap): String = json.encodeToString(t.tags)
    @TypeConverter fun jsonToTags(s: String): TagMap =
        TagMap(runCatching { json.decodeFromString<Map<String, TagStat>>(s) }.getOrDefault(emptyMap()))

    @TypeConverter fun setToJson(s: StringSet): String = json.encodeToString(s.values)
    @TypeConverter fun jsonToSet(s: String): StringSet =
        StringSet(runCatching { json.decodeFromString<Set<String>>(s) }.getOrDefault(emptySet()))

    @TypeConverter fun relationToJson(r: Relation): String = json.encodeToString(r)
    @TypeConverter fun jsonToRelation(s: String): Relation =
        runCatching { json.decodeFromString<Relation>(s) }.getOrDefault(Relation())

    @TypeConverter fun statusToString(s: CaptureStatus) = s.name
    @TypeConverter fun stringToStatus(s: String) =
        CaptureStatus.entries.firstOrNull { it.name == s } ?: CaptureStatus.PENDING
}
```

A corrupt JSON cell decodes to empty instead of crashing the list. The raw text is still in `captures` and `interaction_logs`.

### 1.3 Migration 4 → 5

The SQL must match Room's generated schema byte for byte, or Room throws at open with "Migration didn't properly handle".
**I'm also proposing** `exportSchema = true` with `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`,
so CI writes the JSON schema and a `MigrationTestHelper` test can check it.

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `people` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `name` TEXT NOT NULL, `name_key` TEXT NOT NULL, `aliases_json` TEXT NOT NULL,
            `dynamic_tags_json` TEXT NOT NULL, `embedding` BLOB, `embedding_model` TEXT,
            `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `last_interaction_at` INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_people_name_key` ON `people` (`name_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_people_last_interaction_at` ON `people` (`last_interaction_at`)")

        db.execSQL("""CREATE TABLE IF NOT EXISTS `captures` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `raw_text` TEXT NOT NULL, `status` TEXT NOT NULL, `attempts` INTEGER NOT NULL, `last_error` TEXT,
            `summary` TEXT, `mention_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_captures_status` ON `captures` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_captures_created_at` ON `captures` (`created_at`)")

        db.execSQL("""CREATE TABLE IF NOT EXISTS `interaction_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `person_id` INTEGER NOT NULL, `capture_id` INTEGER, `timestamp` INTEGER NOT NULL, `raw_text` TEXT NOT NULL,
            `nano_summary` TEXT, `tags_added_json` TEXT NOT NULL,
            FOREIGN KEY(`person_id`) REFERENCES `people`(`id`) ON UPDATE CASCADE ON DELETE CASCADE,
            FOREIGN KEY(`capture_id`) REFERENCES `captures`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_interaction_logs_person_id_timestamp` ON `interaction_logs` (`person_id`, `timestamp`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_interaction_logs_capture_id` ON `interaction_logs` (`capture_id`)")

        db.execSQL("""CREATE TABLE IF NOT EXISTS `connections` (`edge_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `person_a` INTEGER NOT NULL, `person_b` INTEGER NOT NULL, `relation_json` TEXT NOT NULL,
            FOREIGN KEY(`person_a`) REFERENCES `people`(`id`) ON UPDATE CASCADE ON DELETE CASCADE,
            FOREIGN KEY(`person_b`) REFERENCES `people`(`id`) ON UPDATE CASCADE ON DELETE CASCADE)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_connections_person_a_person_b` ON `connections` (`person_a`, `person_b`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_connections_person_b` ON `connections` (`person_b`)")
    }
}
```

Room turns on `PRAGMA foreign_keys` itself when entities declare foreign keys. Deleting a person cascades to their
logs and edges in SQLite, so no manual cleanup code is needed.

### 1.4 DAOs (signatures)

```kotlin
@Dao interface PersonDao {
    @Insert suspend fun insert(p: PersonEntity): Long
    @Update suspend fun update(p: PersonEntity)
    @Query("DELETE FROM people WHERE id = :id") suspend fun delete(id: Long)  // cascades
    @Query("SELECT * FROM people WHERE id = :id") suspend fun get(id: Long): PersonEntity?

    // Name resolution (§4.3)
    @Query("SELECT * FROM people WHERE name_key = :key OR aliases_json LIKE '%\"' || :key || '\"%'")
    suspend fun byKeyOrAlias(key: String): List<PersonEntity>
    @Query("SELECT * FROM people WHERE name_key = :first OR name_key LIKE :first || ' %' ORDER BY last_interaction_at DESC")
    suspend fun byFirstName(first: String): List<PersonEntity>

    // UI lists never load the BLOB
    @Query("SELECT id, name, dynamic_tags_json, last_interaction_at FROM people ORDER BY last_interaction_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PersonCard>>

    // Vector index load
    @Query("SELECT id, embedding FROM people WHERE embedding IS NOT NULL AND embedding_model = :model")
    suspend fun vectors(model: String): List<PersonVector>
    @Query("SELECT * FROM people WHERE embedding IS NULL OR embedding_model IS NOT :model LIMIT :limit")
    suspend fun needingEmbedding(model: String, limit: Int): List<PersonEntity>
    @Query("SELECT * FROM people WHERE id IN (:ids)") suspend fun byIds(ids: List<Long>): List<PersonEntity>
}

@Dao interface InteractionLogDao {
    @Insert suspend fun insert(log: InteractionLogEntity): Long
    @Query("SELECT * FROM interaction_logs WHERE person_id = :personId ORDER BY timestamp DESC")
    fun observeFor(personId: Long): Flow<List<InteractionLogEntity>>
}

@Dao interface ConnectionDao {
    @Query("SELECT * FROM connections WHERE person_a = :a AND person_b = :b") suspend fun get(a: Long, b: Long): ConnectionEntity?
    @Insert suspend fun insert(c: ConnectionEntity): Long
    @Update suspend fun update(c: ConnectionEntity)
    @Query("SELECT * FROM connections WHERE person_a = :id OR person_b = :id") fun observeFor(id: Long): Flow<List<ConnectionEntity>>
}

@Dao interface CaptureDao {
    @Insert suspend fun insert(c: CaptureEntity): Long
    @Update suspend fun update(c: CaptureEntity)
    @Query("SELECT * FROM captures WHERE id = :id") suspend fun get(id: Long): CaptureEntity?
    @Query("SELECT id FROM captures WHERE status = 'PENDING' ORDER BY created_at, id") suspend fun pendingIds(): List<Long>
}
```

---

## 2. Local embedding layer (MediaPipe Text Embedder / LiteRT)

### 2.1 Dependencies and assets

```toml
# libs.versions.toml: pin the current release. Don't use `latest.release`, because CI builds wouldn't be reproducible.
mediapipeTasks = "0.10.x"   # TODO: set the exact current version when implementing
mediapipe-tasks-text = { group = "com.google.mediapipe", name = "tasks-text", version.ref = "mediapipeTasks" }
```

```kotlin
// app/build.gradle.kts
android { androidResources { noCompress += listOf("tflite", "task") } } // memory-map the model, don't inflate it
```

Model asset: `app/src/main/assets/universal_sentence_encoder.tflite`, from
`https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite`.
It is fetched **at build time**, either committed to the repo (Git LFS) or downloaded by a `curl` step in the CI workflow.
The app itself never downloads anything, so it still has no `INTERNET` permission.

R8: add `-keep class com.google.mediapipe.** { *; }` and `-keep class com.google.protobuf.** { *; }`, because MediaPipe uses JNI and reflection.

### 2.2 Engine

```kotlin
interface TextEmbeddingEngine {
    val modelId: String                        // stored in people.embedding_model
    /** Unit-length vector. [asQuery] picks the query vs. document form on models that have both (Gemma). */
    fun embed(text: String, asQuery: Boolean): FloatArray?
}

@Singleton
class MediaPipeTextEmbeddingEngine @Inject constructor(@ApplicationContext private val context: Context) : TextEmbeddingEngine {
    override val modelId = "use-v1"

    // Lazy: ~50–150 ms to load the model. Built on first use off the main thread, then kept for the app's lifetime.
    private val embedder: TextEmbedder? by lazy {
        runCatching {
            TextEmbedder.createFromOptions(
                context,
                TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder()
                        .setModelAssetPath("universal_sentence_encoder.tflite")
                        .setDelegate(Delegate.CPU)
                        .build())
                    .setL2Normalize(true)   // so cosine == dot product
                    .setQuantize(false)
                    .build(),
            )
        }.getOrNull()   // missing or corrupt asset: semantic search turns off, ingestion still works
    }

    private val lock = Any()   // TextEmbedder isn't documented as thread-safe

    override fun embed(text: String, asQuery: Boolean): FloatArray? {
        if (text.isBlank()) return null
        val e = embedder ?: return null
        return runCatching {
            synchronized(lock) { e.embed(text).embeddingResult().embeddings().first().floatEmbedding() }
        }.getOrNull()?.let(::normalize)   // normalize again, to be safe across model swaps
    }
}
```

> **Check these symbols in the first CI build:** `setL2Normalize`, `setQuantize`, `Embedding.floatEmbedding()`.
> The official guide confirms `createFromOptions`, `embed(...)`, `.embeddingResult().embeddings()`
> and `TextEmbedder.cosineSimilarity`, but it doesn't spell out these three builder and accessor names.
> They're the names MediaPipe uses in its other tasks.

Unlike Gemini Nano, the embedder runs **in our own process**, so it works in the background too
(re-embedding everyone after a model swap can run in a `WorkManager` job).

### 2.3 What gets embedded (the "profile document")

```kotlin
/** Tags only (D8), strongest first, as words: "car expert, high trust, generous". No name: it adds noise to queries. */
fun profileText(p: PersonEntity, now: Long): String =
    TagScoring.ranked(p.dynamicTags, now).take(EMBED_TAGS)          // EMBED_TAGS = 16
        .joinToString(", ") { it.replace('_', ' ') }
```

A tag that was seen several times appears once, but it ranks earlier. `TagScoring` (§4.2) ranks by count × recency,
so `flaked_today` from 3 months ago falls off the end of the list and out of the vector.

### 2.4 Vector index and search

```kotlin
@Singleton
class PersonVectorIndex @Inject constructor(private val dao: PersonDao, private val engine: TextEmbeddingEngine) {
    private val mutex = Mutex()
    private var ids = LongArray(0)
    private var matrix = FloatArray(0)       // row-major, n × dim, unit rows
    private var dim = 0
    private var loaded = false

    suspend fun put(id: Long, v: FloatArray) = mutex.withLock { ensureLoaded(); upsertRow(id, v) }
    suspend fun remove(id: Long) = mutex.withLock { ensureLoaded(); removeRow(id) }

    /** Top [k] by cosine (dot product of unit vectors). 2,000 people × 100-d ≈ 0.2 M multiply-adds, under a millisecond. */
    suspend fun search(query: FloatArray, k: Int): List<Pair<Long, Float>> = mutex.withLock {
        ensureLoaded()
        if (dim == 0 || query.size != dim) return@withLock emptyList()
        val heap = PriorityQueue<Pair<Long, Float>>(k + 1, compareBy { it.second })
        for (r in ids.indices) {
            var s = 0f; val o = r * dim
            for (d in 0 until dim) s += matrix[o + d] * query[d]
            heap += ids[r] to s; if (heap.size > k) heap.poll()
        }
        heap.sortedByDescending { it.second }
    }

    private suspend fun ensureLoaded() { if (!loaded) { /* dao.vectors(engine.modelId) -> ids/matrix */ loaded = true } }
}
```

All writes to people go through the repository, so it keeps the index in step with the database. There's no
separate invalidation. Brute force is the correct choice at personal-CRM scale. An ANN index (HNSW, `sqlite-vec`)
only pays off well beyond 50k rows.

**Model choice (D6), in detail:**

|  | Universal Sentence Encoder | EmbeddingGemma-300m (`embedding_gemma.task`) |
|---|---|---|
| Bundled size | small (tens of MB) | large (300M params, int4/int8). Bundling it in the APK pushes against Play's base-APK limit; needs Play Asset Delivery |
| Query vs. document | same encoder for both | separate `RETRIEVAL_QUERY` / `RETRIEVAL_DOCUMENT` modes (via `TextFormatContext`), better for "who can help" |
| Embed latency | a few ms | tens of ms |

My recommendation: ship USE now. `TextEmbeddingEngine.asQuery` and `embedding_model` are already there,
so switching to Gemma later is one new class plus a background re-embed.

---

## 3. Gemini Nano prompts

These reuse the existing `AICoreManager.infer()` pipeline: status probe, mutex, error classification,
retry with the rules moved into the user turn, fallback across installed builds, and promotion of the build that works.
The new methods are `extractMentions()` and `rankCandidates()`, each a `Prompt<T>` plus a pure parser.

### 3.1 Ingestion (system instruction)

```text
You keep a private address book for the user. Between <<< and >>> is something the user said out loud, possibly messy speech-to-text, about people in their life.
Find every other person the user mentions and describe each one with short tags.
Rules:
- People only. Never include the user (I, me, my, myself). Skip companies, places, pets and things.
- name: the name as said ("Ali", "Sara Khan"). If there is no name, use the relationship with a capital letter ("Mom", "Boss", "Landlord").
- tags: 1 to 4 per person, lowercase_snake_case, 1 to 3 words each. Invent them from what was said: skills (car_expert), reliability (flaked_today, always_on_time), trust (high_trust), how they treat the user (supportive), life events (new_job), what they could help with (knows_landlords).
- Only tags the words support. Never guess. Same idea, same tag: prefer short common words.
- interaction_summary: one sentence of at most 20 words about what happened, in the user's language.
- Output ONLY one JSON object in exactly this shape. Every value is a plain string or a list of plain strings. Stop right after the closing brace.
{"interaction_summary":"...","mentioned":[{"name":"...","tags":["..."]}]}
Example input: <<<nobody really, just tired, work was long>>>
Example output: {"interaction_summary":"A long, tiring day at work.","mentioned":[]}
Example input: <<<ugh Bilal bailed on the gym again third time this month. at least Sara sorted my brakes for free, she really knows engines>>>
Example output: {"interaction_summary":"Bilal skipped the gym again; Sara fixed my brakes for free.","mentioned":[{"name":"Bilal","tags":["flaked_today","unreliable_plans","gym_buddy"]},{"name":"Sara","tags":["car_expert","generous","high_trust"]}]}
```

User turn: `Said: <<<{sanitized rant}>>>\nJSON:`

Retry suffix: `\nYour previous answer was not valid JSON. Output only the JSON object now, starting with {"interaction_summary".`

Tuning: temperature 0.2, topK 10, `maxOutputTokens` 384, input capped at ~2,000 chars.

Why it's built this way (from the skill's rules for nano-v3):
- `interaction_summary` comes **first** and is short. If the token limit cuts the output off, it cuts into the tail of the `mentioned` list, and the repair step in §3.3 recovers the people that came before the cut.
- The "nobody mentioned" example comes first, because small models copy their examples. Without it, nano-v3 invents a person for every rant.
- The two examples come from different domains (gym, car), so the model doesn't copy one domain's tags.
- The prompt never says "CRM", "network" or "business", because the model would echo that framing ("networking_opportunity").

### 3.2 "Who can help" (system instruction)

```text
You help the user find who in their own private network can help with something. Between <<< and >>> under People are people from the user's address book, one per line:
id | name | tags, each with (times noted, days since last noted)
Evaluate the query against this local network context. Return the top matches with a 1-sentence specific reason based on their tags.
Rules:
- Use only the people listed and only what their tags say. Never invent people, facts or ids.
- At most 3 matches, best first. A tag noted more times counts more. A recent negative tag (flaked_today, unreliable) counts against someone.
- reason: one sentence, at most 20 words, that names the tags it relies on.
- If nobody fits, output {"matches":[]}.
- Output ONLY one JSON object in exactly this shape. Stop right after the closing brace.
{"matches":[{"id":"P1","reason":"..."}]}
Example query: who can help me fix my car
Example people:
P4 | Sara | car_expert (3, 2d), high_trust (1, 30d)
P9 | Bilal | gym_buddy (5, 1d), flaked_today (2, 1d)
Example output: {"matches":[{"id":"P4","reason":"Tagged car_expert three times and high_trust, so she can likely sort out your car."}]}
```

User turn: `People: <<<{lines}>>>\nQuery: <<<{sanitized query}>>>\nJSON:`

Retry suffix: `\nYour previous answer was not valid JSON. Output only {"matches":[...]} now.`

Tuning: temperature 0.1, `maxOutputTokens` 256. Candidates use the **short ids** `P<rowIndex>` (P1…P15), not
database ids or names. Those are short to emit, can't be confused with each other, and are mapped back
locally, so an id that wasn't in the shortlist is simply dropped (hallucination guard).

### 3.3 Lenient parsing (pure functions, unit-tested)

```kotlin
object CrmParsers {
    data class Mention(val name: String, val tags: List<String>)
    data class Extraction(val summary: String, val mentioned: List<Mention>)
    data class Match(val shortId: String, val reason: String)

    fun parseExtraction(raw: String): Extraction? {
        val obj = JsonRepair.firstObject(raw) ?: return null       // strips ``` fences and prose, closes truncated JSON,
                                                                    // then cuts back to the last comma, up to 3 tries
        val f = obj.normalizedKeys()                                // lowercase, alphanumerics only
        val summary = f.string("interactionsummary", "summary", "interaction").orEmpty().take(200)
        val people = f.array("mentioned", "mentions", "people", "persons").mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> Mention(el.content, emptyList())          // ["Ali", "Sara"]
                is JsonObject -> {
                    val p = el.normalizedKeys()
                    val name = p.string("name", "person", "who") ?: return@mapNotNull null
                    Mention(name, p.stringList("tags", "tag", "traits", "labels")) // a list, or one "a, b; c" string
                }
                else -> null
            }
        }.mapNotNull(::cleanMention)
        if (summary.isEmpty() && people.isEmpty()) return null
        return Extraction(summary, people.distinctBy { NameKey.of(it.name) })
    }

    private val SELF = setOf("i", "me", "my", "myself", "user", "the user", "you", "we", "us")

    private fun cleanMention(m: Mention): Mention? {
        val name = m.name.trim().trim('"', '\'', '.', ',').replace(Regex("""\s+"""), " ").take(60)
        if (name.isEmpty() || name.lowercase() in SELF) return null
        return Mention(name, m.tags.mapNotNull(TagScoring::normalize).distinct().take(MAX_TAGS_PER_MENTION)) // 6
    }

    fun parseMatches(raw: String, allowedIds: Set<String>): List<Match>? { /* same pattern; drop ids not in allowedIds */ }
}
```

Every bad output that turns up in testing on a real device becomes a test case in `src/test/.../CrmParsersTest.kt`.

---

## 4. Repository and merge logic

### 4.1 Ingestion pipeline

```kotlin
@Singleton
class PeopleRepositoryImpl @Inject constructor(
    private val db: BrainDumpDatabase,
    private val people: PersonDao,
    private val logs: InteractionLogDao,
    private val edges: ConnectionDao,
    private val captures: CaptureDao,
    private val ai: AICoreManager,
    private val embedder: TextEmbeddingEngine,
    private val index: PersonVectorIndex,
) : PeopleRepository {

    private val drainMutex = Mutex()

    /** Save first, then process right away while the capture UI is still in the foreground. */
    override suspend fun ingest(rawText: String): IngestOutcome {
        val text = rawText.trim().ifEmpty { return IngestOutcome.Empty }
        val now = System.currentTimeMillis()
        val id = captures.insert(CaptureEntity(rawText = text, createdAt = now, updatedAt = now))
        drainPending()
        val c = captures.get(id) ?: return IngestOutcome.Empty
        return when (c.status) {
            CaptureStatus.DONE -> IngestOutcome.Saved(c.summary.orEmpty(), c.mentionCount)
            CaptureStatus.PENDING -> IngestOutcome.Queued(c.lastError)
            CaptureStatus.FAILED -> IngestOutcome.Failed(c.lastError)
        }
    }

    /** Called from ingest() and from MainActivity ON_START. Same queue semantics as IdeaRepositoryImpl.drain(). */
    override suspend fun drainPending() = drainMutex.withLock {
        val handled = HashSet<Long>()
        while (true) {
            val batch = captures.pendingIds().filterNot { it in handled }.ifEmpty { return@withLock }
            for (id in batch) {
                handled += id
                val c = captures.get(id)?.takeIf { it.status == CaptureStatus.PENDING } ?: continue
                try {
                    val extraction = ai.extractMentions(c.rawText)          // Nano, outside any DB transaction
                    val written = applyExtraction(c, extraction)
                    written.forEach { (pid, v) -> if (v != null) index.put(pid, v) }   // after commit
                } catch (e: SplitException.Deferred) {
                    captures.update(c.copy(lastError = e.message, updatedAt = System.currentTimeMillis()))
                    return@withLock                                           // backgrounded or busy: stop the whole drain
                } catch (e: SplitException) {
                    val attempts = c.attempts + 1
                    val giveUp = !e.retryable || attempts >= MAX_ATTEMPTS     // 3
                    captures.update(c.copy(
                        status = if (giveUp) CaptureStatus.FAILED else CaptureStatus.PENDING,
                        attempts = attempts, lastError = e.message, updatedAt = System.currentTimeMillis()))
                }
            }
        }
    }
```

### 4.2 The merge: one transaction per rant

```kotlin
    /** Returns personId -> new vector (null when the embedder is unavailable). */
    private suspend fun applyExtraction(c: CaptureEntity, x: CrmParsers.Extraction): Map<Long, FloatArray?> =
        db.withTransaction {
            val at = c.createdAt                     // the time the user spoke, so a queued rant isn't post-dated
            val touched = LinkedHashMap<Long, FloatArray?>()

            for (m in x.mentioned) {
                val existing = resolvePerson(m.name)                         // §4.3
                val base = existing ?: PersonEntity(
                    name = m.name, nameKey = NameKey.of(m.name),
                    createdAt = at, updatedAt = at, lastInteractionAt = at,
                )

                // Shallow merge: new keys are added, existing keys get count+1 and lastAt=max. Nothing is ever removed here.
                val mergedTags = TagScoring.merge(base.dynamicTags, m.tags, at)
                val merged = base.copy(
                    name = longerName(base.name, m.name),                    // "Ali" -> "Ali Khan", never the reverse
                    // Every key this person has gone by, minus the current one (set below), so "Ali" still resolves after the upgrade.
                    aliases = base.aliases.withAliases(NameKey.of(m.name), base.nameKey, except = NameKey.of(longerName(base.name, m.name))),
                    dynamicTags = mergedTags,
                    updatedAt = System.currentTimeMillis(),
                    lastInteractionAt = maxOf(base.lastInteractionAt, at),
                )

                // Fresh vector on every tag change. ~5 ms with USE. A null vector leaves the row flagged for re-embedding.
                val vector = embedder.embed(profileText(merged, at), asQuery = false)
                val row = merged.copy(
                    embedding = vector ?: base.embedding,
                    embeddingModel = if (vector != null) embedder.modelId else null,
                    nameKey = NameKey.of(merged.name),
                )

                val pid = if (existing == null) people.insert(row) else row.id.also { people.update(row) }
                if (pid in touched) continue                                 // two names resolved to one person

                logs.insert(InteractionLogEntity(
                    personId = pid, captureId = c.id, timestamp = at,
                    rawText = c.rawText, nanoSummary = x.summary.ifEmpty { null },
                    tagsAdded = StringSet(m.tags.toSet()),
                ))
                touched[pid] = vector
            }

            upsertCoMentions(touched.keys.toList(), at)                      // D7

            captures.update(c.copy(
                status = CaptureStatus.DONE, summary = x.summary, mentionCount = touched.size,
                lastError = null, updatedAt = System.currentTimeMillis(),
            ))
            touched
        }

    private suspend fun upsertCoMentions(ids: List<Long>, at: Long) {
        if (ids.size < 2 || ids.size > MAX_CLIQUE) return                   // MAX_CLIQUE = 8: a 20-name rant isn't 190 friendships
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = minOf(ids[i], ids[j]); val b = maxOf(ids[i], ids[j])
            val e = edges.get(a, b)
            if (e == null) edges.insert(ConnectionEntity(personA = a, personB = b, relation = Relation(1, at)))
            else edges.update(e.copy(relation = e.relation.copy(coMentions = e.relation.coMentions + 1, lastAt = maxOf(e.relation.lastAt, at))))
        }
    }
```

Tag rules:

```kotlin
object TagScoring {
    private const val HALF_LIFE_DAYS = 45.0
    private const val MAX_TAGS = 40

    /** "Car Expert!" -> "car_expert"; drops junk ("person", "friend", "male") and anything over 3 words or 32 chars. */
    fun normalize(raw: String): String? {
        val t = raw.lowercase().replace(Regex("""[^a-z0-9]+"""), "_").trim('_')
        if (t.length !in 2..32 || t.count { it == '_' } > 2 || t in STOP_TAGS) return null
        return t
    }

    fun merge(current: TagMap, incoming: List<String>, at: Long): TagMap {
        val out = current.tags.toMutableMap()
        for (tag in incoming) {
            val s = out[tag]
            out[tag] = if (s == null) TagStat(1, at, at) else s.copy(count = s.count + 1, lastAt = maxOf(s.lastAt, at))
        }
        // Cap: evict the weakest AI tags. User-added tags are never evicted.
        if (out.size > MAX_TAGS) {
            out.entries.filter { !it.value.byUser }.sortedBy { score(it.value, at) }
                .take(out.size - MAX_TAGS).forEach { out.remove(it.key) }
        }
        return TagMap(out)
    }

    /** count × exp-decay on recency: "flaked_today" once, 90 days ago, scores ~0.25. */
    fun score(s: TagStat, now: Long): Double =
        s.count * Math.pow(0.5, (now - s.lastAt) / 86_400_000.0 / HALF_LIFE_DAYS)

    fun ranked(t: TagMap, now: Long): List<String> =
        t.tags.entries.sortedByDescending { score(it.value, now) }.map { it.key }
}
```

The merge deliberately doesn't try to resolve contradictions (`high_trust` and later `broke_trust`). Both stay, each
with its count and timestamps, and the recency decay plus the "who can help" prompt weigh them. Tags the user edits
by hand are set with `byUser = true`. For "the AI keeps re-adding a tag I deleted", I'd add a `suppressed` set to the
person later, not now.

### 4.3 Name resolution (`resolvePerson`)

The hard part of a voice CRM is deciding that "Ali", "ali khan" and "Ali K." are one person. The rules are deterministic and conservative:

1. `NameKey.of(name)`: NFKD, strip diacritics, lowercase, keep letters, digits and spaces, collapse whitespace.
2. **Exact key or alias match.** If exactly 1 person matches, use them. If several do, pick the one with the latest `last_interaction_at`.
3. **First-name match** (only when step 2 found nothing), in either direction:
   - the mention has one word ("Ali"): match people whose key starts with `ali ` or equals `ali`;
   - the mention has several words ("Ali Khan"): match a person whose key is exactly the first word (`ali`), so that short entry gets upgraded.
   Use the match **only if it is unique.** Two people named Ali → **create a new person.** A duplicate can be merged later; a wrong merge mixes two people's data.
4. Otherwise create a new person.
5. Relationship names ("Mom", "Boss") go through the same rules and work naturally as keys.

`mergePeople(keepId, dropId)` (for the later UI): in one transaction, re-point the logs, merge the tag maps by summing counts,
re-point the edges (combining on a unique-index clash), union the aliases, delete `dropId`, then re-embed.

### 4.4 "Who can help" (local RAG)

```kotlin
    override suspend fun askNetwork(query: String): AskOutcome {
        val q = query.trim().take(300).ifEmpty { return AskOutcome.Empty }
        val qv = embedder.embed(q, asQuery = true)
            ?: return AskOutcome.Unavailable("The on-device search model didn't load")

        // 1. Retrieve: cosine top 40, then rerank with a small lexical boost so exact tag words count.
        val now = System.currentTimeMillis()
        val qTokens = q.lowercase().split(Regex("""\W+""")).filter { it.length > 2 }.toSet()
        val hits = index.search(qv, 40)
        val rows = people.byIds(hits.map { it.first }).associateBy { it.id }
        val shortlist = hits.mapNotNull { (id, cos) ->
            val p = rows[id] ?: return@mapNotNull null
            val lexical = p.dynamicTags.tags.keys.count { tag -> tag.split('_').any { it in qTokens } }
            p to (cos + 0.05f * minOf(lexical, 3))
        }.sortedByDescending { it.second }.take(SHORTLIST)              // SHORTLIST = 12; fits Nano's input with room to spare

        if (shortlist.isEmpty()) return AskOutcome.NoOne

        // 2. Reason: Nano picks and explains. Short ids P1..Pn map back locally.
        val byShort = shortlist.mapIndexed { i, (p, _) -> "P${i + 1}" to p }.toMap()
        val lines = byShort.entries.joinToString("\n") { (sid, p) ->
            val tags = TagScoring.ranked(p.dynamicTags, now).take(8).joinToString(", ") { t ->
                val s = p.dynamicTags.tags.getValue(t); "$t (${s.count}, ${daysAgo(s.lastAt, now)}d)"
            }
            "$sid | ${p.name} | $tags"
        }.take(2_400)

        return try {
            val matches = ai.rankCandidates(q, lines, byShort.keys)
            AskOutcome.Matches(matches.map { m -> PersonMatch(byShort.getValue(m.shortId).id, byShort.getValue(m.shortId).name, m.reason, fromAi = true) })
        } catch (e: SplitException) {
            // Nano unavailable or garbled: still answer, with the top 3 by retrieval and a reason built locally from the matching tags.
            AskOutcome.Matches(shortlist.take(3).map { (p, _) -> PersonMatch(p.id, p.name, localReason(p, qTokens, now), fromAi = false) }, degraded = e.message)
        }
    }
```

`askNetwork` needs the app in the foreground, which it always is, since the user is typing in the bottom sheet.
Retrieval alone runs anywhere and in under a millisecond.

---

## 5. Threading, failure and privacy summary

| Concern | How it's handled |
|---|---|
| Nano only in the foreground | Capture always goes through an activity (D3). The queue drains on `ON_START`. `Deferred` stops the drain without counting an attempt. |
| Nano unavailable on the device | Rants are still saved. People can still be added by hand (later UI). Search falls back to retrieval with locally built reasons. |
| Embedder asset missing | `embed()` returns null and the rows are flagged (`embedding_model IS NULL`). `needingEmbedding()` backfills them later. |
| Model swap | A different `modelId` → background `WorkManager` re-embed in batches of 50. The embedder doesn't need the foreground. |
| Concurrency | Nano is serialized by the existing `AICoreManager` mutex. One drain at a time. One Room transaction per rant. The vector index is updated only after commit. |
| Data loss | Migration 4→5 only adds tables. The raw text is kept in both `captures` and `interaction_logs`. |
| Privacy | No `INTERNET` permission. All models are on the device. Vectors never leave the DB. |

## 6. Files this will add or change (once approved)

```
data/local/crm/{PersonEntity, InteractionLogEntity, ConnectionEntity, CaptureEntity, CrmConverters, *Dao}.kt
data/local/BrainDumpDatabase.kt      version 5, new entities, MIGRATION_4_5, exportSchema = true
data/ai/embedding/{TextEmbeddingEngine, MediaPipeTextEmbeddingEngine, PersonVectorIndex}.kt
data/ai/CrmPrompts.kt                §3.1 / §3.2 text
data/ai/CrmParsers.kt, JsonRepair.kt pure parsers
data/ai/AICoreManager.kt             + extractMentions(), rankCandidates() on top of the existing infer()
data/crm/{TagScoring, NameKey}.kt
data/repository/PeopleRepositoryImpl.kt, domain/repository/PeopleRepository.kt
di/AppModule.kt                      migration, DAOs, embedder binding
gradle/libs.versions.toml, app/build.gradle.kts, proguard-rules.pro, CI model download step
app/src/test/...                     CrmParsersTest, TagScoringTest, NameKeyTest (+ test deps; there's no src/test yet)
```
