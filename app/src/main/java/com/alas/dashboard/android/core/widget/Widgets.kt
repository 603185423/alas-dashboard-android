package com.alas.dashboard.android.core.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
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
import androidx.glance.GlanceComposable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.updateAll
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column as GlanceColumn
import androidx.glance.layout.Spacer as GlanceSpacer
import androidx.glance.layout.fillMaxSize as glanceFillMaxSize
import androidx.glance.layout.fillMaxWidth as glanceFillMaxWidth
import androidx.glance.layout.height as glanceHeight
import androidx.glance.layout.padding as glancePadding
import androidx.glance.text.Text as GlanceText
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.alas.dashboard.android.core.repository.DashboardRepository
import com.alas.dashboard.android.core.work.triggerImmediateDashboardSync
import com.alas.dashboard.android.core.model.displayResourceName
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

private enum class WidgetLayoutStyle(
    val title: String?,
    val defaultMaxItems: Int,
    val padding: Int,
    val titleSizeSp: Int,
    val bodySizeSp: Int,
    val mutedSizeSp: Int,
    val showAge: Boolean,
) {
    COMPACT(
        title = null,
        defaultMaxItems = 2,
        padding = 8,
        titleSizeSp = 0,
        bodySizeSp = 14,
        mutedSizeSp = 11,
        showAge = false,
    ),
    STANDARD(
        title = "当前资源",
        defaultMaxItems = 4,
        padding = 12,
        titleSizeSp = 15,
        bodySizeSp = 13,
        mutedSizeSp = 12,
        showAge = false,
    ),
    EXPANDED(
        title = "当前资源",
        defaultMaxItems = 6,
        padding = 14,
        titleSizeSp = 16,
        bodySizeSp = 14,
        mutedSizeSp = 12,
        showAge = true,
    ),
}

private open class BaseResourceWidget(
    private val layoutStyle: WidgetLayoutStyle,
) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

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
            .sortedBy { com.alas.dashboard.android.core.model.resourceDisplayOrder(it.resourceName) }
        val items = if (config.selectedResources.isEmpty()) {
            latest
        } else if (selectedItems.isNotEmpty()) {
            selectedItems
        } else {
            latest
        }
        provideContent {
            ResourceWidgetContent(
                items = items,
                maxItems = config.maxItems,
                showAge = config.showAge,
                showDebugInfo = config.showDebugInfo,
                debugInfo = WidgetDebugInfo(
                    renderedAtText = formatWidgetDebugTime(System.currentTimeMillis()),
                    itemCount = items.size,
                    latestReceivedAtText = items.maxOfOrNull { it.receivedAtMs }?.let(::formatWidgetDebugTime),
                    latestRecordedAtText = items.maxOfOrNull { it.recordedAtMs }?.let(::formatWidgetDebugTime),
                    sourceLabel = if (loadedFromNetwork) "网络补拉" else "本地缓存",
                ),
                layoutStyle = layoutStyle,
            )
        }
    }
}

private class ResourceWidget : BaseResourceWidget(WidgetLayoutStyle.STANDARD)

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

private class ResourceWidgetCompact : BaseResourceWidget(WidgetLayoutStyle.COMPACT)

class ResourceWidgetCompactReceiver : BaseResourceWidgetReceiver(ResourceWidgetCompact())

private class ResourceWidgetExpanded : BaseResourceWidget(WidgetLayoutStyle.EXPANDED)

class ResourceWidgetExpandedReceiver : BaseResourceWidgetReceiver(ResourceWidgetExpanded())

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
                                            text = "仅在 4x2 和 4x4 小组件中显示刷新相关信息",
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
}

private fun widgetEntryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)

suspend fun updateAllWidgets(context: Context) {
    ResourceWidget().updateAll(context)
    ResourceWidgetCompact().updateAll(context)
    ResourceWidgetExpanded().updateAll(context)
}

private val WidgetBackground = ColorProvider(Color(0xFF1F2937))

