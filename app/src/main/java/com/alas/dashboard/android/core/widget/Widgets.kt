package com.alas.dashboard.android.core.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable as glanceClickable
import androidx.glance.GlanceComposable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column as GlanceColumn
import androidx.glance.layout.Row as GlanceRow
import androidx.glance.layout.Spacer as GlanceSpacer
import androidx.glance.layout.fillMaxSize as glanceFillMaxSize
import androidx.glance.layout.fillMaxWidth as glanceFillMaxWidth
import androidx.glance.layout.height as glanceHeight
import androidx.glance.layout.padding as glancePadding
import androidx.glance.layout.size as glanceSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text as GlanceText
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.alas.dashboard.android.R
import com.alas.dashboard.android.core.repository.DashboardRepository
import com.alas.dashboard.android.core.work.DashboardSyncRunner
import com.alas.dashboard.android.core.work.triggerImmediateDashboardSync
import com.alas.dashboard.android.core.model.ResourceSnapshot
import com.alas.dashboard.android.core.model.displayResourceName
import com.alas.dashboard.android.core.model.resourceDisplayOrder
import com.alas.dashboard.android.core.model.resourceIconRes
import com.alas.dashboard.android.core.model.sortedResourceNames
import com.alas.dashboard.android.ui.theme.DashboardTheme
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.launch

private open class BaseResourceWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = widgetEntryPoint(context).repository()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val config = repository.currentWidgetConfig(appWidgetId)
        var loadedFromNetwork = false
        val latest = repository.latestNow().ifEmpty {
            runCatching {
                loadedFromNetwork = true
                repository.refreshLatest()
            }.getOrElse { emptyList() }
        }
        val selectedItems = latest
            .filter { it.resourceName in config.selectedResources }
            .sortedBy { resourceDisplayOrder(it.resourceName) }
        val items = if (config.selectedResources.isEmpty()) {
            latest
        } else if (selectedItems.isNotEmpty()) {
            selectedItems
        } else {
            latest
        }
        provideContent {
            GlanceTheme {
                if (config.showDebugInfo) {
                    ResourceWidgetDebugContent(
                        items = items,
                        debugInfo = WidgetDebugInfo(
                            renderedAtText = formatWidgetDebugTime(System.currentTimeMillis()),
                            itemCount = items.size,
                            latestReceivedAtText = items.maxOfOrNull { it.receivedAtMs }?.let(::formatWidgetDebugTime),
                            latestRecordedAtText = items.maxOfOrNull { it.recordedAtMs }?.let(::formatWidgetDebugTime),
                            sourceLabel = if (loadedFromNetwork) "网络补拉" else "本地缓存",
                        ),
                    )
                } else {
                    ResourceWidgetContent(items = items)
                }
            }
        }
    }
}

private class ResourceWidget : BaseResourceWidget()

abstract class BaseResourceWidgetReceiver(
    final override val glanceAppWidget: GlanceAppWidget,
) : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        triggerImmediateDashboardSync(context, goAsync())
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        triggerImmediateDashboardSync(context, goAsync())
    }
}

class ResourceWidgetReceiver : BaseResourceWidgetReceiver(ResourceWidget())

private class ResourceWidgetCompact : BaseResourceWidget()

class ResourceWidgetCompactReceiver : BaseResourceWidgetReceiver(ResourceWidgetCompact())

private class ResourceWidgetExpanded : BaseResourceWidget()

class ResourceWidgetExpandedReceiver : BaseResourceWidgetReceiver(ResourceWidgetExpanded())

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        runCatching {
            widgetEntryPoint(context).syncRunner().syncLatest()
        }.onFailure {
            triggerImmediateDashboardSync(context)
        }
    }
}

