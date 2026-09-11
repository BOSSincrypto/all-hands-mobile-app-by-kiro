package dev.openhands.mobile.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openhands.mobile.data.remote.AppConversation
import dev.openhands.mobile.data.repo.ChatEntry
import dev.openhands.mobile.data.repo.ConversationRepository
import dev.openhands.mobile.data.repo.ConversationSummary
import dev.openhands.mobile.data.repo.ExecutionStatus
import dev.openhands.mobile.data.repo.SandboxStatus
import dev.openhands.mobile.data.remote.StreamMessage
import dev.openhands.mobile.data.repo.isActive
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val sending: Boolean = false,
    val streaming: Boolean = false,
    val error: String? = null,
    val draft: String = "",
)

/**
 * Polling cadence while the agent is working. The WebSocket carries events, so this only
 * refreshes the coarse status the socket does not report.
 */
private const val STATUS_POLL_MS = 8_000L

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ConversationRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    val summary: StateFlow<ConversationSummary?> =
        repository.observeConversation(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val entries: StateFlow<List<ChatEntry>> =
        repository.observeEvents(conversationId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    /** Live record kept for agent-server operations that need its URL and session key. */
    private var conversation: AppConversation? = null
    private var streamJob: Job? = null
    private var pollJob: Job? = null

    init {
        refresh()
    }

    fun onDraftChange(value: String) = _ui.update { it.copy(draft = value) }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                conversation = repository.refreshConversation(conversationId)
                repository.refreshEvents(conversationId)
            }.onFailure { error -> _ui.update { it.copy(error = error.message) } }
            syncLiveUpdates()
        }
    }

    fun send() {
        val text = _ui.value.draft.trim()
        if (text.isEmpty() || _ui.value.sending) return

        viewModelScope.launch {
            _ui.update { it.copy(sending = true, error = null) }
            val running = summary.value?.execution?.isActive == true
            runCatching { repository.sendMessage(conversationId, text, running) }
                .fold(
                    onSuccess = { _ui.update { it.copy(sending = false, draft = "") } },
                    onFailure = { error ->
                        _ui.update { it.copy(sending = false, error = error.message) }
                    },
                )
            refresh()
        }
    }

    /** Answers a `waiting_for_confirmation` prompt from the agent. */
    fun respondToConfirmation(accept: Boolean) {
        val target = conversation ?: return
        viewModelScope.launch {
            runCatching { repository.respondToConfirmation(target, accept) }
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
            refresh()
        }
    }

    fun pauseAgent() {
        val target = conversation ?: return
        viewModelScope.launch {
            runCatching { repository.pauseAgent(target) }
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
            refresh()
        }
    }

    fun resumeSandbox() {
        val sandboxId = conversation?.sandboxId ?: return
        viewModelScope.launch {
            runCatching { repository.resumeSandbox(sandboxId) }
                .onFailure { error -> _ui.update { it.copy(error = error.message) } }
            refresh()
        }
    }

    fun dismissError() = _ui.update { it.copy(error = null) }

    /**
     * Attaches the event socket and status poller only while the sandbox is live, so an
     * idle or finished conversation costs no network traffic.
     */
    private fun syncLiveUpdates() {
        val target = conversation
        val sandboxLive = SandboxStatus.from(target?.sandboxStatus) == SandboxStatus.RUNNING

        if (!sandboxLive) {
            stopLiveUpdates()
            return
        }
        if (streamJob?.isActive != true) startStream(target!!)
        if (pollJob?.isActive != true) startStatusPolling()
    }

    private fun startStream(target: AppConversation) {
        val stream = repository.streamEvents(target) ?: return
        streamJob = viewModelScope.launch {
            _ui.update { it.copy(streaming = true) }
            stream.collect { message ->
                when (message) {
                    is StreamMessage.Event ->
                        repository.cacheStreamedEvent(conversationId, message.event)

                    is StreamMessage.Closed, is StreamMessage.Error ->
                        _ui.update { it.copy(streaming = false) }

                    StreamMessage.Connected -> _ui.update { it.copy(streaming = true) }
                }
            }
            _ui.update { it.copy(streaming = false) }
        }
    }

    private fun startStatusPolling() {
        pollJob = viewModelScope.launch {
            while (true) {
                delay(STATUS_POLL_MS)
                val updated = runCatching {
                    repository.refreshConversation(conversationId)
                }.getOrNull() ?: continue
                conversation = updated

                val execution = ExecutionStatus.from(updated.executionStatus)
                val sandbox = SandboxStatus.from(updated.sandboxStatus)

                if (sandbox != SandboxStatus.RUNNING || !execution.isActive) {
                    // Pull a final page so the last events are cached, then stop streaming.
                    runCatching { repository.refreshEvents(conversationId) }
                    stopStream()
                    return@launch
                }

                // A dropped socket (network switch, sandbox restart) would otherwise freeze
                // the chat while status kept updating. Reconnect and backfill the gap.
                if (streamJob?.isActive != true) {
                    runCatching { repository.refreshEvents(conversationId) }
                    startStream(updated)
                }
            }
        }
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        _ui.update { it.copy(streaming = false) }
    }

    private fun stopLiveUpdates() {
        pollJob?.cancel()
        pollJob = null
        stopStream()
    }

    override fun onCleared() {
        stopLiveUpdates()
    }
}
