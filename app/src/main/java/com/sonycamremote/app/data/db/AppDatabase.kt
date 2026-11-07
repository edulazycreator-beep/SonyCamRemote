package com.sonycamremote.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** A saved camera connection profile. The Wi-Fi password is stored encrypted (Android Keystore). */
@Entity(tableName = "saved_cameras")
data class SavedCameraEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** [com.sonycamremote.engine.model.ProtocolKind] name. */
    val protocol: String,
    val host: String? = null,
    val port: Int? = null,
    val deviceDescriptionUrl: String? = null,
    val usbDeviceName: String? = null,
    val bridgeUrl: String? = null,
    val bridgeCameraId: String? = null,
    val wifiSsid: String? = null,
    val wifiPasswordEncrypted: String? = null,
    val modelName: String? = null,
    val lastConnectedAt: Long? = null,
)

@Dao
interface SavedCameraDao {
    @Query("SELECT * FROM saved_cameras ORDER BY lastConnectedAt DESC")
    fun observeAll(): Flow<List<SavedCameraEntity>>

    @Query("SELECT * FROM saved_cameras WHERE id = :id")
    suspend fun get(id: String): SavedCameraEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedCameraEntity)

    @Delete
    suspend fun delete(entity: SavedCameraEntity)
}

/** Exposure preset. Values are matched by label on apply, since raw tokens differ between protocols. */
@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** JSON object: PropertyId name → value label. */
    val valuesJson: String,
    val createdAt: Long,
)

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Insert
    suspend fun insert(entity: PresetEntity): Long

    @Delete
    suspend fun delete(entity: PresetEntity)
}

/** Persisted session history (connect / disconnect / capture events). */
@Entity(tableName = "session_events")
data class SessionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val cameraName: String,
    val event: String,
)

@Dao
interface SessionEventDao {
    @Query("SELECT * FROM session_events ORDER BY timestamp DESC LIMIT 500")
    fun observeRecent(): Flow<List<SessionEventEntity>>

    @Insert
    suspend fun insert(entity: SessionEventEntity)

    @Query("DELETE FROM session_events")
    suspend fun clear()
}

@Database(
    entities = [SavedCameraEntity::class, PresetEntity::class, SessionEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun savedCameras(): SavedCameraDao
    abstract fun presets(): PresetDao
    abstract fun sessionEvents(): SessionEventDao
}
