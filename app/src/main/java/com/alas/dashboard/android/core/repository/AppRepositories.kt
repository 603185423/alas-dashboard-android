package com.alas.dashboard.android.core.repository

import android.content.ContentResolver
import android.net.Uri
import com.alas.dashboard.android.core.database.LatestResourceEntity
import com.alas.dashboard.android.core.database.ResourceDao
import com.alas.dashboard.android.core.database.ResourceHistoryEntity
import com.alas.dashboard.android.core.database.ResourceSyncMetaEntity
import com.alas.dashboard.android.core.datastore.SettingsStore
import com.alas.dashboard.android.core.datastore.ThemeMode
import com.alas.dashboard.android.core.model.AccountConfig
import com.alas.dashboard.android.core.model.AccountConfigJson
import com.alas.dashboard.android.core.model.AppPreferences
import com.alas.dashboard.android.core.model.AppPreferencesJson
import com.alas.dashboard.android.core.model.DashboardUser
import com.alas.dashboard.android.core.model.ExportConfig
import com.alas.dashboard.android.core.model.NotificationRule
import com.alas.dashboard.android.core.model.NotificationRuleJson
import com.alas.dashboard.android.core.model.ResourceSnapshot
import com.alas.dashboard.android.core.model.RuleKind
import com.alas.dashboard.android.core.model.ThresholdDirection
import com.alas.dashboard.android.core.model.WidgetConfig
import com.alas.dashboard.android.core.model.WidgetConfigJson
import com.alas.dashboard.android.core.model.toDomain
import com.alas.dashboard.android.core.network.ApiFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class DashboardRepository @Inject constructor(
    private val apiFactory: ApiFactory,
    private val dao: ResourceDao,
    private val settingsStore: SettingsStore,
) {
    private val exportJson = Json { prettyPrint = true }
    private val importJson = Json { ignoreUnknownKeys = true }

    val accountConfig: Flow<AccountConfig> = settingsStore.accountConfig
    val appPreferences: Flow<AppPreferences> = settingsStore.appPreferences
    val notificationRules: Flow<List<NotificationRule>> = settingsStore.notificationRules
    val widgetConfigs: Flow<List<WidgetConfig>> = settingsStore.widgetConfigs

    val hasAdminToken: Flow<Boolean> = accountConfig.map { it.adminToken.isNotBlank() }

    fun observeLatestResources(): Flow<List<ResourceSnapshot>> =
        dao.observeLatest().map { items -> items.map { it.toDomain() } }

    fun observeHistory(resourceName: String): Flow<List<ResourceSnapshot>> =
        dao.observeHistory(resourceName).map { items -> items.map { it.toDomain() } }

    fun observeDashboardState(): Flow<DashboardState> = combine(
        observeLatestResources(),
        accountConfig,
        appPreferences,
        notificationRules,
    ) { latest, account, preferences, rules ->
        DashboardState(
            latestResources = latest,
            accountConfig = account,
            preferences = preferences,
            rules = rules,
        )
    }

    suspend fun updateThemeMode(mode: ThemeMode) = settingsStore.updateThemeMode(mode)

    suspend fun updatePollingMinutes(minutes: Int) = settingsStore.updatePollingMinutes(minutes)

    suspend fun updateBackgroundSync(enabled: Boolean) =
        settingsStore.updateBackgroundSyncEnabled(enabled)

    suspend fun completeOnboarding(accountConfig: AccountConfig) {
        settingsStore.updateAccount(accountConfig)
        settingsStore.updateOnboardingCompleted(true)
    }

    suspend fun saveAccount(accountConfig: AccountConfig) = settingsStore.updateAccount(accountConfig)

    suspend fun saveRules(rules: List<NotificationRule>) = settingsStore.saveRules(rules)

    suspend fun saveWidgetConfig(config: WidgetConfig) {
        val current = settingsStore.widgetConfigsSnapshot().filterNot { it.appWidgetId == config.appWidgetId }
        settingsStore.saveWidgetConfigs((current + config).sortedBy { it.appWidgetId })
    }

    suspend fun removeWidgetConfig(appWidgetId: Int) {
        val current = settingsStore.widgetConfigsSnapshot().filterNot { it.appWidgetId == appWidgetId }
        settingsStore.saveWidgetConfigs(current)
    }

    suspend fun testConnections(config: AccountConfig? = null): ConnectionTestReport {
        val accountConfig = config ?: accountConfig.first()
        if (accountConfig.baseUrl.isBlank()) {
            return ConnectionTestReport(
                baseUrlValid = false,
                userToken = TokenTestResult(
                    type = TokenType.USER,
                    configured = accountConfig.userToken.isNotBlank(),
                    success = false,
                    message = "未填写 Base URL",
                ),
                adminToken = TokenTestResult(
                    type = TokenType.ADMIN,
                    configured = accountConfig.adminToken.isNotBlank(),
                    success = false,
                    message = "未填写 Base URL",
                ),
            )
        }

        val api = apiFactory.create(accountConfig.baseUrl)
        val healthMessage = runCatching {
            api.health()
            "服务可访问"
        }.getOrElse { error ->
            return ConnectionTestReport(
                baseUrlValid = false,
                userToken = TokenTestResult(
                    type = TokenType.USER,
                    configured = accountConfig.userToken.isNotBlank(),
                    success = false,
                    message = error.message ?: "无法连接到服务",
                ),
                adminToken = TokenTestResult(
                    type = TokenType.ADMIN,
                    configured = accountConfig.adminToken.isNotBlank(),
                    success = false,
                    message = error.message ?: "无法连接到服务",
                ),
            )
        }

        val userResult = if (accountConfig.userToken.isBlank()) {
            TokenTestResult(
                type = TokenType.USER,
                configured = false,
                success = false,
                message = "未配置用户 Token，已跳过",
            )
        } else {
            runCatching {
                val user = api.me(auth(accountConfig.userToken)).toDomain()
                TokenTestResult(
                    type = TokenType.USER,
                    configured = true,
                    success = true,
                    message = "验证通过：${user.displayName} (${user.userKey})",
                )
            }.getOrElse { error ->
                TokenTestResult(
                    type = TokenType.USER,
                    configured = true,
                    success = false,
                    message = error.message ?: "用户 Token 验证失败",
                )
            }
        }

        val adminResult = if (accountConfig.adminToken.isBlank()) {
            TokenTestResult(
                type = TokenType.ADMIN,
                configured = false,
                success = false,
                message = "未配置管理员 Token，已跳过",
            )
        } else {
            runCatching {
                val users = api.adminUsers(auth(accountConfig.adminToken)).users
                TokenTestResult(
                    type = TokenType.ADMIN,
                    configured = true,
                    success = true,
                    message = "验证通过：可访问管理员接口，当前用户数 ${users.size}",
                )
            }.getOrElse { error ->
                TokenTestResult(
                    type = TokenType.ADMIN,
                    configured = true,
                    success = false,
                    message = error.message ?: "管理员 Token 验证失败",
                )
            }
        }

        return ConnectionTestReport(
            baseUrlValid = true,
            healthMessage = healthMessage,
            userToken = userResult,
            adminToken = adminResult,
        )
    }

    suspend fun refreshLatest(): List<ResourceSnapshot> {
        val account = accountConfig.first()
        require(account.baseUrl.isNotBlank() && account.userToken.isNotBlank()) {
            "请先配置 Base URL 和用户 Token"
        }
        val api = apiFactory.create(account.baseUrl)
        val latest = api.latest(auth(account.userToken)).resources.map { it.toDomain() }
        dao.upsertLatest(latest.map { it.toLatestEntity() })
        return latest
    }

    suspend fun refreshHistory(
        resourceName: String,
        fromMs: Long? = null,
        toMs: Long? = null,
        limit: Int = 500,
    ): List<ResourceSnapshot> {
        val account = accountConfig.first()
        val api = apiFactory.create(account.baseUrl)
        val response = api.history(
            auth = auth(account.userToken),
            resourceName = resourceName,
            fromMs = fromMs,
            toMs = toMs,
            limit = limit.coerceAtMost(5000),
            order = "asc",
        )
        val items = response.items.map { it.toDomain() }
        dao.deleteHistory(resourceName)
        dao.upsertHistory(items.map { it.toHistoryEntity() })
        dao.upsertMeta(ResourceSyncMetaEntity(resourceName = resourceName, lastSyncedAtMs = System.currentTimeMillis()))
        return items
    }

    suspend fun loadAdminUsers(): List<DashboardUser> {
        val account = accountConfig.first()
        check(account.adminToken.isNotBlank()) { "未配置管理员 Token" }
        val api = apiFactory.create(account.baseUrl)
        return api.adminUsers(auth(account.adminToken)).users.map { it.toDomain() }
    }

    suspend fun createAdminUser(userKey: String, displayName: String): Pair<DashboardUser, String> {
        val account = accountConfig.first()
        val api = apiFactory.create(account.baseUrl)
        val response = api.createUser(
            auth = auth(account.adminToken),
            request = com.alas.dashboard.android.core.model.CreateUserRequest(
                userKey = userKey,
                displayName = displayName,
            ),
        )
        return response.user.toDomain() to response.token
    }

    suspend fun updateAdminUser(userId: Long, displayName: String, isActive: Boolean): DashboardUser {
        val account = accountConfig.first()
        val api = apiFactory.create(account.baseUrl)
        return api.updateUser(
            auth = auth(account.adminToken),
            userId = userId,
            request = com.alas.dashboard.android.core.model.UpdateUserRequest(
                displayName = displayName,
                isActive = isActive,
            ),
        ).toDomain()
    }

    suspend fun rotateUserToken(userId: Long): Pair<DashboardUser, String> {
        val account = accountConfig.first()
        val api = apiFactory.create(account.baseUrl)
        val response = api.rotateToken(auth(account.adminToken), userId)
        return response.user.toDomain() to response.token
    }

    suspend fun exportConfig(
        contentResolver: ContentResolver,
        uri: Uri,
        includeTokens: Boolean = true,
    ) {
        val account = accountConfig.first()
        val preferences = appPreferences.first()
        val rules = notificationRules.first()
        val widgets = widgetConfigs.first()
        val export = ExportConfig(
            account = AccountConfigJson(
                baseUrl = account.baseUrl,
                userToken = if (includeTokens) account.userToken else "",
                adminToken = if (includeTokens) account.adminToken else "",
            ),
            preferences = AppPreferencesJson(
                themeMode = preferences.themeMode,
                pollingMinutes = preferences.pollingMinutes,
                backgroundSyncEnabled = preferences.backgroundSyncEnabled,
                onboardingCompleted = preferences.onboardingCompleted,
            ),
            rules = rules.map { it.toJson() },
            widgets = widgets.filter { it.appWidgetId >= 0 }.map { it.toJson() },
        )
        contentResolver.openOutputStream(uri)?.use { output ->
            output.write(exportJson.encodeToString(export).toByteArray())
        }
    }

    suspend fun importConfig(contentResolver: ContentResolver, uri: Uri) {
        val export = contentResolver.openInputStream(uri)?.use { input ->
            importJson.decodeFromString<ExportConfig>(input.readBytes().decodeToString())
        } ?: error("无法读取导入文件")

        settingsStore.updateAccount(
            AccountConfig(
                baseUrl = export.account.baseUrl,
                userToken = export.account.userToken,
                adminToken = export.account.adminToken,
            ),
        )
        settingsStore.updateThemeMode(ThemeMode.valueOf(export.preferences.themeMode))
        settingsStore.updatePollingMinutes(export.preferences.pollingMinutes)
        settingsStore.updateBackgroundSyncEnabled(export.preferences.backgroundSyncEnabled)
        settingsStore.updateOnboardingCompleted(export.preferences.onboardingCompleted)
        settingsStore.saveRules(export.rules.map { it.toDomain() })
        settingsStore.saveWidgetConfigs(export.widgets.map { it.toDomain() })
    }

    suspend fun availableResourceNames(): List<String> =
        dao.getLatestNow().map { it.resourceName }.sorted()

    suspend fun currentWidgetConfig(appWidgetId: Int): WidgetConfig =
        settingsStore.widgetConfigsSnapshot().firstOrNull { it.appWidgetId == appWidgetId }
            ?: WidgetConfig(appWidgetId = appWidgetId)

    suspend fun latestNow(): List<ResourceSnapshot> = dao.getLatestNow().map { it.toDomain() }

    private fun auth(token: String) = "Bearer ${token.trim()}"
}

