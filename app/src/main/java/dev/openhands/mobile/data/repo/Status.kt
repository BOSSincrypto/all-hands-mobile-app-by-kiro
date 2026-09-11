package dev.openhands.mobile.data.repo

/**
 * Conversation lifecycle mapped to what the UI must decide: is work ongoing, is it done,
 * or does the user need to act? Enum values mirror the API's string enums exactly.
 */
enum class SandboxStatus {
    STARTING, RUNNING, PAUSED, ERROR, MISSING, UNKNOWN;

    companion object {
        /** Absent status means the sandbox does not exist, which the API models as MISSING. */
        fun from(raw: String?): SandboxStatus = when {
            raw == null -> MISSING
            else -> entries.firstOrNull { it.name == raw.uppercase() } ?: UNKNOWN
        }
    }
}

enum class ExecutionStatus {
    IDLE, RUNNING, PAUSED, WAITING_FOR_CONFIRMATION, FINISHED, ERROR, STUCK, DELETING, UNKNOWN;

    companion object {
        fun from(raw: String?): ExecutionStatus = when (raw?.lowercase()) {
            null -> UNKNOWN
            "idle" -> IDLE
            "running" -> RUNNING
            "paused" -> PAUSED
            "waiting_for_confirmation" -> WAITING_FOR_CONFIRMATION
            "finished" -> FINISHED
            "error" -> ERROR
            "stuck" -> STUCK
            "deleting" -> DELETING
            else -> UNKNOWN
        }
    }
}

enum class StartStatus {
    WORKING,
    WAITING_FOR_SANDBOX,
    PREPARING_REPOSITORY,
    RUNNING_SETUP_SCRIPT,
    SETTING_UP_GIT_HOOKS,
    SETTING_UP_SKILLS,
    STARTING_CONVERSATION,
    READY,
    ERROR,
    UNKNOWN;

    val isTerminal: Boolean get() = this == READY || this == ERROR

    companion object {
        fun from(raw: String?): StartStatus =
            entries.firstOrNull { it.name == raw?.uppercase() } ?: UNKNOWN
    }
}

/** Whether polling should stop. Mirrors the terminal set documented for the Cloud API. */
val ExecutionStatus.isTerminal: Boolean
    get() = this in TERMINAL_EXECUTION_STATES

private val TERMINAL_EXECUTION_STATES = setOf(
    ExecutionStatus.FINISHED,
    ExecutionStatus.ERROR,
    ExecutionStatus.STUCK,
    ExecutionStatus.WAITING_FOR_CONFIRMATION,
)

/** True when the agent is doing work and the UI should keep showing progress. */
val ExecutionStatus.isActive: Boolean
    get() = this == ExecutionStatus.RUNNING || this == ExecutionStatus.PAUSED

/** True when the sandbox can no longer produce progress without user action. */
val SandboxStatus.isDead: Boolean
    get() = this == SandboxStatus.ERROR || this == SandboxStatus.MISSING
