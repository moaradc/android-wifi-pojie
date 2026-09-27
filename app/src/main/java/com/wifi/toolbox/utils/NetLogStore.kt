package com.wifi.toolbox.utils

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 网络日志会话存储层。
 *
 * 会话目录结构（filesDir/netlog/session_yyyyMMdd_HHmmss/）：
 * - timeline.jsonl      统一时间线（所有来源事件的 JSON 行流，每行一个事件）
 * - logcat_network.txt  系统网络标签日志流（logcat -s 过滤，逐行原样）
 * - snapshots/          事件触发的系统状态存档（dumpsys/ip 命令组输出，
 *                       每文件带触发原因后缀，如 snap_101530_net-lost.txt）
 * - meta.json           会话元数据（设备指纹/Android 版本/通道/统计，停止时写入）
 *
 * 会话级操作（列表/删除/轮转/打包）与文件级操作（追加/大小）全部集中于此，
 * 服务层只管事件流，不碰目录结构。
 */
object NetLogStore {

    /** 会话根目录（filesDir/netlog） */
    fun root(context: Context): File =
        File(context.filesDir, "netlog").apply { mkdirs() }

    /** 新建一个会话目录（名字含时间戳，同秒重复开始时加序号防撞名） */
    fun newSessionDir(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(Date())
        var dir = File(root(context), "session_$stamp")
        var seq = 1
        while (dir.exists()) {
            dir = File(root(context), "session_${stamp}_$seq")
            seq++
        }
        dir.mkdirs()
        return dir
    }

    /** 全部会话目录（按名字倒序 = 最新在前） */
    fun listSessions(context: Context): List<File> =
        root(context).listFiles { f -> f.isDirectory && f.name.startsWith("session_") }
            ?.sortedByDescending { it.name } ?: emptyList()

    /** 删除一个会话 */
    fun deleteSession(dir: File) {
        dir.deleteRecursively()
    }

    /**
     * 会话轮转：保留最新 keep 个，删除更旧的。
     * keep <= 0 表示不限个数。
     * @return 删除的会话数
     */
    fun pruneSessions(context: Context, keep: Int): Int {
        if (keep <= 0) return 0
        val sessions = listSessions(context)
        val victims = sessions.drop(keep)
        victims.forEach { it.deleteRecursively() }
        return victims.size
    }

    // ==================== 会话内文件读写 ====================

    /** 追加一行到统一时间线（timeline.jsonl），行尾自动补换行 */
    fun appendTimeline(sessionDir: File, jsonLine: String) {
        appendText(File(sessionDir, "timeline.jsonl"), jsonLine + "\n")
    }

    /** 追加一段文本到系统日志流（logcat_network.txt），行尾自动补换行 */
    fun appendLogcat(sessionDir: File, line: String) {
        appendText(File(sessionDir, "logcat_network.txt"), line + "\n")
    }

    /** 写入一个事件快照文件，返回文件对象（用于统计大小） */
    fun writeSnapshot(sessionDir: File, reason: String, content: String): File {
        val name = SimpleDateFormat("HHmmss_SSS", Locale.US).format(Date()) +
                "_" + reason.replace(Regex("[^A-Za-z0-9_-]"), "-") + ".txt"
        val f = File(File(sessionDir, "snapshots").apply { mkdirs() }, name)
        f.writeText(content)
        return f
    }

    /** 写入会话元数据（停止时调用，覆盖式） */
    fun writeMeta(sessionDir: File, meta: JSONObject) {
        File(sessionDir, "meta.json").writeText(meta.toString(2))
    }

    /** 读取时间线尾部若干行（UI 预览用，避免整文件载入内存）。
     *  JSON 行解析为「HH:mm:ss [src] msg」可读格式，解析失败原样返回 */
    fun readTimelineTail(sessionDir: File, maxLines: Int = 60): List<String> {
        val f = File(sessionDir, "timeline.jsonl")
        if (!f.exists()) return emptyList()
        return readTailLines(f, maxLines).map { line ->
            try {
                val o = JSONObject(line)
                "${o.optString("t")} [${o.optString("src")}] ${o.optString("msg")}"
            } catch (_: Exception) {
                line
            }
        }
    }

    /** 读取会话元数据（未停止/损坏返回 null） */
    fun readMeta(sessionDir: File): JSONObject? {
        return try {
            val f = File(sessionDir, "meta.json")
            if (f.exists()) JSONObject(f.readText()) else null
        } catch (_: Exception) {
            null
        }
    }

    /** 目录总大小（字节，含子文件） */
    fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /** 会话大小（[dirSize] 的语义别名，UI 调用点更直观） */
    fun sessionSize(dir: File): Long = dirSize(dir)

    /** 可读的文件大小（B/KB/MB） */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * 将会话目录打包为 zip（放在 cacheDir/netlog_share/，供 FileProvider 分享）。
     * @return zip 文件；打包失败返回 null
     */
    fun zipSession(context: Context, sessionDir: File): File? {
        return try {
            val outDir = File(context.cacheDir, "netlog_share").apply { mkdirs() }
            // 清掉同名的旧 zip（重复分享时覆盖）
            val zipFile = File(outDir, sessionDir.name + ".zip")
            if (zipFile.exists()) zipFile.delete()
            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                sessionDir.walkBottomUp()
                    .filter { it.isFile }
                    .sortedBy { it.absolutePath }
                    .forEach { file ->
                        val entryName = file.relativeTo(sessionDir.parentFile).path
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
            zipFile
        } catch (_: Exception) {
            null
        }
    }

    // ==================== 内部工具 ====================

    private fun appendText(file: File, text: String) {
        try {
            file.appendText(text)
        } catch (_: Exception) {
            // 磁盘满/目录被外部清理：丢弃该行，绝不让日志写入异常打断捕获
        }
    }

    /** 高效读取文件尾部 N 行（大文件不全量载入） */
    private fun readTailLines(file: File, maxLines: Int): List<String> {
        return try {
            val lines = mutableListOf<String>()
            file.forEachLine { lines.add(it) }
            if (lines.size <= maxLines) lines else lines.takeLast(maxLines)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
