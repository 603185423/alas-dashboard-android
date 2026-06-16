# Android Dashboard App 实施计划

## Summary

- 目标：基于 `dashboard-api.md` 从零开发一个 Android 12+ 应用，用于消费 Dashboard API，提供当前资源页、历史图表页、设置页，以及在提供 `admin token` 时显示的管理员页面。
- 架构原则：采用 Google 推荐的现代 Android 架构，使用单 Activity + Jetpack Compose + Navigation Compose + ViewModel + Repository + Room + DataStore + WorkManager + Hilt。
- 平台重点：优先规避 Widget、通知、后台刷新和电池优化带来的常见问题，避免依赖不稳定的常驻后台方案；轮询统一由 WorkManager 驱动，前台刷新与 Widget 刷新共用数据层。
- 关键用户决策：最低版本 `Android 12+`，UI 技术栈 `Jetpack Compose`，账户模型为单连接，Widget 采用“每个实例独立配置”，支持应用配置的导入导出。

## Current State Analysis

### 仓库现状

- 当前仓库并非 Android 工程，根目录仅有：
  - `c:\xiangmu\androidstudio\alas-dashboard-android\alas-dashboard-android\dashboard-api.md`
  - `c:\xiangmu\androidstudio\alas-dashboard-android\alas-dashboard-android\LICENSE`
  - `c:\xiangmu\androidstudio\alas-dashboard-android\alas-dashboard-android\.gitignore`
- 不存在 `settings.gradle(.kts)`、`build.gradle(.kts)`、`AndroidManifest.xml`、`app/` 模块、Kotlin 源码或资源目录。
- 因此本计划默认“从零初始化 Android 工程”，而不是在现有 App 上增量修改。

### 已确认的接口与产品约束

- 资源总览接口：`GET /api/v1/resources/latest`
- 历史图表接口：`GET /api/v1/resources/{resource_name}/history`
- Widget 概览接口：`GET /api/v1/widget/overview`
- 普通用户只需配置 `Base URL + user token`
- `admin token` 为可选项；仅在填写后显示管理员页面并开放用户管理能力
- 小组件支持多尺寸，每个 Widget 实例可独立配置展示资源
- 通知分为两类：
  - 一次性阈值通知：资源首次超过或低于阈值时触发一次
  - 持续阈值通知：资源连续超过或低于阈值达到设定时长后转为持久通知
- 必须支持应用配置的导入导出，便于换机迁移

## Proposed Changes

### 1. 初始化工程与模块结构

新增以下文件与目录，用于建立可维护的基础工程：

- `c:\xiangmu\androidstudio\alas-dashboard-android\alas-dashboard-android\settings.gradle.kts`
  - 声明根工程与模块。
- `c:\xiangmu\androidstudio\alas-dashboard-android\alas-dashboard-android\build.gradle.kts`
  - 定义版本目录、插件管理和全局构建约定。
- `c:\xiangmu\androidstudio\alas-dashboard-android\alas-dashboard-android\gradle.properties`
  - 开启 AndroidX、Kotlin 增量编译、Compose 等构建参数。
- `c:\xiangmu\androidstudio\alas-dashboard-android\alas-dashboard-android\app\build.gradle.kts`
  - 配置应用模块、Compose、Hilt、Room、WorkManager、Proto/DataStore、Glance、网络库和测试依赖。
- `c:\xiangmu\androidstudio\alas-dashboard-android\alas-dashboard-android\app\src\main\AndroidManifest.xml`
  - 声明 `Application`、主 Activity、`WorkManager`、Widget Receiver、通知权限、文件导入导出所需 provider。

首版不拆分多 Gradle module，原因如下：

- 当前仓库为空，从零搭建时先保证可交付和低维护成本。
- 业务规模目前适合“单 app module + 包级分层”；若后续体量扩大，再按 `core-*`、`feature-*` 方式拆分。
- 这样可以减少初期 Gradle 配置、Hilt entry point 和测试工程复杂度。

包结构按分层组织，路径规划如下：

- `app/src/main/java/.../app`
  - `DashboardApplication`
- `app/src/main/java/.../core/network`
  - Retrofit API、认证拦截器、DTO、错误映射
- `app/src/main/java/.../core/database`
  - Room Database、Entity、Dao、TypeConverter
- `app/src/main/java/.../core/datastore`
  - 应用设置、账户配置、通知规则、Widget 配置的持久化访问
- `app/src/main/java/.../core/model`
  - 领域模型、枚举、UI model mapper
- `app/src/main/java/.../core/repository`
  - 资源、历史、管理员、配置、通知、Widget 相关仓库
