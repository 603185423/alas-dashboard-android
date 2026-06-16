package com.alas.dashboard.android.navigation

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.alas.dashboard.android.core.datastore.ThemeMode
import com.alas.dashboard.android.core.model.AccountConfig
import com.alas.dashboard.android.core.model.DashboardUser
import com.alas.dashboard.android.core.model.NotificationRule
import com.alas.dashboard.android.core.model.ResourceSnapshot
import com.alas.dashboard.android.core.repository.ConnectionTestReport
import com.alas.dashboard.android.core.repository.DashboardRepository
import com.alas.dashboard.android.core.work.DashboardSyncRunner
import com.alas.dashboard.android.core.work.SyncScheduler
import com.alas.dashboard.android.feature.screens.AdminScreen
import com.alas.dashboard.android.feature.screens.HistoryScreen
import com.alas.dashboard.android.feature.screens.OverviewScreen
import com.alas.dashboard.android.feature.screens.SettingsScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private enum class Destination(val route: String, val label: String) {
    Overview("overview", "当前资源"),
    History("history", "历史图表"),
    Settings("settings", "设置"),
    Admin("admin", "管理员"),
}

@Composable
fun DashboardApp(viewModel: DashboardViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val destinations = buildList {
        add(Destination.Overview)
        add(Destination.History)
        add(Destination.Settings)
        if (uiState.hasAdminToken) add(Destination.Admin)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = { navController.navigate(destination.route) },
                        icon = {
                            val image = when (destination) {
                                Destination.Overview -> Icons.Rounded.Home
                                Destination.History -> Icons.Rounded.BarChart
                                Destination.Settings -> Icons.Rounded.Settings
                                Destination.Admin -> Icons.Rounded.AdminPanelSettings
                            }
                            Icon(imageVector = image, contentDescription = destination.label)
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (uiState.onboardingCompleted) Destination.Overview.route else Destination.Settings.route,
            modifier = Modifier.fillMaxSize(),
        ) {
            composable(Destination.Overview.route) {
                OverviewScreen(
                    padding = padding,
                    state = uiState,
                    onRefresh = viewModel::refreshLatest,
                )
            }
            composable(Destination.History.route) {
                HistoryScreen(
                    padding = padding,
                    state = uiState,
                    onRefresh = viewModel::refreshHistory,
                    onToggleResource = viewModel::toggleHistoryResource,
                    onQuickRangeSelected = viewModel::updateHistoryQuickRange,
                    onCustomDurationChanged = viewModel::updateCustomDuration,
                    onCustomRangeChanged = viewModel::updateCustomRange,
                )
            }
            composable(Destination.Settings.route) {
                SettingsScreen(
                    padding = padding,
                    state = uiState,
                    onSaveAccount = viewModel::saveAccount,
                    onThemeSelected = viewModel::updateThemeMode,
                    onPollingMinutesChanged = viewModel::updatePolling,
                    onBackgroundSyncChanged = viewModel::updateBackgroundSync,
                    onAddRule = viewModel::addRule,
                    onDeleteRule = viewModel::deleteRule,
                    onExportConfig = viewModel::exportConfig,
                    onImportConfig = viewModel::importConfig,
                )
            }
            composable(Destination.Admin.route) {
                AdminScreen(
                    padding = padding,
                    state = uiState,
                    onRefresh = viewModel::loadAdminUsers,
                    onCreateUser = viewModel::createAdminUser,
                    onUpdateUser = viewModel::updateAdminUser,
                    onRotateToken = viewModel::rotateUserToken,
                )
            }
        }
    }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: DashboardRepository,
    private val syncScheduler: SyncScheduler,
    private val syncRunner: DashboardSyncRunner,
) : ViewModel() {
    private val selectedHistoryResources = MutableStateFlow<Set<String>>(emptySet())
    private val historyResources = MutableStateFlow<Map<String, List<ResourceSnapshot>>>(emptyMap())
    private val historyRange = MutableStateFlow(HistoryRangeState())
    private val historyLoading = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val connectionTestReport = MutableStateFlow<ConnectionTestReport?>(null)
    private val adminUsers = MutableStateFlow<List<DashboardUser>>(emptyList())
    private val rotatedToken = MutableStateFlow<String?>(null)

    val themeMode: StateFlow<ThemeMode> = repository.appPreferences
        .map { it.themeMode.toThemeMode() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeMode.SYSTEM,
        )

    private val baseUiState = combine(
        repository.observeDashboardState(),
        historyResources,
        repository.hasAdminToken,
        adminUsers,
        historyLoading,
    ) { dashboard, history, hasAdminToken, users, loading ->
        BaseUiState(
            dashboard = dashboard,
            history = history,
            hasAdminToken = hasAdminToken,
            users = users,
            historyLoading = loading,
        )
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        baseUiState,
        selectedHistoryResources,
        historyRange,
        message,
        connectionTestReport,
    ) { base: BaseUiState, selectedResources: Set<String>, range: HistoryRangeState, snackbar: String?, testReport: ConnectionTestReport? ->
        UiStateDraft(
            base = base,
            selectedResources = selectedResources,
            range = range,
            snackbar = snackbar,
            testReport = testReport,
        )
    }.combine(rotatedToken) { draft: UiStateDraft, token: String? ->
        val availableResources = draft.base.dashboard.latestResources.map { it.resourceName }
        val displaySelection = draft.selectedResources
            .filter { it in availableResources }
            .ifEmpty { availableResources.take(4) }
        val historySections = displaySelection.map { resourceName ->
            HistoryChartSectionUiState(
                resourceName = resourceName,
                samples = draft.base.history[resourceName].orEmpty(),
            )
        }
        DashboardUiState(
            latestResources = draft.base.dashboard.latestResources,
            historySections = historySections,
            accountConfig = draft.base.dashboard.accountConfig,
            themeMode = draft.base.dashboard.preferences.themeMode.toThemeMode(),
            pollingMinutes = draft.base.dashboard.preferences.pollingMinutes,
            backgroundSyncEnabled = draft.base.dashboard.preferences.backgroundSyncEnabled,
            onboardingCompleted = draft.base.dashboard.preferences.onboardingCompleted,
            rules = draft.base.dashboard.rules,
            hasAdminToken = draft.base.hasAdminToken,
            adminUsers = draft.base.users,
            availableHistoryResources = availableResources,
            selectedHistoryResources = displaySelection,
            historyRange = draft.range,
            historyLoading = draft.base.historyLoading,
            snackbarMessage = draft.snackbar,
            connectionTestReport = draft.testReport,
            latestToken = token,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState(),
    )

    init {
        viewModelScope.launch {
            syncScheduler.schedule()
        }
        refreshLatest()
        loadAdminUsers()
    }

    fun refreshLatest() {
        viewModelScope.launch {
            performImmediateSync()
            ensureHistorySelectionSeeded()
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            val selection = normalizedHistorySelection()
            if (selection.isEmpty()) return@launch
            val range = historyRange.value
            val (fromMs, toMs) = range.resolveWindow()
            runCatching {
                historyLoading.value = true
                val results = coroutineScope {
                    selection.map { resourceName ->
                        async {
                            resourceName to repository.refreshHistory(
                                resourceName = resourceName,
                                fromMs = fromMs,
                                toMs = toMs,
                                limit = range.queryLimit(),
                            )
                        }
                    }.awaitAll().toMap()
                }
                historyResources.value = results
            }.onFailure {
                message.value = it.message
            }.also {
                historyLoading.value = false
            }
        }
    }

    fun toggleHistoryResource(resourceName: String) {
        val current = selectedHistoryResources.value.toMutableSet()
        if (!current.add(resourceName)) {
            current.remove(resourceName)
        }
        selectedHistoryResources.value = current
        historyResources.value = historyResources.value.filterKeys { it in current }
        refreshHistory()
    }

    fun updateHistoryQuickRange(range: HistoryQuickRange) {
        historyRange.value = historyRange.value.copy(quickRange = range)
        refreshHistory()
    }

    fun updateCustomDuration(value: Int, unit: HistoryDurationUnit) {
        historyRange.value = historyRange.value.copy(
            quickRange = HistoryQuickRange.CUSTOM_DURATION,
            customDurationValue = value.coerceAtLeast(1),
            customDurationUnit = unit,
        )
        refreshHistory()
    }

    fun updateCustomRange(startMs: Long?, endMs: Long?) {
        historyRange.value = historyRange.value.copy(
            quickRange = HistoryQuickRange.CUSTOM_RANGE,
            customStartMs = startMs,
            customEndMs = endMs,
        )
        refreshHistory()
    }

    fun saveAccount(accountConfig: AccountConfig) {
        viewModelScope.launch {
            repository.completeOnboarding(accountConfig)
            val report = repository.testConnections(accountConfig)
            connectionTestReport.value = report
            message.value = report.toUserMessage()

            if (report.userToken.success) {
                syncScheduler.schedule()
                performImmediateSync()
            } else {
                syncScheduler.schedule()
            }

            if (report.adminToken.success) {
                loadAdminUsers()
            } else {
                adminUsers.value = emptyList()
            }
        }
    }

    fun updateThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            repository.updateThemeMode(mode)
            message.value = "主题已更新"
        }
    }

    fun updatePolling(minutes: Int) {
        viewModelScope.launch {
            repository.updatePollingMinutes(minutes)
            syncScheduler.schedule()
            message.value = "后台轮询已更新"
        }
    }

    fun updateBackgroundSync(enabled: Boolean) {
        viewModelScope.launch {
            repository.updateBackgroundSync(enabled)
            syncScheduler.schedule()
        }
    }

    fun addRule(rule: NotificationRule) {
        viewModelScope.launch {
            val rules = repository.notificationRules.first()
            repository.saveRules(rules + rule)
            message.value = "规则已新增"
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            repository.saveRules(repository.notificationRules.first().filterNot { it.id == ruleId })
            message.value = "规则已删除"
        }
    }

    fun loadAdminUsers() {
        viewModelScope.launch {
            runCatching { repository.loadAdminUsers() }
                .onSuccess { adminUsers.value = it }
                .onFailure { if (repository.hasAdminToken.first()) message.value = it.message }
        }
    }

    fun createAdminUser(userKey: String, displayName: String) {
        viewModelScope.launch {
            runCatching { repository.createAdminUser(userKey, displayName) }
                .onSuccess { (user, token) ->
                    adminUsers.value = adminUsers.value + user
                    rotatedToken.value = token
                }
                .onFailure { message.value = it.message }
        }
    }

    fun updateAdminUser(userId: Long, displayName: String, isActive: Boolean) {
        viewModelScope.launch {
            runCatching { repository.updateAdminUser(userId, displayName, isActive) }
                .onSuccess { updated ->
                    adminUsers.value = adminUsers.value.map { if (it.id == updated.id) updated else it }
                }
                .onFailure { message.value = it.message }
        }
    }

    fun rotateUserToken(userId: Long) {
        viewModelScope.launch {
            runCatching { repository.rotateUserToken(userId) }
                .onSuccess { (user, token) ->
                    adminUsers.value = adminUsers.value.map { if (it.id == user.id) user else it }
                    rotatedToken.value = token
                }
                .onFailure { message.value = it.message }
        }
    }

    fun exportConfig(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                repository.exportConfig(context.contentResolver, uri)
            }.onSuccess {
                message.value = "配置已导出"
            }.onFailure {
                message.value = it.message
            }
        }
    }

    fun importConfig(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                repository.importConfig(context.contentResolver, uri)
                syncScheduler.schedule()
                performImmediateSync()
            }.onSuccess {
                message.value = "配置已导入"
            }.onFailure {
                message.value = it.message
            }
        }
    }

    fun onAppForegrounded() {
        viewModelScope.launch {
            performImmediateSync()
        }
    }

    private suspend fun performImmediateSync() {
        val account = repository.accountConfig.first()
        if (account.baseUrl.isBlank() || account.userToken.isBlank()) {
            return
        }
        runCatching {
            syncRunner.syncLatest()
        }.onFailure {
            message.value = it.message
        }
    }

    private suspend fun normalizedHistorySelection(): List<String> {
        val latest = repository.latestNow().map { it.resourceName }
        if (latest.isEmpty()) {
            selectedHistoryResources.value = emptySet()
            historyResources.value = emptyMap()
            return emptyList()
        }
        val normalized = selectedHistoryResources.value
            .filter { it in latest }
            .ifEmpty { latest.take(4) }
        if (normalized.toSet() != selectedHistoryResources.value) {
            selectedHistoryResources.value = normalized.toSet()
        }
        return normalized
    }

    private suspend fun ensureHistorySelectionSeeded() {
        normalizedHistorySelection()
    }
}

