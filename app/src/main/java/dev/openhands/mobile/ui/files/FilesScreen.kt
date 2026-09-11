package dev.openhands.mobile.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.openhands.mobile.R
import dev.openhands.mobile.data.repo.ConversationRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FilesState(
    val path: String = "",
    val entries: List<String> = emptyList(),
    val loading: Boolean = false,
    /** Non-null while previewing a file instead of a directory. */
    val fileContent: String? = null,
    val fileName: String? = null,
    val error: String? = null,
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val repository: ConversationRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()

    init {
        browse("")
    }

    fun browse(path: String) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, fileContent = null, fileName = null) }
            runCatching { repository.listFiles(conversationId, path.takeIf { p -> p.isNotBlank() }) }
                .fold(
                    onSuccess = { entries ->
                        _state.update {
                            it.copy(path = path, entries = entries, loading = false, error = null)
                        }
                    },
                    onFailure = { error ->
                        _state.update { it.copy(loading = false, error = error.message) }
                    },
                )
        }
    }

    fun open(entry: String) {
        // The API returns directories with a trailing slash, so no extra stat call is needed.
        if (entry.endsWith("/")) {
            browse(entry)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { repository.readFile(conversationId, entry) }.fold(
                onSuccess = { content ->
                    _state.update {
                        it.copy(
                            loading = false,
                            fileContent = content,
                            fileName = entry.substringAfterLast('/'),
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    _state.update { it.copy(loading = false, error = error.message) }
                },
            )
        }
    }

    /** Steps one path segment up, or closes an open file preview first. */
    fun up() {
        if (_state.value.fileContent != null) {
            _state.update { it.copy(fileContent = null, fileName = null) }
            return
        }
        val current = _state.value.path.trimEnd('/')
        if (current.isEmpty()) return
        browse(current.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(onBack: () -> Unit, viewModel: FilesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.fileName ?: state.path.ifEmpty {
                            stringResource(R.string.files_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.path.isEmpty() && state.fileContent == null) {
                                onBack()
                            } else {
                                viewModel.up()
                            }
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            val content = state.fileContent
            if (content != null) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            } else {
                LazyColumn {
                    items(state.entries, key = { it }) { entry ->
                        ListItem(
                            headlineContent = {
                                Text(entry.trimEnd('/').substringAfterLast('/'))
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = if (entry.endsWith("/")) {
                                        Icons.Default.Folder
                                    } else {
                                        Icons.AutoMirrored.Filled.InsertDriveFile
                                    },
                                    contentDescription = null,
                                )
                            },
                            modifier = Modifier.clickable { viewModel.open(entry) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
