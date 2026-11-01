package dev.braindump.splitter.domain.repository

import dev.braindump.splitter.domain.model.AiStatus
import dev.braindump.splitter.domain.model.CaptureOutcome
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaCategory
import dev.braindump.splitter.domain.model.Persona
import dev.braindump.splitter.domain.model.RefineOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IdeaRepository {
    val aiStatus: StateFlow<AiStatus>

    /** All ideas, newest first. */
    fun observeIdeas(): Flow<List<Idea>>

    /**
     * Persists the raw dump, then has Gemini Nano polish it into a note right away while the app is in the foreground.
     * Gemini Nano refuses background requests, so anything left over stays queued for [splitPending].
     */
    suspend fun captureAndSplit(rawInput: String, image: android.graphics.Bitmap? = null): CaptureOutcome

    suspend fun retry(ideaId: Long)

    /**
     * Asks Gemini Nano to change a polished note ("make it shorter", "add a section on X") and
     * saves the result along with the exchange. Call while the app is in the foreground.
     */
    suspend fun refine(ideaId: Long, ask: String): RefineOutcome

    /** Overrides the category Gemini Nano picked. */
    suspend fun setCategory(ideaId: Long, category: IdeaCategory)

    suspend fun delete(ideaId: Long)

    /** Splits everything still queued. Call while the app is in the foreground. */
    suspend fun splitPending()

    /** Checks model availability, downloads it if needed and warms it up. */
    suspend fun warmUpAi()

    /** Whether to try the AICore Developer Preview build of Gemini Nano first. */
    val usePreviewModel: StateFlow<Boolean>

    /** Saves the choice, re-selects the model and splits anything that was waiting. */
    suspend fun setUsePreviewModel(enabled: Boolean)

    /** How the user writes, learned from their dumps; null until there are enough of them. */
    val persona: StateFlow<Persona?>

    /** Whether new notes and refines are written in the [persona]'s style. */
    val personaEnabled: StateFlow<Boolean>

    /** True while Gemini Nano is reading the dumps. */
    val personaBuilding: StateFlow<Boolean>

    fun setPersonaEnabled(enabled: Boolean)

    /** Drops the learned persona and stops learning it until [refreshPersona] is forced. */
    fun forgetPersona()

    /**
     * Re-learns the persona when it's missing or [Persona.REFRESH_EVERY] dumps old, or always
     * with [force]. Returns why it couldn't, or null. Call while the app is in the foreground.
     */
    suspend fun refreshPersona(force: Boolean = false): String?
}
