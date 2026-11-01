package dev.braindump.splitter.data.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.braindump.splitter.domain.model.Persona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The learned [Persona] and whether notes are written in it, persisted across launches. */
@Singleton
class PersonaStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _persona = MutableStateFlow(readPersona())
    val persona: StateFlow<Persona?> = _persona.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))

    /** Whether new notes and refines are written in the persona's style. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** The persona to write in right now, or null when off or not learned yet. */
    val active: Persona? get() = _persona.value?.takeIf { _enabled.value }

    fun save(persona: Persona) {
        prefs.edit().putString(KEY_PERSONA, Json.encodeToString(Persona.serializer(), persona)).apply()
        _persona.value = persona
    }

    fun clear() {
        prefs.edit().remove(KEY_PERSONA).apply()
        _persona.value = null
    }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    private fun readPersona(): Persona? =
        prefs.getString(KEY_PERSONA, null)?.let {
            runCatching { Json.decodeFromString(Persona.serializer(), it) }.getOrNull()
        }

    private companion object {
        const val PREFS_NAME = "persona"
        const val KEY_PERSONA = "persona"
        const val KEY_ENABLED = "enabled"
    }
}
