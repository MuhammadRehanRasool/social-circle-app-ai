package dev.braindump.splitter.ui.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import dev.braindump.splitter.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer as GenAiSpeechRecognizer

/**
 * Live dictation that never leaves the phone.
 *
 * First choice is ML Kit GenAI Speech Recognition in Advanced mode, which on Pixel 10 runs
 * on Gemini Nano through AICore (the SDK drops to its Basic on-device model elsewhere).
 * When that isn't ready, it uses the platform's on-device recognizer (on Pixel: the offline
 * engine behind Live Caption and Live Transcribe). Neither goes through the Google app, so
 * region restrictions on voice search don't apply.
 *
 * Offline packs exist per language *and region* (en-US, not en-PK), and Android 14+ also tags
 * the locale with regional preferences (en-US-u-mu-celsius). Asking for the phone's locale
 * verbatim therefore often finds no pack, so the language is matched against the packs that
 * are already installed before anything is downloaded.
 */
@Stable
class VoiceTranscriber(
    private val context: Context,
    private val scope: CoroutineScope,
) : RecognitionListener {

    var isListening by mutableStateOf(false)
        private set

    /** Words of the phrase being spoken right now; finished phrases go to onFinal as they land. */
    var partialText by mutableStateOf("")
        private set

    /** Smoothed input level, 0..1, for the listening indicator. */
    var level by mutableFloatStateOf(0f)
        private set

    private var onFinal: (String) -> Unit = {}
    private var onMessage: (String) -> Unit = {}

    // Recognition language: resolved once, then moved to a better-covered region if a pack is missing.
    private var speechLocale: Locale? = null
    private val triedLocales = HashSet<String>()

    /** Picks the language and engine, and runs the GenAI stream. */
    private var session: Job? = null

    // Dictation keeps going across pauses (both engines end a session after each phrase) until
    // the user taps stop, a long silence passes, or the session cap is hit.
    private var stopRequested = false
    private var lastSpeechAt = 0L
    private var watchdog: Job? = null
    private var restartJob: Job? = null
    private var restartRetries = 0

    // ML Kit GenAI path.
    private var genAiRecognizer: GenAiSpeechRecognizer? = null
    private var genAiLocale: Locale? = null
    private var genAiUnusable = false
    private var genAiDownloading = false
    private var genAiListening = false

    // Platform fallback.
    private var recognizer: SpeechRecognizer? = null

    fun start(onFinal: (String) -> Unit, onMessage: (String) -> Unit) {
        if (isListening) return
        this.onFinal = onFinal
        this.onMessage = onMessage
        begin()
    }

    /** Stops listening; whatever was heard is still delivered through onFinal. */
    fun stop() {
        if (!isListening) return
        stopRequested = true
        val genAi = genAiRecognizer
        when {
            genAiListening && genAi != null -> scope.launch { stopGenAi(genAi) }
            session?.isActive == true -> {
                // Still choosing an engine, so nothing has been heard yet.
                session?.cancel()
                resetState()
            }
            restartJob?.isActive == true -> {
                // Between two platform phrases: everything heard is already delivered.
                restartJob?.cancel()
                resetState()
            }
            else -> recognizer?.stopListening()
        }
    }

    fun release() {
        stopRequested = true
        restartJob?.cancel()
        session?.cancel()
        session = null
        genAiListening = false
        genAiRecognizer?.close()
        genAiRecognizer = null
        genAiLocale = null
        recognizer?.destroy()
        recognizer = null
        resetState()
    }

    private fun begin() {
        partialText = ""
        level = 0f
        isListening = true
        stopRequested = false
        restartRetries = 0
        startWatchdog()
        session = scope.launch {
            val target = recognitionLocale()
            val genAi = if (genAiUnusable) null else readyGenAiRecognizer(target)
            if (genAi == null) startPlatform(target) else listenWithGenAi(genAi, target)
        }
    }

    private fun resetState() {
        watchdog?.cancel()
        isListening = false
        partialText = ""
        level = 0f
    }

    /** Ends dictation after a long silence or at the session cap, so a forgotten mic doesn't stay open. */
    private fun startWatchdog() {
        watchdog?.cancel()
        val startedAt = SystemClock.elapsedRealtime()
        lastSpeechAt = startedAt
        watchdog = scope.launch {
            while (true) {
                delay(WATCHDOG_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                if (now - lastSpeechAt > IDLE_STOP_MS || now - startedAt > MAX_SESSION_MS) {
                    stop()
                    return@launch
                }
            }
        }
    }

    private fun heardSpeech() {
        lastSpeechAt = SystemClock.elapsedRealtime()
    }

    private suspend fun recognitionLocale(): Locale {
        speechLocale?.let { return it }
        val preferred = plainLocale(Locale.getDefault())
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) installedOnDeviceLanguages() else emptyList()
        val chosen = pickLocale(preferred, installed)
        triedLocales += chosen.toLanguageTag()
        speechLocale = chosen
        return chosen
    }

    /** Language tags whose offline packs are already on the phone, or empty if the system won't say. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun installedOnDeviceLanguages(): List<String> {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return emptyList()
        val probe = try {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } catch (e: Exception) {
            return emptyList()
        }
        return try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                suspendCancellableCoroutine<List<String>> { continuation ->
                    val callback = object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            if (continuation.isActive) continuation.resume(support.installedOnDeviceLanguages)
                        }

                        override fun onError(error: Int) {
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                    }
                    try {
                        probe.checkRecognitionSupport(recognitionIntent(Locale.getDefault()), context.mainExecutor, callback)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(emptyList())
                    }
                }
            }.orEmpty()
        } finally {
            probe.destroy()
        }
    }

    /** The GenAI recognizer when its model is on the device; otherwise null, kicking off a download if offered. */
    private suspend fun readyGenAiRecognizer(target: Locale): GenAiSpeechRecognizer? {
        if (genAiLocale != target) {
            genAiRecognizer?.close()
            genAiRecognizer = null
        }
        val genAi = genAiRecognizer ?: SpeechRecognition.getClient(
            speechRecognizerOptions {
                locale = target
                preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED
            }
        ).also {
            genAiRecognizer = it
            genAiLocale = target
        }

        val status = try {
            genAi.checkStatus()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FeatureStatus.UNAVAILABLE
        }
        return when (status) {
            FeatureStatus.AVAILABLE -> genAi
            FeatureStatus.DOWNLOADABLE, FeatureStatus.DOWNLOADING -> {
                downloadGenAi(genAi)
                null
            }
            else -> {
                genAiUnusable = true
                null
            }
        }
    }

    /** Fetches the model in the background so the next dictation can use it. */
    private fun downloadGenAi(genAi: GenAiSpeechRecognizer) {
        if (genAiDownloading) return
        genAiDownloading = true
        scope.launch {
            try {
                genAi.download().collect {}
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The platform recognizer keeps working; try again on a later launch.
            } finally {
                genAiDownloading = false
            }
        }
    }

    /**
     * Runs recognition streams back to back until the user stops. A stream can end on its own
     * after a phrase, and that must not end the dictation.
     */
    private suspend fun listenWithGenAi(genAi: GenAiSpeechRecognizer, target: Locale) {
        var heardAnything = false
        var failed = false
        var emptyStreams = 0
        genAiListening = true
        try {
            while (!stopRequested) {
                var responses = 0
                try {
                    genAi.startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromMic() })
                        .collect { response ->
                            responses++
                            when (response) {
                                is SpeechRecognizerResponse.PartialTextResponse -> {
                                    partialText = response.text.trim()
                                    level = PEAK_LEVEL
                                    heardSpeech()
                                }
                                is SpeechRecognizerResponse.FinalTextResponse -> {
                                    val segment = response.text.trim()
                                    partialText = ""
                                    level = 0f
                                    if (segment.isNotEmpty()) {
                                        heardAnything = true
                                        heardSpeech()
                                        onFinal(segment)
                                    }
                                }
                                is SpeechRecognizerResponse.ErrorResponse -> failed = true
                                else -> Unit // CompletedResponse: the flow ends right after.
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                }
                if (failed) break
                // A stream that closes without saying anything, over and over, isn't going to recover.
                emptyStreams = if (responses == 0) emptyStreams + 1 else 0
                if (emptyStreams >= MAX_EMPTY_STREAMS) break
                if (!stopRequested) delay(RESTART_DELAY_MS)
            }
        } finally {
            genAiListening = false
        }

        val leftover = partialText.trim()
        if (failed && !heardAnything && leftover.isEmpty() && !stopRequested) {
            // Don't keep failing on this path; the platform recognizer takes over from here.
            genAiUnusable = true
            genAiRecognizer?.close()
            genAiRecognizer = null
            genAiLocale = null
            partialText = ""
            startPlatform(target)
            return
        }
        resetState()
        if (leftover.isNotEmpty()) onFinal(leftover)
    }

    private suspend fun stopGenAi(genAi: GenAiSpeechRecognizer) {
        try {
            genAi.stopRecognition()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            session?.cancel()
            genAiListening = false
            resetState()
        }
    }

    private fun startPlatform(target: Locale) {
        val activeRecognizer = recognizer ?: createRecognizer()?.also { recognizer = it }
        if (activeRecognizer == null) {
            resetState()
            message(R.string.voice_unavailable)
            return
        }
        isListening = true
        activeRecognizer.startListening(recognitionIntent(target))
    }

    private fun createRecognizer(): SpeechRecognizer? {
        val created = when {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ->
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            SpeechRecognizer.isRecognitionAvailable(context) ->
                SpeechRecognizer.createSpeechRecognizer(context)
            else -> null
        }
        created?.setRecognitionListener(this)
        return created
    }

    private fun recognitionIntent(target: Locale): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, target.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

    private fun message(@StringRes id: Int, vararg args: Any) {
        onMessage(context.getString(id, *args))
    }

    /**
     * The platform recognizer ends after every phrase. Unless the user stopped, start the next
     * one right away so dictation feels continuous.
     */
    private fun continuePlatform() {
        partialText = ""
        level = 0f
        if (stopRequested) {
            resetState()
            return
        }
        restartJob = scope.launch {
            // Restarting from inside the result callback trips ERROR_CLIENT on some devices.
            delay(RESTART_DELAY_MS)
            val active = recognizer
            if (stopRequested || active == null) {
                resetState()
                return@launch
            }
            active.startListening(recognitionIntent(speechLocale ?: plainLocale(Locale.getDefault())))
        }
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) {
            heardSpeech()
            restartRetries = 0
            onFinal(text)
        }
        continuePlatform()
    }

    override fun onPartialResults(partialResults: Bundle?) {
        partialText = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()
        if (partialText.isNotBlank()) heardSpeech()
    }

    override fun onError(error: Int) {
        val heard = partialText.trim()

        when (error) {
            // A pause, or a hiccup while restarting between phrases: keep any words heard and listen on.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (heard.isNotEmpty()) onFinal(heard)
                continuePlatform()
                return
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT -> if (isListening && !stopRequested && restartRetries < MAX_RESTART_RETRIES) {
                restartRetries++
                if (heard.isNotEmpty()) onFinal(heard)
                continuePlatform()
                return
            }
        }

        resetState()
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Unit // handled above

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                val current = speechLocale ?: plainLocale(Locale.getDefault())
                val fallback = fallbackLocale(current)
                if (heard.isEmpty() && fallback != null && triedLocales.add(fallback.toLanguageTag())) {
                    // No pack for this region; the language's main region nearly always has one.
                    speechLocale = fallback
                    genAiUnusable = false
                    begin()
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    recognizer?.triggerModelDownload(recognitionIntent(current))
                }
                if (genAiDownloading) {
                    message(R.string.voice_model_downloading)
                } else {
                    message(R.string.voice_language_downloading, current.getDisplayName(current))
                }
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> message(R.string.voice_permission_denied)

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> message(R.string.voice_busy)

            else -> {
                // Client/server faults can leave the recognizer unusable; rebuild it next time.
                recognizer?.destroy()
                recognizer = null
                if (heard.isNotEmpty()) onFinal(heard) else message(R.string.voice_error)
            }
        }
    }

    override fun onRmsChanged(rmsdB: Float) {
        val target = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        level = level * (1f - LEVEL_SMOOTHING) + target * LEVEL_SMOOTHING
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private companion object {
        /** Silence that ends the dictation on its own; short pauses between phrases don't. */
        const val IDLE_STOP_MS = 20_000L
        const val MAX_SESSION_MS = 5 * 60_000L
        const val WATCHDOG_TICK_MS = 1_000L
        const val RESTART_DELAY_MS = 150L
        const val MAX_RESTART_RETRIES = 3
        const val MAX_EMPTY_STREAMS = 3
        const val PROBE_TIMEOUT_MS = 1_500L

        /** The GenAI stream has no input level, so the indicator pulses while words arrive. */
        const val PEAK_LEVEL = 0.6f
        const val LEVEL_SMOOTHING = 0.4f
    }
}