data class DashboardUiState(
    val latestResources: List<ResourceSnapshot> = emptyList(),
    val historySections: List<HistoryChartSectionUiState> = emptyList(),
    val accountConfig: AccountConfig = AccountConfig(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val pollingMinutes: Int = 15,
    val backgroundSyncEnabled: Boolean = true,
    val onboardingCompleted: Boolean = false,
    val rules: List<NotificationRule> = emptyList(),
    val hasAdminToken: Boolean = false,
    val adminUsers: List<DashboardUser> = emptyList(),
    val availableHistoryResources: List<String> = emptyList(),
    val selectedHistoryResources: List<String> = emptyList(),
    val historyRange: HistoryRangeState = HistoryRangeState(),
    val historyLoading: Boolean = false,
    val snackbarMessage: String? = null,
    val connectionTestReport: ConnectionTestReport? = null,
    val latestToken: String? = null,
)

private data class BaseUiState(
    val dashboard: com.alas.dashboard.android.core.repository.DashboardState,
    val history: Map<String, List<ResourceSnapshot>>,
    val hasAdminToken: Boolean,
    val users: List<DashboardUser>,
    val historyLoading: Boolean,
)

private data class UiStateDraft(
    val base: BaseUiState,
    val selectedResources: Set<String>,
    val range: HistoryRangeState,
    val snackbar: String?,
    val testReport: ConnectionTestReport?,
)

private fun String.toThemeMode(): ThemeMode = runCatching { ThemeMode.valueOf(this) }.getOrDefault(ThemeMode.SYSTEM)

data class HistoryChartSectionUiState(
    val resourceName: String,
    val samples: List<ResourceSnapshot> = emptyList(),
)

enum class HistoryQuickRange(val label: String) {
    ONE_HOUR("1 小时"),
    FIVE_HOURS("5 小时"),
    TEN_HOURS("10 小时"),
    ONE_DAY("1 天"),
    SEVEN_DAYS("7 天"),
    THIRTY_DAYS("30 天"),
    CUSTOM_DURATION("自定义时长"),
    CUSTOM_RANGE("自定义时间段"),
}

enum class HistoryDurationUnit(val label: String, val millis: Long) {
    HOUR("小时", TimeUnit.HOURS.toMillis(1)),
    DAY("天", TimeUnit.DAYS.toMillis(1)),
}

data class HistoryRangeState(
    val quickRange: HistoryQuickRange = HistoryQuickRange.ONE_HOUR,
    val customDurationValue: Int = 12,
    val customDurationUnit: HistoryDurationUnit = HistoryDurationUnit.HOUR,
    val customStartMs: Long? = null,
    val customEndMs: Long? = null,
) {
    fun resolveWindow(nowMs: Long = System.currentTimeMillis()): Pair<Long?, Long?> {
        val endMs = customEndMs ?: nowMs
        return when (quickRange) {
            HistoryQuickRange.ONE_HOUR -> nowMs - TimeUnit.HOURS.toMillis(1) to nowMs
            HistoryQuickRange.FIVE_HOURS -> nowMs - TimeUnit.HOURS.toMillis(5) to nowMs
            HistoryQuickRange.TEN_HOURS -> nowMs - TimeUnit.HOURS.toMillis(10) to nowMs
            HistoryQuickRange.ONE_DAY -> nowMs - TimeUnit.DAYS.toMillis(1) to nowMs
            HistoryQuickRange.SEVEN_DAYS -> nowMs - TimeUnit.DAYS.toMillis(7) to nowMs
            HistoryQuickRange.THIRTY_DAYS -> nowMs - TimeUnit.DAYS.toMillis(30) to nowMs
            HistoryQuickRange.CUSTOM_DURATION -> {
                val durationMs = customDurationValue.coerceAtLeast(1) * customDurationUnit.millis
                (endMs - durationMs) to endMs
            }
            HistoryQuickRange.CUSTOM_RANGE -> {
                val startMs = customStartMs
                when {
                    startMs == null -> null to endMs
                    startMs <= endMs -> startMs to endMs
                    else -> endMs to startMs
                }
            }
        }
    }

    fun queryLimit(): Int = when (quickRange) {
        HistoryQuickRange.ONE_HOUR,
        HistoryQuickRange.FIVE_HOURS,
        HistoryQuickRange.TEN_HOURS,
        HistoryQuickRange.ONE_DAY,
        HistoryQuickRange.CUSTOM_DURATION -> 2000
        HistoryQuickRange.SEVEN_DAYS,
        HistoryQuickRange.THIRTY_DAYS,
        HistoryQuickRange.CUSTOM_RANGE -> 5000
    }
}
