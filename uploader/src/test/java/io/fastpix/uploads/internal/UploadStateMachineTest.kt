package io.fastpix.uploads.internal

import io.fastpix.uploads.UploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadStateMachineTest {

    private fun recording(): Pair<UploadStateMachine, MutableList<UploadState>> {
        val emitted = mutableListOf<UploadState>()
        val sm = UploadStateMachine { emitted.add(it) }
        return sm to emitted
    }

    @Test fun `initial state is IDLE and no callback fires`() {
        val (sm, emitted) = recording()
        assertEquals(UploadState.IDLE, sm.state)
        assertTrue(emitted.isEmpty())
    }

    @Test fun `happy path IDLE -- PREPARING -- UPLOADING -- COMPLETED`() {
        val (sm, emitted) = recording()
        assertTrue(sm.transition(UploadState.PREPARING))
        assertTrue(sm.transition(UploadState.UPLOADING))
        assertTrue(sm.transition(UploadState.COMPLETED))
        assertEquals(
            listOf(UploadState.PREPARING, UploadState.UPLOADING, UploadState.COMPLETED),
            emitted,
        )
        assertTrue(sm.isTerminal())
    }

    @Test fun `pause then resume cycles UPLOADING and PAUSED and QUERYING_STATUS`() {
        val (sm, _) = recording()
        sm.transition(UploadState.PREPARING)
        sm.transition(UploadState.UPLOADING)
        assertTrue(sm.transition(UploadState.PAUSED))
        assertTrue(sm.transition(UploadState.QUERYING_STATUS))
        assertTrue(sm.transition(UploadState.UPLOADING))
    }

    @Test fun `network loss while uploading transitions to NETWORK_LOST then recovers via QUERYING_STATUS`() {
        val (sm, _) = recording()
        sm.transition(UploadState.PREPARING)
        sm.transition(UploadState.UPLOADING)
        assertTrue(sm.transition(UploadState.NETWORK_LOST))
        assertTrue(sm.transition(UploadState.QUERYING_STATUS))
        assertTrue(sm.transition(UploadState.UPLOADING))
    }

    @Test fun `terminal states are sticky`() {
        for (terminal in listOf(UploadState.COMPLETED, UploadState.FAILED, UploadState.CANCELLED)) {
            val (sm, emitted) = recording()
            sm.transition(UploadState.PREPARING)
            sm.transition(UploadState.UPLOADING)
            sm.transition(terminal)
            emitted.clear()
            // Every further transition should be rejected silently.
            UploadState.values().forEach { next ->
                assertFalse(
                    "transition $terminal -> $next must be rejected",
                    sm.transition(next),
                )
            }
            assertTrue(emitted.isEmpty())
            assertEquals(terminal, sm.state)
        }
    }

    @Test fun `transition to current state is a no-op and emits nothing`() {
        val (sm, emitted) = recording()
        sm.transition(UploadState.PREPARING)
        emitted.clear()
        assertFalse(sm.transition(UploadState.PREPARING))
        assertTrue(emitted.isEmpty())
    }

    @Test fun `cannot jump straight from IDLE to UPLOADING`() {
        val (sm, _) = recording()
        assertFalse(sm.transition(UploadState.UPLOADING))
        assertEquals(UploadState.IDLE, sm.state)
    }

    @Test fun `cannot go from PAUSED back to UPLOADING without QUERYING_STATUS`() {
        // Resume must always go through status-query — that's the architectural rule.
        val (sm, _) = recording()
        sm.transition(UploadState.PREPARING)
        sm.transition(UploadState.UPLOADING)
        sm.transition(UploadState.PAUSED)
        assertFalse(sm.transition(UploadState.UPLOADING))
        assertEquals(UploadState.PAUSED, sm.state)
    }

    @Test fun `RETRYING goes through QUERYING_STATUS before UPLOADING`() {
        val (sm, _) = recording()
        sm.transition(UploadState.PREPARING)
        sm.transition(UploadState.UPLOADING)
        sm.transition(UploadState.RETRYING)
        assertFalse(
            "RETRYING -> UPLOADING must not be allowed; status query is required",
            sm.transition(UploadState.UPLOADING),
        )
        assertTrue(sm.transition(UploadState.QUERYING_STATUS))
        assertTrue(sm.transition(UploadState.UPLOADING))
    }

    @Test fun `cancel reachable from every non-terminal state`() {
        for (origin in UploadState.values().filter { !it.isTerminal }) {
            val (sm, _) = recording()
            // Drive from IDLE to origin via the canonical happy path.
            driveTo(sm, origin)
            assertTrue(
                "expected CANCELLED reachable from $origin",
                sm.transition(UploadState.CANCELLED),
            )
        }
    }

    private fun driveTo(sm: UploadStateMachine, target: UploadState) {
        // Smallest path graph the test cases above exercise.
        when (target) {
            UploadState.IDLE -> Unit
            UploadState.PREPARING -> sm.transition(UploadState.PREPARING)
            UploadState.UPLOADING -> {
                sm.transition(UploadState.PREPARING); sm.transition(UploadState.UPLOADING)
            }
            UploadState.PAUSED -> {
                sm.transition(UploadState.PREPARING); sm.transition(UploadState.UPLOADING)
                sm.transition(UploadState.PAUSED)
            }
            UploadState.RETRYING -> {
                sm.transition(UploadState.PREPARING); sm.transition(UploadState.UPLOADING)
                sm.transition(UploadState.RETRYING)
            }
            UploadState.NETWORK_LOST -> {
                sm.transition(UploadState.PREPARING); sm.transition(UploadState.UPLOADING)
                sm.transition(UploadState.NETWORK_LOST)
            }
            UploadState.QUERYING_STATUS -> {
                sm.transition(UploadState.PREPARING); sm.transition(UploadState.UPLOADING)
                sm.transition(UploadState.QUERYING_STATUS)
            }
            else -> error("driveTo only intended for non-terminal states, got $target")
        }
    }
}
