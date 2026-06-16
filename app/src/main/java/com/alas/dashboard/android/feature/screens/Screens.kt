package com.alas.dashboard.android.feature.screens

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.alas.dashboard.android.core.datastore.ThemeMode
import com.alas.dashboard.android.core.model.AccountConfig
import com.alas.dashboard.android.core.model.DashboardUser
import com.alas.dashboard.android.core.model.NotificationRule
import com.alas.dashboard.android.core.model.ResourceSnapshot
import com.alas.dashboard.android.core.model.RuleKind
import com.alas.dashboard.android.core.model.ThresholdDirection
import com.alas.dashboard.android.core.model.displayResourceName
import com.alas.dashboard.android.navigation.DashboardUiState
import com.alas.dashboard.android.navigation.HistoryChartSectionUiState
import com.alas.dashboard.android.navigation.HistoryDurationUnit
import com.alas.dashboard.android.navigation.HistoryQuickRange
import com.alas.dashboard.android.navigation.HistoryRangeState
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottomAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStartAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.shader.color
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.data.AxisValueOverrider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.common.shader.DynamicShader
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    padding: PaddingValues,
    state: DashboardUiState,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("当前资源", style = MaterialTheme.typography.headlineSmall)
                ElevatedAssistChip(onClick = onRefresh, label = { Text("刷新") })
            }
        }
        items(state.latestResources) { resource ->
            ResourceCard(resource = resource)
        }
    }
}

