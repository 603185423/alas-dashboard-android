# 小组件定时刷新问题记录

## 1. 背景

本项目的桌面小组件需要在以下场景下尽量保持资源数据更新：

- App 在前台时可以立即刷新。
- App 退到后台时可以继续按配置的轮询间隔刷新。
- 用户把 App 从最近任务列表划掉后，小组件仍尽量继续刷新。
- 设备重启、应用更新后，后台刷新能力能够自动恢复。

说明：

- 如果用户在系统设置里执行了“强行停止（Force stop）”，Android 会冻结该应用的广播、后台任务和闹钟，这种情况不在可解决范围内。

## 2. 开发过程中遇到的问题

### 问题 1：仅依赖 WorkManager，后台长时间不刷新

现象：

- 设置了后台轮询间隔后，前台时刷新正常。
- App 退到后台甚至被最近任务划掉后，1 到 2 小时都可能看不到小组件数据更新。

原因：

- 仅使用 `WorkManager` 的周期任务时，系统会按自身策略调度。
- `WorkManager` 适合“最终会执行”的后台任务，但不保证严格按设置时间准点执行。
- 某些 ROM 即使已给白名单，也可能继续延后周期任务。

解决方法：

- 保留 `WorkManager` 作为官方推荐的主链路。
- 同时增加 `AlarmManager.setInexactRepeating()` 作为第二条唤醒链路。
- 闹钟到点后不只是简单更新小组件，而是主动触发一次同步逻辑。

当前代码位置：

- `app/src/main/java/com/alas/dashboard/android/core/work/Sync.kt`

### 问题 2：小组件被系统唤醒了，但数据还是旧的

现象：

- 调试信息里可以看到：
  - `渲染` 时间在变化
  - `接收` 时间不变化
- 说明小组件本身被系统重新绘制了，但实际网络数据没有同步成功。

原因：

- 早期实现中，`onUpdate()` 和闹钟广播只是“排队一个 WorkManager 立即任务”。
- 广播确实到了，但 Worker 仍可能被系统继续延后执行。
- 最终结果是小组件重绘时仍然只能读到本地旧缓存。

解决方法：

- 将广播和 Widget 更新入口改成“先直接同步，失败后再回退到 Worker”。
- 具体做法：
  - `triggerImmediateDashboardSync()` 直接通过 Hilt `EntryPoint` 拿到 `DashboardSyncRunner`
  - 先执行 `syncRunner.syncLatest()`
  - 如果直接同步失败，再调用 `enqueueImmediateDashboardSync()` 让 `WorkManager` 兜底

当前代码位置：

- `app/src/main/java/com/alas/dashboard/android/core/work/Sync.kt`
- `app/src/main/java/com/alas/dashboard/android/core/widget/Widgets.kt`

### 问题 3：应用被最近任务划掉后，担心小组件不刷新

现象：

- 用户从最近任务列表把 App 划掉后，希望小组件仍能继续刷新。

原因：

- “最近任务划掉”不等于“强行停止”。
- 但如果仅依赖进程内逻辑，应用进程被回收后就没有主动刷新能力。

解决方法：

- 将刷新入口放到系统可唤醒的组件中，而不是依赖 Activity 或前台页面。
- 当前使用的可唤醒入口：
  - AppWidget 的 `onEnabled()` / `onUpdate()`
  - `AlarmManager` 广播
  - `BOOT_COMPLETED`
  - `MY_PACKAGE_REPLACED`
  - `WorkManager`

结论：

- 用户把 App 从后台划掉后，小组件仍可以继续刷新。
- 刷新间隔通常会比前台状态稍长，这是系统后台调度导致的正常现象。

### 问题 4：小组件更新入口分散，不同尺寸可能刷新不一致

现象：

- 紧凑、标准、大号三种 Widget 实例都存在时，必须确保它们一起刷新。

原因：

- 如果只刷新某一种 Widget Receiver 对应的实例，其他尺寸可能不会同步更新。

解决方法：

- 提供统一入口 `updateAllWidgets(context)`。
- 每次同步完成后统一刷新：
  - `ResourceWidget`
  - `ResourceWidgetCompact`
  - `ResourceWidgetExpanded`

当前代码位置：

- `app/src/main/java/com/alas/dashboard/android/core/widget/Widgets.kt`

### 问题 5：缺少可观察的调试信息，难以判断到底是“没触发”还是“触发了但没同步”

现象：

- 出现问题时，只能看到小组件显示旧数据，无法快速判断刷新链路卡在哪一层。

解决方法：

- 为 `4x2` 和 `4x4` 小组件增加“显示调试信息”开关。
- 调试信息包括：
  - 渲染时间
  - 当前显示的数据条数
  - 数据来源：`本地缓存` / `网络补拉`
  - 最新 `received_at`
  - 大号组件额外显示最新 `recorded_at`

