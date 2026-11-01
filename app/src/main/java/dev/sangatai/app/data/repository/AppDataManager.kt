package dev.sangatai.app.data.repository

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sangatai.app.data.ai.AICoreManager
import dev.sangatai.app.data.ai.AiSettings
import dev.sangatai.app.data.ai.AiTuning
import dev.sangatai.app.data.ai.CrmSettings
import dev.sangatai.app.data.ai.CrmTuning
import dev.sangatai.app.data.ai.PersonaStore
import dev.sangatai.app.data.ai.embedding.PersonVectorIndex
import dev.sangatai.app.data.ai.embedding.TextEmbeddingEngine
import dev.sangatai.app.data.local.SangatDatabase
import dev.sangatai.app.data.local.crm.CrmJson
import dev.sangatai.app.domain.model.Persona
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One file the app keeps on the phone, for the "what's stored" view. */
data class StoredFile(
    /** Relative to the app's private data folder, e.g. "databases/sangatai.db". */
    val path: String,
    val bytes: Long,
    /** Who writes it: this app, or a library it uses. */
    val owner: String,
)

enum class WipeScope {
    /** People, rants, interactions and connections. Notes and settings stay. */
    SOCIAL_GRAPH,

    /** Everything this app stores: the whole database, every setting, the persona, caches. */
    EVERYTHING,
}

/**
 * The user's control over their data: export all of it, see every file it lives in, and delete
 * it. Exports go only where the user points them (a file they pick); nothing is sent anywhere.
 */