@Composable
fun HistoryScreen(
    padding: PaddingValues,
    state: DashboardUiState,
    onRefresh: () -> Unit,
    onToggleResource: (String) -> Unit,
    onQuickRangeSelected: (HistoryQuickRange) -> Unit,
    onCustomDurationChanged: (Int, HistoryDurationUnit) -> Unit,
    onCustomRangeChanged: (Long?, Long?) -> Unit,
) {
    var customDurationValue by remember(state.historyRange.customDurationValue) {
        mutableStateOf(state.historyRange.customDurationValue.toString())
    }
    var customDurationUnit by remember(state.historyRange.customDurationUnit) {
        mutableStateOf(state.historyRange.customDurationUnit)
    }
    var customStartMs by remember(state.historyRange.customStartMs) {
        mutableStateOf(state.historyRange.customStartMs)
    }
    var customEndMs by remember(state.historyRange.customEndMs) {
        mutableStateOf(state.historyRange.customEndMs)
    }

    LaunchedEffect(state.availableHistoryResources, state.selectedHistoryResources, state.historyLoading) {
        if (
            state.availableHistoryResources.isNotEmpty() &&
            state.selectedHistoryResources.isNotEmpty() &&
            state.historySections.all { it.samples.isEmpty() } &&
            !state.historyLoading
        ) {
            onRefresh()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("历史图表", style = MaterialTheme.typography.headlineSmall)
                ElevatedAssistChip(onClick = onRefresh, label = { Text("刷新") })
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("显示资源", style = MaterialTheme.typography.titleMedium)
                    Text("可多选，图表会纵向排列，页面支持上下滑动。", style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.availableHistoryResources) { resourceName ->
                            FilterChip(
                                selected = state.selectedHistoryResources.contains(resourceName),
                                onClick = { onToggleResource(resourceName) },
                                label = { Text(resourceName.displayResourceName()) },
                            )
                        }
                    }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("时间范围", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(HistoryQuickRange.entries) { range ->
                            FilterChip(
                                selected = state.historyRange.quickRange == range,
                                onClick = { onQuickRangeSelected(range) },
                                label = { Text(range.label) },
                            )
                        }
                    }
                    if (state.historyRange.quickRange == HistoryQuickRange.CUSTOM_DURATION) {
                        OutlinedTextField(
                            value = customDurationValue,
                            onValueChange = { customDurationValue = it.filter(Char::isDigit) },
                            label = { Text("自定义时长") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(HistoryDurationUnit.entries) { unit ->
                                FilterChip(
                                    selected = customDurationUnit == unit,
                                    onClick = { customDurationUnit = unit },
                                    label = { Text(unit.label) },
                                )
                            }
                        }
                        Button(
                            onClick = {
                                onCustomDurationChanged(
                                    customDurationValue.toIntOrNull() ?: 1,
                                    customDurationUnit,
                                )
                            },
                        ) {
                            Text("应用自定义时长")
                        }
                    }
                    if (state.historyRange.quickRange == HistoryQuickRange.CUSTOM_RANGE) {
                        DateTimeField(label = "开始时间", value = customStartMs, onValueChange = { customStartMs = it })
                        DateTimeField(label = "结束时间", value = customEndMs, onValueChange = { customEndMs = it })
                        Button(
                            onClick = { onCustomRangeChanged(customStartMs, customEndMs) },
                            enabled = customEndMs != null,
                        ) {
                            Text("应用自定义时间段")
                        }
                    }
                    Text(historyRangeSummary(state.historyRange), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text("支持横向拖动与缩放，点击某个点后会显示时间和值。", style = MaterialTheme.typography.bodySmall)
        }
        if (state.historyLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
        if (state.selectedHistoryResources.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("请先选择至少一种资源。", modifier = Modifier.padding(16.dp))
                }
            }
        }
        items(state.historySections, key = { it.resourceName }) { section ->
            HistoryChartCard(
                section = section,
                range = state.historyRange,
            )
        }
        if (!state.historyLoading && state.historySections.isEmpty() && state.selectedHistoryResources.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("当前时间范围内没有可显示的历史数据。", modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    state: DashboardUiState,
    onSaveAccount: (AccountConfig) -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    onPollingMinutesChanged: (Int) -> Unit,
    onBackgroundSyncChanged: (Boolean) -> Unit,
    onAddRule: (NotificationRule) -> Unit,
    onDeleteRule: (String) -> Unit,
    onExportConfig: (Context, Uri) -> Unit,
    onImportConfig: (Context, Uri) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var baseUrl by remember(state.accountConfig.baseUrl) { mutableStateOf(state.accountConfig.baseUrl) }
    var userToken by remember(state.accountConfig.userToken) { mutableStateOf(state.accountConfig.userToken) }
    var adminToken by remember(state.accountConfig.adminToken) { mutableStateOf(state.accountConfig.adminToken) }
    var pollingMinutes by remember(state.pollingMinutes) { mutableIntStateOf(state.pollingMinutes) }
    var ruleResource by remember { mutableStateOf(state.latestResources.firstOrNull()?.resourceName.orEmpty()) }
    var ruleThreshold by remember { mutableLongStateOf(1000L) }
    var durationMinutes by remember { mutableIntStateOf(30) }
    var above by remember { mutableStateOf(true) }
    var persistent by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) onExportConfig(context, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportConfig(context, uri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text("设置", style = MaterialTheme.typography.headlineSmall) }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("连接配置", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = userToken, onValueChange = { userToken = it }, label = { Text("用户 Token") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = adminToken, onValueChange = { adminToken = it }, label = { Text("管理员 Token（可选）") }, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "支持只填写管理员 Token。适用于服务端刚初始化、还没有普通用户 Token 时，先在安卓端创建用户。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            onSaveAccount(
                                AccountConfig(
                                    baseUrl = baseUrl,
                                    userToken = userToken,
                                    adminToken = adminToken,
                                ),
                            )
                        },
                    ) {
                        Text("保存并测试连接")
                    }
                    state.connectionTestReport?.let { report ->
                        HorizontalDivider()
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            tonalElevation = 2.dp,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("最近一次测试结果", style = MaterialTheme.typography.titleSmall)
                                if (report.healthMessage.isNotBlank()) {
                                    CopyableMessageText(
                                        label = "服务",
                                        message = report.healthMessage,
                                        clipboard = clipboard,
                                    )
                                }
                                CopyableMessageText(
                                    label = "用户 Token",
                                    message = report.userToken.message,
                                    clipboard = clipboard,
                                )
                                CopyableMessageText(
                                    label = "管理员 Token",
                                    message = report.adminToken.message,
                                    clipboard = clipboard,
                                )
                                Text(
                                    text = "长按任一结果可复制完整内容",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("后台刷新", style = MaterialTheme.typography.titleMedium)
                    Text("轮询间隔（分钟）：$pollingMinutes")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 120).forEach { item ->
                            FilterChip(
                                selected = pollingMinutes == item,
                                onClick = {
                                    pollingMinutes = item
                                    onPollingMinutesChanged(item)
                                },
                                label = { Text("$item") },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("启用后台轮询")
                        Switch(
                            checked = state.backgroundSyncEnabled,
                            onCheckedChange = onBackgroundSyncChanged,
                        )
                    }
                    Text("实际后台调度遵循 WorkManager 的系统最小周期限制，低于 15 分钟的请求不会被系统按原值执行。")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("主题", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = state.themeMode == mode,
                                onClick = { onThemeSelected(mode) },
                                label = { Text(mode.name) },
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("阈值通知", style = MaterialTheme.typography.titleMedium)
                    if (state.latestResources.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(
                                items = state.latestResources,
                                key = { it.resourceName },
                            ) { item ->
                                FilterChip(
                                    selected = ruleResource == item.resourceName,
                                    onClick = { ruleResource = item.resourceName },
                                    label = { Text(item.resourceName.displayResourceName()) },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = ruleThreshold.toString(),
                        onValueChange = { ruleThreshold = it.toLongOrNull() ?: ruleThreshold },
                        label = { Text("阈值") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = above, onClick = { above = true }, label = { Text("高于") })
                        }
                        item {
                            FilterChip(selected = !above, onClick = { above = false }, label = { Text("低于") })
                        }
                        item {
                            FilterChip(selected = !persistent, onClick = { persistent = false }, label = { Text("一次性") })
                        }
                        item {
                            FilterChip(selected = persistent, onClick = { persistent = true }, label = { Text("持续型") })
                        }
                    }
                    if (persistent) {
                        OutlinedTextField(
                            value = durationMinutes.toString(),
                            onValueChange = { durationMinutes = it.toIntOrNull() ?: durationMinutes },
                            label = { Text("持续时长（分钟）") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(
                        enabled = ruleResource.isNotBlank(),
                        onClick = {
                            onAddRule(
                                NotificationRule(
                                    id = "rule-${System.currentTimeMillis()}",
                                    resourceName = ruleResource,
                                    direction = if (above) ThresholdDirection.ABOVE else ThresholdDirection.BELOW,
                                    threshold = ruleThreshold,
                                    kind = if (persistent) RuleKind.PERSISTENT else RuleKind.ONE_SHOT,
                                    durationMinutes = durationMinutes,
                                ),
                            )
                        },
                    ) {
                        Text("新增规则")
                    }
                    state.rules.forEach { rule ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${rule.resourceName.displayResourceName()} " +
                                    "${if (rule.direction == ThresholdDirection.ABOVE) ">" else "<"} " +
                                    "${rule.threshold} (${rule.kind})",
                            )
                            TextButton(onClick = { onDeleteRule(rule.id) }) { Text("删除") }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("迁移", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { exportLauncher.launch("dashboard_app_config.json") }) {
                        Text("导出配置")
                    }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                        Text("导入配置")
                    }
                }
            }
        }
    }
}

@Composable
fun AdminScreen(
    padding: PaddingValues,
    state: DashboardUiState,
    onRefresh: () -> Unit,
    onCreateUser: (String, String) -> Unit,
    onUpdateUser: (Long, String, Boolean) -> Unit,
    onRotateToken: (Long) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var userKey by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("管理员", style = MaterialTheme.typography.headlineSmall)
                ElevatedAssistChip(onClick = onRefresh, label = { Text("刷新") })
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("创建用户")
                    OutlinedTextField(value = userKey, onValueChange = { userKey = it }, label = { Text("user_key") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("display_name") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onCreateUser(userKey, displayName) }) { Text("创建并生成 Token") }
                    state.latestToken?.let {
                        CopyableMessageText(
                            label = "最新返回 Token",
                            message = it,
                            clipboard = clipboard,
                        )
                    }
                }
            }
        }
        items(state.adminUsers) { user ->
            AdminUserCard(user = user, onUpdateUser = onUpdateUser, onRotateToken = onRotateToken)
        }
    }
}

