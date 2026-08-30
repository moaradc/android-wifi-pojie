package com.wifi.toolbox.ui.screen.test

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.IToolboxCallback
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.ui.screen.testAction
import com.wifi.toolbox.utils.AidlServiceHelper
import com.wifi.toolbox.utils.LogState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AidlTest(logState: LogState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = LocalContext.current.applicationContext as ToolboxApp
    var name by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun checkStatus() {
        logState.addLog(context.getString(R.string.log_service_status, (app.aidl.ipc != null).toString()))
        app.aidl.ipc?.let {
            try {
                logState.addLog(context.getString(R.string.log_service_uid, it.uid.toString()))
            } catch (e: Exception) {
                logState.addLog(context.getString(R.string.log_get_uid_failed, e.message ?: ""))
            }
        }
    }

    LazyColumn {
        item {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                SectionTitle(title = stringResource(R.string.permission), icon = Icons.Default.Key)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionChip(
                        text = stringResource(R.string.check_status),
                        icon = Icons.Filled.Search,
                        onClick = { checkStatus() })
                }

                SectionDivider()

                SectionTitle(
                    title = stringResource(R.string.device_control),
                    icon = Icons.Default.Devices
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionChip(stringResource(R.string.enable_wifi), Icons.Filled.Wifi) {
                        testAction(
                            context,
                            logState,
                            context.getString(R.string.enable_wifi_failed)
                        ) {
                            app.aidl.ipc?.setWifiEnabled(true)
                            logState.addLog(context.getString(R.string.request_sent))
                        }
                    }
                    ActionChip(stringResource(R.string.disable_wifi), Icons.Filled.WifiOff) {
                        testAction(
                            context,
                            logState,
                            context.getString(R.string.disable_wifi_failed)
                        ) {
                            app.aidl.ipc?.setWifiEnabled(false)
                            logState.addLog(context.getString(R.string.request_sent))
                        }
                    }
                    ActionChip(stringResource(R.string.scan_wifi), Icons.Filled.Radar) {
                        scope.launch {
                            testAction(
                                context,
                                logState,
                                context.getString(R.string.scan_wifi_failed)
                            ) {
                                if (app.aidl.ipc?.startWifiScan(true) == true) {
                                    logState.addLog(context.getString(R.string.start_scan_success_later))
                                }
                                delay(3000)


                                logState.addLog(context.getString(R.string.scan_result_head))
                                val result = AidlServiceHelper.getWifiScanResults(app)
                                result.forEach {
                                    logState.addLog(
                                        context.getString(
                                            R.string.scan_result_item,
                                            it.ssid,
                                            it.level,
                                            it.bssid,
                                            it.capabilities
                                        )
                                    )
                                }
                                logState.addLog(context.getString(R.string.command_end))
                            }
                        }
                    }
                    ActionChip(stringResource(R.string.get_saved_wifi), Icons.Outlined.Dns) {
                        testAction(context, logState, context.getString(R.string.get_failed)) {
                            val result = AidlServiceHelper.getSavedWifiList(app)
                            logState.addLog(context.getString(R.string.saved_wifi_list_head))
                            result.forEach {
                                @Suppress("DEPRECATION") logState.addLog(
                                    context.getString(
                                        R.string.saved_wifi_item_with_psk,
                                        it.networkId.toString(),
                                        it.SSID.trim('"'),
                                        it.preSharedKey.trim('"')
                                    )
                                )
                            }
                            logState.addLog(context.getString(R.string.command_end))
                        }
                    }
                    ActionChip(stringResource(R.string.disconnect_wifi), Icons.Filled.WifiOff) {
                        testAction(
                            context,
                            logState,
                            context.getString(R.string.disconnect_failed)
                        ) {
                            app.aidl.ipc?.disconnectWifi()
                            logState.addLog(context.getString(R.string.request_sent))
                        }
                    }
                    ActionChip(stringResource(R.string.lock_screen), Icons.Filled.Lock) {
                        testAction(
                            context,
                            logState,
                            context.getString(R.string.lock_screen_failed)
                        ) {
                            app.aidl.ipc?.pressPowerKey()
                            logState.addLog(context.getString(R.string.request_sent))
                        }
                    }
                    ActionChip(stringResource(R.string.set_max_volume), Icons.AutoMirrored.Filled.VolumeUp) {
                        testAction(context, logState, context.getString(R.string.set_volume_failed)) {
                            app.aidl.ipc?.setMediaVolumeMax()
                            logState.addLog(context.getString(R.string.request_sent))
                        }
                    }
                }

                SectionDivider()

                SectionTitle(
                    title = stringResource(R.string.connect_wifi),
                    icon = Icons.Default.InsertLink
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
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
                            testAction(
                                context,
                                logState,
                                context.getString(R.string.execute_shell_failed)
                            ) {
                                val cmd = "cmd wifi connect-network $name wpa2 $password"
                                logState.addLog(context.getString(R.string.run_command_string, cmd))
                                app.aidl.ipc?.executeCommand(cmd, object : IToolboxCallback.Stub() {
                                    override fun onOutput(line: String) {
                                        logState.addLog(line)
                                    }

                                    override fun onFinished(all: String, code: Int) {
                                        logState.addLog(context.getString(R.string.log_exit_code, code.toString()))
                                    }
                                 })
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp)
                    ) { Text(stringResource(R.string.btn_cmd_connect)) }
                    Button(
                        onClick = {
                            testAction(
                                context,
                                logState,
                                context.getString(R.string.connect_wifi_failed_general)
                            ) {
                                val netId = app.aidl.ipc?.connectToWifi(name, password) ?: -1
                                logState.addLog(context.getString(R.string.log_connect_sent_with_id, netId.toString()))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                    ) { Text(stringResource(R.string.btn_hidden_api_connect)) }
                }
            }
        }
    }
}