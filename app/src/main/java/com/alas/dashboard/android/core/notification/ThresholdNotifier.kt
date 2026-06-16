package com.alas.dashboard.android.core.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alas.dashboard.android.core.datastore.SettingsStore
import com.alas.dashboard.android.core.model.NotificationRule
import com.alas.dashboard.android.core.model.ResourceSnapshot
import com.alas.dashboard.android.core.model.RuleKind
import com.alas.dashboard.android.core.model.RuleRuntimeState
import com.alas.dashboard.android.core.model.ThresholdDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val CHANNEL_ONCE = "resource_alert_once"
private const val CHANNEL_PERSISTENT = "resource_alert_persistent"

@Singleton
class ThresholdNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
) {
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONCE,
                "一次性资源提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PERSISTENT,
                "持续资源提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    suspend fun evaluate(resources: List<ResourceSnapshot>) {
        ensureChannels()
        val rules = settingsStore.notificationRules.first()
        val currentStates = settingsStore.runtimeStatesSnapshot().associateBy { it.ruleId }.toMutableMap()
        val now = System.currentTimeMillis()

        rules.filter { it.enabled }.forEach { rule ->
            val resource = resources.firstOrNull { it.resourceName == rule.resourceName } ?: return@forEach
            val satisfied = rule.matches(resource)
            val state = currentStates[rule.id] ?: RuleRuntimeState(ruleId = rule.id)

            when (rule.kind) {
                RuleKind.ONE_SHOT -> {
                    if (satisfied && !state.hasTriggered) {
                        notifyOnce(rule, resource)
                        currentStates[rule.id] = state.copy(hasTriggered = true)
                    } else if (!satisfied && state.hasTriggered) {
                        currentStates[rule.id] = state.copy(hasTriggered = false)
                    }
                }

                RuleKind.PERSISTENT -> {
                    if (satisfied) {
                        val start = state.satisfiedSinceMs ?: now
                        val durationMs = rule.durationMinutes.coerceAtLeast(1) * 60_000L
                        val shouldNotify = now - start >= durationMs
                        if (shouldNotify && !state.persistentShown) {
                            notifyPersistent(rule, resource)
                            currentStates[rule.id] = state.copy(
                                satisfiedSinceMs = start,
                                persistentShown = true,
                            )
                        } else {
                            currentStates[rule.id] = state.copy(satisfiedSinceMs = start)
                        }
                    } else {
                        cancelPersistent(rule.id)
                        currentStates[rule.id] = RuleRuntimeState(ruleId = rule.id)
                    }
                }
            }
        }

        settingsStore.saveRuntimeStates(currentStates.values.toList())
    }

    private fun notifyOnce(rule: NotificationRule, resource: ResourceSnapshot) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ONCE)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("资源阈值提醒")
            .setContentText("${resource.resourceName} 已${rule.directionText()} ${rule.threshold}，当前 ${resource.value}")
            .setAutoCancel(true)
            .build()
        notify(rule.id.hashCode(), notification)
    }

    private fun notifyPersistent(rule: NotificationRule, resource: ResourceSnapshot) {
        val notification = NotificationCompat.Builder(context, CHANNEL_PERSISTENT)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("持续阈值提醒")
            .setContentText("${resource.resourceName} 持续${rule.directionText()} ${rule.threshold}，当前 ${resource.value}")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notify(rule.id.hashCode(), notification)
    }

    private fun cancelPersistent(ruleId: String) {
        NotificationManagerCompat.from(context).cancel(ruleId.hashCode())
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}

fun evaluateRule(
    rule: NotificationRule,
    resource: ResourceSnapshot,
    state: RuleRuntimeState,
    nowMs: Long,
): RuleRuntimeState {
    val satisfied = rule.matches(resource)
    return when (rule.kind) {
        RuleKind.ONE_SHOT -> {
            if (satisfied) state.copy(hasTriggered = true) else state.copy(hasTriggered = false)
        }

        RuleKind.PERSISTENT -> {
            if (!satisfied) {
                RuleRuntimeState(ruleId = rule.id)
            } else {
                val since = state.satisfiedSinceMs ?: nowMs
                val triggered = nowMs - since >= rule.durationMinutes.coerceAtLeast(1) * 60_000L
                state.copy(satisfiedSinceMs = since, persistentShown = triggered || state.persistentShown)
            }
        }
    }
}

private fun NotificationRule.matches(resource: ResourceSnapshot): Boolean = when (direction) {
    ThresholdDirection.ABOVE -> resource.value > threshold
    ThresholdDirection.BELOW -> resource.value < threshold
}

private fun NotificationRule.directionText(): String = when (direction) {
    ThresholdDirection.ABOVE -> "高于"
    ThresholdDirection.BELOW -> "低于"
}