private val WidgetTitleColor = ColorProvider(Color(0xFFF9FAFB))

private val WidgetBodyColor = ColorProvider(Color(0xFFE5E7EB))

private val WidgetMutedColor = ColorProvider(Color(0xFFD1D5DB))

private data class WidgetDebugInfo(
    val renderedAtText: String,
    val itemCount: Int,
    val latestReceivedAtText: String?,
    val latestRecordedAtText: String?,
    val sourceLabel: String,
)

@Composable
@GlanceComposable
private fun ResourceWidgetContent(
    items: List<com.alas.dashboard.android.core.model.ResourceSnapshot>,
    maxItems: Int,
    showAge: Boolean,
    showDebugInfo: Boolean,
    debugInfo: WidgetDebugInfo,
    layoutStyle: WidgetLayoutStyle,
) {
    val visibleItems = items.take(minOf(maxItems, layoutStyle.defaultMaxItems))
    val titleStyle = TextStyle(
        color = WidgetTitleColor,
        fontSize = layoutStyle.titleSizeSp.sp,
    )
    val bodyStyle = TextStyle(
        color = WidgetBodyColor,
        fontSize = layoutStyle.bodySizeSp.sp,
    )
    val mutedStyle = TextStyle(
        color = WidgetMutedColor,
        fontSize = layoutStyle.mutedSizeSp.sp,
    )

    GlanceColumn(
        modifier = GlanceModifier
            .glanceFillMaxSize()
            .background(WidgetBackground)
            .glancePadding(layoutStyle.padding.dp),
        verticalAlignment = androidx.glance.layout.Alignment.Vertical.Top,
        horizontalAlignment = androidx.glance.layout.Alignment.Horizontal.Start,
    ) {
        layoutStyle.title?.let { title ->
            GlanceText(
                text = title,
                style = titleStyle,
            )
            GlanceSpacer(modifier = GlanceModifier.glanceHeight(8.dp))
        }
        if (visibleItems.isEmpty()) {
            GlanceText(
                text = if (layoutStyle == WidgetLayoutStyle.COMPACT) "暂无数据" else "暂无数据，请打开 App 检查连接",
                style = mutedStyle,
            )
        } else {
            visibleItems.forEach { item ->
                GlanceText(
                    text = buildString {
                        append(item.resourceName.displayResourceName())
                        append(": ")
                        append(item.value)
                        when {
                            item.total != null && layoutStyle != WidgetLayoutStyle.COMPACT -> append("/${item.total}")
                            item.limit != null && layoutStyle == WidgetLayoutStyle.EXPANDED -> append("/${item.limit}")
                        }
                    },
                    modifier = GlanceModifier.glanceFillMaxWidth(),
                    style = bodyStyle,
                )
                if (showAge && layoutStyle.showAge && item.ageMs != null) {
                    GlanceText(
                        text = "更新于 ${item.ageMs / 1000}s 前",
                        style = mutedStyle,
                    )
                }
            }
            if (showDebugInfo && layoutStyle != WidgetLayoutStyle.COMPACT) {
                GlanceSpacer(modifier = GlanceModifier.glanceHeight(8.dp))
                GlanceText(
                    text = "调试 渲染 ${debugInfo.renderedAtText}",
                    style = mutedStyle,
                )
                GlanceText(
                    text = "调试 数据 ${debugInfo.itemCount} 项 / ${debugInfo.sourceLabel}",
                    style = mutedStyle,
                )
                debugInfo.latestReceivedAtText?.let { received ->
                    GlanceText(
                        text = "调试 接收 $received",
                        style = mutedStyle,
                    )
                }
                if (layoutStyle == WidgetLayoutStyle.EXPANDED) {
                    debugInfo.latestRecordedAtText?.let { recorded ->
                        GlanceText(
                            text = "调试 记录 $recorded",
                            style = mutedStyle,
                        )
                    }
                }
            }
        }
    }
}

private fun formatWidgetDebugTime(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestampMs))