@Composable
private fun AdminUserCard(
    user: DashboardUser,
    onUpdateUser: (Long, String, Boolean) -> Unit,
    onRotateToken: (Long) -> Unit,
) {
    var displayName by remember(user.displayName) { mutableStateOf(user.displayName) }
    var isActive by remember(user.isActive) { mutableStateOf(user.isActive) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${user.userKey} (#${user.id})", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text("显示名") }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("启用状态")
                Switch(checked = isActive, onCheckedChange = { isActive = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onUpdateUser(user.id, displayName, isActive) }) { Text("保存") }
                OutlinedButton(onClick = { onRotateToken(user.id) }) { Text("轮换 Token") }
            }
        }
    }
}

@Composable
private fun ResourceCard(resource: ResourceSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(resource.resourceName.displayResourceName(), style = MaterialTheme.typography.titleMedium)
            Text("当前值: ${resource.value}")
            resource.limit?.let { Text("上限: $it") }
            resource.total?.let { Text("总量: $it") }
            Text("记录时间: ${formatTime(resource.recordedAtMs)}")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CopyableMessageText(
    label: String,
    message: String,
    clipboard: ClipboardManager,
) {
    val text = "$label：$message"
    Text(
        text = text,
        modifier = Modifier.combinedClickable(
            onClick = { },
            onLongClick = {
                clipboard.setText(AnnotatedString(text))
            },
        ),
    )
}

@Composable
private fun HistoryChartCard(
    section: HistoryChartSectionUiState,
    range: HistoryRangeState,
) {
    var selectedSample by remember(section.samples) { mutableStateOf(section.samples.lastOrNull()) }
    var expanded by rememberSaveable(section.resourceName) { mutableStateOf(false) }
    val chartHeight = if (expanded) 260.dp else 124.dp
    val cardVerticalPadding = if (expanded) 16.dp else 10.dp
    val cardHorizontalPadding = if (expanded) 16.dp else 12.dp
    val contentSpacing = if (expanded) 12.dp else 6.dp
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(
                horizontal = cardHorizontalPadding,
                vertical = cardVerticalPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    section.resourceName.displayResourceName(),
                    style = if (expanded) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable { expanded = !expanded },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) "收起图表详情" else "展开图表详情",
                    )
                }
            }
            if (expanded) {
                Text("样本数: ${section.samples.size}", style = MaterialTheme.typography.bodySmall)
            }
            HistoryLineChart(
                resourceName = section.resourceName,
                samples = section.samples,
                range = range,
                expanded = expanded,
                onPointSelected = { selectedSample = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(chartHeight),
            )
            if (expanded) selectedSample?.let { sample ->
                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("选中点", style = MaterialTheme.typography.titleSmall)
                        Text("时间: ${formatDateTime(sample.recordedAtMs)}")
                        Text("数值: ${sample.value}")
                        sample.limit?.let { Text("上限: $it") }
                        sample.total?.let { Text("总量: $it") }
                    }
                }
            }
            if (expanded && section.samples.isNotEmpty()) {
                val latest = section.samples.last()
                Text(
                    text = "最新记录: ${formatDateTime(latest.recordedAtMs)} / ${latest.value}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun HistoryLineChart(
    resourceName: String,
    samples: List<ResourceSnapshot>,
    range: HistoryRangeState,
    expanded: Boolean,
    onPointSelected: (ResourceSnapshot) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (samples.size < 2) {
        Surface(modifier = modifier) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("图表数据不足，至少需要 2 个采样点。")
            }
        }
        return
    }

    val chartSamples = remember(samples, range) {
        downsampleHistorySamples(
            samples = samples.sortedBy { it.recordedAtMs },
            maxPoints = historyChartMaxPoints(range),
        )
    }
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val chartLineColor = MaterialTheme.colorScheme.primary
    val modelProducer = remember(resourceName) { CartesianChartModelProducer() }
    val marker = rememberDefaultCartesianMarker(label = rememberTextComponent())
    val axisLabelComponent = rememberAxisLabelComponent(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val yAxisBounds = remember(chartSamples) { calculateHistoryYAxisBounds(chartSamples) }
    val axisValueOverrider = remember(yAxisBounds) {
        AxisValueOverrider.fixed(
            minY = yAxisBounds.minY,
            maxY = yAxisBounds.maxY,
        )
    }
    val horizontalItemPlacer = remember(chartSamples.size, range.quickRange) {
        HorizontalAxis.ItemPlacer.default(
            spacing = historyAxisLabelSpacing(
                pointCount = chartSamples.size,
                maxVisibleLabels = historyMaxVisibleLabels(range.quickRange),
            ),
            addExtremeLabelPadding = true,
        )
    }
    val showBottomGuideline = remember(chartSamples.size, range.quickRange) {
        historyShouldShowBottomGuideline(
            pointCount = chartSamples.size,
            range = range.quickRange,
        )
    }
    val bottomAxisFormatter = remember(chartSamples, range.quickRange, axisTextColor) {
        CartesianValueFormatter { value, _, _ ->
            val index = value.toInt().coerceIn(0, chartSamples.lastIndex)
            colorizedAxisLabel(
                text = formatAxisTime(chartSamples[index].recordedAtMs, range.quickRange),
                color = axisTextColor,
            )
        }
    }
    val startAxisFormatter = remember(axisTextColor, expanded) {
        CartesianValueFormatter { value, _, _ ->
            colorizedAxisLabel(
                text = formatChartAxisValue(value, compact = !expanded),
                color = axisTextColor,
            )
        }
    }
    val markerVisibilityListener = remember(chartSamples, onPointSelected) {
        object : CartesianMarkerVisibilityListener {
            override fun onHidden(marker: CartesianMarker) = Unit

            override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) {
                val target = targets.firstOrNull() as? LineCartesianLayerMarkerTarget ?: return
                val selected = chartSamples.getOrNull(target.x.toInt()) ?: return
                onPointSelected(selected)
            }
        }
    }
    val lineProvider = LineCartesianLayer.LineProvider.series(
        rememberLine(
            shader = DynamicShader.color(chartLineColor),
            backgroundShader = null,
        ),
    )
    val lineLayer = rememberLineCartesianLayer(
        lineProvider = lineProvider,
        axisValueOverrider = axisValueOverrider,
    )

    LaunchedEffect(chartSamples) {
        modelProducer.runTransaction {
            lineSeries {
                series(
                    x = chartSamples.indices.toList(),
                    y = chartSamples.map { it.value },
                )
            }
        }
        onPointSelected(chartSamples.last())
    }

    key(
        expanded,
        range.quickRange,
        range.customDurationValue,
        range.customDurationUnit,
        range.customStartMs,
        range.customEndMs,
        chartSamples.first().recordedAtMs,
        chartSamples.last().recordedAtMs,
        chartSamples.size,
    ) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                lineLayer,
                startAxis = rememberStartAxis(
                    label = axisLabelComponent,
                    valueFormatter = startAxisFormatter,
                ),
                bottomAxis = if (expanded) {
                    rememberBottomAxis(
                        label = axisLabelComponent,
                        valueFormatter = bottomAxisFormatter,
                        itemPlacer = horizontalItemPlacer,
                        guideline = if (showBottomGuideline) rememberAxisGuidelineComponent() else null,
                    )
                } else {
                    null
                },
                marker = marker,
                markerVisibilityListener = markerVisibilityListener,
            ),
            modelProducer = modelProducer,
            modifier = modifier.heightIn(min = 240.dp),
            scrollState = rememberVicoScrollState(scrollEnabled = true),
            zoomState = rememberVicoZoomState(
                zoomEnabled = true,
                initialZoom = Zoom.Content,
                minZoom = Zoom.Content,
            ),
        )
    }
}

