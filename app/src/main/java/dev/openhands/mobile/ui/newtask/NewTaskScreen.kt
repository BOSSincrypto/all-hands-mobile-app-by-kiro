package dev.openhands.mobile.ui.newtask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openhands.mobile.R

private val PROVIDERS = listOf("github", "gitlab", "bitbucket")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewTaskScreen(
    onBack: () -> Unit,
    onLaunched: (startTaskId: String, title: String) -> Unit,
    viewModel: NewTaskViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val handoff by viewModel.handoff.collectAsStateWithLifecycle()

    handoff?.let {
        onLaunched(it.startTaskId, it.title)
        viewModel.consumeHandoff()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_task_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = state.prompt,
                onValueChange = viewModel::onPromptChange,
                label = { Text(stringResource(R.string.new_task_prompt_label)) },
                placeholder = { Text(stringResource(R.string.new_task_prompt_hint)) },
                minLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )

            PickerField(
                label = stringResource(R.string.new_task_provider_label),
                selected = state.provider,
                options = PROVIDERS,
                optionLabel = { it.orEmpty() },
                onSelect = { provider -> provider?.let(viewModel::onProviderChange) },
            )

            OutlinedTextField(
                value = state.repositoryQuery,
                onValueChange = viewModel::onRepositoryQueryChange,
                label = { Text(stringResource(R.string.new_task_repo_search_label)) },
                trailingIcon = {
                    if (state.loadingRepositories) {
                        CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            PickerField(
                label = stringResource(R.string.new_task_repo_label),
                selected = state.selectedRepository?.fullName,
                options = listOf<String?>(null) + state.repositories.map { it.fullName },
                optionLabel = { it ?: stringResource(R.string.new_task_no_repo) },
                onSelect = { name ->
                    viewModel.onRepositorySelected(
                        state.repositories.firstOrNull { it.fullName == name },
                    )
                },
                placeholder = stringResource(R.string.new_task_no_repo),
            )

            if (state.selectedRepository != null) {
                PickerField(
                    label = stringResource(R.string.new_task_branch_label),
                    selected = state.selectedBranch,
                    options = state.branches.map { it.name },
                    optionLabel = { it ?: "" },
                    onSelect = viewModel::onBranchSelected,
                    loading = state.loadingBranches,
                    placeholder = stringResource(R.string.new_task_branch_default),
                )
            }

            PickerField(
                label = stringResource(R.string.new_task_model_label),
                selected = state.selectedModel,
                options = listOf<String?>(null) + state.models.map { it.name },
                optionLabel = { it ?: stringResource(R.string.new_task_model_default) },
                onSelect = viewModel::onModelSelected,
                placeholder = stringResource(R.string.new_task_model_default),
            )

            state.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.new_task_start))
                }
            }
            Text(
                text = stringResource(R.string.new_task_background_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Read-only dropdown; the caller supplies the label mapping so nulls can mean "default". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> PickerField(
    label: String,
    selected: T?,
    options: List<T>,
    optionLabel: @Composable (T?) -> String,
    onSelect: (T?) -> Unit,
    loading: Boolean = false,
    placeholder: String = "",
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.let { optionLabel(it) } ?: placeholder,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = {
                if (loading) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(
                androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable,
            ),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
