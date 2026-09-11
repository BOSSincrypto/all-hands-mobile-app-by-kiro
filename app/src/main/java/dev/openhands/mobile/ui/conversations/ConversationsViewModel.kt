package dev.openhands.mobile.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openhands.mobile.data.repo.ConversationRepository
import dev.openhands.mobile.data.repo.ConversationSummary
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ConversationsViewModel @Inject constructor(
    private val repository: ConversationRepository,
) : ViewModel() {

    /** Cache-backed, so the list renders offline and then updates after a refresh. */
    val conversations: StateFlow<List<ConversationSummary>> =
        repository.observeConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            runCatching { repository.refreshConversations() }
                .onFailure { _error.value = it.message }
                .onSuccess { _error.value = null }
            _refreshing.value = false
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }.onFailure { _error.value = it.message }
        }
    }

    fun dismissError() {
        _error.value = null
    }
}