- `app/src/main/java/.../core/work`
  - 后台轮询 Worker、刷新调度器、开机恢复逻辑
- `app/src/main/java/.../core/notification`
  - 通知渠道、一次性通知与持久通知管理、阈值状态机
- `app/src/main/java/.../core/widget`
  - Glance Widget、配置 Activity、Remote refresh 适配
- `app/src/main/java/.../feature/overview`
  - 当前资源页
- `app/src/main/java/.../feature/history`
  - 历史图表页
- `app/src/main/java/.../feature/settings`
  - 通用设置页、主题、轮询、导入导出、阈值规则入口
- `app/src/main/java/.../feature/admin`
  - 用户列表、创建用户、启停用户、轮换 token
- `app/src/main/java/.../navigation`
  - 顶层路由与底部导航
- `app/src/main/java/.../ui/theme`
  - Material 3 浅色、深色、跟随系统主题

### 2. 技术选型与核心依赖

确定以下技术栈：

- UI：Jetpack Compose + Material 3 + Navigation Compose
- DI：Hilt
- 网络：Retrofit + OkHttp + Kotlinx Serialization
- 本地缓存：Room
- 键值配置：Proto DataStore
- 后台任务：WorkManager
- Widget：Jetpack Glance
- 图表：Vico Compose
- 异步：Kotlin Coroutines + Flow
- 测试：JUnit4/5（按 Android 默认组合）、Turbine、MockWebServer、Compose UI Test、WorkManager Test

选型原因：

- Compose / Material 3 / Navigation Compose 与 Google 官方推荐一致。
- WorkManager 是 Android 12+ 背景下最稳妥的轮询方案，能够更好适配 Doze、电池优化和系统调度。
- Glance 是 Google 针对现代 App Widget 的推荐方向，适合多尺寸 Widget。
- Vico 对 Compose 友好，适合实现缩放、拖动、Marker 等交互图表。

### 3. 数据模型与本地持久化设计

本地层需保存三类数据：

- 服务端资源数据缓存
- 用户配置与应用设置
- 通知触发状态与 Widget 独立配置

Room 表设计：

- `latest_resources`
  - 缓存 `/resources/latest` 最新资源列表
  - 字段包含 `resourceName`、`recordedAtMs`、`receivedAtMs`、`value`、`limit`、`total`、`color`
- `resource_history`
  - 缓存按资源拉取的历史序列
  - 用于图表离线显示与减少重复请求
- `resource_sync_meta`
  - 保存各资源最近拉取区间、etag 等扩展信息；首版至少记录最后成功同步时间

Proto DataStore / JSON 文件设计：

- `app_preferences.pb`
  - 主题模式
  - 全局轮询间隔
  - 是否启用后台刷新
  - 首次运行引导完成状态
- `account_config.pb`
  - `baseUrl`
  - `userToken`
  - 可选 `adminToken`
  - 连接测试结果缓存
- `notification_rules.pb`
  - 每条规则的资源名、方向（高于/低于）、阈值、是否一次性、是否持续型、持续时长、启用状态
- `widget_configs.pb`
  - 每个 `appWidgetId` 对应的资源选择、排序、显示条数、是否显示时间戳/过期信息
- `notification_runtime.pb`
  - 一次性通知是否已触发
  - 持续型规则开始满足条件的起始时间
  - 当前持久通知是否已展示

导入导出文件格式：

- 新增 `dashboard_app_config.json`
  - 保存应用配置、账户信息、阈值规则、Widget 默认配置
  - 不导出 Room 的历史缓存，避免体积膨胀和迁移不必要数据
- 导出时可选“是否包含 token”
  - 默认包含，因为用户明确需要换机迁移
  - 导入时提供确认页，避免误覆盖

### 4. API 接入层设计

新增 `DashboardApiService`，覆盖以下接口：

- `GET /api/v1/health`
- `GET /api/v1/me`
- `GET /api/v1/resources/latest`
- `GET /api/v1/resources/{resource_name}/history`
- `GET /api/v1/widget/overview`
- `GET /api/v1/admin/users`
- `POST /api/v1/admin/users`
- `GET /api/v1/admin/users/{user_id}`
- `PATCH /api/v1/admin/users/{user_id}`
- `POST /api/v1/admin/users/{user_id}/rotate-token`

实现规则：

- 用户请求默认使用 `user token`
- 管理员请求只有在存在 `admin token` 时才可访问
- `Authorization: Bearer <token>` 由拦截器统一注入，但区分普通接口与管理员接口
- 错误统一映射为领域错误：
  - 认证失败
  - 权限不足
  - 服务不可达
  - 数据格式异常
  - 业务错误消息

数据流：