@Singleton
class AppDataManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: SangatDatabase,
    private val ai: AICoreManager,
    private val aiSettings: AiSettings,
    private val personaStore: PersonaStore,
    private val crmSettings: CrmSettings,
    private val index: PersonVectorIndex,
    private val embedder: TextEmbeddingEngine,
) {
    private val pretty = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Writes everything the app knows as one JSON document: people (with tag history, aliases and
     * removed tags), every rant, interaction and connection, the notes, the persona, and every
     * setting including edited prompts. Vectors are left out unless [includeVectors]; they can
     * always be rebuilt from the tags. [out] is flushed, not closed.
     */
    suspend fun exportJson(out: OutputStream, includeVectors: Boolean = false) = withContext(Dispatchers.IO) {
        // One consistent snapshot of every table.
        val snapshot = db.withTransaction {
            Snapshot(
                people = db.personDao().all(),
                captures = db.captureDao().all(),
                interactions = db.interactionLogDao().all(),
                connections = db.connectionDao().all(),
                notes = db.ideaDao().all(),
            )
        }
        val document = run {
            buildJsonObject {
                put("format", "sangatai-export")
                put("formatVersion", EXPORT_VERSION)
                put("databaseVersion", DATABASE_VERSION)
                put("exportedAt", System.currentTimeMillis())
                put("embeddingModel", embedder.modelId)

                put("settings", buildJsonObject {
                    put("socialGraph", pretty.encodeToJsonElement(CrmTuning.serializer(), crmSettings.tuning.value))
                    put("notes", aiTuningJson(aiSettings.tuning.value))
                    put("usePreviewModel", aiSettings.usePreviewModel.value)
                    put("personaEnabled", personaStore.enabled.value)
                })
                put(
                    "persona",
                    personaStore.persona.value?.let { pretty.encodeToJsonElement(Persona.serializer(), it) } ?: JsonNull,
                )

                put("people", buildJsonArray {
                    for (p in snapshot.people) add(buildJsonObject {
                        put("id", p.id)
                        put("name", p.name)
                        put("nameKey", p.nameKey)
                        put("aliases", strings(p.aliases.values))
                        put("tags", CrmJson.json.parseToJsonElement(CrmJson.encodeTags(p.dynamicTags)))
                        put("suppressedTags", strings(p.suppressedTags.values))
                        put("nameLocked", p.nameLocked)
                        put("createdByUser", p.createdByUser)
                        put("createdAt", p.createdAt)
                        put("updatedAt", p.updatedAt)
                        put("lastInteractionAt", p.lastInteractionAt)
                        put("embeddingModel", p.embeddingModel)
                        if (includeVectors) {
                            put("embedding", p.embedding?.let { v -> JsonArray(v.map { JsonPrimitive(it) }) } ?: JsonNull)
                        }
                    })
                })

                put("captures", buildJsonArray {
                    for (c in snapshot.captures) add(buildJsonObject {
                        put("id", c.id)
                        put("rawText", c.rawText)
                        put("status", c.status.name)
                        put("attempts", c.attempts)
                        put("lastError", c.lastError)
                        put("summary", c.summary)
                        put("mentionCount", c.mentionCount)
                        put("createdAt", c.createdAt)
                        put("updatedAt", c.updatedAt)
                    })
                })

                put("interactions", buildJsonArray {
                    for (l in snapshot.interactions) add(buildJsonObject {
                        put("id", l.id)
                        put("personId", l.personId)
                        put("captureId", l.captureId)
                        put("timestamp", l.timestamp)
                        put("rawText", l.rawText)
                        put("nanoSummary", l.nanoSummary)
                        put("tagsAdded", strings(l.tagsAdded.values))
                    })
                })

                put("connections", buildJsonArray {
                    for (e in snapshot.connections) add(buildJsonObject {
                        put("edgeId", e.edgeId)
                        put("personA", e.personA)
                        put("personB", e.personB)
                        put("coMentions", e.relation.coMentions)
                        put("lastAt", e.relation.lastAt)
                        put("labels", strings(e.relation.labels))
                    })
                })

                put("notes", buildJsonArray {
                    for (i in snapshot.notes) add(buildJsonObject {
                        put("id", i.id)
                        put("rawInput", i.rawInput)
                        put("title", i.ideaTitle)
                        put("body", i.body)
                        put("category", i.category)
                        put("status", i.status.name)
                        put("attempts", i.attempts)
                        put("lastError", i.lastError)
                        put("createdAt", i.createdAt)
                        put("updatedAt", i.updatedAt)
                        put("refinements", i.refinements?.let { r -> runCatching { Json.parseToJsonElement(r) }.getOrNull() } ?: JsonNull)
                    })
                })
            }
        }
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.write(pretty.encodeToString(JsonObject.serializer(), document))
        writer.flush()
    }

    /**
     * Every file in the app's private storage, with its size and owner, including what the
     * libraries write. Nothing is left out, so the user can see there's nothing hidden.
     */
    suspend fun inventory(): List<StoredFile> = withContext(Dispatchers.IO) {
        val root = context.dataDir
        root.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val path = file.relativeTo(root).path.replace(File.separatorChar, '/')
                StoredFile(path = path, bytes = file.length(), owner = ownerOf(path))
            }
            .sortedBy { it.path }
            .toList()
    }

    /**
     * Deletes data for good. Rows are overwritten in the database file (secure_delete), the
     * write-ahead log is truncated and the file is compacted, so deleted text doesn't linger.
     */
    suspend fun wipe(scope: WipeScope) = withContext(Dispatchers.IO) {
        db.withTransaction {
            // Interactions and connections cascade from people.
            db.personDao().deleteAll()
            db.captureDao().deleteAll()
            if (scope == WipeScope.EVERYTHING) db.ideaDao().deleteAll()
        }
        index.invalidate()
        ai.clearTrace()

        if (scope == WipeScope.EVERYTHING) {
            crmSettings.reset()
            aiSettings.setTuning(AiTuning())
            aiSettings.setUsePreviewModel(true)
            personaStore.clear()
            personaStore.setEnabled(true)
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            // Usage metrics ML Kit queues for upload. The app has no network access, so they can't
            // be sent; they're deleted anyway so nothing is left behind.
            runCatching { context.deleteDatabase(DATATRANSPORT_DB) }
        }
        compact()
    }

    /** Best effort: secure_delete already zeroed the rows; this also shrinks the file and clears the WAL. */
    private fun compact() {
        runCatching {
            val sqlite = db.openHelper.writableDatabase
            sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            sqlite.execSQL("VACUUM")
            sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
        }
    }

    private class Snapshot(
        val people: List<dev.sangatai.app.data.local.crm.PersonEntity>,
        val captures: List<dev.sangatai.app.data.local.crm.CaptureEntity>,
        val interactions: List<dev.sangatai.app.data.local.crm.InteractionLogEntity>,
        val connections: List<dev.sangatai.app.data.local.crm.ConnectionEntity>,
        val notes: List<dev.sangatai.app.data.local.IdeaEntity>,
    )

    private fun strings(values: Collection<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

    private fun aiTuningJson(t: AiTuning): JsonElement = buildJsonObject {
        put("notePrompt", t.notePrompt)
        put("refinePrompt", t.refinePrompt)
        put("maxOutputTokens", t.maxOutputTokens)
        put("temperature", t.temperature)
        put("topK", t.topK)
        put("attempts", t.attempts)
        put("maxInputChars", t.maxInputChars)
        put("maxTitleChars", t.maxTitleChars)
        put("maxAskChars", t.maxAskChars)
        put("maxRefineDumpChars", t.maxRefineDumpChars)
    }

    private fun ownerOf(path: String): String = when {
        path.startsWith("databases/${SangatDatabase.NAME}") -> "SangatAI: notes and social graph"
        path.startsWith("databases/$DATATRANSPORT_DB") -> "ML Kit / MediaPipe: queued usage metrics (never sent, no network access)"
        path.startsWith("shared_prefs/${CrmSettings.PREFS_NAME}") -> "SangatAI: social graph settings"
        path.startsWith("shared_prefs/ai_settings") -> "SangatAI: note settings"
        path.startsWith("shared_prefs/persona") -> "SangatAI: writing persona"
        path.startsWith("cache/") -> "Cache"
        path.startsWith("code_cache/") -> "Android runtime"
        path.startsWith("app_webview/") -> "WebView"
        else -> "Library or system"
    }

    private companion object {
        const val EXPORT_VERSION = 1
        const val DATABASE_VERSION = 5
        const val DATATRANSPORT_DB = "com.google.android.datatransport.events"
    }
}