/** Language, script and region only: recognizers reject tags carrying -u- preference extensions. */
internal fun plainLocale(locale: Locale): Locale =
    Locale.forLanguageTag(listOf(locale.language, locale.script, locale.country).filter { it.isNotEmpty() }.joinToString("-"))

/**
 * The phone's own locale when its pack is installed; otherwise an installed pack in the same
 * language, preferring that language's main region (en-PK speaks through en-US).
 */
internal fun pickLocale(preferred: Locale, installedTags: List<String>): Locale {
    val installed = installedTags.map { plainLocale(Locale.forLanguageTag(it)) }
    if (preferred in installed) return preferred
    val sameLanguage = installed.filter { it.language == preferred.language }
    if (sameLanguage.isEmpty()) return preferred
    val main = fallbackLocale(preferred)
    return sameLanguage.firstOrNull { it == main } ?: sameLanguage.first()
}

/** The region of [locale]'s language with the widest offline coverage, unless [locale] is already it. */
internal fun fallbackLocale(locale: Locale): Locale? {
    val region = MAIN_REGIONS[locale.language] ?: return null
    if (locale.country.equals(region, ignoreCase = true)) return null
    return Locale.forLanguageTag("${locale.language}-$region")
}

private val MAIN_REGIONS = mapOf(
    "en" to "US",
    "es" to "US",
    "fr" to "FR",
    "de" to "DE",
    "it" to "IT",
    "pt" to "BR",
    "ja" to "JP",
    "ko" to "KR",
    "hi" to "IN",
    "ru" to "RU",
    "nl" to "NL",
    "tr" to "TR",
)

@Composable
fun rememberVoiceTranscriber(): VoiceTranscriber {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transcriber = remember(context, scope) { VoiceTranscriber(context, scope) }
    DisposableEffect(transcriber) {
        onDispose { transcriber.release() }
    }
    return transcriber
}
