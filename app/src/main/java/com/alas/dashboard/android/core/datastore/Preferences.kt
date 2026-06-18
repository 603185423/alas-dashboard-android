package com.alas.dashboard.android.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.alas.dashboard.android.core.model.AccountConfig
import com.alas.dashboard.android.core.model.AppPreferences
import com.alas.dashboard.android.core.model.NotificationRule
import com.alas.dashboard.android.core.model.NotificationRuleJson
import com.alas.dashboard.android.core.model.RuleKind
import com.alas.dashboard.android.core.model.RuleRuntimeState
import com.alas.dashboard.android.core.model.ScriptStatusRuntimeState
import com.alas.dashboard.android.core.model.ThresholdDirection
import com.alas.dashboard.android.core.model.WidgetConfig
import com.alas.dashboard.android.core.model.WidgetConfigJson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "dashboard_preferences")

enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM,
}

@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val pollingMinutes = intPreferencesKey("polling_minutes")
        val backgroundSyncEnabled = booleanPreferencesKey("background_sync_enabled")
        val onboardingCompleted = booleanPreferencesKey("onboarding_completed")
        val scriptStatusAlertsEnabled = booleanPreferencesKey("script_status_alerts_enabled")
        val scriptStatusChangeNotificationsEnabled = booleanPreferencesKey("script_status_change_notifications_enabled")
        val scriptStatusPersistentNotificationsEnabled = booleanPreferencesKey("script_status_persistent_notifications_enabled")
        val scriptStatusPersistentMinutes = intPreferencesKey("script_status_persistent_minutes")
        val baseUrl = stringPreferencesKey("base_url")
        val userToken = stringPreferencesKey("user_token")
        val adminToken = stringPreferencesKey("admin_token")
        val rulesJson = stringPreferencesKey("rules_json")
        val widgetConfigsJson = stringPreferencesKey("widget_configs_json")
        val runtimeJson = stringPreferencesKey("runtime_json")
        val scriptStatusRuntimeJson = stringPreferencesKey("script_status_runtime_json")
    }

    val appPreferences: Flow<AppPreferences> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppPreferences(
                themeMode = prefs[Keys.themeMode] ?: ThemeMode.SYSTEM.name,
                pollingMinutes = prefs[Keys.pollingMinutes] ?: 15,
                backgroundSyncEnabled = prefs[Keys.backgroundSyncEnabled] ?: true,
                onboardingCompleted = prefs[Keys.onboardingCompleted] ?: false,
                scriptStatusAlertsEnabled = prefs[Keys.scriptStatusAlertsEnabled] ?: false,
                scriptStatusChangeNotificationsEnabled = prefs[Keys.scriptStatusChangeNotificationsEnabled] ?: true,
                scriptStatusPersistentNotificationsEnabled = prefs[Keys.scriptStatusPersistentNotificationsEnabled] ?: false,
                scriptStatusPersistentMinutes = prefs[Keys.scriptStatusPersistentMinutes] ?: 30,
            )
        }

    val accountConfig: Flow<AccountConfig> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AccountConfig(
                baseUrl = prefs[Keys.baseUrl].orEmpty(),
                userToken = prefs[Keys.userToken].orEmpty(),
                adminToken = prefs[Keys.adminToken].orEmpty(),
            )
        }

    val notificationRules: Flow<List<NotificationRule>> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val raw = prefs[Keys.rulesJson].orEmpty()
            if (raw.isBlank()) return@map emptyList()
            json.decodeFromString<List<NotificationRuleJson>>(raw).map { it.toDomain() }
        }

    val widgetConfigs: Flow<List<WidgetConfig>> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val raw = prefs[Keys.widgetConfigsJson].orEmpty()
            if (raw.isBlank()) return@map emptyList()
            json.decodeFromString<List<WidgetConfigJson>>(raw).map { it.toDomain() }
        }

    val runtimeStates: Flow<List<RuleRuntimeState>> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val raw = prefs[Keys.runtimeJson].orEmpty()
            if (raw.isBlank()) return@map emptyList()
            json.decodeFromString<List<RuleRuntimeStateJson>>(raw).map { it.toDomain() }
        }

    val scriptStatusRuntimeStates: Flow<List<ScriptStatusRuntimeState>> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val raw = prefs[Keys.scriptStatusRuntimeJson].orEmpty()
            if (raw.isBlank()) return@map emptyList()
            json.decodeFromString<List<ScriptStatusRuntimeState>>(raw)
        }

    suspend fun updateThemeMode(mode: ThemeMode) = edit { it[Keys.themeMode] = mode.name }

    suspend fun updatePollingMinutes(minutes: Int) = edit { it[Keys.pollingMinutes] = minutes }

    suspend fun updateBackgroundSyncEnabled(enabled: Boolean) =
        edit { it[Keys.backgroundSyncEnabled] = enabled }

    suspend fun updateOnboardingCompleted(completed: Boolean) =
        edit { it[Keys.onboardingCompleted] = completed }

    suspend fun updateScriptStatusAlertsEnabled(enabled: Boolean) =
        edit { it[Keys.scriptStatusAlertsEnabled] = enabled }

    suspend fun updateScriptStatusChangeNotificationsEnabled(enabled: Boolean) =
        edit { it[Keys.scriptStatusChangeNotificationsEnabled] = enabled }

    suspend fun updateScriptStatusPersistentNotificationsEnabled(enabled: Boolean) =
        edit { it[Keys.scriptStatusPersistentNotificationsEnabled] = enabled }

    suspend fun updateScriptStatusPersistentMinutes(minutes: Int) =
        edit { it[Keys.scriptStatusPersistentMinutes] = minutes.coerceAtLeast(1) }

    suspend fun updateAccount(config: AccountConfig) = edit {
        it[Keys.baseUrl] = config.baseUrl.trimEnd('/')
        it[Keys.userToken] = config.userToken.trim()
        it[Keys.adminToken] = config.adminToken.trim()
    }

    suspend fun saveRules(rules: List<NotificationRule>) = edit {
        it[Keys.rulesJson] = json.encodeToString(rules.map { item -> item.toJson() })
    }

    suspend fun saveWidgetConfigs(configs: List<WidgetConfig>) = edit {
        it[Keys.widgetConfigsJson] = json.encodeToString(configs.map { item -> item.toJson() })
    }

    suspend fun saveRuntimeStates(states: List<RuleRuntimeState>) = edit {
        it[Keys.runtimeJson] = json.encodeToString(states.map { item -> item.toJson() })
    }

    suspend fun saveScriptStatusRuntimeStates(states: List<ScriptStatusRuntimeState>) = edit {
        it[Keys.scriptStatusRuntimeJson] = json.encodeToString(states)
    }

    suspend fun clearRuntimeStateForRule(ruleId: String) {
        val updated = runtimeStatesSnapshot().filterNot { it.ruleId == ruleId }
        saveRuntimeStates(updated)
    }

    suspend fun runtimeStatesSnapshot(): List<RuleRuntimeState> = firstValue(Keys.runtimeJson) { raw ->
        if (raw.isBlank()) emptyList() else json.decodeFromString<List<RuleRuntimeStateJson>>(raw).map { it.toDomain() }
    }

    suspend fun scriptStatusRuntimeStatesSnapshot(): List<ScriptStatusRuntimeState> =
        firstValue(Keys.scriptStatusRuntimeJson) { raw ->
            if (raw.isBlank()) emptyList() else json.decodeFromString<List<ScriptStatusRuntimeState>>(raw)
        }

    suspend fun rulesSnapshot(): List<NotificationRule> = firstValue(Keys.rulesJson) { raw ->
        if (raw.isBlank()) emptyList() else json.decodeFromString<List<NotificationRuleJson>>(raw).map { it.toDomain() }
    }

    suspend fun widgetConfigsSnapshot(): List<WidgetConfig> = firstValue(Keys.widgetConfigsJson) { raw ->
        if (raw.isBlank()) emptyList() else json.decodeFromString<List<WidgetConfigJson>>(raw).map { it.toDomain() }
    }

    suspend fun accountSnapshot(): AccountConfig = firstValue(Keys.baseUrl) {
        AccountConfig(
            baseUrl = it,
            userToken = context.dataStore.data.first()[Keys.userToken].orEmpty(),
            adminToken = context.dataStore.data.first()[Keys.adminToken].orEmpty(),
        )
    }

    private suspend fun edit(block: suspend (MutablePreferencesWrapper) -> Unit) {
        context.dataStore.edit { prefs ->
            block(MutablePreferencesWrapper(prefs))
        }
    }

    private suspend fun <T> firstValue(
        key: Preferences.Key<String>,
        transform: suspend (String) -> T,
    ): T {
        val prefs = context.dataStore.data.first()
        return transform(prefs[key].orEmpty())
    }
}

