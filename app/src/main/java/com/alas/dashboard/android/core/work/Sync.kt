package com.alas.dashboard.android.core.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alas.dashboard.android.core.notification.ThresholdNotifier
import com.alas.dashboard.android.core.repository.DashboardRepository
import com.alas.dashboard.android.core.widget.updateAllWidgets
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dagger.hilt.android.EntryPointAccessors

private const val PERIODIC_WORK_NAME = "dashboard_periodic_sync"

private const val IMMEDIATE_WORK_NAME = "dashboard_immediate_sync"

private const val SYNC_ALARM_ACTION = "com.alas.dashboard.android.action.SYNC_ALARM"

@Singleton
class DashboardSyncRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DashboardRepository,
    private val thresholdNotifier: ThresholdNotifier,
) {
    suspend fun syncLatest() = repository.refreshLatest().also { latest ->
        thresholdNotifier.evaluate(latest)
        updateAllWidgets(context)
    }
}

@HiltWorker
class DashboardSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRunner: DashboardSyncRunner,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = runCatching {
        syncRunner.syncLatest()
        Result.success()
    }.getOrElse { error ->
        if (error is IllegalArgumentException || error is IllegalStateException) {
            Result.success()
        } else {
            Result.retry()
        }
    }
}

@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: DashboardRepository,
) {
    suspend fun schedule() {
        val prefs = repository.appPreferences.first()
        val account = repository.accountConfig.first()
        val workManager = WorkManager.getInstance(context)
        if (!prefs.backgroundSyncEnabled || account.baseUrl.isBlank() || account.userToken.isBlank()) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            cancelRepeatingSyncAlarm(context)
            return
        }
        val interval = prefs.pollingMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<DashboardSyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        scheduleRepeatingSyncAlarm(context, interval)
    }

    suspend fun enqueueImmediate() {
        val account = repository.accountConfig.first()
        if (account.baseUrl.isBlank() || account.userToken.isBlank()) {
            return
        }
        enqueueImmediateDashboardSync(context)
    }
}

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    val entryPoint = EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        SyncReceiverEntryPoint::class.java,
                    )
                    entryPoint.syncScheduler().schedule()
                    runCatching {
                        entryPoint.syncRunner().syncLatest()
                    }.onFailure {
                        enqueueImmediateDashboardSync(context)
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}

class DashboardSyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == SYNC_ALARM_ACTION) {
            triggerImmediateDashboardSync(context, goAsync())
        }
    }
}

fun triggerImmediateDashboardSync(context: Context, pendingResult: BroadcastReceiver.PendingResult? = null) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                SyncReceiverEntryPoint::class.java,
            )
            runCatching {
                entryPoint.syncRunner().syncLatest()
            }.onFailure {
                enqueueImmediateDashboardSync(context)
            }
        } finally {
            pendingResult?.finish()
        }
    }
}

fun enqueueImmediateDashboardSync(context: Context) {
    val request = OneTimeWorkRequestBuilder<DashboardSyncWorker>().build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        IMMEDIATE_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        request,
    )
}

private fun scheduleRepeatingSyncAlarm(context: Context, intervalMinutes: Long) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intervalMillis = TimeUnit.MINUTES.toMillis(intervalMinutes)
    val pendingIntent = repeatingSyncAlarmPendingIntent(context)
    alarmManager.cancel(pendingIntent)
    alarmManager.setInexactRepeating(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + intervalMillis,
        intervalMillis,
        pendingIntent,
    )
}

private fun cancelRepeatingSyncAlarm(context: Context) {
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val pendingIntent = repeatingSyncAlarmPendingIntent(context)
    alarmManager.cancel(pendingIntent)
    pendingIntent.cancel()
}

private fun repeatingSyncAlarmPendingIntent(context: Context): PendingIntent {
    val intent = Intent(context, DashboardSyncAlarmReceiver::class.java).apply {
        action = SYNC_ALARM_ACTION
    }
    return PendingIntent.getBroadcast(
        context,
        1001,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncReceiverEntryPoint {
    fun syncScheduler(): SyncScheduler
    fun syncRunner(): DashboardSyncRunner
}
