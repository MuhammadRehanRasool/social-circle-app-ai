package dev.sangatai.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sangatai.app.data.ai.AICoreManager
import dev.sangatai.app.di.ApplicationScope
import dev.sangatai.app.domain.model.AiStatus
import dev.sangatai.app.domain.model.AskOutcome
import dev.sangatai.app.domain.model.Capture
import dev.sangatai.app.domain.model.IngestOutcome
import dev.sangatai.app.domain.model.SocialGraph
import dev.sangatai.app.domain.repository.PeopleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val graph: SocialGraph = SocialGraph(emptyList(), emptyList()),
    val captures: List<Capture> = emptyList(),
    val aiStatus: AiStatus = AiStatus.Unknown,
    val loading: Boolean = true,
)

/** The last thing said from the home screen: being read, or what came of it. */
sealed interface CaptureResult {
    data class Reading(val text: String) : CaptureResult
    data class Done(val outcome: IngestOutcome) : CaptureResult
}

data class AskState(
    val query: String = "",
    val running: Boolean = false,
    val outcome: AskOutcome? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val people: PeopleRepository,
    private val ai: AICoreManager,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        people.observeGraph().flowOn(Dispatchers.Default),
        people.observeCaptures(),
        ai.status,
    ) { graph, captures, status ->
        HomeUiState(graph = graph, captures = captures, aiStatus = status, loading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private val _result = MutableStateFlow<CaptureResult?>(null)
    val result: StateFlow<CaptureResult?> = _result.asStateFlow()

    private val _ask = MutableStateFlow(AskState())
    val ask: StateFlow<AskState> = _ask.asStateFlow()
    private var askJob: Job? = null

    /** Gemini Nano only serves the app while it's on screen, so this is where the queue drains. */
    fun onForeground() {
        // warmUp() follows a model download to the end, so the queue drains once it's ready.
        viewModelScope.launch {
            ai.warmUp()
            people.drainPending()
        }
        // Vectors missing or from another model. In-process and doesn't need Nano, so it
        // mustn't wait behind a download that can take minutes.
        viewModelScope.launch { people.reembedStale() }
    }

    /** Runs in the app scope, so leaving the screen never loses what was said. */
    fun capture(text: String) {
        if (text.isBlank()) return
        _result.value = CaptureResult.Reading(text)
        appScope.launch {
            _result.value = CaptureResult.Done(people.ingest(text))
        }
    }

    fun dismissResult() {
        if (_result.value is CaptureResult.Done) _result.value = null
    }

    fun ask(query: String) {
        if (query.isBlank()) return
        askJob?.cancel()
        _ask.value = AskState(query = query, running = true)
        askJob = viewModelScope.launch {
            val outcome = people.askNetwork(query)
            _ask.value = AskState(query = query, running = false, outcome = outcome)
        }
    }

    fun clearAsk() {
        askJob?.cancel()
        _ask.value = AskState()
    }

    fun retryCapture(id: Long) {
        appScope.launch { people.retryCapture(id) }
    }

    fun deleteCapture(id: Long) {
        appScope.launch { people.deleteCapture(id) }
    }

    fun addPerson(name: String, relation: String, onAdded: (Long) -> Unit) {
        viewModelScope.launch {
            val id = people.addPerson(name) ?: return@launch
            if (relation.isNotBlank()) people.setRelation(id, relation)
            onAdded(id)
        }
    }
}
