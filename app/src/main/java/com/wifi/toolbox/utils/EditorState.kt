package com.wifi.toolbox.utils

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import io.github.rosemoe.sora.widget.CodeEditor

class EditorViewModel : ViewModel() {
    var editorInstance: CodeEditor? = null
    var isEditorOpen by mutableStateOf(false)
    var editorInitialContent by mutableStateOf("")
    var originalContent by mutableStateOf("")
    var editorLanguage by mutableStateOf("text")
    var errorMessage by mutableStateOf<String?>(null)
    var onSaveAction by mutableStateOf<(String) -> Unit>({})
    var showExitConfirmDialog by mutableStateOf(false)

    fun handleBackPress(hideKeyboard: () -> Unit) {
        val currentText = editorInstance?.text.toString()
        if (currentText != originalContent) {
            showExitConfirmDialog = true
        } else {
            closeAndRelease(hideKeyboard)
        }
    }

    fun closeAndRelease(hideKeyboard: () -> Unit) {
        hideKeyboard()
        isEditorOpen = false
        editorInstance = null
        showExitConfirmDialog = false
    }
}

class EditorController(private val vm: EditorViewModel) {
    val isEditorOpen get() = vm.isEditorOpen
    fun open(content: String, language: String = "text", onSave: (String) -> Unit) {
        vm.editorInitialContent = content
        vm.originalContent = content
        vm.editorLanguage = language
        vm.onSaveAction = onSave
        vm.isEditorOpen = true
    }
}

val LocalEditorController = staticCompositionLocalOf<EditorController> { error("No Controller") }