@AndroidEntryPoint
class WidgetConfigureActivity : ComponentActivity() {
    @Inject
    lateinit var repository: DashboardRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED)
        setContent {
            var allResources by remember { mutableStateOf(emptyList<String>()) }
            val selected = remember { mutableStateListOf<String>() }
            var showDebugInfo by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(widgetId) {
                allResources = repository.availableResourceNames().sortedResourceNames()
                val config = repository.currentWidgetConfig(widgetId)
                selected.clear()
                selected.addAll(config.selectedResources)
                showDebugInfo = config.showDebugInfo
            }

            DashboardTheme(themeMode = com.alas.dashboard.android.core.datastore.ThemeMode.SYSTEM) {
                Scaffold { padding ->
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Text(
                                text = "配置小组件资源",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                        items(allResources) { name ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (selected.contains(name)) selected.remove(name) else selected.add(name)
                                    },
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(name.displayResourceName())
                                    Checkbox(
                                        checked = selected.contains(name),
                                        onCheckedChange = {
                                            if (it) selected.add(name) else selected.remove(name)
                                        },
                                    )
                                }
                            }
                        }
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text("显示调试信息")
                                        Text(
                                            text = "开启后小组件以纯文字方式显示资源与刷新调试信息，关闭则使用图标卡片样式",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Switch(
                                        checked = showDebugInfo,
                                        onCheckedChange = { showDebugInfo = it },
                                    )
                                }
                            }
                        }
                        item {
                            Button(
                                onClick = {
                                    scope.launch {
                                        repository.saveWidgetConfig(
                                            com.alas.dashboard.android.core.model.WidgetConfig(
                                                appWidgetId = widgetId,
                                                selectedResources = selected.toList(),
                                                maxItems = 4,
                                                showAge = true,
                                                showDebugInfo = showDebugInfo,
                                            ),
                                        )
                                        updateAllWidgets(this@WidgetConfigureActivity)
                                        setResult(
                                            RESULT_OK,
                                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                                        )
                                        finish()
                                    }
                                },
                            ) {
                                Text("保存")
                            }
                        }
                    }
                }
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun repository(): DashboardRepository
    fun syncRunner(): DashboardSyncRunner
}

private fun widgetEntryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)

suspend fun updateAllWidgets(context: Context) {
    ResourceWidget().updateAll(context)
    ResourceWidgetCompact().updateAll(context)
    ResourceWidgetExpanded().updateAll(context)
}

private data class WidgetDebugInfo(
    val renderedAtText: String,
    val itemCount: Int,
    val latestReceivedAtText: String?,
    val latestRecordedAtText: String?,
    val sourceLabel: String,
)

private data class UpdateTimePresentation(
    val text: String,
    val isStale: Boolean,
)

private val WidgetStaleTimeColor = ColorProvider(R.color.widget_stale_time)