- Repository 先读本地缓存，再决定是否触发网络刷新
- 前台页面优先展示缓存，网络结果到达后自动刷新 UI
- Widget 与通知逻辑复用同一 Repository，避免各自直接打网络请求造成不一致

### 5. 页面与导航设计

采用单 Activity + 底部导航，页面规划如下：

- “当前资源”
  - 入口页，展示所有最新资源
  - 支持手动下拉刷新
  - 支持按资源名称、更新时间、资源值排序
  - 支持资源搜索和 pin 常用资源
- “历史图表”
  - 选择资源后展示历史曲线
  - 提供时间范围切换：24 小时、7 天、30 天、自定义
  - 图表支持拖拽、缩放、点选数据点显示 tooltip
  - 显示数据最新时间、最小值、最大值、最近变化
- “设置”
  - 账户连接设置
  - 轮询间隔设置
  - 主题设置（浅色 / 深色 / 跟随系统）
  - 通知规则管理
  - Widget 默认说明入口
  - 应用配置导入导出
  - 电池优化说明与系统设置跳转
- “管理员”
  - 仅当 `admin token` 已配置时显示
  - 用户列表、创建用户、修改显示名/启停、轮换 token

补充页面：

- 首次启动引导页
  - 引导填写 `Base URL` 与 `user token`
  - 提供“测试连接”按钮，验证 `/health` 和 `/me`
- Widget 配置页
  - 由每个 Widget 实例单独拉起
  - 选择要展示的资源、顺序、最多显示数量、是否突出超阈值资源
- 通知规则编辑页
  - 支持创建、编辑、删除一次性规则和持续型规则

### 6. 后台刷新与电池优化策略

统一采用 WorkManager，不设计前台常驻服务。

调度方案：

- 周期性刷新：`PeriodicWorkRequest`
  - 间隔由用户设置，但需要遵守 WorkManager 最小周期限制
  - 若用户设置低于系统允许值，UI 中明确提示并做“前台即时刷新 + 后台按系统最小值”处理
- 立即刷新：`OneTimeWorkRequest`
  - 手动刷新、保存设置、导入配置、Widget 重新配置后触发
- 开机恢复：接收 `BOOT_COMPLETED` 后重新注册后台刷新

关键设计决策：

- 后台轮询设置项以“用户期望值”保存，但实际调度值需映射到系统允许范围
- Widget 不单独维护独立网络轮询，而是在 `GlanceAppWidgetReceiver` 的更新周期中读取本地缓存；必要时触发一次轻量刷新任务
- 避免多个入口分别调度 Worker，统一由 `SyncScheduler` 负责注册、去重、更新
- 设置页展示电池优化说明，并提供跳转到系统忽略电池优化页面；不强制申请，以符合平台规范

### 7. 通知与阈值规则设计

通知体系分三层：

- 通知规则配置
- 规则状态机计算
- Android 通知展示

规则模型：

- `OneShotThresholdRule`
  - 资源名
  - 比较方向：`ABOVE` / `BELOW`
  - 阈值
  - 已触发标记
- `PersistentThresholdRule`
  - 资源名
  - 比较方向
  - 阈值
  - 持续时长毫秒
  - 首次满足条件时间
  - 当前是否处于持久通知展示中

判定逻辑：

- 每次成功同步最新资源后，运行规则评估器
- 一次性规则：
  - 当条件从“不满足”切换为“满足”时发一次通知
  - 已触发后不重复，除非用户重置规则或编辑阈值
- 持续规则：
  - 条件首次满足时记录开始时间
  - 连续满足达到配置时长后发持久通知
  - 条件不再满足时自动取消持久通知并重置状态

通知渠道：

- `resource_alert_once`
  - 用于一次性阈值提醒
- `resource_alert_persistent`
  - 用于持续型阈值提醒
- `sync_status`（可选，仅调试）
  - 首版默认不暴露给普通用户

### 8. Widget 设计

使用 Glance 实现多尺寸 Widget，并定义至少三种尺寸布局：

- 小尺寸
  - 显示 1-2 个重点资源
- 中尺寸
  - 显示 3-4 个资源及更新时间
- 大尺寸
  - 显示更多资源，并突出超阈值状态或过期状态

每个 Widget 实例独立配置：

- 选择显示哪些资源
- 调整展示顺序
- 设置最大显示数量
- 控制是否显示 `age_ms`/过期标记

渲染规则：

- 优先读取本地 `latest_resources`
- 若缓存过旧，则展示“数据过期”状态，而不是在 Widget 进程内直接做高频网络请求
- 根据尺寸自动裁剪内容，避免 RemoteViews/Glance 渲染异常

### 9. 主题与设计规范

主题方案：

