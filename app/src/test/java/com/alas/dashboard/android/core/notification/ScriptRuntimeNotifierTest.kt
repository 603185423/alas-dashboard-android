package com.alas.dashboard.android.core.notification

import com.alas.dashboard.android.core.model.ScriptRuntimeEvent
import com.alas.dashboard.android.core.model.ScriptStatusRuntimeState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptRuntimeNotifierTest {
    @Test
    fun statusChangeOnlyTriggersWhenPreviousStatusExists() {
        val first = evaluateScriptRuntimeState(
            event = scriptEvent(status = "running", recordedAtMs = 1_000L),
            previous = ScriptStatusRuntimeState(sourceKey = "alas||script_runtime"),
            persistentMinutes = 30,
            nowMs = 1_000L,
        )
        val changed = evaluateScriptRuntimeState(
            event = scriptEvent(status = "error", reason = "exception", recordedAtMs = 2_000L),
            previous = first.nextState,
            persistentMinutes = 30,
            nowMs = 2_000L,
        )

        assertFalse(first.shouldNotifyStatusChange)
        assertTrue(changed.shouldNotifyStatusChange)
    }

    @Test
    fun persistentNotificationTriggersAfterContinuousNonRunningDuration() {
        val initial = evaluateScriptRuntimeState(
            event = scriptEvent(status = "stopped", reason = "manual_stop", recordedAtMs = 0L),
            previous = ScriptStatusRuntimeState(sourceKey = "alas||script_runtime"),
            persistentMinutes = 10,
            nowMs = 5 * 60_000L,
        )
        val triggered = evaluateScriptRuntimeState(
            event = scriptEvent(status = "error", reason = "exception", recordedAtMs = 8 * 60_000L),
            previous = initial.nextState,
            persistentMinutes = 10,
            nowMs = 10 * 60_000L,
        )
        val recovered = evaluateScriptRuntimeState(
            event = scriptEvent(status = "running", reason = "restart", recordedAtMs = 11 * 60_000L),
            previous = triggered.nextState,
            persistentMinutes = 10,
            nowMs = 11 * 60_000L,
        )

        assertFalse(initial.shouldNotifyPersistent)
        assertTrue(triggered.shouldNotifyPersistent)
        assertTrue(recovered.shouldCancelPersistent)
    }
}

private fun scriptEvent(
    status: String,
    reason: String? = null,
    recordedAtMs: Long,
) = ScriptRuntimeEvent(
    id = 1L,
    sourceInstance = "alas",
    sourceConfig = null,
    eventCategory = "script_runtime",
    eventType = "test",
    status = status,
    reason = reason,
    payload = null,
    recordedAtMs = recordedAtMs,
    receivedAtMs = recordedAtMs,
)
