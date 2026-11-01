package dev.braindump.splitter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.braindump.splitter.data.ai.AiSettings
import dev.braindump.splitter.data.ai.AiTuning
import dev.braindump.splitter.di.ApplicationScope
import dev.braindump.splitter.domain.repository.IdeaRepository
import dev.braindump.splitter.ui.dashboard.DaySection
import dev.braindump.splitter.ui.dashboard.groupByDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: IdeaRepository,
    private val aiSettings: AiSettings,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val usePreviewModel: StateFlow<Boolean> = repository.usePreviewModel

    val tuning: StateFlow<AiTuning> = aiSettings.tuning

    /** Every idea, for export. */
    val sections: StateFlow<List<DaySection>> = repository.observeIdeas()
        .map(::groupByDay)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setUsePreviewModel(enabled: Boolean) {
        appScope.launch { repository.setUsePreviewModel(enabled) }
    }

    fun saveTuning(value: AiTuning) {
        aiSettings.setTuning(value)
    }

    fun resetTuning() {
        aiSettings.setTuning(AiTuning())
    }
}
