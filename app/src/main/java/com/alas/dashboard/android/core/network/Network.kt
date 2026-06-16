package com.alas.dashboard.android.core.network

import com.alas.dashboard.android.core.model.CreateUserRequest
import com.alas.dashboard.android.core.model.HealthResponse
import com.alas.dashboard.android.core.model.LatestResourcesResponse
import com.alas.dashboard.android.core.model.ResourceHistoryResponse
import com.alas.dashboard.android.core.model.TokenResponse
import com.alas.dashboard.android.core.model.UpdateUserRequest
import com.alas.dashboard.android.core.model.UserDto
import com.alas.dashboard.android.core.model.UsersResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Query
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

interface DashboardApiService {
    @GET("/api/v1/health")
    suspend fun health(): HealthResponse

    @GET("/api/v1/me")
    suspend fun me(@Header("Authorization") auth: String): UserDto

    @GET("/api/v1/resources/latest")
    suspend fun latest(@Header("Authorization") auth: String): LatestResourcesResponse

    @GET("/api/v1/resources/{resourceName}/history")
    suspend fun history(
        @Header("Authorization") auth: String,
        @Path("resourceName") resourceName: String,
        @Query("from_ms") fromMs: Long? = null,
        @Query("to_ms") toMs: Long? = null,
        @Query("limit") limit: Int = 500,
        @Query("order") order: String = "asc",
    ): ResourceHistoryResponse

    @GET("/api/v1/widget/overview")
    suspend fun widgetOverview(@Header("Authorization") auth: String): LatestResourcesResponse

    @GET("/api/v1/admin/users")
    suspend fun adminUsers(@Header("Authorization") auth: String): UsersResponse

    @POST("/api/v1/admin/users")
    suspend fun createUser(
        @Header("Authorization") auth: String,
        @Body request: CreateUserRequest,
    ): TokenResponse

    @GET("/api/v1/admin/users/{userId}")
    suspend fun userDetail(
        @Header("Authorization") auth: String,
        @Path("userId") userId: Long,
    ): UserDto

    @PATCH("/api/v1/admin/users/{userId}")
    suspend fun updateUser(
        @Header("Authorization") auth: String,
        @Path("userId") userId: Long,
        @Body request: UpdateUserRequest,
    ): UserDto

    @POST("/api/v1/admin/users/{userId}/rotate-token")
    suspend fun rotateToken(
        @Header("Authorization") auth: String,
        @Path("userId") userId: Long,
    ): TokenResponse
}

@Singleton
class ApiFactory @Inject constructor() {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            },
        )
        .build()

    fun create(baseUrl: String): DashboardApiService {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DashboardApiService::class.java)
    }
}
