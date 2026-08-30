package com.wifi.toolbox.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.*
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.BuildConfig
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.GlobalSettings
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.ui.pages.AppNav
import com.wifi.toolbox.ui.pages.CodeEditorPage
import com.wifi.toolbox.ui.theme.AppTheme
import com.wifi.toolbox.utils.*
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.wifi.toolbox.R

val LocalNavTarget = compositionLocalOf<MutableState<String?>> { error("No NavTarget provided") }

class MainActivity : AppCompatActivity() {
    private var pendingNavigation = mutableStateOf<String?>(null)
    private val editorViewModel: EditorViewModel by viewModels()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("target")?.let { pendingNavigation.value = it }
    }

    private fun handleIntent(intent: Intent) {
        intent.getStringExtra("target")?.let { pendingNavigation.value = it }
        intent.removeExtra("target")
    }

    val handleSave: (String, () -> Unit) -> Unit = { text, onSuccess ->
        try {
            editorViewModel.onSaveAction(text)
            editorViewModel.originalContent = text
            onSuccess()
        } catch (e: Exception) {
            editorViewModel.errorMessage = e.message ?: e.toString()
        }
    }

    private fun handleCrashRecovery(intent: Intent?) {
        val isRecovery = intent?.getBooleanExtra("CRASH_RECOVERY", false) ?: false
        if (isRecovery) {
            val msg = intent.getStringExtra("ERROR_MESSAGE") ?: getString(R.string.unknown)
            val stack = intent.getStringExtra("ERROR_STACK") ?: ""

            val app = application as ToolboxApp

            app.alert(
                title = getString(R.string.crash_title),
                text = getString(R.string.crash_text, msg)
            )

            if (BuildConfig.DEBUG) {
                println("Crash Recovery StackTrace:\n$stack")
            }
            intent.removeExtra("CRASH_RECOVERY")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = applicationContext as ToolboxApp
        val langIndex = app.settings.global.language

        val targetLocale = LocaleConfigs.getLocaleListCompat(langIndex)

        if (AppCompatDelegate.getApplicationLocales() != targetLocale) {
            AppCompatDelegate.setApplicationLocales(targetLocale)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }

        super.onCreate(savedInstanceState)

        handleCrashRecovery(intent)
        handleIntent(intent)

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            window.isNavigationBarContrastEnforced = false //小米设备导航栏修复

        ShizukuUtil.initialize(applicationContext)

        setContent {
            val app = LocalContext.current.applicationContext as ToolboxApp
            val snackbarHostState = remember { SnackbarHostState() }
            val editorController = remember { EditorController(editorViewModel) }
            val settings = app.settings.global

            LaunchedEffect(Unit) {
                app.snackbarState.collect { data ->
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(
                        data.message,
                        data.actionLabel,
                        duration = SnackbarDuration.Short
                    ).let {
                        if (it == SnackbarResult.ActionPerformed) data.onActionClick?.invoke()
                    }
                }
            }

            AppContent(app, editorController, settings, snackbarHostState)
        }
    }

    @Composable
    fun AppContent(
        app: ToolboxApp,
        editorController: EditorController,
        settings: GlobalSettings,
        snackbarHostState: SnackbarHostState
    ) {
        val useDarkTheme = when (settings.darkTheme) {
            1 -> true
            2 -> false
            else -> isSystemInDarkTheme()
        }

        MiuixTheme(ThemeController(isDark = useDarkTheme)) {
            CompositionLocalProvider(
                LocalEditorController provides editorController,
                LocalNavTarget provides pendingNavigation
            ) {
                AppTheme(
                    darkTheme = useDarkTheme,
                    dynamicColor = settings.dynamicColor,
                    dynamicColorSeed = Color(settings.dynamicColorSeed)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Scaffold(modifier = Modifier.fillMaxSize()) {
                            AppNav(pendingNavigation = pendingNavigation)
                        }

                        CodeEditorPage(editorViewModel, useDarkTheme, handleSave)

                        MainDialogs(app, editorViewModel, handleSave)

                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp)
                        )

                        DebugWatermark()
                    }
                }
            }
        }
    }

    var pendingLocationCallback: (() -> Unit)? = null

    val locationLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                if (ApiUtil.isLocationEnabled(this)) {
                    pendingLocationCallback?.invoke()
                    pendingLocationCallback = null
                }
            }
        }

    override fun onResume() {
        super.onResume()
        if (pendingLocationCallback != null && ApiUtil.isLocationEnabled(this)) {
            pendingLocationCallback?.invoke()
            pendingLocationCallback = null
        }
    }

    var pendingPermissionCallback: (() -> Unit)? = null

    val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                pendingPermissionCallback?.invoke()
                pendingPermissionCallback = null
            }
        }
}