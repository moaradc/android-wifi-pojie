package com.wifi.toolbox.utils

import android.content.Context
import androidx.room.*
import com.wifi.toolbox.structs.PojieHistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@Entity(tableName = "history_metadata")
data class HistoryEntity(
    @PrimaryKey val ssid: String,
    val progress: Int,
    val successfulPassword: String?,
    val lasttime: Long
)

@Entity(
    tableName = "password_items",
    foreignKeys = [ForeignKey(
        entity = HistoryEntity::class,
        parentColumns = ["ssid"],
        childColumns = ["historyId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("historyId")]
)
data class PasswordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val historyId: String,
    val password: String
)

data class HistoryWithPasswords(
    @Embedded val history: HistoryEntity,
    @Relation(
        parentColumn = "ssid",
        entityColumn = "historyId"
    )
    val passwords: List<PasswordEntity>
)

@Dao
interface PojieDao {
    @Transaction
    @Query("SELECT * FROM history_metadata ORDER BY lasttime DESC")
    fun getAllHistoryFlow(): Flow<List<HistoryWithPasswords>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetadata(history: HistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPasswords(passwords: List<PasswordEntity>)

    @Query("DELETE FROM password_items WHERE historyId = :ssid")
    suspend fun deleteOldPasswords(ssid: String)

    @Transaction
    suspend fun fullUpsert(item: PojieHistoryItem) {
        insertMetadata(HistoryEntity(item.ssid, item.progress, item.password, item.lasttime))
        deleteOldPasswords(item.ssid)
        val entities = item.passwords.map { PasswordEntity(historyId = item.ssid, password = it) }
        insertPasswords(entities)
    }

    @Query("UPDATE history_metadata SET progress = :progress, lasttime = :time WHERE ssid = :ssid")
    suspend fun updateProgress(ssid: String, progress: Int, time: Long)

    @Query("DELETE FROM history_metadata WHERE ssid = :ssid")
    suspend fun deleteHistory(ssid: String)
}

// --- Database ---

@Database(entities = [HistoryEntity::class, PasswordEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pojieDao(): PojieDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pojie_history.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- Manager ---

class PojieHistoryManager(context: Context) {
    private val dao = AppDatabase.getInstance(context).pojieDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    val historyFlow: StateFlow<List<PojieHistoryItem>> = dao.getAllHistoryFlow()
        .map { list ->
            list.map {
                PojieHistoryItem(
                    it.history.ssid,
                    it.passwords.map { p -> p.password },
                    it.history.progress,
                    it.history.successfulPassword,
                    it.history.lasttime
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun addOrUpdateHistory(item: PojieHistoryItem) {
        scope.launch {
            val itemWithTime = item.copy(lasttime = System.currentTimeMillis())
            dao.fullUpsert(itemWithTime)
        }
    }

    fun deleteHistory(ssid: String) {
        scope.launch {
            dao.deleteHistory(ssid)
        }
    }
}