- `Light`
- `Dark`
- `System`

实现方式：

- 使用 Material 3 `dynamicColor`（Android 12+ 可用）
- DataStore 保存用户选择
- 顶层 `AppTheme` 读取配置并驱动全局主题

设计规范约束：

- 使用 Material 3 组件和间距体系
- 页面遵守 edge-to-edge 布局
- 支持大字号、TalkBack 语义、基础无障碍标签
- 错误态、空态、加载态统一样式

### 10. 管理员页面设计

仅当检测到 `admin token` 非空时显示“管理员”入口。

页面能力：

- 拉取用户列表
- 创建用户，返回新 token 后立即支持复制/分享/保存
- 查看用户详情
- 修改显示名与启停状态
- 轮换 token，并在弹窗中强调“仅返回一次”

安全处理：

- 默认不在常规列表中明文展示 token
- 新建或轮换返回的 token 仅在当前会话弹窗中显示
- 可提供“复制到剪贴板”操作，并显示敏感信息提醒

### 11. 配置导入导出设计

新增设置页中的“备份与迁移”分组。

导出流程：

- 生成 `dashboard_app_config.json`
- 通过系统文件选择器保存到用户指定位置
- 内容包含：
  - Base URL
  - user token
  - 可选 admin token
  - 主题
  - 轮询设置
  - 通知规则
  - Widget 默认配置与已保存配置（不包含无效 `appWidgetId` 映射）

导入流程：

- 通过系统文件选择器选择 JSON
- 执行结构校验与版本校验
- 显示导入摘要
- 用户确认后覆盖当前配置，并重新调度 Worker、刷新缓存、更新 Widget

兼容策略：

- 文件中增加 `schemaVersion`
- 首版定义为 `1`
- 后续若配置结构变更，通过版本迁移器兼容旧备份文件

### 12. 测试与验收实现

新增测试文件，重点覆盖高价值路径：

- 单元测试
  - 资源 DTO -> 领域模型映射
  - 阈值规则状态机
  - 轮询间隔到 WorkManager 周期的映射逻辑
  - 导入导出配置序列化与版本兼容
- 集成测试
  - Repository + MockWebServer 的接口成功/失败场景
  - 管理员接口鉴权分支
- UI 测试
  - 首次引导
  - 设置页主题切换
  - 管理员页按 token 有无显示/隐藏
  - 历史图表页基础渲染
- Worker 测试
  - 同步成功后更新数据库
  - 满足阈值后发通知
  - 条件解除后取消持久通知

## Assumptions & Decisions

- 应用包名暂定为 `com.alas.dashboard.android`；实施时若用户有明确命名要求，可统一替换。
- 首版仅支持一个服务器连接配置，但允许通过管理员页管理服务端上的多个用户。
- 历史数据缓存仅按已访问资源逐步积累，不做全量预拉取。
- Widget 首版采用 Glance，不单独实现旧式 RemoteViews 双栈。
- 后台轮询以 WorkManager 为准，不承诺秒级实时性；前台页面支持手动刷新补偿。
- 导入导出默认包含 token，以满足换机场景；UI 中需要明确敏感信息提醒。
- 管理员页不是强制入口，没有 `admin token` 时不显示任何管理员功能。
- 通知与 Widget 都依赖本地缓存与统一同步链路，避免多套独立网络逻辑。

## Verification Steps

实施完成后按以下步骤验证：

1. 工程验证
   - 执行 Gradle Sync 成功
   - Debug 包可安装到 Android 12+ 设备或模拟器
2. 连接验证
   - 输入 `Base URL + user token` 后可通过 `/health`、`/me` 完成连接测试
   - 当前资源页能展示 `/resources/latest`
3. 图表验证
   - 选择资源后可加载 `/resources/{resource_name}/history`
   - 24h / 7d / 30d 切换正确，图表支持基本交互
4. 后台刷新验证
   - 修改轮询配置后重新注册 Worker
   - 到达同步周期后数据库缓存刷新，页面与 Widget 同步更新
5. 通知验证
   - 一次性阈值规则仅在首次满足时提醒一次
   - 持续型规则在连续满足设定时长后出现持久通知
   - 条件恢复正常后持久通知自动取消
6. Widget 验证
   - 小、中、大尺寸均能正确渲染
   - 不同 Widget 实例可显示不同资源组合
   - 过期数据能明确显示状态
7. 管理员验证
   - 未配置 `admin token` 时不显示管理员入口
   - 配置 `admin token` 后可查看、创建、编辑用户并轮换 token
8. 迁移验证
   - 导出配置后在另一设备导入成功
   - 导入后主题、轮询、通知规则和账户信息恢复正确

