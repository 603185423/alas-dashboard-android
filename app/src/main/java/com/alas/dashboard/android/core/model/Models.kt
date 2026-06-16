package com.alas.dashboard.android.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResourceSnapshotDto(
    @SerialName("resource_name") val resourceName: String,
    @SerialName("recorded_at_ms") val recordedAtMs: Long,
    @SerialName("received_at_ms") val receivedAtMs: Long,
    val value: Long,
    val limit: Long? = null,
    val total: Long? = null,
    val color: String? = null,
    @SerialName("age_ms") val ageMs: Long? = null,
)

@Serializable
data class LatestResourcesResponse(
    val resources: List<ResourceSnapshotDto>,
)

@Serializable
data class ResourceHistoryResponse(
    @SerialName("resource_name") val resourceName: String,
    val items: List<ResourceSnapshotDto>,
)

@Serializable
data class HealthResponse(
    val status: String,
    val database: String,
    @SerialName("user_count") val userCount: Int,
    @SerialName("generated_at_ms") val generatedAtMs: Long,
)

@Serializable
data class UserDto(
    val id: Long,
    @SerialName("user_key") val userKey: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("is_active") val isActive: Boolean,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("updated_at_ms") val updatedAtMs: Long,
)

@Serializable
data class UsersResponse(
    val users: List<UserDto>,
)

@Serializable
data class CreateUserRequest(
    @SerialName("user_key") val userKey: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class UpdateUserRequest(
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class TokenResponse(
    val user: UserDto,
    val token: String,
)

@Serializable
data class ErrorEnvelope(
    val error: String? = null,
)

data class ResourceSnapshot(
    val resourceName: String,
    val recordedAtMs: Long,
    val receivedAtMs: Long,
    val value: Long,
    val limit: Long?,
    val total: Long?,
    val color: String?,
    val ageMs: Long? = null,
)

data class DashboardUser(
    val id: Long,
    val userKey: String,
    val displayName: String,
    val isActive: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
)

enum class ThresholdDirection { ABOVE, BELOW }

enum class RuleKind { ONE_SHOT, PERSISTENT }

data class NotificationRule(
    val id: String,
    val resourceName: String,
    val direction: ThresholdDirection,
    val threshold: Long,
    val kind: RuleKind,
    val durationMinutes: Int = 0,
    val enabled: Boolean = true,
)

data class RuleRuntimeState(
    val ruleId: String,
    val hasTriggered: Boolean = false,
    val satisfiedSinceMs: Long? = null,
    val persistentShown: Boolean = false,
)

data class WidgetConfig(
    val appWidgetId: Int,
    val selectedResources: List<String> = emptyList(),
    val showAge: Boolean = true,
    val maxItems: Int = 4,
    val showDebugInfo: Boolean = false,
)

data class AccountConfig(
    val baseUrl: String = "",
    val userToken: String = "",
    val adminToken: String = "",
)

data class AppPreferences(
    val themeMode: String = "SYSTEM",
    val pollingMinutes: Int = 15,
    val backgroundSyncEnabled: Boolean = true,
    val onboardingCompleted: Boolean = false,
)

@Serializable
data class ExportConfig(
    val schemaVersion: Int = 1,
    val account: AccountConfigJson,
    val preferences: AppPreferencesJson,
    val rules: List<NotificationRuleJson>,
    val widgets: List<WidgetConfigJson>,
)

@Serializable
data class AccountConfigJson(
    val baseUrl: String,
    val userToken: String,
    val adminToken: String = "",
)

@Serializable
data class AppPreferencesJson(
    val themeMode: String,
    val pollingMinutes: Int,
    val backgroundSyncEnabled: Boolean,
    val onboardingCompleted: Boolean,
)

@Serializable
data class NotificationRuleJson(
    val id: String,
    val resourceName: String,
    val direction: String,
    val threshold: Long,
    val kind: String,
    val durationMinutes: Int,
    val enabled: Boolean,
)

@Serializable
data class WidgetConfigJson(
    val appWidgetId: Int,
    val selectedResources: List<String>,
    val showAge: Boolean,
    val maxItems: Int,
    val showDebugInfo: Boolean = false,
)

fun ResourceSnapshotDto.toDomain() = ResourceSnapshot(
    resourceName = resourceName,
    recordedAtMs = recordedAtMs,
    receivedAtMs = receivedAtMs,
    value = value,
    limit = limit,
    total = total,
    color = color,
    ageMs = ageMs,
)

fun UserDto.toDomain() = DashboardUser(
    id = id,
    userKey = userKey,
    displayName = displayName,
    isActive = isActive,
    createdAtMs = createdAtMs,
    updatedAtMs = updatedAtMs,
)
