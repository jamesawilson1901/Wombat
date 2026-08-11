package com.magpie.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "presets")
data class PresetFolder(
    @PrimaryKey val path: String,
    val name: String,
    val description: String = "",
    val useCount: Int = 0,
    val lastUsed: Long = 0L,
    val sortOrder: Int = 0,
)

// One row per destination the user actually picked; the local suggester
// ranks future files against these (§8.1).
@Entity(tableName = "decisions")
data class Decision(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val extension: String,
    val tokens: String,
    val chosenFolder: String,
    val timestamp: Long,
)

@Entity(tableName = "move_log")
data class MoveLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val originalPath: String,
    val originalName: String,
    val destFolder: String,
    val finalName: String,
    val suggestedBy: String, // "ai" | "local" | "manual" | "sweep"
    val accepted: Boolean,
    val undone: Boolean = false,
)

// Files the user answered "Not now" to; shown on the home screen.
@Entity(tableName = "pending")
data class PendingFile(
    @PrimaryKey val path: String,
    val size: Long,
    val detectedAt: Long,
)

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<PresetFolder>>

    @Query("SELECT * FROM presets ORDER BY sortOrder, name")
    suspend fun all(): List<PresetFolder>

    @Query("SELECT * FROM presets WHERE path = :path")
    suspend fun byPath(path: String): PresetFolder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(preset: PresetFolder)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(presets: List<PresetFolder>)

    @Query("DELETE FROM presets WHERE path = :path")
    suspend fun delete(path: String)

    @Query("UPDATE presets SET useCount = useCount + 1, lastUsed = :now WHERE path = :path")
    suspend fun recordUse(path: String, now: Long)
}

@Dao
interface DecisionDao {
    @Query("SELECT * FROM decisions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<Decision>

    @Insert
    suspend fun insert(decision: Decision)
}

@Dao
interface MoveLogDao {
    @Query("SELECT * FROM move_log ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<MoveLog>>

    @Query("SELECT * FROM move_log WHERE id = :id")
    suspend fun byId(id: Long): MoveLog?

    @Insert
    suspend fun insert(log: MoveLog): Long

    @Update
    suspend fun update(log: MoveLog)
}

@Dao
interface PendingDao {
    @Query("SELECT * FROM pending ORDER BY detectedAt DESC")
    fun observeAll(): Flow<List<PendingFile>>

    @Query("SELECT * FROM pending")
    suspend fun all(): List<PendingFile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: PendingFile)

    @Query("DELETE FROM pending WHERE path = :path")
    suspend fun delete(path: String)
}

@Database(
    entities = [PresetFolder::class, Decision::class, MoveLog::class, PendingFile::class],
    version = 1,
    exportSchema = false,
)
abstract class MagpieDb : RoomDatabase() {
    abstract fun presets(): PresetDao
    abstract fun decisions(): DecisionDao
    abstract fun moveLog(): MoveLogDao
    abstract fun pending(): PendingDao

    companion object {
        fun build(context: Context): MagpieDb =
            Room.databaseBuilder(context, MagpieDb::class.java, "magpie.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
