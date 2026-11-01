package dev.braindump.splitter.data.repository

import dev.braindump.splitter.data.ai.AICoreManager
import dev.braindump.splitter.data.ai.AiSettings
import dev.braindump.splitter.data.ai.IdeaNote
import dev.braindump.splitter.data.ai.PersonaStore
import dev.braindump.splitter.data.ai.SplitException
import dev.braindump.splitter.data.local.IdeaDao
import dev.braindump.splitter.data.local.IdeaEntity
import dev.braindump.splitter.data.local.toDomain
import dev.braindump.splitter.data.local.withTurn
import dev.braindump.splitter.domain.model.AiStatus
import dev.braindump.splitter.domain.model.CaptureOutcome
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaCategory
import dev.braindump.splitter.domain.model.IdeaStatus
import dev.braindump.splitter.domain.model.Persona
import dev.braindump.splitter.domain.model.RefineOutcome
import dev.braindump.splitter.domain.model.RefineTurn
import dev.braindump.splitter.domain.repository.IdeaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdeaRepositoryImpl @Inject constructor(
    private val dao: IdeaDao,
    private val ai: AICoreManager,
    private val settings: AiSettings,
    private val personaStore: PersonaStore,
) : IdeaRepository {

    // One drain at a time; a caller that waits here sees everything queued before it.
    private val drainMutex = Mutex()
    private val personaMutex = Mutex()

    // Photos live in memory only until their note is written, then they're dropped.
    private val pendingImages = java.util.concurrent.ConcurrentHashMap<Long, android.graphics.Bitmap>()

    override val aiStatus: StateFlow<AiStatus> get() = ai.status

    override fun observeIdeas(): Flow<List<Idea>> =
        dao.observeAll().map { entities -> entities.map(IdeaEntity::toDomain) }

    override suspend fun captureAndSplit(rawInput: String, image: android.graphics.Bitmap?): CaptureOutcome {
        val text = rawInput.trim().ifEmpty { if (image != null) PHOTO_ONLY else "" }
        if (text.isEmpty()) return CaptureOutcome.Empty
        val now = System.currentTimeMillis()
        val id = dao.insert(IdeaEntity(rawInput = text, createdAt = now, updatedAt = now))
        image?.let { pendingImages[id] = it }

        splitPending()

        val idea = dao.getById(id) ?: return CaptureOutcome.Empty
        return when (idea.status) {
            IdeaStatus.READY -> CaptureOutcome.Split(idea.ideaTitle.orEmpty())
            IdeaStatus.PENDING -> CaptureOutcome.Queued(idea.lastError)
            IdeaStatus.FAILED -> CaptureOutcome.Failed(idea.lastError)
        }
    }

    override suspend fun retry(ideaId: Long) {
        if (dao.resetFailed(ideaId, System.currentTimeMillis()) > 0) splitPending()
    }

    override suspend fun refine(ideaId: Long, ask: String): RefineOutcome {
        val request = ask.trim()
        if (request.isEmpty()) return RefineOutcome.Failed("Say what to change")
        val idea = dao.getById(ideaId) ?: return RefineOutcome.Failed(DELETED)
        if (idea.status != IdeaStatus.READY) return RefineOutcome.Failed("Wait until the note is written")

        val before = idea.toNote()
        val refined = try {
            ai.refineIdea(before, idea.rawInput, request, personaStore.active)
        } catch (e: SplitException) {
            return RefineOutcome.Failed(e.message ?: "Gemini Nano couldn't refine this")
        }

        // Re-read: the category may have been changed while Gemini Nano was thinking.
        val latest = dao.getById(ideaId) ?: return RefineOutcome.Failed(DELETED)
        val summary = describeChanges(before, refined)
        val now = System.currentTimeMillis()
        dao.update(
            latest.copy(
                ideaTitle = refined.title,
                body = refined.body,
                category = refined.category.name,
                updatedAt = now,
            ).withTurn(RefineTurn(ask = request, reply = summary, at = now))
        )
        return RefineOutcome.Updated(summary)
    }

    override suspend fun setCategory(ideaId: Long, category: IdeaCategory) {
        dao.setCategory(ideaId, category.name, System.currentTimeMillis())
    }

    override suspend fun delete(ideaId: Long) {
        dao.deleteById(ideaId)
    }

    override suspend fun warmUpAi() {
        ai.warmUp()
    }

    override val usePreviewModel: StateFlow<Boolean> get() = settings.usePreviewModel

    override suspend fun setUsePreviewModel(enabled: Boolean) {
        if (settings.usePreviewModel.value == enabled) return
        settings.setUsePreviewModel(enabled)
        ai.resetModel()
        ai.warmUp()
        splitPending()
    }

    override suspend fun splitPending() {
        drainMutex.withLock { drain() }
    }

    override val persona: StateFlow<Persona?> get() = personaStore.persona

    override val personaEnabled: StateFlow<Boolean> get() = personaStore.enabled

    private val _personaBuilding = MutableStateFlow(false)
    override val personaBuilding: StateFlow<Boolean> = _personaBuilding.asStateFlow()

    override fun setPersonaEnabled(enabled: Boolean) {
        personaStore.setEnabled(enabled)
    }

    override fun forgetPersona() {
        // Off too, or the next foreground would quietly learn it all over again.
        personaStore.setEnabled(false)
        personaStore.clear()
    }

    override suspend fun refreshPersona(force: Boolean): String? {
        // One build at a time; a second tap or the foreground check just rides on the first.
        if (!personaMutex.tryLock()) return null
        try {
            val count = dao.countDumps(Persona.MIN_DUMP_CHARS)
            if (count < Persona.MIN_DUMPS) return if (force) "Dump a few more thoughts first" else null
            if (!force) {
                if (!personaStore.enabled.value) return null
                val current = personaStore.persona.value
                if (current != null && count - current.dumpCount < Persona.REFRESH_EVERY) return null
            }

            _personaBuilding.value = true
            val dumps = dao.recentDumps(Persona.MIN_DUMP_CHARS, PERSONA_SAMPLE_LIMIT)
            val persona = try {
                ai.describePersona(dumps)
            } catch (e: SplitException) {
                return e.message ?: "Gemini Nano couldn't read your style"
            }
            personaStore.save(persona.copy(dumpCount = count))
            // Asking for it by hand is opting back in after a Forget.
            if (force) personaStore.setEnabled(true)
            return null
        } finally {
            _personaBuilding.value = false
            personaMutex.unlock()
        }
    }

    private suspend fun drain() {
        val handled = HashSet<Long>()

        // Re-query after each pass so ideas captured mid-run are picked up too.
        while (true) {
            val batch = dao.pendingIds().filterNot { it in handled }
            if (batch.isEmpty()) return

            for (id in batch) {
                handled += id
                val idea = dao.getById(id) ?: continue
                if (idea.status != IdeaStatus.PENDING) continue

                try {
                    val note = ai.polishIdea(
                        rawIdea = idea.rawInput,
                        image = pendingImages[id],
                        persona = personaStore.active,
                    )
                    pendingImages.remove(id)
                    dao.update(
                        idea.copy(
                            ideaTitle = note.title,
                            body = note.body,
                            category = note.category.name,
                            status = IdeaStatus.READY,
                            lastError = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                } catch (e: SplitException.Deferred) {
                    // Every other queued idea would hit the same wall; stop and wait for the next foreground.
                    dao.update(idea.copy(lastError = e.message, updatedAt = System.currentTimeMillis()))
                    return
                } catch (e: SplitException) {
                    val attempts = idea.attempts + 1
                    val giveUp = !e.retryable || attempts >= MAX_ATTEMPTS
                    if (giveUp) pendingImages.remove(id)
                    dao.update(
                        idea.copy(
                            status = if (giveUp) IdeaStatus.FAILED else IdeaStatus.PENDING,
                            attempts = attempts,
                            lastError = e.message,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }
    }

    private fun IdeaEntity.toNote() = IdeaNote(
        title = ideaTitle.orEmpty(),
        body = body.orEmpty(),
        category = IdeaCategory.parse(category),
    )

    /** The chat reply: what actually changed, read off the note rather than the model's word for it. */
    private fun describeChanges(before: IdeaNote, after: IdeaNote): String {
        val changes = ArrayList<String>()
        if (before.title != after.title) changes += "Renamed to “${after.title}”"
        if (before.category != after.category) {
            changes += "Moved to ${after.category.name.lowercase().replaceFirstChar { it.titlecase() }}"
        }
        if (before.body != after.body) {
            val words = { text: String -> text.split(WHITESPACE).count { it.isNotBlank() } }
            changes += "Rewrote the note (${words(before.body)} → ${words(after.body)} words)"
        }
        return changes.joinToString("\n").ifEmpty { "Nothing changed. Try naming exactly what to change." }
    }

    private companion object {
        const val PHOTO_ONLY = "📸 pic dump"
        const val MAX_ATTEMPTS = 3
        const val DELETED = "This idea was deleted"
        const val PERSONA_SAMPLE_LIMIT = 8
        val WHITESPACE = Regex("""\s+""")
    }
}
