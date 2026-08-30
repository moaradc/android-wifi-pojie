package com.wifi.toolbox.services

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.wifi.toolbox.R
import java.io.File

class AppFileProvider : DocumentsProvider() {

    private val FLAG_SUPPORTS_IS_CHILD = 1 shl 4

    private val DEFAULT_ROOT_PROJECTION: Array<String> = arrayOf(
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_DOCUMENT_ID
    )

    private val DEFAULT_DOCUMENT_PROJECTION: Array<String> = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    )

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        return MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, "app_data_root_v7_16")
                add(Root.COLUMN_DOCUMENT_ID, context?.applicationInfo?.dataDir)
                add(Root.COLUMN_TITLE, context?.getString(R.string.app_name))
                add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            }
        }
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addFileRow(cursor, File(documentId ?: return cursor))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val parent = File(parentDocumentId ?: return cursor)
        parent.listFiles()?.forEach { file ->
            addFileRow(cursor, file)
        }
        return cursor
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor? {
        val file = File(documentId ?: return null)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getDocumentType(documentId: String?): String {
        val file = File(documentId ?: return "application/octet-stream")
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
    }

    override fun createDocument(
        parentDocumentId: String?,
        mimeType: String?,
        displayName: String?
    ): String? {
        val parent = File(parentDocumentId ?: return null)
        var file = File(parent, displayName ?: "unnamed")

        // 避免文件名冲突逻辑
        if (file.exists()) {
            val name = displayName ?: "unnamed"
            file = File(parent, "${System.currentTimeMillis()}_$name")
        }

        return try {
            val success = if (Document.MIME_TYPE_DIR == mimeType) file.mkdirs() else file.createNewFile()
            if (success) {
                notifyDocumentChange(parentDocumentId)
                file.absolutePath
            } else null
        } catch (e: Exception) {
            null
        }
    }

    override fun renameDocument(documentId: String?, displayName: String?): String? {
        val source = File(documentId ?: return null)
        val target = File(source.parentFile, displayName ?: return null)

        if (target.exists()) return null

        return if (source.renameTo(target)) {
            notifyDocumentChange(documentId)
            notifyDocumentChange(target.absolutePath)
            target.absolutePath
        } else null
    }

    private fun notifyDocumentChange(documentId: String?) {
        if (documentId == null) return
        val uri = DocumentsContract.buildDocumentUri("${context?.packageName}.documents", documentId)
        context?.contentResolver?.notifyChange(uri, null)
        notifyChange()
    }

    override fun deleteDocument(documentId: String?) {
        val file = File(documentId ?: return)
        if (if (file.isDirectory) file.deleteRecursively() else file.delete()) {
            notifyChange()
        }
    }

    override fun removeDocument(documentId: String?, parentDocumentId: String?) {
        deleteDocument(documentId)
    }

    override fun moveDocument(
        sourceDocumentId: String?,
        sourceParentDocumentId: String?,
        targetParentDocumentId: String?
    ): String? {
        val source = File(sourceDocumentId ?: return null)
        val target = File(targetParentDocumentId ?: return null, source.name)
        return if (source.renameTo(target)) {
            notifyChange()
            target.absolutePath
        } else null
    }

    override fun copyDocument(sourceDocumentId: String?, targetParentDocumentId: String?): String? {
        val source = File(sourceDocumentId ?: return null)
        val target = File(targetParentDocumentId ?: return null, source.name)
        source.copyTo(target, overwrite = true)
        notifyChange()
        return target.absolutePath
    }

    private fun addFileRow(cursor: MatrixCursor, file: File) {
        var flags = Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_RENAME or
                Document.FLAG_SUPPORTS_MOVE or
                Document.FLAG_SUPPORTS_COPY

        val mimeType = if (file.isDirectory) {
            flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
            Document.MIME_TYPE_DIR
        } else {
            val extension = file.extension
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        }

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, file.absolutePath)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun notifyChange() {
        val uri = DocumentsContract.buildRootsUri("${context?.packageName}.documents")
        context?.contentResolver?.notifyChange(uri, null)
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        return documentId?.startsWith(parentDocumentId ?: "") ?: false
    }
}