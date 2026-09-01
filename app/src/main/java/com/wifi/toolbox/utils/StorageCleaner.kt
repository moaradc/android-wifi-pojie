package com.wifi.toolbox.utils

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 存储空间清理器：统一扫描与清理本应用私有目录内的可清理占用。
 *
 * 覆盖四类（与主流应用「清理存储空间」的分类式设计一致）：
 * 1. 缓存文件——cacheDir（分享/导出的临时文件等，可安全清理）
 * 2. 守护日志——filesDir/log（自动保存与手动保存的日志，仅应用私有目录部分；
 *    SAF 自选目录在本应用之外，不属于「本 APP 内占用」，不触碰）
 * 3. 破解历史——databases/pojie_history.db（经 PojieHistoryManager.clearAll()
 *    走 Room DAO 删除保证在运行的 Flow 自动刷新，随后 VACUUM 回收文件空间；
 *    本类只负责其字节数统计）
 * 4. 自定义破解资源——filesDir/pojieres（覆写的内置资源与自建资源；删除后
 *    内置恢复 assets 原版，自建资源丢失；运行中任务已将内容载入内存不受影响）
 *
 * 设计原则：
 * - 只清本 APP 私有目录数据，绝不触碰 shared_prefs（设置/统计）与外部 SAF 目录
 * - 所有方法供调用方在 IO 线程执行；内部异常吞掉（清理失败不影响功能）
 * - 清理返回「清理前字节数」用于「已清理 X」反馈
 */
object StorageCleaner {

    /** 四类占用扫描结果 */
    data class Info(
        val cacheBytes: Long,
        val logBytes: Long,
        val historyBytes: Long,
        val resBytes: Long
    ) {
        val total: Long get() = cacheBytes + logBytes + historyBytes + resBytes
    }

    /** 扫描四类占用（小目录遍历，IO 开销极小） */
    fun scan(context: Context): Info = try {
        Info(
            cacheBytes = dirSize(context.cacheDir),
            logBytes = dirSize(File(context.filesDir, "log")),
            historyBytes = historyDbBytes(context),
            resBytes = dirSize(File(context.filesDir, "pojieres"))
        )
    } catch (_: Exception) {
        Info(0, 0, 0, 0)
    }

    /**
     * 清理所选类别（破解历史除外——须走 PojieHistoryManager.clearAll() 的
     * DAO 路径，由调用方单独触发），返回清理前字节数。
     */
    fun clean(context: Context, cache: Boolean, log: Boolean, res: Boolean): Long {
        var freed = 0L
        if (cache) freed += cleanDir(context.cacheDir)
        if (log) freed += cleanDir(File(context.filesDir, "log"))
        if (res) freed += cleanDir(File(context.filesDir, "pojieres"))
        return freed
    }

    /** 清空目录内容（保留目录本身供后续写入），返回清理前字节数 */
    private fun cleanDir(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        val size = dirSize(dir)
        dir.listFiles()?.forEach {
            try {
                it.deleteRecursively()
            } catch (_: Exception) {
            }
        }
        return size
    }

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0
        return try {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0
        }
    }

    /** 破解历史库字节数（主库 + WAL + SHM + journal，Room WAL 模式实际占用的即这些） */
    private fun historyDbBytes(context: Context): Long {
        val db = context.getDatabasePath("pojie_history.db")
        val dir = db.parentFile ?: return 0
        var total = 0L
        for (suffix in listOf("", "-wal", "-shm", "-journal")) {
            val f = File(dir, db.name + suffix)
            if (f.isFile) total += f.length()
        }
        return total
    }

    /** 字节数人性化格式化：B / KB / MB / GB（单位为国际通用记号，各语言不本地化） */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }
}
