package com.wifi.toolbox.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 已保存日志文件的统一描述（私有目录 File 与 SAF DocumentFile 两种来源）
 */
data class StoredLogFile(
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isSaf: Boolean,
    val file: File?,   // 私有目录来源时的 File
    val uri: Uri?      // SAF 来源时的文档 Uri
)

/**
 * 守护日志存储层：统一两种保存位置。
 *
 * - 默认：应用私有目录 filesDir/log（免任何权限，卸载即清）
 * - 自选：SAF tree URI（系统文件管理器选择的文件夹，跨位置可见，
 *   通过 ACTION_OPEN_DOCUMENT_TREE 授权，持久化在 GuardSettings.logDirUri）
 *
 * SAF 目录失效（被删除/授权被系统回收）时自动回退私有目录，保存永不失败于"位置不存在"。
 */
object GuardLogStore {

    /** 应用私有日志目录（filesDir/log） */
    fun privateDir(context: Context): File =
        File(context.filesDir, "log").apply { mkdirs() }

    /** 当前设置的 SAF 目录是否可写（未设置/失效返回 null） */
    fun safDir(context: Context, uriStr: String): DocumentFile? {
        if (uriStr.isBlank()) return null
        return try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
            if (tree?.isDirectory == true && tree.canWrite()) tree else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 保存日志内容到当前设置位置。
     * @return 成功返回已保存文件的描述（SAF 可能追加扩展名重命名）；失败返回 null
     */
    fun save(context: Context, uriStr: String, fileName: String, content: String): StoredLogFile? {
        if (content.isEmpty()) return null
        val saf = safDir(context, uriStr)
        if (saf != null) {
            try {
                val doc = saf.createFile("text/plain", fileName)
                if (doc != null) {
                    context.contentResolver.openOutputStream(doc.uri)?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    return StoredLogFile(
                        name = doc.name ?: fileName,
                        sizeBytes = doc.length(),
                        lastModified = doc.lastModified(),
                        isSaf = true,
                        file = null,
                        uri = doc.uri
                    )
                }
            } catch (_: Exception) {
                // SAF 写入失败 → 回退私有目录
            }
        }
        return try {
            val f = File(privateDir(context), fileName)
            f.writeText(content)
            StoredLogFile(
                name = f.name,
                sizeBytes = f.length(),
                lastModified = f.lastModified(),
                isSaf = false,
                file = f,
                uri = null
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 追加写入（自动保存日志用；文件不存在则创建）。
     * - SAF：优先 openOutputStream(uri, "wa") 追加模式；个别 provider 不支持时
     *   回退「读已有内容 + 整体重写」；SAF 不可写时回退私有目录
     * - 私有目录：File.appendText
     * @return 是否成功
     */
    fun append(context: Context, uriStr: String, fileName: String, content: String): Boolean {
        if (content.isEmpty()) return false
        val saf = safDir(context, uriStr)
        if (saf != null) {
            try {
                var doc = saf.findFile(fileName)
                if (doc == null) doc = saf.createFile("text/plain", fileName)
                if (doc != null) {
                    try {
                        val os = context.contentResolver.openOutputStream(doc.uri, "wa")
                        if (os != null) {
                            os.use {
                                it.write(content.toByteArray(Charsets.UTF_8))
                                it.flush()
                            }
                            return true
                        }
                    } catch (_: Exception) {
                        // 追加模式不受支持：读出已有内容 + 整体重写
                        val existing = try {
                            context.contentResolver.openInputStream(doc.uri)?.use {
                                it.readBytes().toString(Charsets.UTF_8)
                            } ?: ""
                        } catch (_: Exception) {
                            ""
                        }
                        val os = context.contentResolver.openOutputStream(doc.uri, "wt")
                        if (os != null) {
                            os.use {
                                it.write((existing + content).toByteArray(Charsets.UTF_8))
                                it.flush()
                            }
                            return true
                        }
                    }
                }
            } catch (_: Exception) {
                // SAF 写入失败 → 回退私有目录
            }
        }
        return try {
            File(privateDir(context), fileName).appendText(content)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 列出两个位置的全部日志（时间倒序；含已失效 SAF 之外的私有目录文件） */
    fun list(context: Context, uriStr: String): List<StoredLogFile> {
        val out = mutableListOf<StoredLogFile>()
        try {
            privateDir(context).listFiles()
                ?.filter { it.isFile }
                ?.forEach {
                    out += StoredLogFile(
                        name = it.name,
                        sizeBytes = it.length(),
                        lastModified = it.lastModified(),
                        isSaf = false,
                        file = it,
                        uri = null
                    )
                }
        } catch (_: Exception) {
        }
        val saf = safDir(context, uriStr)
        if (saf != null) {
            try {
                saf.listFiles().forEach {
                    out += StoredLogFile(
                        name = it.name ?: "?",
                        sizeBytes = it.length(),
                        lastModified = it.lastModified(),
                        isSaf = true,
                        file = null,
                        uri = it.uri
                    )
                }
            } catch (_: Exception) {
            }
        }
        return out.sortedByDescending { it.lastModified }
    }

    /** 删除单个已保存日志 */
    fun delete(context: Context, f: StoredLogFile): Boolean {
        return try {
            if (f.isSaf) {
                DocumentFile.fromSingleUri(context, f.uri!!)?.delete() == true
            } else {
                f.file?.delete() == true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 清空指定来源的全部日志；返回删除数量 */
    fun clear(context: Context, files: List<StoredLogFile>): Int {
        var n = 0
        files.forEach { if (delete(context, it)) n++ }
        return n
    }

    /** 当前保存位置的展示名（SAF 目录名或"应用私有目录"标记串由调用方本地化） */
    fun safDirName(context: Context, uriStr: String): String? {
        val dir = safDir(context, uriStr) ?: return null
        return dir.name
    }
}
