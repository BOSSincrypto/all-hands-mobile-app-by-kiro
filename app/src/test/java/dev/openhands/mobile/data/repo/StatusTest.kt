package dev.openhands.mobile.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the mapping from the API's status strings, which are documented at
 * https://docs.openhands.dev/openhands/usage/cloud/cloud-api (checked 2026-09-11).
 */
class StatusTest {

    @Test
    fun `execution statuses parse from api strings`() {
        assertEquals(ExecutionStatus.RUNNING, ExecutionStatus.from("running"))
        assertEquals(ExecutionStatus.IDLE, ExecutionStatus.from("idle"))
        assertEquals(ExecutionStatus.FINISHED, ExecutionStatus.from("finished"))
        assertEquals(ExecutionStatus.STUCK, ExecutionStatus.from("stuck"))
        assertEquals(
            ExecutionStatus.WAITING_FOR_CONFIRMATION,
            ExecutionStatus.from("waiting_for_confirmation"),
        )
        assertEquals(ExecutionStatus.DELETING, ExecutionStatus.from("deleting"))
    }

    @Test
    fun `null execution status means unknown rather than crashing`() {
        assertEquals(ExecutionStatus.UNKNOWN, ExecutionStatus.from(null))
        assertEquals(ExecutionStatus.UNKNOWN, ExecutionStatus.from("something_new"))
    }

    @Test
    fun `terminal statuses match the documented polling exit set`() {
        val terminal = ExecutionStatus.entries.filter { it.isTerminal }.toSet()
        assertEquals(
            setOf(
                ExecutionStatus.FINISHED,
                ExecutionStatus.ERROR,
                ExecutionStatus.STUCK,
                ExecutionStatus.WAITING_FOR_CONFIRMATION,
            ),
            terminal,
        )
    }

    @Test
    fun `waiting for confirmation is terminal because it needs the user`() {
        // The agent cannot progress on its own, so polling must stop and prompt instead.
        assertTrue(ExecutionStatus.WAITING_FOR_CONFIRMATION.isTerminal)
        assertFalse(ExecutionStatus.WAITING_FOR_CONFIRMATION.isActive)
    }

    @Test
    fun `active statuses drive the live stream`() {
        assertTrue(ExecutionStatus.RUNNING.isActive)
        assertTrue(ExecutionStatus.PAUSED.isActive)
        assertFalse(ExecutionStatus.FINISHED.isActive)
        assertFalse(ExecutionStatus.IDLE.isActive)
    }

    @Test
    fun `sandbox statuses parse and flag dead sandboxes`() {
        assertEquals(SandboxStatus.RUNNING, SandboxStatus.from("RUNNING"))
        assertEquals(SandboxStatus.MISSING, SandboxStatus.from(null))
        assertTrue(SandboxStatus.ERROR.isDead)
        assertTrue(SandboxStatus.MISSING.isDead)
        assertFalse(SandboxStatus.PAUSED.isDead)
        assertFalse(SandboxStatus.RUNNING.isDead)
    }

    @Test
    fun `sandbox missing parses to missing not unknown`() {
        // MISSING is a real API value; treating it as UNKNOWN would hide a dead sandbox.
        assertEquals(SandboxStatus.MISSING, SandboxStatus.from("MISSING"))
    }

    @Test
    fun `start statuses cover every documented step`() {
        listOf(
            "WORKING",
            "WAITING_FOR_SANDBOX",
            "PREPARING_REPOSITORY",
            "RUNNING_SETUP_SCRIPT",
            "SETTING_UP_GIT_HOOKS",
            "SETTING_UP_SKILLS",
            "STARTING_CONVERSATION",
            "READY",
            "ERROR",
        ).forEach { raw ->
            assertEquals(
                "Unmapped start status: $raw",
                raw,
                StartStatus.from(raw).name,
            )
        }
    }

    @Test
    fun `only ready and error stop the start poll`() {
        assertTrue(StartStatus.READY.isTerminal)
        assertTrue(StartStatus.ERROR.isTerminal)
        assertFalse(StartStatus.WAITING_FOR_SANDBOX.isTerminal)
        assertFalse(StartStatus.SETTING_UP_SKILLS.isTerminal)
    }
}