@Composable
private fun DateTimeField(
    label: String,
    value: Long?,
    onValueChange: (Long) -> Unit,
) {
    val context = LocalContext.current
    val zoneId = remember { ZoneId.systemDefault() }
    val current = remember(value) {
        Instant.ofEpochMilli(value ?: System.currentTimeMillis()).atZone(zoneId).toLocalDateTime()
    }
    OutlinedButton(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, dayOfMonth ->
                    TimePickerDialog(
                        context,
                        { _, hour, minute ->
                            val dateTime = current
                                .withYear(year)
                                .withMonth(month + 1)
                                .withDayOfMonth(dayOfMonth)
                                .withHour(hour)
                                .withMinute(minute)
                            onValueChange(dateTime.atZone(zoneId).toInstant().toEpochMilli())
                        },
                        current.hour,
                        current.minute,
                        true,
                    ).show()
                },
                current.year,
                current.monthValue - 1,
                current.dayOfMonth,
            ).show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("$label: ${value?.let(::formatDateTime) ?: "未设置"}")
    }
}

private fun historyRangeSummary(range: HistoryRangeState): String {
    val (fromMs, toMs) = range.resolveWindow()
    return when (range.quickRange) {
        HistoryQuickRange.CUSTOM_DURATION,
        HistoryQuickRange.CUSTOM_RANGE -> "当前范围: ${fromMs?.let(::formatDateTime) ?: "开始不限"} ~ ${toMs?.let(::formatDateTime) ?: "现在"}"
        else -> "当前范围: ${range.quickRange.label}"
    }
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun formatDateTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun formatAxisTime(timestamp: Long, range: HistoryQuickRange): String =
    when (range) {
        HistoryQuickRange.ONE_HOUR,
        HistoryQuickRange.FIVE_HOURS,
        HistoryQuickRange.TEN_HOURS -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(timestamp))
    }

