package dev.sangatai.app.ui.capture

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sangatai.app.di.ApplicationScope
import dev.sangatai.app.domain.model.IngestOutcome
import dev.sangatai.app.domain.repository.PeopleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface CaptureUiState {
    data object Editing : CaptureUiState
    data object Reading : CaptureUiState
    data class Finished(val outcome: IngestOutcome) : CaptureUiState
}

@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val people: PeopleRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow<CaptureUiState>(CaptureUiState.Editing)
    val state: StateFlow<CaptureUiState> = _state.asStateFlow()

    /**
     * Saves and reads while the sheet is still on screen, because Gemini Nano won't serve a
     * backgrounded app. Runs in the app scope, so dismissing the sheet never loses what was said.
     */
    fun submit(text: String) {
        if (text.isBlank() || _state.value != CaptureUiState.Editing) return
        _state.value = CaptureUiState.Reading
        appScope.launch {
            _state.value = CaptureUiState.Finished(people.ingest(text))
        }
    }
}
