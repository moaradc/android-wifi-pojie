package com.wifi.toolbox.ui.screen.test

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.ui.items.ActionChip
import com.wifi.toolbox.ui.items.SectionDivider
import com.wifi.toolbox.ui.items.SectionTitle
import androidx.compose.ui.res.colorResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp

@Composable
fun ShellTest(viewModel: TestViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as ToolboxApp

    val buttonLabels = arrayOf(
        stringResource(R.string.normal),
        stringResource(R.string.shizuku),
        "AIDL",
        stringResource(R.string.root)
    )

    val actionChips = listOf(
        ActionChipItem(Icons.Filled.Search, stringResource(R.string.check_id), "id"),
        ActionChipItem(Icons.Filled.Build, stringResource(R.string.fix_hidden_api), "settings put global hidden_api_policy 1"),
        ActionChipItem(Icons.Filled.Link, stringResource(R.string.connect_wifi), "cmd wifi connect-network 名称 wpa2 密码"),
        ActionChipItem(Icons.Filled.Radar, stringResource(R.string.scan_wifi), "sh -c \"cmd wifi start-scan && echo 3秒后获取结果 && sleep 3 && cmd wifi list-scan-results\""),
        ActionChipItem(Icons.Filled.Timelapse, stringResource(R.string.wait_10s), "sh -c \"for i in $(seq 1 10); do echo \$i; sleep 1; done\""),
        ActionChipItem(Icons.AutoMirrored.Filled.ManageSearch, stringResource(R.string.wifi_logcat), "sh -c \"logcat -c && logcat -s \\\"WifiService:D\\\" \\\"wpa_supplicant:D\\\" \\\"DhcpClient:D\\\"\"")
    )

    LazyColumn {
        item {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                SectionTitle(
                    title = stringResource(R.string.input_command),
                    icon = Icons.Default.Code
                )
                OutlinedTextField(
                    value = viewModel.shellCommand,
                    onValueChange = { viewModel.shellCommand = it },
                    label = { Text(stringResource(R.string.command)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    actionChips.forEach { item ->
                        ActionChip(
                            text = item.text,
                            icon = item.icon,
                            onClick = { viewModel.shellCommand = item.command }
                        )
                    }
                }

                SectionDivider()

                SectionTitle(
                    title = stringResource(R.string.run_method),
                    icon = Icons.Default.Build
                )

                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    buttonLabels.forEachIndexed { index, label ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = buttonLabels.size),
                            onClick = { viewModel.shellMethodIndex = index },
                            selected = viewModel.shellMethodIndex == index
                        ) {
                            Text(label)
                        }
                    }
                }

                Button(
                    onClick = {
                        if (viewModel.isCommandRunning) {
                            viewModel.logState.addLog(context.getString(R.string.run_pause_tip))
                        } else {
                            val logPrefix = when (viewModel.shellMethodIndex) {
                                0 -> context.getString(R.string.run_command_string, viewModel.shellCommand)
                                1 -> context.getString(R.string.run_command_string_shizuku, viewModel.shellCommand)
                                2 -> context.getString(R.string.run_command_string_aidl, viewModel.shellCommand)
                                3 -> context.getString(R.string.run_command_string_root, viewModel.shellCommand)
                                else -> ""
                            }
                            viewModel.logState.addLog(logPrefix)
                        }
                        viewModel.runShellCommand(app) { exitCode ->
                            context.getString(R.string.run_completed_tip, exitCode)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(0.dp, 4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.isCommandRunning) colorResource(android.R.color.holo_red_light)
                        else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        text = if (viewModel.isCommandRunning) stringResource(R.string.stop_run)
                        else stringResource(R.string.start_run),
                        color = if (viewModel.isCommandRunning) Color.White else MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

data class ActionChipItem(
    val icon: ImageVector,
    val text: String,
    val command: String
)