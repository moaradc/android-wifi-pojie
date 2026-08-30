package com.wifi.toolbox.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.R
import com.wifi.toolbox.structs.PojieResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ResourcesState(
    val context: Context,
    val scope: CoroutineScope,
    val editor: EditorController,
    val app: ToolboxApp,
    val defaultScriptContent: String,
    val onShowFabDialogChange: (Boolean) -> Unit
) {
    var resources by mutableStateOf<List<PojieResource>>(emptyList())
    var refreshKey by mutableIntStateOf(0)
    var selectedResource by mutableStateOf<PojieResource?>(null)
    var showDetailSheet by mutableStateOf(false)
    var draftState by mutableStateOf<PojieResource?>(null)
    var showEditDialog by mutableStateOf(false)
    var selectedFabOption by mutableStateOf<Int?>(null)
    var currentEditingId by mutableStateOf<String?>(null)
    var originalIdForEdit by mutableStateOf<String?>(null)

    companion object {
        @Composable
        fun rememberState(onShowFabDialogChange: (Boolean) -> Unit): ResourcesState {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val editor = LocalEditorController.current
            val app = context.applicationContext as ToolboxApp
            val defaultScriptContent = stringResource(id = R.string.default_script_content)

            return remember {
                ResourcesState(
                    context,
                    scope,
                    editor,
                    app,
                    defaultScriptContent,
                    onShowFabDialogChange
                )
            }
        }
    }

    fun loadResources() {
        scope.launch(Dispatchers.IO) {
            resources = PojieStore.getAll(context)
        }
    }

    fun deleteResource(id: String) {
        scope.launch(Dispatchers.IO) {
            PojieStore.delete(context, id)
            refreshKey++
            withContext(Dispatchers.Main) {
                showDetailSheet = false
                selectedResource = null
            }
        }
    }

    fun handleImport(uri: Uri?) {
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    val content = context.contentResolver.openInputStream(it)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: return@launch

                    var fileName = ""
                    context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (cursor.moveToFirst()) fileName = cursor.getString(nameIndex)
                    }
                    val lowerName = fileName.lowercase(Locale.ROOT)

                    val newRes = when {
                        lowerName.endsWith(".js") -> PojieResource.parseScript(content)
                        lowerName.endsWith(".json") -> PojieResource.parseJSON(content)
                        lowerName.endsWith(".txt") -> {
                            PojieResource(
                                id = PojieStore.randomID(),
                                name = fileName.removeSuffix(".txt"),
                                description = null,
                                type = 0,
                                content = content,
                                author = null,
                                version = null
                            )
                        }
                        else -> throw Exception(context.getString(R.string.unsupported_file_format))
                    }

                    PojieStore.testExists(context, newRes, null)
                    PojieStore.save(context, newRes)

                    refreshKey++
                    withContext(Dispatchers.Main) { onShowFabDialogChange(false) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        app.alert(context.getString(R.string.import_failed), e.message.toString())
                    }
                }
            }
        }
    }

    fun openEditorForScript(res: PojieResource, isNew: Boolean) {
        currentEditingId = res.id
        editor.open(res.content, "js") { newContent ->
            try {
                val newRes = PojieResource.parseScript(newContent)
                val oldId = currentEditingId ?: res.id
                PojieStore.testExists(context, newRes, oldId)
                scope.launch(Dispatchers.IO) {
                    if (isNew && oldId == res.id) {
                        PojieStore.save(context, newRes, oldId)
                    } else {
                        if (oldId != newRes.id) {
                            PojieStore.update(context, oldId, newRes)
                        } else {
                            PojieStore.save(context, newRes, oldId)
                        }
                    }
                    currentEditingId = newRes.id
                    refreshKey++
                }
            } catch (e: Exception) {
                app.alert(context.getString(R.string.parse_failed), e.message.toString())
            }
        }
    }
}