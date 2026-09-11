package dev.openhands.mobile.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.openhands.mobile.R
import dev.openhands.mobile.data.repo.ExecutionStatus
import dev.openhands.mobile.data.repo.SandboxStatus

/** Human-readable label and colour for a conversation's combined state. */
data class StatusPresentation(
    @StringRes val label: Int,
    val color: Color,
    val showSpinner: Boolean,
)

@Composable
fun statusPresentation(
    sandbox: SandboxStatus,
    execution: ExecutionStatus,
): StatusPresentation {
    val scheme = MaterialTheme.colorScheme
    // Sandbox problems dominate: without a live sandbox the execution status is meaningless.
    return when {
        sandbox == SandboxStatus.ERROR ->
            StatusPresentation(R.string.status_sandbox_error, scheme.error, false)

        sandbox == SandboxStatus.MISSING ->
            StatusPresentation(R.string.status_sandbox_missing, scheme.outline, false)

        sandbox == SandboxStatus.STARTING ->
            StatusPresentation(R.string.status_starting, scheme.primary, true)

        sandbox == SandboxStatus.PAUSED ->
            StatusPresentation(R.string.status_paused, scheme.outline, false)

        execution == ExecutionStatus.RUNNING ->
            StatusPresentation(R.string.status_running, scheme.primary, true)

        execution == ExecutionStatus.WAITING_FOR_CONFIRMATION ->
            StatusPresentation(R.string.status_waiting_confirmation, scheme.tertiary, false)

        execution == ExecutionStatus.FINISHED ->
            StatusPresentation(R.string.status_finished, scheme.secondary, false)

        execution == ExecutionStatus.ERROR ->
            StatusPresentation(R.string.status_error, scheme.error, false)

        execution == ExecutionStatus.STUCK ->
            StatusPresentation(R.string.status_stuck, scheme.error, false)

        execution == ExecutionStatus.PAUSED ->
            StatusPresentation(R.string.status_paused, scheme.outline, false)

        execution == ExecutionStatus.IDLE ->
            StatusPresentation(R.string.status_idle, scheme.secondary, false)

        else -> StatusPresentation(R.string.status_unknown, scheme.outline, false)
    }
}

@Composable
fun StatusChip(
    sandbox: SandboxStatus,
    execution: ExecutionStatus,
    modifier: Modifier = Modifier,
) {
    val presentation = statusPresentation(sandbox, execution)
    AssistChip(
        onClick = {},
        enabled = false,
        modifier = modifier,
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = presentation.color,
            disabledLeadingIconContentColor = presentation.color,
        ),
        label = {
            Row {
                if (presentation.showSpinner) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = presentation.color,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(presentation.label))
            }
        },
    )
}
