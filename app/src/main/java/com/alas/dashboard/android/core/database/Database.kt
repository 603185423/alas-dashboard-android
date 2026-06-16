package com.alas.dashboard.android.core.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "latest_resources")
data class LatestResourceEntity(
    @PrimaryKey val resourceName: String,
    val recordedAtMs: Long,
    val receivedAtMs: Long,
    val value: Long,
    val limitValue: Long?,
    val totalValue: Long?,
    val color: String?,
)

@Entity(tableName = "resource_history", primaryKeys = ["resourceName", "recordedAtMs"])
data class ResourceHistoryEntity(
    val resourceName: String,
    val recordedAtMs: Long,
    val receivedAtMs: Long,
    val value: Long,
    val limitValue: Long?,
    val totalValue: Long?,
    val color: String?,
)

@Entity(tableName = "resource_sync_meta")
data class ResourceSyncMetaEntity(
    @PrimaryKey val resourceName: String,
    val lastSyncedAtMs: Long,
)

@Dao
interface ResourceDao {
    @Query("SELECT * FROM latest_resources ORDER BY resourceName")
    fun observeLatest(): Flow<List<LatestResourceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLatest(items: List<LatestResourceEntity>)

    @Query("DELETE FROM latest_resources")
    suspend fun clearLatest()

    @Query("SELECT * FROM resource_history WHERE resourceName = :resourceName ORDER BY recordedAtMs ASC")
    fun observeHistory(resourceName: String): Flow<List<ResourceHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(items: List<ResourceHistoryEntity>)

    @Query("DELETE FROM resource_history WHERE resourceName = :resourceName")
    suspend fun deleteHistory(resourceName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: ResourceSyncMetaEntity)

    @Query("SELECT * FROM latest_resources")
    suspend fun getLatestNow(): List<LatestResourceEntity>
}

@Database(
    entities = [LatestResourceEntity::class, ResourceHistoryEntity::class, ResourceSyncMetaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class DashboardDatabase : RoomDatabase() {
    abstract fun resourceDao(): ResourceDao
}
