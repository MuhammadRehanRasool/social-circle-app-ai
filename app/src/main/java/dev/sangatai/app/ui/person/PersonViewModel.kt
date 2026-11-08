package dev.sangatai.app.ui.person

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sangatai.app.di.ApplicationScope
import dev.sangatai.app.domain.model.Person
import dev.sangatai.app.domain.model.PersonDetail
import dev.sangatai.app.domain.repository.PeopleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Null while loading; [Gone] once the person no longer exists (deleted or merged away). */
sealed interface PersonUiState {
    data object Loading : PersonUiState
    data object Gone : PersonUiState
    data class Shown(val detail: PersonDetail) : PersonUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PersonViewModel @Inject constructor(
    private val people: PeopleRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val personId = MutableStateFlow<Long?>(null)

    val state: StateFlow<PersonUiState> = personId.filterNotNull()
        .flatMapLatest { id -> people.observePerson(id) }
        .map { detail -> if (detail == null) PersonUiState.Gone else PersonUiState.Shown(detail) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersonUiState.Loading)

    /** Everyone, for the merge and link pickers. */
    val everyone: StateFlow<List<Person>> = people.observePeople()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun show(id: Long) {
        personId.value = id
    }

    private fun edit(block: suspend PeopleRepository.(Long) -> Unit) {
        val id = personId.value ?: return
        // App scope: an edit made just before leaving the screen still lands.
        appScope.launch { people.block(id) }
    }

    fun rename(name: String) = edit { renamePerson(it, name) }

    fun setRelation(relation: String?) = edit { setRelation(it, relation) }

    fun addTag(tag: String) = edit { addTag(it, tag) }

    fun removeTag(tag: String) = edit { removeTag(it, tag) }

    fun restoreTag(tag: String) = edit { unsuppressTag(it, tag) }

    fun setLabels(otherId: Long, labels: List<String>) = edit { setConnectionLabels(it, otherId, labels) }

    /** Folds [otherId] into this person. */
    fun mergeIn(otherId: Long) = edit { mergePeople(keepId = it, dropId = otherId) }

    fun deleteInteraction(interactionId: Long) = edit { deleteInteraction(interactionId) }

    fun delete(alsoDeleteRants: Boolean) = edit { deletePerson(it, alsoDeleteRants) }
}