@Composable
@GlanceComposable
private fun ResourceWidgetContent(items: List<ResourceSnapshot>) {
    val size = LocalSize.current
    val width = size.width
    val height = size.height
    val latestReceivedAtMs = items.maxOfOrNull { it.receivedAtMs }

    val columns = when {
        width < 110.dp -> 1
        width < 170.dp -> 2
        width < 240.dp -> 3
        width < 300.dp -> 4
        else -> 5
    }
    val showHeader = height >= 140.dp && width >= 170.dp
    val showDetails = height >= 110.dp
    val gridHeight = if (showHeader) height - 30.dp else height
    val rows = when {
        gridHeight < 100.dp -> 1
        gridHeight < 190.dp -> 2
        else -> 3
    }
    val capacity = (columns * rows).coerceAtLeast(1)
    val visibleItems = items.take(capacity)
    val iconSize = if (showDetails) 30.dp else 26.dp
    val useCompactTextMode = !showDetails && visibleItems.size > columns
    val updateTime = latestReceivedAtMs?.let(::formatUpdateTimePresentation)
        ?: UpdateTimePresentation(text = "未更新", isStale = true)

    GlanceColumn(
        modifier = GlanceModifier
            .glanceFillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .glancePadding(if (showDetails) 14.dp else 8.dp),
        verticalAlignment = Alignment.Vertical.Top,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
    ) {
        if (showHeader) {
            GlanceRow(
                modifier = GlanceModifier.glanceFillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                GlanceText(
                    text = "当前资源",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    modifier = GlanceModifier.defaultWeight(),
                )
                GlanceText(
                    text = updateTime.text,
                    modifier = GlanceModifier.glanceClickable(
                        onClick = actionRunCallback<RefreshWidgetAction>(),
                    ),
                    style = TextStyle(
                        color = if (updateTime.isStale) WidgetStaleTimeColor else GlanceTheme.colors.secondary,
                        fontSize = 12.sp,
                        fontWeight = if (updateTime.isStale) FontWeight.Bold else null,
                    ),
                )
            }
            GlanceSpacer(modifier = GlanceModifier.glanceHeight(8.dp))
        }

        if (visibleItems.isEmpty()) {
            Box(
                modifier = GlanceModifier.glanceFillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                GlanceText(
                    text = "暂无数据",
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        } else {
            visibleItems.chunked(columns).forEach { rowItems ->
                GlanceRow(
                    modifier = GlanceModifier.glanceFillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    rowItems.forEach { item ->
                        ResourceTile(
                            item = item,
                            iconSize = iconSize,
                            showDetails = showDetails,
                            useCompactTextMode = useCompactTextMode,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                    }
                    repeat(columns - rowItems.size) {
                        GlanceSpacer(modifier = GlanceModifier.defaultWeight())
                    }
                }
            }
        }
    }
}

@Composable
@GlanceComposable
private fun ResourceTile(
    item: ResourceSnapshot,
    iconSize: androidx.compose.ui.unit.Dp,
    showDetails: Boolean,
    useCompactTextMode: Boolean,
    modifier: GlanceModifier,
) {
    GlanceColumn(
        modifier = modifier.glancePadding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        if (useCompactTextMode) {
            GlanceText(
                text = item.resourceName.displayResourceName(),
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                ),
            )
            GlanceText(
                text = formatShort(item.value),
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
            )
        } else {
            Image(
                provider = ImageProvider(resourceIconRes(item.resourceName)),
                contentDescription = item.resourceName.displayResourceName(),
                modifier = GlanceModifier.glanceSize(iconSize),
            )
            GlanceSpacer(modifier = GlanceModifier.glanceHeight(3.dp))
            if (showDetails) {
                GlanceText(
                    text = item.resourceName.displayResourceName(),
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
            GlanceText(
                text = if (showDetails) formatValue(item.value) else formatShort(item.value),
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = if (showDetails) 14.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
            )
            if (showDetails) {
                secondaryText(item)?.let { secondary ->
                    GlanceText(
                        text = secondary,
                        maxLines = 1,
                        style = TextStyle(
                            color = GlanceTheme.colors.secondary,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
@GlanceComposable
private fun ResourceWidgetDebugContent(
    items: List<ResourceSnapshot>,
    debugInfo: WidgetDebugInfo,
) {
    val bodyStyle = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp)
    val mutedStyle = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp)
    GlanceColumn(
        modifier = GlanceModifier
            .glanceFillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .glancePadding(12.dp),
        verticalAlignment = Alignment.Vertical.Top,
        horizontalAlignment = Alignment.Horizontal.Start,
    ) {
        GlanceText(
            text = "当前资源",
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
        GlanceSpacer(modifier = GlanceModifier.glanceHeight(6.dp))
        if (items.isEmpty()) {
            GlanceText(text = "暂无数据，请打开 App 检查连接", style = mutedStyle)
        } else {
            items.take(8).forEach { item ->
                GlanceText(
                    text = buildString {
                        append(item.resourceName.displayResourceName())
                        append(": ")
                        append(item.value)
                        when {
                            item.total != null -> append("/${item.total}")
                            item.limit != null -> append("/${item.limit}")
                        }
                    },
                    modifier = GlanceModifier.glanceFillMaxWidth(),
                    style = bodyStyle,
                )
            }
            GlanceSpacer(modifier = GlanceModifier.glanceHeight(8.dp))
            GlanceText(text = "调试 渲染 ${debugInfo.renderedAtText}", style = mutedStyle)
            GlanceText(text = "调试 数据 ${debugInfo.itemCount} 项 / ${debugInfo.sourceLabel}", style = mutedStyle)
            debugInfo.latestReceivedAtText?.let {
                GlanceText(text = "调试 接收 $it", style = mutedStyle)
            }
            debugInfo.latestRecordedAtText?.let {
                GlanceText(text = "调试 记录 $it", style = mutedStyle)
            }
        }
    }
}

private fun secondaryText(item: ResourceSnapshot): String? = when {
    item.total != null -> "/ ${formatValue(item.total)}"
    item.limit != null -> "上限 ${formatValue(item.limit)}"
    else -> null
}

private fun formatValue(value: Long): String =
    String.format(Locale.getDefault(), "%,d", value)

private fun formatShort(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.getDefault(), "%.1fM", value / 1_000_000.0)
    value >= 10_000 -> String.format(Locale.getDefault(), "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private fun formatUpdateTimePresentation(receivedAtMs: Long): UpdateTimePresentation {
    val diffMs = (System.currentTimeMillis() - receivedAtMs).coerceAtLeast(0L)
    val minuteMs = 60_000L
    val hourMs = 60 * minuteMs
    val dayMs = 24 * hourMs
    val staleThresholdMs = 12 * hourMs
    return when {
        diffMs < staleThresholdMs -> UpdateTimePresentation(
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(receivedAtMs)),
            isStale = false,
        )
        diffMs < dayMs -> UpdateTimePresentation(
            text = "${diffMs / hourMs}小时前",
            isStale = true,
        )
        else -> UpdateTimePresentation(
            text = "${diffMs / dayMs}天前",
            isStale = true,
        )
    }
}

private fun formatWidgetDebugTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