private class MutablePreferencesWrapper(
    private val preferences: androidx.datastore.preferences.core.MutablePreferences,
) {
    operator fun set(key: Preferences.Key<String>, value: String) {
        preferences[key] = value
    }

    operator fun set(key: Preferences.Key<Int>, value: Int) {
        preferences[key] = value
    }

    operator fun set(key: Preferences.Key<Boolean>, value: Boolean) {
        preferences[key] = value
    }
}

@kotlinx.serialization.Serializable
private data class RuleRuntimeStateJson(
    val ruleId: String,
    val hasTriggered: Boolean,
    val satisfiedSinceMs: Long? = null,
    val persistentShown: Boolean,
)

private fun NotificationRuleJson.toDomain() = NotificationRule(
    id = id,
    resourceName = resourceName,
    direction = ThresholdDirection.valueOf(direction),
    threshold = threshold,
    kind = RuleKind.valueOf(kind),
    durationMinutes = durationMinutes,
    enabled = enabled,
)

private fun NotificationRule.toJson() = NotificationRuleJson(
    id = id,
    resourceName = resourceName,
    direction = direction.name,
    threshold = threshold,
    kind = kind.name,
    durationMinutes = durationMinutes,
    enabled = enabled,
)

private fun WidgetConfigJson.toDomain() = WidgetConfig(
    appWidgetId = appWidgetId,
    selectedResources = selectedResources,
    showAge = showAge,
    maxItems = maxItems,
    showDebugInfo = showDebugInfo,
)

private fun WidgetConfig.toJson() = WidgetConfigJson(
    appWidgetId = appWidgetId,
    selectedResources = selectedResources,
    showAge = showAge,
    maxItems = maxItems,
    showDebugInfo = showDebugInfo,
)

private fun RuleRuntimeStateJson.toDomain() = RuleRuntimeState(
    ruleId = ruleId,
    hasTriggered = hasTriggered,
    satisfiedSinceMs = satisfiedSinceMs,
    persistentShown = persistentShown,
)

private fun RuleRuntimeState.toJson() = RuleRuntimeStateJson(
    ruleId = ruleId,
    hasTriggered = hasTriggered,
    satisfiedSinceMs = satisfiedSinceMs,
    persistentShown = persistentShown,
)
