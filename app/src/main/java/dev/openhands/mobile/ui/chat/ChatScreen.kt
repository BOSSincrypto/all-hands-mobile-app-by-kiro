package dev.openhands.mobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openhands.mobile.R
import dev.openhands.mobile.data.repo.ChatEntry
import dev.openhands.mobile.data.repo.ChatRole
import dev.openhands.mobile.data.repo.ExecutionStatus
import dev.openhands.mobile.data.repo.SandboxStatus
import dev.openhands.mobile.ui.StatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTerminal: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Follow the tail as new events stream in.
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = summary?.title?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.conversation_untitled),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        summary?.repository?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (summary?.execution == ExecutionStatus.RUNNING) {
                        IconButton(onClick = viewModel::pauseAgent) {
                            Icon(Icons.Default.Pause, stringResource(R.string.action_pause))
                        }
                    }
                    IconButton(onClick = onOpenFiles) {
                        Icon(Icons.Default.Folder, stringResource(R.string.files_title))
                    }
                    IconButton(onClick = onOpenTerminal) {
                        Icon(Icons.Default.Terminal, stringResource(R.string.terminal_title))
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (summary?.execution == ExecutionStatus.WAITING_FOR_CONFIRMATION) {
                    ConfirmationBar(
                        onAccept = { viewModel.respondToConfirmation(true) },
                        onReject = { viewModel.respondToConfirmation(false) },
                    )
                }
                if (summary?.sandbox == SandboxStatus.PAUSED) {
                    ResumeBar(onResume = viewModel::resumeSandbox)
                }
                Composer(
                    draft = ui.draft,
                    sending = ui.sending,
                    onDraftChange = viewModel::onDraftChange,
                    onSend = viewModel::send,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                summary?.let { StatusChip(it.sandbox, it.execution) }
                Spacer(Modifier.weight(1f))
                if (ui.streaming) {
                    Text(
                        text = stringResource(R.string.chat_live),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            ui.error?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { viewModel.dismissError() },
                )
            }
            val expanded = remember { mutableStateMapOf<String, Boolean>() }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp,
                    vertical = 8.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    ChatBubble(
                        entry = entry,
                        expanded = expanded[entry.id] ?: !entry.collapsedByDefault,
                        onToggle = {
                            expanded[entry.id] = !(expanded[entry.id] ?: !entry.collapsedByDefault)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry, expanded: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (background, alignEnd) = when (entry.role) {
        ChatRole.USER -> scheme.primaryContainer to true
        ChatRole.AGENT -> scheme.surfaceVariant to false
        ChatRole.TOOL -> scheme.secondaryContainer to false
        ChatRole.TOOL_RESULT -> scheme.surfaceContainerHighest to false
        ChatRole.ERROR -> scheme.errorContainer to false
        ChatRole.SYSTEM -> Color.Transparent to false
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = background,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(if (entry.role == ChatRole.SYSTEM) 1f else 0.92f),
        ) {
            Column(Modifier.padding(10.dp).clickable(enabled = entry.collapsedByDefault) { onToggle() }) {
                entry.toolName?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        entry.securityRisk?.takeIf { risk -> risk == "HIGH" || risk == "MEDIUM" }
                            ?.let { risk ->
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = risk,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.error,
                                )
                            }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = entry.text,
                    style = if (entry.role == ChatRole.TOOL_RESULT || entry.role == ChatRole.TOOL) {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ConfirmationBar(onAccept: () -> Unit, onReject: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                text = stringResource(R.string.chat_confirmation_prompt),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text(stringResource(R.string.action_approve)) }
                OutlinedButton(onClick = onReject) { Text(stringResource(R.string.action_reject)) }
            }
        }
    }
}

@Composable
private fun ResumeBar(onResume: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_sandbox_paused),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onResume) { Text(stringResource(R.string.action_resume)) }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text(stringResource(R.string.chat_message_hint)) },
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSend, enabled = draft.isNotBlank() && !sending) {
                if (sending) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        stringResource(R.string.action_send),
                    )
                }
            }
        }
    }
}
