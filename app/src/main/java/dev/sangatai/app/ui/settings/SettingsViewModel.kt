package dev.sangatai.app.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.sangatai.app.data.ai.AICoreManager
import dev.sangatai.app.data.ai.AiSettings
import dev.sangatai.app.data.ai.AiTrace
import dev.sangatai.app.data.ai.CrmSettings
import dev.sangatai.app.data.ai.CrmTuning
import dev.sangatai.app.data.repository.AppDataManager
import dev.sangatai.app.data.repository.StoredFile
import dev.sangatai.app.data.repository.WipeScope
import dev.sangatai.app.di.ApplicationScope
import dev.sangatai.app.domain.repository.PeopleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crmSettings: CrmSettings,
    private val aiSettings: AiSettings,
    private val ai: AICoreManager,
    private val people: PeopleRepository,
    private val data: AppDataManager,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val tuning: StateFlow<CrmTuning> = crmSettings.tuning
    val usePreviewModel: StateFlow<Boolean> = aiSettings.usePreviewModel
    val lastTrace: StateFlow<AiTrace?> = ai.lastTrace
    val legacyNotes: StateFlow<Int> = data.observeLegacyNoteCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun saveTuning(value: CrmTuning) = crmSettings.set(value)

    fun resetTuning() = crmSettings.reset()

    fun setUsePreviewModel(enabled: Boolean) {
        if (aiSettings.usePreviewModel.value == enabled) return
        aiSettings.setUsePreviewModel(enabled)
        appScope.launch {
            ai.resetModel()
            ai.warmUp()
            people.drainPending()
        }
    }

    /** Writes the full JSON export to [uri]. Returns whether it worked. */
    suspend fun export(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { data.exportJson(it) } != null
        }.getOrDefault(false)
    }

    suspend fun inventory(): List<StoredFile> = data.inventory()

    fun wipe(scope: WipeScope, onDone: () -> Unit) {
        appScope.launch {
            data.wipe(scope)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    fun deleteLegacyNotes() {
        appScope.launch { data.deleteLegacyNotes() }
    }

    fun reembedAll(onDone: (Int) -> Unit) {
        appScope.launch {
            val count = people.reembedAll()
            withContext(Dispatchers.Main) { onDone(count) }
        }
    }

    fun clearTrace() = ai.clearTrace()
}
