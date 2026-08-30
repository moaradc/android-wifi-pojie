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

    @Query("SELECT * FROM history_metadata WHERE ssid = :ssid")
    suspend fun getMetadata(ssid: String): HistoryEntity?

    @Transaction
    suspend fun fullUpsert(item: PojieHistoryItem) {
        insertMetadata(HistoryEntity(item.ssid, item.progress, item.password, item.lasttime))
        deleteOldPasswords(item.ssid)
        val entities = item.passwords.map { PasswordEntity(historyId = item.ssid, password = it) }
        insertPasswords(entities)
    }

    /**
     * 进度型更新（每次尝试密码后调用）：只更新 progress/lasttime 与尝试密码列表，
     * 【绝不触碰 successfulPassword】。
     *
     * 历史缺陷：原 updateHistory 用 fullUpsert 写入 password=null 的
     * PojieHistoryItem，REPLACE 覆盖会把已破解成功的密码清空——重新破解同一
     * SSID 时历史密码丢失，管理器已保存页的破解记录随之消失。
     */
    @Transaction
    suspend fun upsertAttempt(item: PojieHistoryItem) {
        val existing = getMetadata(item.ssid)
        if (existing == null) {
            insertMetadata(HistoryEntity(item.ssid, item.progress, null, item.lasttime))
        } else {
            updateProgress(item.ssid, item.progress, item.lasttime)
        }
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

    /** 进度型更新：保留既有 successfulPassword（见 PojieDao#upsertAttempt） */
    fun updateAttempt(item: PojieHistoryItem) {
        scope.launch {
            val itemWithTime = item.copy(lasttime = System.currentTimeMillis())
            dao.upsertAttempt(itemWithTime)
        }
    }

    fun deleteHistory(ssid: String) {
        scope.launch {
            dao.deleteHistory(ssid)
        }
    }
}