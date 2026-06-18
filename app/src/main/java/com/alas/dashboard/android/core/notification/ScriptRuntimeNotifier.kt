package com.alas.dashboard.android.core.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.alas.dashboard.android.core.datastore.SettingsStore
import com.alas.dashboard.android.core.model.ScriptRuntimeEvent
import com.alas.dashboard.android.core.model.ScriptStatusRuntimeState
import com.alas.dashboard.android.core.model.isRunningStatus
import com.alas.dashboard.android.core.model.sourceKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private const val CHANNEL_SCRIPT_STATUS_CHANGE = "script_status_change"
private const val CHANNEL_SCRIPT_STATUS_PERSISTENT = "script_status_persistent"
private const val CHANGE_NOTIFICATION_SALT = 0x1100
private const val PERSISTENT_NOTIFICATION_SALT = 0x2200

@Singleton
class ScriptRuntimeNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
) {
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCRIPT_STATUS_CHANGE,
                "脚本状态变化提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SCRIPT_STATUS_PERSISTENT,
                "脚本长时间未运行提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    suspend fun evaluate(events: List<ScriptRuntimeEvent>) {
        ensureChannels()
        val prefs = settingsStore.appPreferences.first()
        if (!prefs.scriptStatusAlertsEnabled) {
            clearAll()
            return
        }

        val currentStates = settingsStore.scriptStatusRuntimeStatesSnapshot()
            .associateBy { it.sourceKey }
            .toMutableMap()
        val nextStates = mutableListOf<ScriptStatusRuntimeState>()
        val activeKeys = events.map { it.sourceKey() }.toSet()

        currentStates.keys
            .filterNot { it in activeKeys }
            .forEach { missingKey ->
                cancelChangeNotification(missingKey)
                cancelPersistentNotification(missingKey)
            }

        val nowMs = System.currentTimeMillis()
        events.sortedBy { it.sourceKey() }.forEach { event ->
            val previous = currentStates[event.sourceKey()] ?: ScriptStatusRuntimeState(sourceKey = event.sourceKey())
            val evaluation = evaluateScriptRuntimeState(
                event = event,
                previous = previous,
                persistentMinutes = prefs.scriptStatusPersistentMinutes,
                nowMs = nowMs,
            )

            if (prefs.scriptStatusChangeNotificationsEnabled && evaluation.shouldNotifyStatusChange) {
                notifyStatusChange(event)
            } else if (!prefs.scriptStatusChangeNotificationsEnabled) {
                cancelChangeNotification(event.sourceKey())
            }

            if (prefs.scriptStatusPersistentNotificationsEnabled && evaluation.shouldNotifyPersistent) {
                notifyPersistent(event, prefs.scriptStatusPersistentMinutes)
            } else if (!prefs.scriptStatusPersistentNotificationsEnabled || evaluation.shouldCancelPersistent) {
                cancelPersistentNotification(event.sourceKey())
            }

            nextStates += if (prefs.scriptStatusPersistentNotificationsEnabled) {
                evaluation.nextState
            } else {
                evaluation.nextState.copy(persistentShown = false)
            }
        }

        settingsStore.saveScriptStatusRuntimeStates(nextStates)
    }

    suspend fun clearAll() {
        settingsStore.scriptStatusRuntimeStatesSnapshot().forEach { state ->
            cancelChangeNotification(state.sourceKey)
            cancelPersistentNotification(state.sourceKey)
        }
        settingsStore.saveScriptStatusRuntimeStates(emptyList())
    }

    private fun notifyStatusChange(event: ScriptRuntimeEvent) {
        val content = buildString {
            append("${event.instanceLabel()} 状态变为 ${event.status}")
            event.reason.takeUnless { it.isNullOrBlank() }?.let { append("，原因：$it") }
        }
        notify(
            id = changeNotificationId(event.sourceKey()),
            notification = NotificationCompat.Builder(context, CHANNEL_SCRIPT_STATUS_CHANGE)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("脚本状态变化")
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun notifyPersistent(event: ScriptRuntimeEvent, durationMinutes: Int) {
        val content = buildString {
            append("${event.instanceLabel()} 已连续 $durationMinutes 分钟不在 running")
            event.reason.takeUnless { it.isNullOrBlank() }?.let { append("，原因：$it") }
        }
        notify(
            id = persistentNotificationId(event.sourceKey()),
            notification = NotificationCompat.Builder(context, CHANNEL_SCRIPT_STATUS_PERSISTENT)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("脚本长时间未运行")
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build(),
        )
    }

    private fun cancelChangeNotification(sourceKey: String) {
        NotificationManagerCompat.from(context).cancel(changeNotificationId(sourceKey))
    }

    private fun cancelPersistentNotification(sourceKey: String) {
        NotificationManagerCompat.from(context).cancel(persistentNotificationId(sourceKey))
    }

    private fun notify(id: Int, notification: Notification) {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }

    private fun changeNotificationId(sourceKey: String): Int = sourceKey.hashCode() xor CHANGE_NOTIFICATION_SALT

    private fun persistentNotificationId(sourceKey: String): Int = sourceKey.hashCode() xor PERSISTENT_NOTIFICATION_SALT
}

data class ScriptRuntimeEvaluationResult(
    val nextState: ScriptStatusRuntimeState,
    val shouldNotifyStatusChange: Boolean,
    val shouldNotifyPersistent: Boolean,
    val shouldCancelPersistent: Boolean,
)

fun evaluateScriptRuntimeState(
    event: ScriptRuntimeEvent,
    previous: ScriptStatusRuntimeState,
    persistentMinutes: Int,
    nowMs: Long,
): ScriptRuntimeEvaluationResult {
    if (previous.lastRecordedAtMs != null && event.recordedAtMs < previous.lastRecordedAtMs) {
        return ScriptRuntimeEvaluationResult(
            nextState = previous,
            shouldNotifyStatusChange = false,
            shouldNotifyPersistent = false,
            shouldCancelPersistent = false,
        )
    }

    val statusChanged = previous.lastStatus != null && !previous.lastStatus.equals(event.status, ignoreCase = true)
    val isRunning = event.isRunningStatus()
    val nonRunningSinceMs = if (isRunning) {
        null
    } else {
        previous.nonRunningSinceMs ?: event.recordedAtMs
    }
    val durationMs = persistentMinutes.coerceAtLeast(1) * 60_000L
    val shouldNotifyPersistent = !isRunning &&
        !previous.persistentShown &&
        nonRunningSinceMs != null &&
        nowMs - nonRunningSinceMs >= durationMs
    val shouldCancelPersistent = isRunning && previous.persistentShown

    return ScriptRuntimeEvaluationResult(
        nextState = ScriptStatusRuntimeState(
            sourceKey = event.sourceKey(),
            lastStatus = event.status,
            lastReason = event.reason,
            lastRecordedAtMs = event.recordedAtMs,
            nonRunningSinceMs = nonRunningSinceMs,
            persistentShown = if (isRunning) false else previous.persistentShown || shouldNotifyPersistent,
        ),
        shouldNotifyStatusChange = statusChanged,
        shouldNotifyPersistent = shouldNotifyPersistent,
        shouldCancelPersistent = shouldCancelPersistent,
    )
}

private fun ScriptRuntimeEvent.instanceLabel(): String =
    sourceConfig?.takeIf { it.isNotBlank() }?.let { "$sourceInstance/$it" } ?: sourceInstance
