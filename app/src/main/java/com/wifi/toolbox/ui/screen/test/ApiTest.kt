package com.wifi.toolbox.ui.screen.test

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.wifi.toolbox.R
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.utils.*
import kotlinx.coroutines.*


@Composable
fun ApiTest(logState: LogState, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    val scope = rememberCoroutineScope()

    val wifiScanLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            scope.launch {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    performWifiScan(context, logState)
                } else {
                    logState.addLog(context.getString(R.string.error_scan_wifi_no_permission))
                }
            }
        } else {
            logState.addLog(context.getString(R.string.error_scan_wifi_no_permission))
        }
    }

    LazyColumn {
        item {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                SectionTitle(
                    title = stringResource(R.string.device_control),
                    icon = Icons.Default.Devices
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionChip(
                        text = stringResource(R.string.enable_wifi),
                        icon = Icons.Filled.Wifi,
                        onClick = {
                            val success = ApiUtil.setWifiEnabled(context, true)
                            if (success) {
                                logState.addLog(context.getString(R.string.enable_wifi_success))
                            } else {
                                logState.addLog(context.getString(R.string.request_sent))
                            }
                        })
                    ActionChip(
                        text = stringResource(R.string.disable_wifi),
                        icon = Icons.Filled.WifiOff,
                        onClick = {
                            val success = ApiUtil.setWifiEnabled(context, false)
                            if (success) {
                                logState.addLog(context.getString(R.string.disable_wifi_success))
                            } else {
                                logState.addLog(context.getString(R.string.request_sent))
                            }
                        })
                    ActionChip(
                        text = stringResource(R.string.disconnect_wifi),
                        icon = Icons.Filled.WifiOff,
                        onClick = {
                            ApiUtil.disconnectWifi(context)
                            logState.addLog(context.getString(R.string.request_sent))
                        })
                    ActionChip(
                        text = stringResource(R.string.scan_wifi),
                        icon = Icons.Filled.Radar,
                        onClick = {
                            checkAndPerformWifiScan(context, logState, wifiScanLauncher, scope)
                        })
                    ActionChip(
                        text = stringResource(R.string.get_saved_wifi),
                        icon = Icons.Outlined.Dns,
                        onClick = {
                            checkAndPerformAndGetWifiList(context, logState, wifiScanLauncher)
                        })
                }

                SectionDivider()

                SectionTitle(
                    title = stringResource(R.string.connect_wifi),
                    icon = Icons.Default.InsertLink
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.ssid)) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.password)) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (ApiUtil.connectToWifiApi28(context, name, password) != -1)
                            logState.addLog(context.getString(R.string.request_sent))
                        else logState.addLog(context.getString(R.string.connect_wifi_failed))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(stringResource(R.string.wifimanager))
                }
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ApiUtil.connectToWifiApi29(context, name, password)
                            { success ->
                                logState.addLog(
                                    if (success) context.getString(R.string.connect_success)
                                    else context.getString(R.string.connect_failed)
                                )
                            }
                        } else {
                            logState.addLog(context.getString(R.string.device_too_old))
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Text(stringResource(R.string.wifinetworkspecifier))
                }
            }
        }
    }
}

private suspend fun performWifiScan(context: Context, logState: LogState) {
    try {
        val success = ApiUtil.startScan(context)
        if (!success) {
            logState.addLog(context.getString(R.string.start_scan_failed_warning))
        } else {
            logState.addLog(context.getString(R.string.start_scan_success_later))
        }

        delay(3000)

        if (!ApiUtil.hasLocationPermission(context)) {
            throw RuntimeException(context.getString(R.string.no_location_permission))
        }

        val scanResults = ApiUtil.getScanResults(context)

        logState.addLog(context.getString(R.string.scan_result_head))
        scanResults.forEach {
            logState.addLog(
                String.format(
                    context.getString(R.string.scan_result_item),
                    it.ssid,
                    it.level,
                    it.bssid,
                    it.capabilities
                )
            )
        }
        logState.addLog(context.getString(R.string.command_end))

    } catch (e: Exception) {
        logState.addLog(context.getString(R.string.scan_wifi_failed))
        logState.addLog(e.stackTraceToString())
    }
}

private fun checkAndPerformWifiScan(
    context: Context,
    logState: LogState,
    wifiScanLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    scope: CoroutineScope
) {
    if (ApiUtil.hasLocationPermission(context)) {
        if (!ApiUtil.isLocationEnabled(context)) {
            logState.addLog(context.getString(R.string.location_not_enabled))
            ApiUtil.enableLocation(context)
            return
        }

        if (!ApiUtil.isWifiEnabled(context)) {
            logState.addLog(context.getString(R.string.wifi_not_enabled))
            return
        }

        scope.launch {
            performWifiScan(context, logState)
        }
    } else {
        logState.addLog(context.getString(R.string.please_grant_loaction_permission))
        wifiScanLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}


private fun checkAndPerformAndGetWifiList(
    context: Context,
    logState: LogState,
    wifiScanLauncher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    try {
        if (ApiUtil.hasLocationPermission(context)) {
            if (!ApiUtil.isLocationEnabled(context)) {
                logState.addLog(context.getString(R.string.location_not_enabled))
                ApiUtil.enableLocation(context)
                return
            }

            val result = ApiUtil.getSavedWifiList(context)
            logState.addLog(context.getString(R.string.saved_wifi_list_head))
            result.forEach {
                @Suppress("DEPRECATION") logState.addLog(
                    context.getString(
                        R.string.saved_wifi_item_with_psk,
                        it.networkId.toString(),
                        it.SSID.removeSurrounding("\""),
                        it.preSharedKey?.removeSurrounding("\"") ?: ""
                    )
                )
            }
            logState.addLog(context.getString(R.string.command_end))

        } else {
            logState.addLog(context.getString(R.string.please_grant_loaction_permission))
            wifiScanLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    } catch (e: Exception) {
        logState.addLog(context.getString(R.string.get_failed))
        logState.addLog(e.stackTraceToString())
    }

}