private fun formatChartAxisValue(value: Double, compact: Boolean): String {
    val roundedValue = value.roundToLong()
    if (!compact || abs(value) < 1_000) {
        return roundedValue.toString()
    }

    val valueInThousands = value / 1_000.0
    return if (abs(value) < 10_000) {
        String.format(Locale.getDefault(), "%.1fk", valueInThousands)
    } else {
        "${valueInThousands.roundToLong()}k"
    }
}

private fun colorizedAxisLabel(text: String, color: Int): CharSequence =
    SpannableString(text).apply {
        setSpan(
            ForegroundColorSpan(color),
            0,
            length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }

private data class HistoryYAxisBounds(
    val minY: Double,
    val maxY: Double,
)

private fun historyAxisLabelSpacing(pointCount: Int, maxVisibleLabels: Int): Int =
    max(1, ceil(pointCount.toDouble() / maxVisibleLabels).toInt())

private fun historyChartMaxPoints(range: HistoryRangeState): Int =
    when (range.quickRange) {
        HistoryQuickRange.ONE_HOUR -> 120
        HistoryQuickRange.FIVE_HOURS -> 120
        HistoryQuickRange.TEN_HOURS -> 110
        HistoryQuickRange.ONE_DAY -> 96
        HistoryQuickRange.SEVEN_DAYS -> 84
        HistoryQuickRange.THIRTY_DAYS -> 72
        HistoryQuickRange.CUSTOM_DURATION,
        HistoryQuickRange.CUSTOM_RANGE -> 90
    }

private fun historyMaxVisibleLabels(range: HistoryQuickRange): Int =
    when (range) {
        HistoryQuickRange.ONE_HOUR,
        HistoryQuickRange.FIVE_HOURS,
        HistoryQuickRange.TEN_HOURS -> 3
        else -> 4
    }

private fun historyShouldShowBottomGuideline(pointCount: Int, range: HistoryQuickRange): Boolean =
    when (range) {
        HistoryQuickRange.ONE_HOUR,
        HistoryQuickRange.FIVE_HOURS,
        HistoryQuickRange.TEN_HOURS -> pointCount <= 24
        else -> pointCount <= 18
    }

private fun calculateHistoryYAxisBounds(samples: List<ResourceSnapshot>): HistoryYAxisBounds {
    val values = samples.map { it.value.toDouble() }
    val minValue = values.minOrNull() ?: 0.0
    val maxValue = values.maxOrNull() ?: 0.0

    if (minValue == maxValue) {
        val padding = max(1.0, abs(maxValue) * 0.05)
        val minY = if (minValue > 0.0) minValue - min(padding, minValue * 0.2) else minValue - padding
        return HistoryYAxisBounds(
            minY = minY,
            maxY = maxValue + padding,
        )
    }

    val span = maxValue - minValue
    val rawPadding = max(1.0, max(span * 0.15, abs(maxValue) * 0.02))
    val bottomPadding = if (minValue > 0.0) min(rawPadding, max(1.0, minValue * 0.2)) else rawPadding
    val minY = when {
        minValue > 0.0 -> max(0.0, minValue - bottomPadding)
        minValue < 0.0 -> minValue - bottomPadding
        else -> 0.0
    }
    val maxY = maxValue + rawPadding
    return HistoryYAxisBounds(minY = minY, maxY = maxY)
}

private fun downsampleHistorySamples(
    samples: List<ResourceSnapshot>,
    maxPoints: Int,
): List<ResourceSnapshot> {
    if (samples.size <= maxPoints) return samples

    val bucketSize = ceil(samples.size.toDouble() / maxPoints).toInt()
    val buckets = samples.chunked(bucketSize)
    return buckets.mapIndexed { index, bucket ->
        when (index) {
            0 -> bucket.first()
            buckets.lastIndex -> bucket.last()
            else -> bucket.aggregateForChart()
        }
    }
}

private fun List<ResourceSnapshot>.aggregateForChart(): ResourceSnapshot {
    val representative = this[this.size / 2]
    return representative.copy(
        recordedAtMs = map { it.recordedAtMs }.average().roundToLong(),
        receivedAtMs = map { it.receivedAtMs }.average().roundToLong(),
        value = map { it.value.toDouble() }.average().roundToLong(),
        ageMs = mapNotNull { it.ageMs?.toDouble() }.averageOrNull()?.roundToLong() ?: representative.ageMs,
    )
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
