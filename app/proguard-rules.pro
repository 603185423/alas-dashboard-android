# Keep Android entry components and the background refresh chain stable in release.

# Hilt application / entry points / injected Android components.
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.EntryPoint class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# WorkManager + Hilt worker integration.
-keepnames class * extends androidx.work.ListenableWorker
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep @androidx.hilt.work.HiltWorker class * { <init>(...); }

# Background refresh components used by the system outside direct app code paths.
-keep class com.alas.dashboard.android.core.work.DashboardSyncRunner { *; }
-keep class com.alas.dashboard.android.core.work.SyncScheduler { *; }
-keep class com.alas.dashboard.android.core.work.BootCompletedReceiver { *; }
-keep class com.alas.dashboard.android.core.work.DashboardSyncAlarmReceiver { *; }
-keep class com.alas.dashboard.android.core.work.SyncReceiverEntryPoint { *; }

# Widget receivers and callback/configuration entry points.
-keep class com.alas.dashboard.android.core.widget.ResourceWidgetReceiver { *; }
-keep class com.alas.dashboard.android.core.widget.ResourceWidgetCompactReceiver { *; }
-keep class com.alas.dashboard.android.core.widget.ResourceWidgetExpandedReceiver { *; }
-keep class com.alas.dashboard.android.core.widget.RefreshWidgetAction { *; }
-keep class com.alas.dashboard.android.core.widget.WidgetConfigureActivity { *; }
-keep class com.alas.dashboard.android.core.widget.WidgetEntryPoint { *; }
