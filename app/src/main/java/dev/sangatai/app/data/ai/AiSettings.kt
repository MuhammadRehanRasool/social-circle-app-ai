package dev.sangatai.app.data.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which Gemini Nano build to prefer, persisted across launches. Everything else the model is
 * told lives in [CrmTuning].
 */
@Singleton
class AiSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Note prompts and limits from the note-taking days: nothing reads them any more.
        val stale = prefs.all.keys - KEY_USE_PREVIEW
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
        // The writing persona lived in its own file.
        context.deleteSharedPreferences(LEGACY_PERSONA_PREFS)
    }

    private val _usePreviewModel = MutableStateFlow(prefs.getBoolean(KEY_USE_PREVIEW, true))

    /** Try the AICore Developer Preview build of Gemini Nano before the stable one. */
    val usePreviewModel: StateFlow<Boolean> = _usePreviewModel.asStateFlow()

    fun setUsePreviewModel(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_PREVIEW, enabled).apply()
        _usePreviewModel.value = enabled
    }

    /**
     * Back to defaults. Also removes keys older versions stored here (note prompts and limits),
     * so nothing from them lingers on the phone.
     */
    fun clear() {
        prefs.edit().clear().apply()
        _usePreviewModel.value = true
    }

    companion object {
        const val PREFS_NAME = "ai_settings"
        private const val KEY_USE_PREVIEW = "use_preview_model"
        private const val LEGACY_PERSONA_PREFS = "persona"
    }
}
