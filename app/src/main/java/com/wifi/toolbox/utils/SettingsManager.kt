package com.wifi.toolbox.utils

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.wifi.toolbox.structs.GlobalSettings
import com.wifi.toolbox.ui.theme.defaultColorSeed
import androidx.compose.ui.graphics.toArgb

class SettingsManager(context: Context) {
    private val prefs =
        context.getSharedPreferences(GlobalSettings.PREFS_NAME, Context.MODE_PRIVATE)

    var global by mutableStateOf(load())
        private set

    private fun load(): GlobalSettings {
        return GlobalSettings(
            dynamicColor = prefs.getBoolean(GlobalSettings.DYNAMIC_COLOR, true),
            dynamicColorSeed = prefs.getInt(
                GlobalSettings.DYNAMIC_COLOR_SEED,
                defaultColorSeed.toArgb()
            ),
            darkTheme = prefs.getInt(GlobalSettings.DARK_THEME, 0),
            hiddenApiBypass = prefs.getInt(GlobalSettings.HIDDEN_API_BYPASS, 1),
            startAidlServiceOnBoot = prefs.getBoolean(
                GlobalSettings.START_AIDL_SERVICE_ON_BOOT,
                false
            ),
            language = prefs.getInt(GlobalSettings.LANGUAGE, 0)
        )
    }

    fun update(action: (GlobalSettings) -> GlobalSettings) {
        val new = action(global)
        global = new
        prefs.edit {
            putBoolean(GlobalSettings.DYNAMIC_COLOR, new.dynamicColor)
            putInt(GlobalSettings.DYNAMIC_COLOR_SEED, new.dynamicColorSeed)
            putInt(GlobalSettings.DARK_THEME, new.darkTheme)
            putInt(GlobalSettings.HIDDEN_API_BYPASS, new.hiddenApiBypass)
            putBoolean(GlobalSettings.START_AIDL_SERVICE_ON_BOOT, new.startAidlServiceOnBoot)
            putInt(GlobalSettings.LANGUAGE, new.language)
        }
    }
}