package dev.sangatai.app.data.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import dev.sangatai.app.domain.model.AiStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

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

    class EmptyInput : SplitException("Nothing to read", retryable = false)
}

/**
 * Thread-safe owner of the ML Kit GenAI Prompt client, which runs the system Gemini Nano
 * that AICore already has on the device (no app-private model copy).
 *
 * Requests are serialized through [mutex] because AICore runs one inference at a time.
 * Answers are JSON read leniently by [CrmParsers]. An empty or unreadable one gets a nudged
 * retry, then the other builds AICore has installed.
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
     * Finds the people in a rant and the tags it supports for each. A rant naming nobody is a
     * valid answer (an empty list), not a failure.
     */
    suspend fun extractMentions(rant: String, crm: CrmTuning): CrmParsers.Extraction {
        val input = sanitize(rant, crm.maxRantChars)
        if (input.isEmpty()) throw SplitException.EmptyInput()
        return infer(
            Prompt(
                label = "ingest",
                tuning = GenParams(
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
                tuning = GenParams(
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

    /** Sampling for one request. */
    private data class GenParams(
        val temperature: Float,
        val topK: Int,
        val maxOutputTokens: Int,
        val attempts: Int,
    )

    /** One kind of request: its rules, the user turn, and how to read the answer. */
    private class Prompt<T : Any>(
        val label: String,
        val tuning: GenParams,
        val instruction: String,
        val retrySuffix: String,
        val userTurn: String,
        val parse: (String) -> T?,
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

            // This build had nothing usable to say; another installed build may.
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
        val request = if (useSystemInstruction) {
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

    private fun com.google.mlkit.genai.prompt.GenerateContentRequest.Builder.tune(tuning: GenParams) {
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

    private fun sanitize(raw: String, maxChars: Int = Int.MAX_VALUE): String =
        raw.replace(INPUT_OPEN, "").replace(INPUT_CLOSE, "").trim().take(maxChars)

    private companion object {
        const val INPUT_OPEN = "<<<"
        const val INPUT_CLOSE = ">>>"

        const val UNAVAILABLE_REASON =
            "Gemini Nano isn't available. Update AICore in the Play Store; it won't run on an unlocked bootloader."
    }
}