data class DashboardState(
    val latestResources: List<ResourceSnapshot> = emptyList(),
    val accountConfig: AccountConfig = AccountConfig(),
    val preferences: AppPreferences = AppPreferences(),
    val rules: List<NotificationRule> = emptyList(),
)

data class ConnectionTestReport(
    val baseUrlValid: Boolean,
    val healthMessage: String = "",
    val userToken: TokenTestResult,
    val adminToken: TokenTestResult,
) {
    val hasAnyUsableToken: Boolean
        get() = userToken.success || adminToken.success

    fun toUserMessage(): String = buildString {
        appendLine("保存完成，连接测试结果：")
        if (healthMessage.isNotBlank()) {
            appendLine("服务：$healthMessage")
        }
        appendLine("用户 Token：${userToken.message}")
        append("管理员 Token：${adminToken.message}")
    }
}

data class TokenTestResult(
    val type: TokenType,
    val configured: Boolean,
    val success: Boolean,
    val message: String,
)

enum class TokenType {
    USER,
    ADMIN,
}

private fun LatestResourceEntity.toDomain() = ResourceSnapshot(
    resourceName = resourceName,
    recordedAtMs = recordedAtMs,
    receivedAtMs = receivedAtMs,
    value = value,
    limit = limitValue,
    total = totalValue,
    color = color,
)

private fun ResourceHistoryEntity.toDomain() = ResourceSnapshot(
    resourceName = resourceName,
    recordedAtMs = recordedAtMs,
    receivedAtMs = receivedAtMs,
    value = value,
    limit = limitValue,
    total = totalValue,
    color = color,
)

private fun ResourceSnapshot.toLatestEntity() = LatestResourceEntity(
    resourceName = resourceName,
    recordedAtMs = recordedAtMs,
    receivedAtMs = receivedAtMs,
    value = value,
    limitValue = limit,
    totalValue = total,
    color = color,
)

private fun ResourceSnapshot.toHistoryEntity() = ResourceHistoryEntity(
    resourceName = resourceName,
    recordedAtMs = recordedAtMs,
    receivedAtMs = receivedAtMs,
    value = value,
    limitValue = limit,
    totalValue = total,
    color = color,
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

private fun WidgetConfig.toJson() = WidgetConfigJson(
    appWidgetId = appWidgetId,
    selectedResources = selectedResources,
    showAge = showAge,
    maxItems = maxItems,
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

private fun WidgetConfigJson.toDomain() = WidgetConfig(
    appWidgetId = appWidgetId,
    selectedResources = selectedResources,
    showAge = showAge,
    maxItems = maxItems,
)
