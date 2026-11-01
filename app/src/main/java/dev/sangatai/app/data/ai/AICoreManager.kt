package dev.sangatai.app.data.ai

import android.graphics.Bitmap
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import dev.sangatai.app.domain.model.AiStatus
import dev.sangatai.app.domain.model.IdeaCategory
import dev.sangatai.app.domain.model.Persona
import dev.sangatai.app.domain.model.PersonaAspect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** One polished note: a title, the Markdown under it, and where it's filed. */
data class IdeaNote(
    val title: String,
    val body: String,
    val category: IdeaCategory = IdeaCategory.OTHER,
)

/**
 * The last exchange with Gemini Nano, exactly as sent and received, for tuning prompts.
 * Memory only: never written to disk, gone when the app process ends.
 */
data class AiTrace(
    val label: String,
    val instruction: String,
    val userTurn: String,
    val output: String?,
    val retry: Boolean,
    val at: Long,
)

sealed class SplitException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean,
) : Exception(message, cause) {
    /** Gemini Nano can't run here; retrying won't help. */
    class Unsupported(message: String, cause: Throwable? = null) : SplitException(message, cause, retryable = false)

    /** Not now: app in background, model downloading, or quota hit. Doesn't count as an attempt. */
    class Deferred(message: String, cause: Throwable? = null) : SplitException(message, cause, retryable = true)

    /** A one-off inference hiccup; worth another go. */
    class Transient(message: String, cause: Throwable? = null) : SplitException(message, cause, retryable = true)

    /** The model answered, but never with any text. */
    class MalformedOutput(message: String) : SplitException(message, retryable = true)

    class EmptyInput : SplitException("Nothing to split", retryable = false)
}

/**
 * Thread-safe owner of the ML Kit GenAI Prompt client, which runs the system Gemini Nano
 * that AICore already has on the device (no app-private model copy).
 *
 * Requests are serialized through [mutex] because AICore runs one inference at a time.
 * The answer is a Markdown note with a "Category:" line and a "# Title"; both are optional
 * when reading it back, so any non-empty answer is usable. An empty one gets a nudged retry,
 * then the other builds AICore has installed.
 */
