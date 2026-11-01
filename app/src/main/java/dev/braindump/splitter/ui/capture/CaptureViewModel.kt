package dev.braindump.splitter.ui.capture

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.braindump.splitter.di.ApplicationScope
import dev.braindump.splitter.domain.model.CaptureOutcome
import dev.braindump.splitter.domain.repository.IdeaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CaptureUiState {
    data object Editing : CaptureUiState
    data object Splitting : CaptureUiState
    data class Finished(val outcome: CaptureOutcome) : CaptureUiState
}

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: IdeaRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow<CaptureUiState>(CaptureUiState.Editing)
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    /**
     * Saves and splits while the sheet is still on screen, because Gemini Nano won't serve
     * a backgrounded app. Runs in the app scope, so dismissing the sheet never loses the idea.
     */
    fun submit(text: String, image: android.graphics.Bitmap?) {
        if ((text.isBlank() && image == null) || _state.value != CaptureUiState.Editing) return
        _state.value = CaptureUiState.Splitting
        appScope.launch {
            _state.value = CaptureUiState.Finished(repository.captureAndSplit(text, image))
        }
    }
}
