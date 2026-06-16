package com.alas.dashboard.android.core.notification

import com.alas.dashboard.android.core.model.NotificationRule
import com.alas.dashboard.android.core.model.ResourceSnapshot
import com.alas.dashboard.android.core.model.RuleKind
import com.alas.dashboard.android.core.model.RuleRuntimeState
import com.alas.dashboard.android.core.model.ThresholdDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThresholdNotifierTest {
    @Test
    fun persistentRuleTriggersAfterDuration() {
        val rule = NotificationRule(
            id = "rule-1",
            resourceName = "Oil",
            direction = ThresholdDirection.BELOW,
            threshold = 1000,
            kind = RuleKind.PERSISTENT,
            durationMinutes = 10,
        )
        val resource = ResourceSnapshot(
            resourceName = "Oil",
            recordedAtMs = 0L,
            receivedAtMs = 0L,
            value = 900,
            limit = 2000,
            total = null,
            color = "#FFFFFF",
        )

        val initial = evaluateRule(rule, resource, RuleRuntimeState(ruleId = rule.id), nowMs = 0L)
        val triggered = evaluateRule(rule, resource, initial, nowMs = 10 * 60_000L)

        assertFalse(initial.persistentShown)
        assertTrue(triggered.persistentShown)
    }

    @Test
    fun oneShotRuleResetsAfterReturningToSafeRange() {
        val rule = NotificationRule(
            id = "rule-2",
            resourceName = "Gem",
            direction = ThresholdDirection.ABOVE,
            threshold = 100,
            kind = RuleKind.ONE_SHOT,
        )
        val aboveThreshold = ResourceSnapshot(
            resourceName = "Gem",
            recordedAtMs = 0L,
            receivedAtMs = 0L,
            value = 120,
            limit = null,
            total = null,
            color = "#FFFFFF",
        )
        val belowThreshold = aboveThreshold.copy(value = 80)

        val triggered = evaluateRule(rule, aboveThreshold, RuleRuntimeState(ruleId = rule.id), nowMs = 0L)
        val reset = evaluateRule(rule, belowThreshold, triggered, nowMs = 60_000L)

        assertTrue(triggered.hasTriggered)
        assertFalse(reset.hasTriggered)
    }
}
