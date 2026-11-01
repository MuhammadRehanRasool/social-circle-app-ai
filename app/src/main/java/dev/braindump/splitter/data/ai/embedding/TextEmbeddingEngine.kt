package dev.braindump.splitter.data.ai.embedding

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.braindump.splitter.data.crm.VectorMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Turns text into a unit-length vector, fully on-device. */
interface TextEmbeddingEngine {
    /** Stored next to each vector; a different id means the vectors get rebuilt. */
    val modelId: String

    /** Null until the first embed, then whether the model loaded, and why not. */
    val state: StateFlow<EmbedderState>

    /**
     * Null if the model isn't usable or [text] is blank. [asQuery] picks the query form on
     * models that embed queries and documents differently; USE uses one form for both.
     */
    suspend fun embed(text: String, asQuery: Boolean): FloatArray?
}

sealed interface EmbedderState {
    data object NotLoaded : EmbedderState
    data class Ready(val dimensions: Int) : EmbedderState
    data class Failed(val reason: String) : EmbedderState
}

/**
 * Universal Sentence Encoder through MediaPipe's Text Embedder. The model is the reviewed file
 * in assets (the build checks its SHA-256); nothing is downloaded. Runs in-process on the CPU,
 * so unlike Gemini Nano it also works while the app is in the background.
 */
@Singleton
class MediaPipeTextEmbeddingEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : TextEmbeddingEngine {

    override val modelId: String = MODEL_ID

    private val _state = MutableStateFlow<EmbedderState>(EmbedderState.NotLoaded)
    override val state: StateFlow<EmbedderState> = _state.asStateFlow()

    // Guarded by lock. TextEmbedder isn't documented as thread-safe.
    private var embedder: TextEmbedder? = null
    private var loadFailed = false
    private val lock = Any()

    override suspend fun embed(text: String, asQuery: Boolean): FloatArray? {
        val input = text.trim()
        if (input.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            synchronized(lock) {
                val model = loadLocked() ?: return@synchronized null
                try {
                    val raw = model.embed(input).embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
                    raw?.let(VectorMath::normalized)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private fun loadLocked(): TextEmbedder? {
        embedder?.let { return it }
        if (loadFailed) return null
        return try {
            TextEmbedder.createFromOptions(
                context,
                TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath(MODEL_ASSET)
                            .setDelegate(Delegate.CPU)
                            .build()
                    )
                    .setL2Normalize(true)
                    .setQuantize(false)
                    .build(),
            ).also { model ->
                embedder = model
                val probe = model.embed("probe").embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
                _state.value = EmbedderState.Ready(probe?.size ?: 0)
            }
        } catch (e: Throwable) {
            // A missing or corrupt asset turns semantic search off; capturing and tagging still work.
            loadFailed = true
            embedder?.close()
            embedder = null
            _state.value = EmbedderState.Failed(e.message ?: e.javaClass.simpleName)
            null
        }
    }

    companion object {
        const val MODEL_ASSET = "universal_sentence_encoder.tflite"
        const val MODEL_ID = "use-mediapipe-f32-v1"
    }
}
