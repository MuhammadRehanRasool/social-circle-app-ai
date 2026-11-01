package dev.braindump.splitter.data.ai

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the user can tune about how a dump is polished or a note refined. */
data class AiTuning(
    val notePrompt: String = DefaultPrompts.NOTE,
    val refinePrompt: String = DefaultPrompts.REFINE,
    val maxOutputTokens: Int = 256,
    // Warm enough that the prose doesn't read flat, cool enough to keep the format.
    val temperature: Float = 0.4f,
    val topK: Int = 16,
    val attempts: Int = 2,
    val maxInputChars: Int = 4_000,
    val maxTitleChars: Int = 80,
    val maxAskChars: Int = 600,
    val maxRefineDumpChars: Int = 600,
)

/** User-facing model choices, persisted across launches. */
@Singleton
class AiSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _usePreviewModel = MutableStateFlow(prefs.getBoolean(KEY_USE_PREVIEW, true))

    /** Try the AICore Developer Preview build of Gemini Nano before the stable one. */
    val usePreviewModel: StateFlow<Boolean> = _usePreviewModel.asStateFlow()

    private val _tuning = MutableStateFlow(readTuning())
    val tuning: StateFlow<AiTuning> = _tuning.asStateFlow()

    fun setUsePreviewModel(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_PREVIEW, enabled).apply()
        _usePreviewModel.value = enabled
    }

    /** Blank prompts fall back to the defaults; numbers are clamped to what still works. */
    fun setTuning(value: AiTuning) {
        val clean = sanitized(value)
        val defaults = AiTuning()
        prefs.edit().apply {
            // A prompt equal to the default isn't stored, so it follows future default improvements.
            putOrRemove(KEY_NOTE_PROMPT, clean.notePrompt.takeIf { it != defaults.notePrompt })
            putOrRemove(KEY_REFINE_PROMPT, clean.refinePrompt.takeIf { it != defaults.refinePrompt })
            putInt(KEY_MAX_OUTPUT_TOKENS, clean.maxOutputTokens)
            putFloat(KEY_TEMPERATURE, clean.temperature)
            putInt(KEY_TOP_K, clean.topK)
            putInt(KEY_ATTEMPTS, clean.attempts)
            putInt(KEY_MAX_INPUT_CHARS, clean.maxInputChars)
            putInt(KEY_MAX_TITLE_CHARS, clean.maxTitleChars)
            putInt(KEY_MAX_ASK_CHARS, clean.maxAskChars)
            putInt(KEY_MAX_REFINE_DUMP_CHARS, clean.maxRefineDumpChars)
        }.apply()
        _tuning.value = clean
    }

    private fun readTuning(): AiTuning {
        val d = AiTuning()
        return sanitized(
            AiTuning(
                notePrompt = prefs.getString(KEY_NOTE_PROMPT, null) ?: d.notePrompt,
                refinePrompt = prefs.getString(KEY_REFINE_PROMPT, null) ?: d.refinePrompt,
                maxOutputTokens = prefs.getInt(KEY_MAX_OUTPUT_TOKENS, d.maxOutputTokens),
                temperature = prefs.getFloat(KEY_TEMPERATURE, d.temperature),
                topK = prefs.getInt(KEY_TOP_K, d.topK),
                attempts = prefs.getInt(KEY_ATTEMPTS, d.attempts),
                maxInputChars = prefs.getInt(KEY_MAX_INPUT_CHARS, d.maxInputChars),
                maxTitleChars = prefs.getInt(KEY_MAX_TITLE_CHARS, d.maxTitleChars),
                maxAskChars = prefs.getInt(KEY_MAX_ASK_CHARS, d.maxAskChars),
                maxRefineDumpChars = prefs.getInt(KEY_MAX_REFINE_DUMP_CHARS, d.maxRefineDumpChars),
            )
        )
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    companion object {
        /** Bounds the settings screen enforces; wide on purpose, the user owns the trade-off. */
        val OUTPUT_TOKENS = 16..4_096
        val TEMPERATURE = 0f..2f
        val TOP_K = 1..128
        val ATTEMPTS = 1..5
        val INPUT_CHARS = 100..32_000
        val TITLE_CHARS = 10..300
        val ASK_CHARS = 20..8_000

        fun sanitized(value: AiTuning): AiTuning =
            value.copy(
                notePrompt = value.notePrompt.trim().ifEmpty { DefaultPrompts.NOTE },
                refinePrompt = value.refinePrompt.trim().ifEmpty { DefaultPrompts.REFINE },
                maxOutputTokens = value.maxOutputTokens.coerceIn(OUTPUT_TOKENS),
                temperature = value.temperature.coerceIn(TEMPERATURE),
                topK = value.topK.coerceIn(TOP_K),
                attempts = value.attempts.coerceIn(ATTEMPTS),
                maxInputChars = value.maxInputChars.coerceIn(INPUT_CHARS),
                maxTitleChars = value.maxTitleChars.coerceIn(TITLE_CHARS),
                maxAskChars = value.maxAskChars.coerceIn(ASK_CHARS),
                maxRefineDumpChars = value.maxRefineDumpChars.coerceIn(ASK_CHARS),
            )

        private const val PREFS_NAME = "ai_settings"
        private const val KEY_USE_PREVIEW = "use_preview_model"
        private const val KEY_NOTE_PROMPT = "note_prompt"
        private const val KEY_REFINE_PROMPT = "refine_note_prompt"
        private const val KEY_MAX_OUTPUT_TOKENS = "max_output_tokens"
        private const val KEY_TEMPERATURE = "note_temperature"
        private const val KEY_TOP_K = "top_k"
        private const val KEY_ATTEMPTS = "attempts"
        private const val KEY_MAX_INPUT_CHARS = "max_input_chars"
        private const val KEY_MAX_TITLE_CHARS = "max_title_chars"
        private const val KEY_MAX_ASK_CHARS = "max_ask_chars"
        private const val KEY_MAX_REFINE_DUMP_CHARS = "max_refine_dump_chars"
    }
}
