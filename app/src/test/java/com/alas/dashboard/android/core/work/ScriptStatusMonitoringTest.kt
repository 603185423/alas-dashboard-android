package com.alas.dashboard.android.core.work

import com.alas.dashboard.android.core.datastore.scriptStatusAccountScope
import com.alas.dashboard.android.core.model.ScriptRuntimeEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ScriptStatusMonitoringTest {
    @Test
    fun ignoredInstancesAreExcludedWithoutChangingOtherEvents() {
        val events = listOf(
            scriptEvent("old-alas"),
            scriptEvent("current-alas"),
        )

        val monitored = monitoredScriptRuntimeEvents(events, setOf("old-alas"))

        assertEquals(listOf("current-alas"), monitored.map { it.sourceInstance })
    }

    @Test
    fun accountScopeNormalizesTrailingSlashAndSeparatesTokens() {
        val first = scriptStatusAccountScope("https://dashboard.example.com/", "token-a")
        val sameAccount = scriptStatusAccountScope("https://dashboard.example.com", "token-a")
        val otherAccount = scriptStatusAccountScope("https://dashboard.example.com", "token-b")

        assertEquals(first, sameAccount)
        assertNotEquals(first, otherAccount)
    }
}

private fun scriptEvent(sourceInstance: String) = ScriptRuntimeEvent(
    id = 1L,
    sourceInstance = sourceInstance,
    sourceConfig = sourceInstance,
    eventCategory = "script_runtime",
    eventType = "stopped",
    status = "stopped",
    reason = "finish",
    payload = null,
    recordedAtMs = 1_000L,
    receivedAtMs = 1_000L,
)
