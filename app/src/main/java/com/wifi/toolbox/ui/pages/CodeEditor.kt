package com.wifi.toolbox.ui.pages

import android.graphics.Typeface
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.wifi.toolbox.R
import com.wifi.toolbox.utils.EditorViewModel
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import io.github.rosemoe.sora.widget.CodeEditor
import org.eclipse.tm4e.core.registry.IThemeSource


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditor(
    vm: EditorViewModel,
    useDarkTheme: Boolean,
    onSave: (String, () -> Unit) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.code_editor)) },
            navigationIcon = {
                IconButton(onClick = {
                    vm.handleBackPress {
                        keyboardController?.hide()
                        vm.editorInstance?.clearFocus()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                }
            },
            actions = {
                TextButton(onClick = {
                    val text = vm.editorInstance?.text.toString()
                    onSave(text) {
                    }
                }) { Text(stringResource(R.string.save)) }
            }
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                vm.editorInstance?.let { existingView ->
                    (existingView.parent as? ViewGroup)?.removeView(existingView)
                    existingView
                } ?: CodeEditor(ctx).apply {
                    setupEditor(this, vm, ctx, useDarkTheme)
                    vm.editorInstance = this
                }
            },
            update = { view ->
                updateEditorTheme(view, useDarkTheme)
            }
        )
    }
}

private fun setupEditor(editor: CodeEditor, vm: EditorViewModel, context: android.content.Context, useDarkTheme: Boolean) {
    editor.apply {
        layoutParams = ViewGroup.LayoutParams(-1, -1)
        typefaceText = Typeface.MONOSPACE
        nonPrintablePaintingFlags = 28
        setText(vm.editorInitialContent)

        val fileProvider = FileProviderRegistry.getInstance()
        fileProvider.addFileProvider(AssetsFileResolver(context.assets))
        val grammarRegistry = GrammarRegistry.getInstance()
        grammarRegistry.loadGrammars("textmate/languages.json")

        updateEditorTheme(this, useDarkTheme)

        if (vm.editorLanguage == "js") {
            setEditorLanguage(TextMateLanguage.create("source.js", true))
        } else {
            setEditorLanguage(null)
        }
    }
}
private fun updateEditorTheme(view: CodeEditor, useDarkTheme: Boolean) {
    val themeRegistry = ThemeRegistry.getInstance()
    val themeName = if (useDarkTheme) "darcula" else "quietlight"

    try {
        if (themeRegistry.findThemeByFileName(themeName) == null) {
            val fileProvider = FileProviderRegistry.getInstance()
            val themePath = "textmate/$themeName.json"
            val inputStream = fileProvider.tryGetInputStream(themePath) ?: return

            val themeModel = ThemeModel(
                IThemeSource.fromInputStream(inputStream, themePath, null),
                themeName
            ).apply { isDark = useDarkTheme }

            themeRegistry.loadTheme(themeModel)
        }

        themeRegistry.setTheme(themeName)
        val scheme = TextMateColorScheme.create(themeRegistry)

        view.colorScheme = scheme

    } catch (e: Exception) {
        e.printStackTrace()
    }
    view.invalidate()
}

@Composable
fun CodeEditorPage(
    vm: EditorViewModel,
    useDarkTheme: Boolean,
    onSave: (String, () -> Unit) -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    AnimatedVisibility(
        visible = vm.isEditorOpen,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it })
    ) {
        BackHandler(enabled = vm.isEditorOpen) {
            vm.handleBackPress { keyboardController?.hide() }
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            CodeEditor(
                vm = vm,
                useDarkTheme = useDarkTheme,
                onSave = onSave
            )
        }
    }
}