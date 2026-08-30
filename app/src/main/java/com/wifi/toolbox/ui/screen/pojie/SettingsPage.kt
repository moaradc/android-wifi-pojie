package com.wifi.toolbox.ui.screen.pojie

import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.R
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.ui.items.BannerTip
import me.zhanghai.compose.preference.*

@Composable
fun SettingsPage(
    pojieSettings: PojieSettings,
    onPojieSettingsChange: (PojieSettings) -> Unit
) {

    val app = LocalContext.current.applicationContext as ToolboxApp
    val context = LocalContext.current

    val readLogValues = listOf(
        stringResource(R.string.no_choose_tip_list),
        stringResource(R.string.command_logcat),
        stringResource(R.string.api_broadcastreceiver)
    )
    val connectValues = listOf(
        stringResource(R.string.no_choose_tip_list),
        stringResource(R.string.shizuku_iwifimanager),
        stringResource(R.string.aidl_iwifimanager),
        stringResource(R.string.api_wifimanager),
        stringResource(R.string.api_connect_device),
    )
    val scanValues = listOf(
        stringResource(R.string.item_free),
        stringResource(R.string.shizuku_iwifimanager),
        stringResource(R.string.aidl_iwifimanager),
        stringResource(R.string.api_wifimanager),
    )
    val turnOnValues = listOf(
        stringResource(R.string.item_free),
        stringResource(R.string.shizuku_iwifimanager),
        stringResource(R.string.aidl_iwifimanager),
        stringResource(R.string.api_wifimanager),
    )
    val commandMethodValues = listOf(
        stringResource(R.string.no_choose_tip_list),
        stringResource(R.string.shizuku_newprocess),
        stringResource(R.string.aidl_runtime_exec),
        stringResource(R.string.root_su_c)
    )

    ProvidePreferenceLocals {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
        ) {
            item {
                PreferenceCategory(
                    title = { Text(stringResource(R.string.run_method)) },
                )
            }

            item {
                BannerTip(
                    text = stringResource(R.string.run_method_settings_tip),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            item {
                ListPreference(
                    value = pojieSettings.readLogMode,
                    onValueChange = { newValue ->
                        onPojieSettingsChange(
                            pojieSettings.copy(
                                readLogMode = newValue
                            )
                        )
                    },
                    title = { Text(stringResource(R.string.read_log_mode)) },
                    summary = { Text(readLogValues[pojieSettings.readLogMode]) },
                    icon = {
                        Icon(
                            Icons.AutoMirrored.Filled.ManageSearch,
                            contentDescription = null
                        )
                    },
                    values = readLogValues.indices.toList(),
                    valueToText = { item: Int -> AnnotatedString(readLogValues[item]) },
                    type = ListPreferenceType.DROPDOWN_MENU,
                )
            }

            item {
                ListPreference(
                    value = pojieSettings.connectMode,
                    onValueChange = { newValue ->
                        onPojieSettingsChange(
                            pojieSettings.copy(
                                connectMode = newValue
                            )
                        )
                    },
                    title = { Text(stringResource(R.string.connect_wifi_mode)) },
                    summary = { Text(connectValues[pojieSettings.connectMode]) },
                    icon = { Icon(Icons.Filled.Link, contentDescription = null) },
                    values = connectValues.indices.toList(),
                    valueToText = { item: Int -> AnnotatedString(connectValues[item]) },
                    type = ListPreferenceType.DROPDOWN_MENU,
                )
            }

            item {
                ListPreference(
                    value = pojieSettings.scanMode,
                    onValueChange = { newValue -> onPojieSettingsChange(pojieSettings.copy(scanMode = newValue)) },
                    title = { Text(stringResource(R.string.scan_wifi_mode)) },
                    summary = { Text(scanValues[pojieSettings.scanMode]) },
                    icon = { Icon(Icons.Filled.Radar, contentDescription = null) },
                    values = scanValues.indices.toList(),
                    valueToText = { item: Int -> AnnotatedString(scanValues[item]) },
                    type = ListPreferenceType.DROPDOWN_MENU,
                )
            }

            item {
                AnimatedVisibility(
                    visible = pojieSettings.scanMode == 1 || pojieSettings.scanMode == 2,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    SwitchPreference(
                        value = pojieSettings.allowScanUseCommand,
                        onValueChange = { newValue ->
                            onPojieSettingsChange(
                                pojieSettings.copy(
                                    allowScanUseCommand = newValue
                                )
                            )
                        },
                        title = { Text(stringResource(R.string.allow_use_command)) },
                        icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                        summary = { Text(stringResource(R.string.allow_use_command_tip)) }
                    )
                }
            }

            item {
                ListPreference(
                    value = pojieSettings.enableMode,
                    onValueChange = { newValue ->
                        onPojieSettingsChange(
                            pojieSettings.copy(
                                enableMode = newValue
                            )
                        )
                    },
                    title = { Text(stringResource(R.string.enable_wifi_mode)) },
                    summary = { Text(turnOnValues[pojieSettings.enableMode]) },
                    icon = { Icon(Icons.Outlined.ToggleOn, contentDescription = null) },
                    values = turnOnValues.indices.toList(),
                    valueToText = { item: Int -> AnnotatedString(turnOnValues[item]) },
                    type = ListPreferenceType.DROPDOWN_MENU,
                )
            }

            val showCommandMethod = pojieSettings.readLogMode == 1 ||
                    pojieSettings.connectMode == 4 ||
                    pojieSettings.scanMode == 3 ||
                    pojieSettings.enableMode == 3

            item {
                AnimatedVisibility(
                    visible = showCommandMethod,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    ListPreference(
                        value = pojieSettings.commandMethod,
                        onValueChange = { newValue ->
                            onPojieSettingsChange(
                                pojieSettings.copy(
                                    commandMethod = newValue
                                )
                            )
                        },
                        title = { Text(stringResource(R.string.command_method)) },
                        summary = { Text(commandMethodValues[pojieSettings.commandMethod]) },
                        icon = { Icon(Icons.Filled.Terminal, contentDescription = null) },
                        values = commandMethodValues.indices.toList(),
                        valueToText = { item: Int -> AnnotatedString(commandMethodValues[item]) },
                        type = ListPreferenceType.DROPDOWN_MENU,
                    )
                }
            }

            item {
                PreferenceCategory(
                    title = { Text(stringResource(R.string.running_behavior)) },
                )
            }

            item {
                SwitchPreference(
                    value = pojieSettings.screenAlwaysOn,
                    onValueChange = { newValue ->
                        onPojieSettingsChange(
                            pojieSettings.copy(
                                screenAlwaysOn = newValue
                            )
                        )
                    },
                    title = { Text(stringResource(R.string.keep_screen_on)) },
                    icon = { Icon(Icons.Filled.BrightnessHigh, contentDescription = null) },
                    summary = { Text(stringResource(R.string.keep_screen_on_tip)) }
                )
            }

            item {
                SwitchPreference(
                    value = true,//pojieSettings.showRunningNotification,
                    onValueChange = { newValue ->
//                        onPojieSettingsChange(
//                            pojieSettings.copy(
//                                showRunningNotification = newValue
//                            )
//                        )
                        app.ui.snackbar(context.getString(R.string.tip_not_completed))
                    },
                    title = { Text(stringResource(R.string.show_notification)) },
                    icon = { Icon(Icons.Outlined.Notifications, contentDescription = null) },
                    summary = { Text(stringResource(R.string.show_notification_tip)) }
                )
            }

            item {
                SwitchPreference(
                    value = pojieSettings.exitToPictureInPicture,
                    onValueChange = { newValue ->
                        onPojieSettingsChange(
                            pojieSettings.copy(
                                exitToPictureInPicture = newValue
                            )
                        )
                    },
                    title = { Text(stringResource(R.string.auto_picture_to_picture)) },
                    icon = { Icon(Icons.Filled.PictureInPictureAlt, contentDescription = null) },
                    summary = { Text(stringResource(R.string.auto_picture_to_picture_tip)) }
                )
            }
        }
    }
}