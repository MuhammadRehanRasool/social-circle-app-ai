package dev.sangatai.app.data.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every knob of the social graph, all user-editable. Nothing here is hidden or remote: defaults
 * live in this file and [CrmPrompts], and the current values are part of every export.
 */
@Serializable
data class CrmTuning(
    /** Null means "use the built-in prompt", so it follows future improvements to it. */
    val ingestPromptOverride: String? = null,
    val askPromptOverride: String? = null,

    // Gemini Nano, ingestion: low temperature keeps the JSON shape.
    val ingestTemperature: Float = 0.2f,
    val ingestTopK: Int = 10,
    val ingestMaxOutputTokens: Int = 384,

    // Gemini Nano, "who can help".
    val askTemperature: Float = 0.1f,
    val askTopK: Int = 10,
    val askMaxOutputTokens: Int = 256,

    /** Tries per model build, the second with the rules moved into the user turn. */
    val attempts: Int = 2,
    val maxRantChars: Int = 2_000,
    val maxQueryChars: Int = 300,

    // Tags
    val maxTagsPerMention: Int = 6,
    val maxTagsPerPerson: Int = 40,
    /** A tag's weight halves every this many days since it was last noted. 0 = never fades. */
    val tagHalfLifeDays: Float = 45f,

    // Embeddings and retrieval
    /** How many of a person's strongest tags go into their embedding. */
    val embedTags: Int = 16,
    /** Nearest people by cosine before reranking. */
    val retrievalPool: Int = 40,
    /** Candidates handed to Gemini Nano. */
    val shortlistSize: Int = 12,
    /** Added to the cosine score per tag word that also appears in the query (up to 3). */
    val lexicalBoost: Float = 0.05f,
    /** Candidates below this cosine are dropped. */
    val minSimilarity: Float = 0.0f,
    val maxMatches: Int = 3,
    /** Tags shown per candidate in the prompt, and the cap on the whole block. */
    val candidateTags: Int = 8,
    val candidateBlockChars: Int = 2_400,

    // Graph
    /** Rants naming more people than this don't create connections. */
    val maxCoMentionClique: Int = 8,
) {
    val ingestPrompt: String get() = ingestPromptOverride?.takeIf { it.isNotBlank() } ?: CrmPrompts.INGEST
    val askPrompt: String get() = askPromptOverride?.takeIf { it.isNotBlank() } ?: CrmPrompts.ASK

    /** Clamped to what still works; wide on purpose, the user owns the trade-off. */
    fun sanitized(): CrmTuning = copy(
        ingestPromptOverride = ingestPromptOverride?.trim()?.takeIf { it.isNotEmpty() && it != CrmPrompts.INGEST },
        askPromptOverride = askPromptOverride?.trim()?.takeIf { it.isNotEmpty() && it != CrmPrompts.ASK },
        ingestTemperature = ingestTemperature.coerceIn(0f, 2f),
        ingestTopK = ingestTopK.coerceIn(1, 128),
        ingestMaxOutputTokens = ingestMaxOutputTokens.coerceIn(64, 4_096),
        askTemperature = askTemperature.coerceIn(0f, 2f),
        askTopK = askTopK.coerceIn(1, 128),
        askMaxOutputTokens = askMaxOutputTokens.coerceIn(64, 4_096),
        attempts = attempts.coerceIn(1, 5),
        maxRantChars = maxRantChars.coerceIn(100, 32_000),
        maxQueryChars = maxQueryChars.coerceIn(20, 4_000),
        maxTagsPerMention = maxTagsPerMention.coerceIn(1, 20),
        maxTagsPerPerson = maxTagsPerPerson.coerceIn(5, 500),
        tagHalfLifeDays = tagHalfLifeDays.coerceIn(0f, 3_650f),
        embedTags = embedTags.coerceIn(1, 100),
        retrievalPool = retrievalPool.coerceIn(1, 500),
        shortlistSize = shortlistSize.coerceIn(1, 50),
        lexicalBoost = lexicalBoost.coerceIn(0f, 1f),
        minSimilarity = minSimilarity.coerceIn(-1f, 1f),
        maxMatches = maxMatches.coerceIn(1, 10),
        candidateTags = candidateTags.coerceIn(1, 40),
        candidateBlockChars = candidateBlockChars.coerceIn(200, 16_000),
        maxCoMentionClique = maxCoMentionClique.coerceIn(2, 100),
    )
}

@Singleton
class CrmSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _tuning = MutableStateFlow(read())
    val tuning: StateFlow<CrmTuning> = _tuning.asStateFlow()

    fun set(value: CrmTuning) {
        val clean = value.sanitized()
        prefs.edit().putString(KEY_TUNING, json.encodeToString(CrmTuning.serializer(), clean)).apply()
        _tuning.value = clean
    }

    fun reset() {
        prefs.edit().remove(KEY_TUNING).apply()
        _tuning.value = CrmTuning()
    }

    private fun read(): CrmTuning =
        prefs.getString(KEY_TUNING, null)
            ?.let { runCatching { json.decodeFromString(CrmTuning.serializer(), it) }.getOrNull() }
            ?.sanitized()
            ?: CrmTuning()

    companion object {
        const val PREFS_NAME = "crm_settings"
        private const val KEY_TUNING = "tuning"
    }
}
