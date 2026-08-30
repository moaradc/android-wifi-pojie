package com.wifi.toolbox.structs

import com.wifi.toolbox.ui.theme.defaultColorSeed
import androidx.compose.ui.graphics.toArgb

data class GlobalSettings(
    val dynamicColor: Boolean = true,
    val dynamicColorSeed: Int = defaultColorSeed.toArgb(),
    val darkTheme: Int = 0,
    val hiddenApiBypass: Int = 1,
    val startAidlServiceOnBoot: Boolean = false,
    val language: Int = 0
) {
    companion object {
        const val PREFS_NAME = "settings_global"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val DYNAMIC_COLOR_SEED = "dynamic_color_seed"
        const val DARK_THEME = "dark_theme"
        const val HIDDEN_API_BYPASS = "hidden_api_bypass"
        const val START_AIDL_SERVICE_ON_BOOT="start_aidl_service_on_boot"
        const val LANGUAGE = "language"
    }
}