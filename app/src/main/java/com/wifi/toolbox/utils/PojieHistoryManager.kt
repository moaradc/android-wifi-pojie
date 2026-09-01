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

    @Query("DELETE FROM password_items")
    suspend fun deleteAllPasswords()

    @Query("DELETE FROM history_metadata")
    suspend fun deleteAllHistory()
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
    private val db = AppDatabase.getInstance(context)
    private val dao = db.pojieDao()
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

    /**
     * 清空全部破解历史（含已破解成功的密码记录），供总设置「清理存储空间」使用。
     *
     * - 走 DAO @Query 删除（非裸 execSQL）：Room 失效追踪器才会通知，
     *   所有页面的 historyFlow 自动刷新为空列表
     * - 删除后执行 VACUUM 真正回收 db 文件占用的磁盘空间
     *   （SQLite DELETE 不缩小文件，仅标记页空闲；VACUUM 不能在事务内执行，
     *   故放在两个 DAO 事务之后单独跑）
     * - 字节数统计由调用方（StorageCleaner）在清理前自行完成
     */
    fun clearAll() {
        scope.launch {
            try {
                dao.deleteAllPasswords()
                dao.deleteAllHistory()
                db.openHelper.writableDatabase.execSQL("VACUUM")
            } catch (_: Exception) {
                // 清理失败不影响功能：Flow 已由 DAO 删除刷新，最坏残留数据
            }
        }
    }
}