package com.wifi.toolbox.ui.screen.pojie

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.*
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.ui.items.pojie.*
import com.wifi.toolbox.utils.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomePage(
    runListView: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as? ToolboxApp }

    if (app == null) {
        HomePageContent(
            pojieConfig = PojieConfig(),
            logState = LogState(),
            onConfigChange = {},
            runListView = runListView
        )
    } else {
        HomePageContent(
            pojieConfig = app.pojieConfig,
            logState = app.logState,
            onConfigChange = { app.pojieTask.update(it) },
            runListView = runListView
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomePageContent(
    pojieConfig: PojieConfig,
    logState: LogState,
    onConfigChange: (PojieConfig) -> Unit,
    runListView: @Composable () -> Unit = {}
) {
    var expandedParamsCard by rememberSaveable { mutableStateOf(true) }
    var expandedOutputCard by rememberSaveable { mutableStateOf(false) }

    val scrollStateSaver = Saver<ScrollState, Int>(
        save = { it.value },
        restore = { ScrollState(it) }
    )
    val configScrollState = rememberSaveable(saver = scrollStateSaver) {
        ScrollState(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FoldCard(
            title = stringResource(R.string.run_config),
            icon = Icons.AutoMirrored.Rounded.MenuOpen,
            expanded = expandedParamsCard,
            onExpandedChange = {
                expandedParamsCard = it
                if (it) {
                    expandedOutputCard = false
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight(0.4f)
                    .verticalScroll(configScrollState)
                    .padding(16.dp, 0.dp, 16.dp, 16.dp),
            ) {
                ConfigView(
                    config = pojieConfig,
                    onConfigChange = { onConfigChange(it) },
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(8.dp),
        ) {
            runListView()
        }

        FoldCard(
            title = stringResource(R.string.run_output),
            icon = Icons.Rounded.Terminal,
            expanded = expandedOutputCard,
            onExpandedChange = {
                expandedOutputCard = it
                if (it) {
                    expandedParamsCard = false
                }
            }
        ) {
            LogView(
                logState = logState,
                modifier = Modifier
                    .fillMaxHeight(0.5f)
                    .padding(8.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomePagePreview() {
    val mockController = object : PojieWifiController {
        override val uiState: ScreenState = ScreenState.Success(true)
        override val isScanning: Boolean = false
        override val trigger: Int = 0
        override val refreshErrorKey: Long = 0L
        override val runningTasks: List<PojieRunInfo> = listOf(
            PojieRunInfo(
                ssid = "ChinaNet-Preview",
                tryList = emptyList(),
                tryIndex = 0,
                lastTryTime = 0,
                retryCount = 0,
                textTip = "加载中",
            )
        )
        override val finishedInfo: SnapshotStateMap<String, String> =
            SnapshotStateMap<String, String>().apply {
                put("TP-LINK_5G", "已完成")
            }

        override fun reload() {}
        override fun fetchResults(): ScanResult = ScanResult(
            code = ScanResult.CODE_SUCCESS,
            wifiList = listOf(
                WifiInfo("ChinaNet-Preview", -40, "", "[WPA2-PSK-CCMP]"),
                WifiInfo("TP-LINK_5G", -60, "", "[WPA2-PSK-CCMP]"),
                WifiInfo("Xiaomi_Router", -75, "", "[WPA2-PSK-CCMP]")
            )
        )

        override fun enableWifi() {}
        override fun applyLocation() {}
        override fun gotoSettings() {}
        override fun gotoAppSettings() {}

        override fun enableLocation() {}
        override fun disconnectWifi() {}
    }

    MaterialTheme {
        HomePageContent(
            pojieConfig = PojieConfig(),
            logState = LogState(),
            onConfigChange = {},
            runListView = {
                RunListView(
                    controller = mockController,
                    onStartClick = {},
                    onStopClick = {}
                )
            }
        )
    }
}