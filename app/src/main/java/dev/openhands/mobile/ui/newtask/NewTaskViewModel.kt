package dev.openhands.mobile.ui.newtask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openhands.mobile.data.remote.Branch
import dev.openhands.mobile.data.remote.LlmModel
import dev.openhands.mobile.data.remote.Repository
import dev.openhands.mobile.data.repo.ConversationRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewTaskState(
    val prompt: String = "",
    val provider: String = "github",
    val repositoryQuery: String = "",
    val repositories: List<Repository> = emptyList(),
    val selectedRepository: Repository? = null,
    val branches: List<Branch> = emptyList(),
    val selectedBranch: String? = null,
    val models: List<LlmModel> = emptyList(),
    /** Null means "use the account default configured in OpenHands settings". */
    val selectedModel: String? = null,
    val loadingRepositories: Boolean = false,
    val loadingBranches: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = prompt.isNotBlank() && !submitting
}

/** Emitted once the server accepted the task, so the UI can hand off to the launch service. */
data class LaunchHandoff(val startTaskId: String, val title: String)

private const val SEARCH_DEBOUNCE_MS = 350L

@HiltViewModel
class NewTaskViewModel @Inject constructor(
    private val repository: ConversationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(NewTaskState())
    val state: StateFlow<NewTaskState> = _state.asStateFlow()

    private val _handoff = MutableStateFlow<LaunchHandoff?>(null)
    val handoff: StateFlow<LaunchHandoff?> = _handoff.asStateFlow()

    private var repoSearchJob: Job? = null

    init {
        loadModels()
        searchRepositories("")
    }

    fun onPromptChange(value: String) = _state.update { it.copy(prompt = value) }

    fun onProviderChange(value: String) {
        _state.update { it.copy(provider = value, selectedRepository = null, branches = emptyList()) }
        searchRepositories(_state.value.repositoryQuery)
    }

    fun onRepositoryQueryChange(value: String) {
        _state.update { it.copy(repositoryQuery = value) }
        searchRepositories(value)
    }

    fun onRepositorySelected(repo: Repository?) {
        _state.update {
            it.copy(
                selectedRepository = repo,
                selectedBranch = repo?.mainBranch,
                branches = emptyList(),
            )
        }
        if (repo != null) loadBranches(repo)
    }

    fun onBranchSelected(branch: String?) = _state.update { it.copy(selectedBranch = branch) }

    fun onModelSelected(model: String?) = _state.update { it.copy(selectedModel = model) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun consumeHandoff() {
        _handoff.value = null
    }

    /**
     * Creates the conversation and returns immediately with the start task id.
     *
     * Waiting for READY happens in a foreground service, so closing the app right after
     * tapping Start does not abandon the launch.
     */
    fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        viewModelScope.launch {
            _state.update { it.copy(submitting = true, error = null) }
            val result = runCatching {
                repository.startConversation(
                    prompt = current.prompt.trim(),
                    repository = current.selectedRepository?.fullName,
                    branch = current.selectedBranch,
                    gitProvider = current.selectedRepository?.gitProvider,
                    model = current.selectedModel,
                    title = current.prompt.trim().take(60),
                )
            }
            result.fold(
                onSuccess = { task ->
                    _handoff.value = LaunchHandoff(task.id, current.prompt.trim().take(40))
                    _state.update { NewTaskState(models = current.models, provider = current.provider) }
                },
                onFailure = { error ->
                    _state.update { it.copy(submitting = false, error = error.message) }
                },
            )
        }
    }

    private fun searchRepositories(query: String) {
        repoSearchJob?.cancel()
        repoSearchJob = viewModelScope.launch {
            // Debounce so typing a repo name does not fire a request per keystroke.
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(loadingRepositories = true) }
            runCatching {
                repository.searchRepositories(_state.value.provider, query.takeIf { it.isNotBlank() })
            }.fold(
                onSuccess = { repos ->
                    _state.update { it.copy(repositories = repos, loadingRepositories = false) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(loadingRepositories = false, error = error.message)
                    }
                },
            )
        }
    }

    private fun loadBranches(repo: Repository) {
        viewModelScope.launch {
            _state.update { it.copy(loadingBranches = true) }
            runCatching {
                repository.searchBranches(repo.gitProvider, repo.fullName, "")
            }.fold(
                onSuccess = { branches ->
                    _state.update { it.copy(branches = branches, loadingBranches = false) }
                },
                onFailure = { _state.update { it.copy(loadingBranches = false) } },
            )
        }
    }

    private fun loadModels() {
        viewModelScope.launch {
            runCatching { repository.models() }
                .onSuccess { models -> _state.update { it.copy(models = models) } }
        }
    }
}
