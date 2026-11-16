package com.agent.eae.android.data

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

@Entity(tableName = "tools")
data class ToolEntity(
    @PrimaryKey val name: String,
    val description: String,
    val keywords: String,
    val schemaJson: String,
    val category: String,
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val text: String,
    val source: String?,
    val createdAt: Long,
    val embedding: ByteArray?,
) {
    override fun equals(other: Any?): Boolean = this === other || (other is MemoryEntity && other.id == id)

    override fun hashCode(): Int = id.hashCode()
}

@Entity(tableName = "kv")
data class KvEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val name: String,
    val description: String,
    val instructions: String,
    val python: String?,
    val createdAt: Long,
)

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val prompt: String,
    val intervalMinutes: Long,
    val nextRunAt: Long,
    val enabled: Boolean,
)

@Dao
interface ToolDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tools: List<ToolEntity>)

    @Query("SELECT * FROM tools")
    suspend fun all(): List<ToolEntity>

    @Query("SELECT COUNT(*) FROM tools")
    suspend fun count(): Int

    @Query(
        "SELECT * FROM tools WHERE name LIKE '%' || :q || '%' " +
            "OR description LIKE '%' || :q || '%' OR keywords LIKE '%' || :q || '%' LIMIT :limit",
    )
    suspend fun search(q: String, limit: Int): List<ToolEntity>

    @Query("SELECT * FROM tools WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): ToolEntity?

    @Query("DELETE FROM tools WHERE name = :name")
    suspend fun delete(name: String): Int
}

@Dao
interface SkillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillEntity)

    @Query("SELECT * FROM skills WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): SkillEntity?

    @Query("SELECT * FROM skills ORDER BY name")
    suspend fun all(): List<SkillEntity>

    @Query("DELETE FROM skills WHERE name = :name")
    suspend fun delete(name: String): Int
}

@Dao
interface ScheduleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(schedule: ScheduleEntity)

    @Query("SELECT * FROM schedules WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ScheduleEntity?

    @Query("SELECT * FROM schedules ORDER BY nextRunAt")
    suspend fun all(): List<ScheduleEntity>

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(item: MemoryEntity): Long

    @Query("SELECT * FROM memories WHERE embedding IS NOT NULL")
    suspend fun allEmbedded(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<MemoryEntity>

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    @Query("DELETE FROM memories")
    suspend fun clear()
}

@Dao
interface KvDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: KvEntity)

    @Query("SELECT * FROM kv WHERE key = :key LIMIT 1")
    suspend fun get(key: String): KvEntity?

    @Query("SELECT * FROM kv")
    suspend fun all(): List<KvEntity>

    @Query("DELETE FROM kv WHERE key = :key")
    suspend fun delete(key: String): Int
}

@Database(
    entities = [ToolEntity::class, MemoryEntity::class, KvEntity::class, SkillEntity::class, ScheduleEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class EaeDatabase : RoomDatabase() {
    abstract fun toolDao(): ToolDao

    abstract fun memoryDao(): MemoryDao

    abstract fun kvDao(): KvDao

    abstract fun skillDao(): SkillDao

    abstract fun scheduleDao(): ScheduleDao

    companion object {
        @Volatile
        private var instance: EaeDatabase? = null

        fun get(context: Context): EaeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EaeDatabase::class.java,
                    "eae.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