作用：

- 如果 `渲染` 在变、`接收` 不变，说明是“重绘了但数据没更新”。
- 如果 `渲染` 和 `接收` 都不变，说明刷新入口本身没有跑到。

当前代码位置：

- `app/src/main/java/com/alas/dashboard/android/core/widget/Widgets.kt`

## 3. 当前最终方案

### 3.1 同步核心

使用 `DashboardSyncRunner.syncLatest()` 作为统一同步入口，负责：

- 调用 `DashboardRepository.refreshLatest()` 拉取最新资源
- 触发阈值通知检查
- 刷新所有 Widget 实例

代码位置：

- `app/src/main/java/com/alas/dashboard/android/core/work/Sync.kt`

### 3.2 后台刷新链路

当前采用“双保险 + 直接同步优先”的方案。

#### 链路 A：WorkManager 周期任务

用途：

- 作为官方推荐的后台任务主链路
- 适合系统允许时执行周期同步

特点：

- 受系统调度影响较大
- 不保证严格准点

#### 链路 B：AlarmManager 周期广播

用途：

- 在后台和进程被回收情况下，额外提供一条可唤醒链路

特点：

- 使用 `setInexactRepeating()`
- 仍然不是严格实时，但比单纯依赖 `WorkManager` 更稳定

#### 链路 C：Widget 自身更新入口

触发点：

- `onEnabled()`
- `onUpdate()`

行为：

- 收到系统的 Widget 更新事件后，立即尝试同步一次

#### 链路 D：系统事件恢复

触发点：

- `BOOT_COMPLETED`
- `MY_PACKAGE_REPLACED`

行为：

- 重新注册后台调度
- 再补一次立即同步

### 3.3 直接同步优先策略

当前所有系统入口都遵循同一原则：

1. 先直接执行同步逻辑。
2. 如果直接同步失败，再回退到 `WorkManager` 立即任务。

这样可以避免下面这种情况：

- 广播已经到了
- 但 Worker 还在排队
- 小组件重新渲染时只能读旧缓存

### 3.4 Widget Provider 配置

当前 Widget provider XML 中启用了系统周期更新：

- `android:updatePeriodMillis="1800000"`

说明：

- 即每 30 分钟允许系统触发一次 Widget 更新。
- 这是对 `AlarmManager + WorkManager` 的补充，不是唯一刷新来源。

相关文件：

- `app/src/main/res/xml/resource_widget_info.xml`
- `app/src/main/res/xml/resource_widget_info_compact.xml`
- `app/src/main/res/xml/resource_widget_info_expanded.xml`

### 3.5 Manifest 注册项

当前 Manifest 中注册了以下与刷新相关的接收器：

- `ResourceWidgetReceiver`
- `ResourceWidgetCompactReceiver`
- `ResourceWidgetExpandedReceiver`
- `BootCompletedReceiver`
- `DashboardSyncAlarmReceiver`

相关文件：

- `app/src/main/AndroidManifest.xml`

## 4. 当前方案的实际表现

当前实测表现：

- App 在前台时，刷新正常。
- App 在后台时，刷新正常。
- App 从最近任务列表划掉后，即使不开自启动和电池优化白名单，小组件仍然能够继续刷新。
- 与前台状态相比，后台或被划掉后的刷新间隔会稍长一些。

这个表现属于预期结果，因为 Android 在后台场景下本来就会对任务调度进行收敛。

## 5. 当前方案的边界

当前方案可以解决：

- App 不在前台时的小组件刷新
- App 从最近任务划掉后的持续刷新
- 设备重启后的刷新恢复
- 应用升级后的刷新恢复

当前方案不能解决：

- 用户执行“强行停止（Force stop）”后的后台刷新
- 所有机型上都做到精确到分钟的严格定时刷新

## 6. 后续可继续优化的方向

- 在设置页增加“最近一次后台同步成功时间”
- 在设置页增加后台调度诊断信息
- 在调试信息里增加“最近一次同步失败原因”
- 区分“同步触发成功但 API 无新数据”和“同步根本没跑起来”
- 针对不同厂商 ROM 增加更明确的后台保活说明

## 7. 维护建议

- 后续如果再次出现“Widget 会重绘但数据不更新”，优先查看调试信息中的 `渲染` 和 `接收`。
- 修改后台刷新逻辑时，尽量不要删除现有多链路设计，否则很容易退回到“前台正常、后台偶发失效”的状态。
- 如果要进一步增强刷新稳定性，优先在“调试可观测性”上补数据，再决定是否调整调度策略。
