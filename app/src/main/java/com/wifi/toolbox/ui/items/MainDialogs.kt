package com.wifi.toolbox.ui.items

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.utils.EditorViewModel
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.extra.SuperDialog

@Composable
fun MainDialogs(
    app: ToolboxApp,
    editorViewModel: EditorViewModel,
    handleSave: (String, () -> Unit) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val alertDialogData by app.alertDialogState.collectAsState(initial = null)
    val showDialog = remember { mutableStateOf(false) }

    if (alertDialogData != null) {
        showDialog.value = true
        SuperDialog(
            title = alertDialogData?.title ?: "",
            summary = alertDialogData?.text ?: "",
            show = showDialog,
            onDismissRequest = { app.ui.dismissAlert() }
        ) {
            TextButton(
                text = stringResource(R.string.btn_ok),
                onClick = { app.ui.dismissAlert() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (editorViewModel.showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { editorViewModel.showExitConfirmDialog = false },
            title = { Text(stringResource(R.string.default_alert_title)) },
            text = { Text(stringResource(R.string.dialog_exit_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    val text = editorViewModel.editorInstance?.text.toString()
                    handleSave(text) {
                        editorViewModel.closeAndRelease { keyboardController?.hide() }
                    }
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    editorViewModel.closeAndRelease { keyboardController?.hide() }
                }) { Text(stringResource(R.string.btn_save_not)) }
            }
        )
    }

    if (editorViewModel.errorMessage != null) {
        AlertDialog(
            onDismissRequest = { editorViewModel.errorMessage = null },
            title = { Text(stringResource(R.string.save_failed)) },
            text = { Text(editorViewModel.errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    editorViewModel.errorMessage = null
                    editorViewModel.showExitConfirmDialog = false
                }) { Text(stringResource(R.string.btn_ok)) }
            }
        )
    }
}