@Singleton
class AICoreManager @Inject constructor(
    private val settings: AiSettings,
) {
    private val mutex = Mutex()
    private val downloadMutex = Mutex()

    // Guarded by mutex.
    private var client: GenerativeModel? = null
    private var activeOption: ModelOption? = null
    private var activeModelName: String? = null
    private var systemPromptSupported: Boolean? = null
    private var warmedUp = false

    // Preview builds that said AVAILABLE but then failed inference (e.g. the Pixel 10 Nano 4
    // FULL package missing its TPU config). Skipped until preview is toggled again.
    private val brokenOptions = mutableSetOf<ModelOption>()

    private val _status = MutableStateFlow<AiStatus>(AiStatus.Unknown)
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    private val _lastTrace = MutableStateFlow<AiTrace?>(null)

    /** The last prompt and raw answer, for the developer view. In memory only. */
    val lastTrace: StateFlow<AiTrace?> = _lastTrace.asStateFlow()

    fun clearTrace() {
        _lastTrace.value = null
    }

    /** Drops the current client so the next call re-runs model selection (e.g. after a settings change). */
    suspend fun resetModel() {
        mutex.withLock {
            dropClientLocked()
            brokenOptions.clear()
            _status.value = AiStatus.Unknown
        }
    }

    /** Checks availability, downloads the model if AICore offers one, and warms it up. */
    suspend fun warmUp() {
        val featureStatus = try {
            mutex.withLock { checkStatusLocked() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = AiStatus.Unavailable(describe(e))
            return
        }

        when (featureStatus) {
            FeatureStatus.AVAILABLE -> try {
                mutex.withLock {
                    if (!warmedUp) {
                        clientLocked().warmup()
                        warmedUp = true
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Warm-up only shaves latency off the first request; inference reports real errors.
            }

            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> download()
        }
    }

    /**
     * Rewrites a rough dump (plus an optional photo, never stored) as a polished Markdown note,
     * in the user's [persona] when there is one.
     */
    suspend fun polishIdea(rawIdea: String, image: Bitmap? = null, persona: Persona? = null): IdeaNote {
        val tuning = settings.tuning.value
        val input = sanitize(rawIdea, tuning.maxInputChars)
        if (input.isEmpty()) throw SplitException.EmptyInput()
        val fallbackTitle = titleFrom(input, tuning.maxTitleChars)

        return infer(
            Prompt(
                tuning = tuning,
                instruction = tuning.notePrompt,
                retrySuffix = DefaultPrompts.NOTE_RETRY_SUFFIX,
                userTurn = styleBlock(persona) +
                    (if (image != null) IMAGE_HINT else "") +
                    "Dump: $INPUT_OPEN$input$INPUT_CLOSE\nNote:",
                parse = { parseNote(it, tuning.maxTitleChars, fallbackTitle, fallbackCategory = null) },
                image = image,
            )
        )
    }

    /**
     * Applies [ask] ("make it shorter", "add a section on X") to a note and returns it rewritten.
     * A title or category the model leaves out keeps its current value.
     */
    suspend fun refineIdea(
        current: IdeaNote,
        originalDump: String,
        ask: String,
        persona: Persona? = null,
    ): IdeaNote {
        val tuning = settings.tuning.value
        val request = sanitize(ask, tuning.maxAskChars)
        if (request.isEmpty()) throw SplitException.EmptyInput()
        val note = sanitize(noteText(current))
        val dump = sanitize(originalDump, tuning.maxRefineDumpChars)

        return infer(
            Prompt(
                tuning = tuning,
                instruction = tuning.refinePrompt,
                retrySuffix = DefaultPrompts.REFINE_RETRY_SUFFIX,
                userTurn = styleBlock(persona) +
                    "Note: $INPUT_OPEN$note$INPUT_CLOSE\n" +
                    "Original dump: $INPUT_OPEN$dump$INPUT_CLOSE\n" +
                    "Request: $INPUT_OPEN$request$INPUT_CLOSE\nUpdated note:",
                parse = { parseNote(it, tuning.maxTitleChars, current.title, current.category) },
            )
        )
    }

    /**
     * Reads how the user writes from their own [dumps], newest first. Only the persona's
     * few short descriptors ever reach later prompts, never the dumps themselves, so one
     * note's facts can't leak into another.
     */
    suspend fun describePersona(dumps: List<String>): Persona {
        val tuning = settings.tuning.value
        val joined = buildString {
            for (dump in dumps) {
                val text = sanitize(dump).replace(WHITESPACE, " ").take(PERSONA_DUMP_CHARS)
                if (text.isEmpty() || length + text.length > PERSONA_INPUT_CHARS) continue
                if (isNotEmpty()) append("\n---\n")
                append(text)
            }
        }
        if (joined.isEmpty()) throw SplitException.EmptyInput()
        val used = joined.split("\n---\n").size

        return infer(
            Prompt(
                // Cool and short: a few descriptors, in a fixed format.
                tuning = tuning.copy(
                    temperature = tuning.temperature.coerceAtMost(0.3f),
                    maxOutputTokens = tuning.maxOutputTokens.coerceIn(PERSONA_MIN_TOKENS, PERSONA_MAX_TOKENS),
                ),
                instruction = DefaultPrompts.PERSONA,
                retrySuffix = DefaultPrompts.PERSONA_RETRY_SUFFIX,
                userTurn = "Dumps: $INPUT_OPEN$joined$INPUT_CLOSE\nStyle:",
                parse = { parsePersona(it, used) },
            )
        )
    }

    /**
     * Finds the people in a rant and the tags it supports for each. A rant naming nobody is a
     * valid answer (an empty list), not a failure.
     */
    suspend fun extractMentions(rant: String, crm: CrmTuning): CrmParsers.Extraction {
        val input = sanitize(rant, crm.maxRantChars)
        if (input.isEmpty()) throw SplitException.EmptyInput()
        return infer(
            Prompt(
                label = "ingest",
                tuning = settings.tuning.value.copy(
                    temperature = crm.ingestTemperature,
                    topK = crm.ingestTopK,
                    maxOutputTokens = crm.ingestMaxOutputTokens,
                    attempts = crm.attempts,
                ),
                instruction = crm.ingestPrompt,
                retrySuffix = CrmPrompts.INGEST_RETRY_SUFFIX,
                userTurn = "Said: $INPUT_OPEN$input$INPUT_CLOSE\nJSON:",
                parse = { CrmParsers.parseExtraction(it, crm.maxTagsPerMention) },
            )
        )
    }

    /**
     * Picks who in [candidates] (lines of "P1 | name | tags") fits [query], with a reason each.
     * Only ids in [allowedIds] come back; an empty list means nobody fits.
     */
    suspend fun rankCandidates(
        query: String,
        candidates: String,
        allowedIds: Set<String>,
        crm: CrmTuning,
    ): List<CrmParsers.Match> {
        val ask = sanitize(query, crm.maxQueryChars)
        if (ask.isEmpty() || allowedIds.isEmpty()) throw SplitException.EmptyInput()
        val people = sanitize(candidates, crm.candidateBlockChars)
        return infer(
            Prompt(
                label = "ask",
                tuning = settings.tuning.value.copy(
                    temperature = crm.askTemperature,
                    topK = crm.askTopK,
                    maxOutputTokens = crm.askMaxOutputTokens,
                    attempts = crm.attempts,
                ),
                instruction = crm.askPrompt,
                retrySuffix = CrmPrompts.ASK_RETRY_SUFFIX,
                userTurn = "People: $INPUT_OPEN\n$people\n$INPUT_CLOSE\nQuery: $INPUT_OPEN$ask$INPUT_CLOSE\nJSON:",
                parse = { CrmParsers.parseMatches(it, allowedIds, crm.maxMatches) },
            )
        )
    }

    /** One line of short descriptors; empty when there's no persona to write in. */
    private fun styleBlock(persona: Persona?): String {
        if (persona == null) return ""
        val traits = PersonaAspect.entries.mapNotNull { aspect ->
            persona.traits[aspect]?.let { "${aspect.name.lowercase()}: ${sanitize(it)}" }
        }
        if (traits.isEmpty()) return ""
        return "Style: $INPUT_OPEN${traits.joinToString("; ")}$INPUT_CLOSE\n"
    }

    /** One kind of request: its rules, the user turn, and how to read the answer. */
    private class Prompt<T : Any>(
        val label: String = "note",
        val tuning: AiTuning,
        val instruction: String,
        val retrySuffix: String,
        val userTurn: String,
        val parse: (String) -> T?,
        val image: Bitmap? = null,
    )

    private suspend fun <T : Any> infer(prompt: Prompt<T>): T =
        mutex.withLock {
            val featureStatus = try {
                checkStatusLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw classify(e)
            }
            when (featureStatus) {
                FeatureStatus.AVAILABLE -> Unit
                FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING ->
                    throw SplitException.Deferred("Gemini Nano is still downloading")
                else -> throw SplitException.Unsupported(UNAVAILABLE_REASON)
            }

            var model = clientLocked()
            var useSystemInstruction = systemPromptSupportedLocked(model)

            var attempt = 0
            while (attempt < prompt.tuning.attempts) {
                val output = try {
                    // The retry puts the rules in the user turn; some Nano builds follow them better there.
                    generate(model, prompt, retry = attempt > 0, useSystemInstruction && attempt == 0)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failure = classify(e)
                    if (!demotePreviewLocked(e, failure)) throw failure
                    // Same attempt again, on the next model in line (this also refreshes the status name).
                    val nextReady = try {
                        checkStatusLocked() == FeatureStatus.AVAILABLE
                    } catch (c: CancellationException) {
                        throw c
                    } catch (statusError: Exception) {
                        false
                    }
                    if (!nextReady) throw failure
                    model = clientLocked()
                    useSystemInstruction = systemPromptSupportedLocked(model)
                    continue
                }
                attempt++

                if (output.isNullOrBlank()) continue
                prompt.parse(output)?.let { return@withLock it }
            }

            // This build had nothing to say about this dump; another installed build may.
            splitWithOtherModelsLocked(prompt)
                ?: throw SplitException.MalformedOutput("Gemini Nano returned an empty answer")
        }

    /**
     * Tries every other build AICore can serve right now. One that returns a note becomes
     * the active model for the rest of the session, so later splits go straight to it.
     */
    private suspend fun <T : Any> splitWithOtherModelsLocked(prompt: Prompt<T>): T? {
        val current = activeOption
        for (option in fallbackOptions()) {
            if (option == current || option in brokenOptions) continue
            val candidate = Generation.getClient(configFor(option))
            var promoted = false
            try {
                if (candidate.checkStatus() != FeatureStatus.AVAILABLE) continue
                val useSystemInstruction = try {
                    candidate.isSystemPromptAvailable()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                for (attempt in 0 until prompt.tuning.attempts) {
                    val output = generate(candidate, prompt, retry = attempt > 0, useSystemInstruction && attempt == 0)
                    if (output.isNullOrBlank()) continue
                    val parsed = prompt.parse(output) ?: continue
                    promoteLocked(option, candidate, useSystemInstruction)
                    promoted = true
                    return parsed
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // This build can't serve right now; the next one may.
            } finally {
                if (!promoted) candidate.close()
            }
        }
        return null
    }

    private suspend fun promoteLocked(option: ModelOption, model: GenerativeModel, useSystemInstruction: Boolean) {
        dropClientLocked()
        client = model
        activeOption = option
        systemPromptSupported = useSystemInstruction
        activeModelName = baseModelName(model)
        _status.value = AiStatus.Ready(activeModelName)
    }

    private suspend fun generate(
        model: GenerativeModel,
        prompt: Prompt<*>,
        retry: Boolean,
        useSystemInstruction: Boolean,
    ): String? {
        val instruction = if (retry) prompt.instruction + prompt.retrySuffix else prompt.instruction
        val userTurn = prompt.userTurn
        val request = if (prompt.image != null) {
            // Image turns go system-instruction-free; not every build takes both.
            generateContentRequest(ImagePart(prompt.image), TextPart("$instruction\n$userTurn")) { tune(prompt.tuning) }
        } else if (useSystemInstruction) {
            generateContentRequest(SystemInstruction(instruction), TextPart(userTurn)) { tune(prompt.tuning) }
        } else {
            generateContentRequest(TextPart("$instruction\n$userTurn")) { tune(prompt.tuning) }
        }
        val output = model.generateContent(request).candidates.firstOrNull()?.text
        _lastTrace.value = AiTrace(
            label = prompt.label,
            instruction = instruction,
            userTurn = userTurn,
            output = output,
            retry = retry,
            at = System.currentTimeMillis(),
        )
        return output
    }

    private fun com.google.mlkit.genai.prompt.GenerateContentRequest.Builder.tune(tuning: AiTuning) {
        temperature = tuning.temperature
        topK = tuning.topK
        candidateCount = 1
        maxOutputTokens = tuning.maxOutputTokens
    }

    private suspend fun checkStatusLocked(): Int {
        val model = clientLocked()
        val featureStatus = model.checkStatus()
        when (featureStatus) {
            FeatureStatus.AVAILABLE -> {
                if (activeModelName == null) activeModelName = baseModelName(model)
                _status.value = AiStatus.Ready(activeModelName)
            }
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING ->
                if (_status.value !is AiStatus.Downloading) _status.value = AiStatus.Downloading(0L, 0L)
            else -> {
                _status.value = AiStatus.Unavailable(UNAVAILABLE_REASON)
                // Re-run model selection next time; AICore may have updated or finished a download meanwhile.
                dropClientLocked()
            }
        }
        return featureStatus
    }

    private fun dropClientLocked() {
        client?.close()
        client = null
        activeOption = null
        activeModelName = null
        systemPromptSupported = null
        warmedUp = false
    }

    /**
     * A preview model can report AVAILABLE and still fail every inference, so a hard
     * inference error on one marks it broken and falls back to the next option. Returns
     * whether the caller should retry on the newly selected model.
     */
    private fun demotePreviewLocked(e: Exception, failure: SplitException): Boolean {
        val option = activeOption ?: return false
        if (option.releaseStage != ModelReleaseStage.PREVIEW) return false
        if (e !is GenAiException || failure !is SplitException.Transient) return false
        brokenOptions += option
        dropClientLocked()
        return true
    }

    private suspend fun download() {
        if (!downloadMutex.tryLock()) return // a download is already being tracked
        try {
            val model = mutex.withLock { clientLocked() }
            var totalBytes = 0L
            model.download().collect { progress ->
                when (progress) {
                    is DownloadStatus.DownloadStarted -> {
                        totalBytes = progress.bytesToDownload
                        _status.value = AiStatus.Downloading(0L, totalBytes)
                    }
                    is DownloadStatus.DownloadProgress ->
                        _status.value = AiStatus.Downloading(progress.totalBytesDownloaded, totalBytes)
                    is DownloadStatus.DownloadCompleted -> _status.value = AiStatus.Ready()
                    is DownloadStatus.DownloadFailed -> _status.value = AiStatus.Unavailable(describe(progress.e))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = AiStatus.Unavailable(describe(e))
        } finally {
            downloadMutex.unlock()
        }
    }

    /**
     * Picks the best model AICore can serve, following Google's guidance: preview builds
     * first when opted in, then stable FULL, then stable FAST. A ready model wins over one
     * that still needs downloading. The SDK doesn't throw at client creation, so
     * checkStatus() is the availability probe.
     */
    private suspend fun clientLocked(): GenerativeModel {
        client?.let { return it }

        var downloadable: GenerativeModel? = null
        var downloadableOption: ModelOption? = null
        for (option in modelOptions().filterNot { it in brokenOptions }) {
            val candidate = Generation.getClient(configFor(option))
            val featureStatus = try {
                candidate.checkStatus()
            } catch (e: CancellationException) {
                candidate.close()
                downloadable?.close()
                throw e
            } catch (e: Exception) {
                FeatureStatus.UNAVAILABLE
            }

            when {
                featureStatus == FeatureStatus.AVAILABLE -> {
                    downloadable?.close()
                    client = candidate
                    activeOption = option
                    return candidate
                }
                downloadable == null && (featureStatus == FeatureStatus.DOWNLOADABLE ||
                    featureStatus == FeatureStatus.DOWNLOADING) -> {
                    downloadable = candidate
                    downloadableOption = option
                }
                else -> candidate.close()
            }
        }

        // Nothing ready: keep a downloadable model, or the default client so checkStatus() explains why.
        val chosen = downloadable ?: Generation.getClient()
        client = chosen
        activeOption = downloadableOption
        return chosen
    }

    private fun modelOptions(): List<ModelOption> = buildList {
        if (settings.usePreviewModel.value) addAll(previewOptions)
        add(ModelOption(ModelReleaseStage.STABLE, ModelPreference.FULL))
        add(ModelOption(ModelReleaseStage.STABLE, ModelPreference.FAST))
    }

    /** The usual order, then preview builds even when not opted in: better than failing the split. */
    private fun fallbackOptions(): List<ModelOption> = (modelOptions() + previewOptions).distinct()

    private val previewOptions = listOf(
        ModelOption(ModelReleaseStage.PREVIEW, ModelPreference.FULL),
        ModelOption(ModelReleaseStage.PREVIEW, ModelPreference.FAST),
    )

    private fun configFor(option: ModelOption) = generationConfig {
        modelConfig = modelConfig {
            releaseStage = option.releaseStage
            preference = option.preference
        }
    }

    private suspend fun baseModelName(model: GenerativeModel): String? = try {
        model.getBaseModelName()?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private data class ModelOption(val releaseStage: Int, val preference: Int)

    private suspend fun systemPromptSupportedLocked(model: GenerativeModel): Boolean {
        systemPromptSupported?.let { return it }
        val supported = try {
            model.isSystemPromptAvailable()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        systemPromptSupported = supported
        return supported
    }

    private fun classify(e: Exception): SplitException {
        if (e is SplitException) return e
        val message = describe(e)
        if (e !is GenAiException) return SplitException.Transient(message, e)
        return when (e.errorCode) {
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            GenAiException.ErrorCode.BUSY,
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> SplitException.Deferred(message, e)

            GenAiException.ErrorCode.NOT_SUPPORTED,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            GenAiException.ErrorCode.REQUEST_TOO_LARGE -> {
                _status.value = AiStatus.Unavailable(message)
                SplitException.Unsupported(message, e)
            }

            else -> SplitException.Transient(message, e)
        }
    }

    private fun describe(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    /**
     * Reads a note out of the model's answer. The "Category:" line and a leading "# Title" are
     * lifted out; a code fence or a "Here is your note:" lead-in is dropped. Missing pieces
     * fall back to [fallbackTitle] and [fallbackCategory]. Null only when nothing is left.
     */
    internal fun parseNote(
        modelOutput: String,
        maxTitleChars: Int,
        fallbackTitle: String,
        fallbackCategory: IdeaCategory?,
    ): IdeaNote? {
        var lines = modelOutput.replace("\r\n", "\n").trim().lines()
        if (lines.firstOrNull()?.trim()?.startsWith("```") == true) lines = lines.drop(1)
        if (lines.lastOrNull()?.trim() == "```") lines = lines.dropLast(1)

        var category: IdeaCategory? = null
        val kept = ArrayList<String>(lines.size)
        for (line in lines) {
            val match = if (category == null) CATEGORY_LINE.matchEntire(line.trim()) else null
            if (match != null) category = IdeaCategory.parse(match.groupValues[1]) else kept += line
        }

        var title = ""
        var bodyLines: List<String> = kept
        val titleAt = kept.indexOfFirst { TITLE_LINE.matches(it.trim()) }
        // A lead-in sentence may sit above the title, but not a whole paragraph.
        if (titleAt >= 0 && kept.subList(0, titleAt).count { it.isNotBlank() } <= 1) {
            val groups = TITLE_LINE.matchEntire(kept[titleAt].trim())!!.groupValues
            title = cleanTitle(groups[1].ifEmpty { groups[2] }).take(maxTitleChars)
            bodyLines = kept.subList(titleAt + 1, kept.size)
        }
        val body = bodyLines.joinToString("\n").trim().replace(EXTRA_BLANK_LINES, "\n\n")
        if (title.isEmpty() && body.isEmpty()) return null

        return IdeaNote(
            title = title.ifEmpty { fallbackTitle },
            body = body,
            category = category ?: fallbackCategory ?: IdeaCategory.OTHER,
        )
    }

    /**
     * Reads "Voice: casual, first person" lines, tolerating bullets, bold keys and stray
     * brackets. Null unless at least [MIN_PERSONA_TRAITS] aspects came back.
     */
    internal fun parsePersona(modelOutput: String, basedOn: Int): Persona? {
        val traits = LinkedHashMap<PersonaAspect, String>()
        var summary = ""
        for (line in modelOutput.replace("\r\n", "\n").lines()) {
            val match = PERSONA_LINE.matchEntire(line.trim()) ?: continue
            val key = match.groupValues[1].lowercase()
            val value = match.groupValues[2].trim().trim('*', '_', '"', '<', '>', '[', ']', '.', ' ')
            if (value.isEmpty()) continue
            if (key == "summary") {
                if (summary.isEmpty()) summary = value.take(PERSONA_SUMMARY_CHARS)
                continue
            }
            val aspect = PersonaAspect.entries.firstOrNull { it.name.lowercase() == key } ?: continue
            traits.putIfAbsent(aspect, value.take(PERSONA_TRAIT_CHARS))
        }
        if (traits.size < MIN_PERSONA_TRAITS) return null
        return Persona(
            traits = traits,
            summary = summary,
            basedOn = basedOn,
            dumpCount = 0,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** The note the way the model writes it, so a refine can edit it in place. */
    private fun noteText(note: IdeaNote): String =
        "Category: ${note.category.name.lowercase().replaceFirstChar { it.titlecase() }}\n# ${note.title}\n\n${note.body}"

    private fun cleanTitle(value: String): String = value.trim().trim('*', '_', '#', '"', '`').trim()

    private fun titleFrom(input: String, maxChars: Int): String =
        input.split(WHITESPACE)
            .filter { it.isNotBlank() }
            .take(FALLBACK_TITLE_WORDS)
            .joinToString(" ")
            .replaceFirstChar { it.titlecase() }
            .take(maxChars)

    private fun sanitize(raw: String, maxChars: Int = Int.MAX_VALUE): String =
        raw.replace(INPUT_OPEN, "").replace(INPUT_CLOSE, "").trim().take(maxChars)

    private companion object {
        const val FALLBACK_TITLE_WORDS = 6

        /** "Category: Health", also bolded or with trailing words. */
        val CATEGORY_LINE = Regex("""^[*_\s]*category[*_\s]*:[*_\s]*([A-Za-z]+).*$""", RegexOption.IGNORE_CASE)

        /** "# Title" (up to ###) or a line that is entirely bold. */
        val TITLE_LINE = Regex("""^(?:#{1,3}\s+(.+?)\s*#*|\*\*([^*]+)\*\*)$""")

        /** "Voice: casual", also as "- **Voice**: casual". */
        val PERSONA_LINE = Regex("""^[-*•\d.)\s]*\**([A-Za-z]+)\**\s*:\s*(.+)$""")
        const val MIN_PERSONA_TRAITS = 3
        const val PERSONA_TRAIT_CHARS = 60
        const val PERSONA_SUMMARY_CHARS = 200
        const val PERSONA_DUMP_CHARS = 400
        const val PERSONA_INPUT_CHARS = 2_400
        const val PERSONA_MIN_TOKENS = 160
        const val PERSONA_MAX_TOKENS = 256

        val EXTRA_BLANK_LINES = Regex("""\n{3,}""")
        val WHITESPACE = Regex("""\s+""")

        const val INPUT_OPEN = "<<<"
        const val IMAGE_HINT = "An image is attached as part of the dump; pull out whatever in it matters.\n"
        const val INPUT_CLOSE = ">>>"

        const val UNAVAILABLE_REASON =
            "Gemini Nano isn't available. Update AICore in the Play Store; it won't run on an unlocked bootloader."
    }
}
