package com.wifi.toolbox.ui.items.pojie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import com.wifi.toolbox.structs.PojieResource
import com.wifi.toolbox.ui.items.TagItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceDetailSheet(
    isVisible: Boolean,
    resource: PojieResource?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onEdit: (PojieResource) -> Unit,
    onDelete: (String) -> Unit
) {
    var lastResource by remember { mutableStateOf<PojieResource?>(null) }
    if (resource != null) lastResource = resource
    val scope = rememberCoroutineScope()

    if (isVisible || sheetState.isVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            contentWindowInsets = { WindowInsets(0) }
        ) {
            lastResource?.let { res ->
                ResourceDetailContent(
                    resource = res,
                    onEdit = { onEdit(res) },
                    onDelete = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                onDismiss()
                                onDelete(res.id)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun ResourceDetailContent(
    resource: PojieResource,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(R.string.resource_details), style = MaterialTheme.typography.headlineSmall)

            IconButton(onClick = {
                val file = if (resource.localPath != null) {
                    java.io.File(resource.localPath!!)
                } else {
                    val cacheDir = java.io.File(context.cacheDir, "shared_res")
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val ext = if (resource.type == 0) "json" else "js"
                    val tempFile = java.io.File(cacheDir, "${resource.id}.$ext")
                    val fileName = "pojieres/${resource.id}.$ext"
                    context.assets.open(fileName).use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempFile
                }

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = if (resource.type == 0) "application/json" else "text/javascript"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(android.content.Intent.createChooser(intent, null))
            }) {
                Icon(Icons.Filled.Share, null)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(Modifier
                .weight(1f)
                .padding(vertical = 4.dp)) {
                Text(
                    text = stringResource(R.string.label_name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = resource.name ?: stringResource(R.string.none),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, false)
                    )
                    if (resource.type == 1) {
                        TagItem(text = stringResource(R.string.tag_script), modifier = Modifier.padding(4.dp))
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                DetailItem(stringResource(R.string.id), resource.id)
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(Modifier.weight(1f)) {
                DetailItem(stringResource(R.string.developer), resource.author)
            }
            Column(Modifier.weight(1f)) {
                DetailItem(stringResource(R.string.version), resource.version)
            }
        }

        Spacer(Modifier.height(12.dp))

        DetailItem(stringResource(R.string.description), resource.description)

        if (resource.localPath != null) {
            Spacer(Modifier.height(12.dp))
            DetailItem(stringResource(R.string.local_path), resource.localPath)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (resource.isBuiltin) {
                1 -> {
                    OutlinedButton(
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        enabled = false
                    ) {
                        Icon(Icons.Default.Lock, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.builtin_resource))
                    }
                    Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.overwrite_resource))
                    }
                }

                2 -> {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.SettingsBackupRestore, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.restore_builtin))
                    }
                    Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.edit_overwrite))
                    }
                }

                else -> {
                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.delete))
                    }
                    Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Edit, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.edit))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun DetailItem(label: String, value: String?) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(text = value ?: stringResource(R.string.none), style = MaterialTheme.typography.bodyLarge)
    }
}