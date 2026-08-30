@file:Suppress("DEPRECATION")

package com.wifi.toolbox.utils

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.PojieHistoryItem
import com.wifi.toolbox.structs.PojieRunInfo
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.ui.LocalNavTarget
import com.wifi.toolbox.ui.items.checkShizukuUI
import com.wifi.toolbox.ui.items.pojie.ScanResult
import com.wifi.toolbox.ui.items.pojie.ScreenState
import com.wifi.toolbox.ui.items.pojie.StartScanResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface PojieWifiController {
    val uiState: ScreenState
    val isScanning: Boolean
    val trigger: Int
    val refreshErrorKey: Long
    val runningTasks: List<PojieRunInfo>
    val finishedInfo: SnapshotStateMap<String, String>
    fun reload()
    fun fetchResults(): ScanResult
    fun enableWifi()
    fun applyLocation()
    fun gotoSettings()
    fun gotoAppSettings()
    fun enableLocation()
    fun disconnectWifi()
}

const val MIN_SCAN_TIME = 500
const val MAX_SCAN_TIME = 3000
const val SCAN_INTERVAL = 250

@Composable
fun rememberPojieWifiController(
    context: Context, app: ToolboxApp, settings: PojieSettings,
    onChangePage: (Int) -> Unit
): PojieWifiController {
    val scope = rememberCoroutineScope()

    var uiState by rememberSaveable { mutableStateOf<ScreenState>(ScreenState.Idle) }
    var cachedScanResult by rememberSaveable { mutableStateOf<ScanResult?>(null) }
    var hasInitialScanned by rememberSaveable { mutableStateOf(false) }
    var lastScanMode by rememberSaveable { mutableIntStateOf(-1) }
    var trigger by rememberSaveable { mutableIntStateOf(0) }
    var showScanResult by rememberSaveable { mutableStateOf(true) }

    var lastWifiEnabledState by rememberSaveable { mutableStateOf(ApiUtil.isWifiEnabled(context)) }

    var refreshJob by remember { mutableStateOf<Job?>(null) }
    var refreshErrorKey by remember { mutableLongStateOf(0L) }
    val currentRunningTasks = app.runningPojieTasks
    val currentFinishedTasks = app.finishedPojieTasksTip
    val navTarget = LocalNavTarget.current
    val historyList by app.pojieHistory.historyFlow.collectAsState(initial = emptyList())
    var cachedSavedNetworks by remember {
        mutableStateOf<List<android.net.wifi.WifiConfiguration>>(
            emptyList()
        )
    }
    var cachedHistory by remember { mutableStateOf<List<PojieHistoryItem>>(emptyList()) }

    var onWifiEnabledAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val fetchRawResultsInternal: () -> ScanResult = {
        try {
            val results = when (settings.scanMode) {
                1 -> ShizukuUtil.getWifiScanResults()
                2 -> AidlServiceHelper.getWifiScanResults(app)
                3 -> ApiUtil.getScanResults(context)
                else -> emptyList()
            }.filter { it.ssid.isNotEmpty() }
                .distinctBy { it.ssid }
                .map { info ->
                    info.copy(
                        savedInfo = cachedSavedNetworks.find { s -> s.SSID == "\"${info.ssid}\"" || s.SSID == info.ssid },
                        pojieHistoryItem = cachedHistory.find { h -> h.ssid == info.ssid }
                    )
                }
            ScanResult(ScanResult.CODE_SUCCESS, null, results)
        } catch (e: Exception) {
            ScanResult(errorMessage = e.message)
        }
    }

    val scanInternal: () -> StartScanResult = {
        if (settings.scanMode == 0) StartScanResult(
            code = StartScanResult.CODE_NOT_SET,
            errorMessage = context.getString(R.string.error_scan_impl_empty)
        )
        else if (!ApiUtil.isWifiEnabled(context)) StartScanResult(code = StartScanResult.CODE_WIFI_NOT_ENABLED)
        else {
            try {
                when (settings.scanMode) {
                    1 -> if (checkShizukuUI(app, onGranted = { trigger++ })) {
                        if (ShizukuUtil.startWifiScan(settings.allowScanUseCommand)) StartScanResult(
                            code = StartScanResult.CODE_SUCCESS
                        )
                        else StartScanResult(code = StartScanResult.CODE_SEND_FAIL)
                    } else StartScanResult(
                        code = StartScanResult.CODE_SCAN_FAIL,
                        errorMessage = context.getString(R.string.error_shizuku_no_permission)
                    )

                    2 -> try {
                        if (AidlServiceHelper.startWifiScan(
                                app,
                                settings.allowScanUseCommand
                            )
                        ) StartScanResult(code = StartScanResult.CODE_SUCCESS)
                        else StartScanResult(code = StartScanResult.CODE_SEND_FAIL)
                    } catch (e: Exception) {
                        StartScanResult(
                            code = StartScanResult.CODE_SERVICE_NOT_BOUND,
                            errorMessage = e.message
                        )
                    }

                    3 -> if (ApiUtil.hasLocationPermission(context)) {
                        if (ApiUtil.isLocationEnabled(context)) {
                            if (ApiUtil.startScan(context)) StartScanResult(code = StartScanResult.CODE_SUCCESS)
                            else StartScanResult(code = StartScanResult.CODE_SEND_FAIL)
                        } else StartScanResult(code = StartScanResult.CODE_LOCATION_NOT_ENABLED)
                    } else StartScanResult(code = StartScanResult.CODE_LOCATION_NOT_ALLOWED)

                    else -> StartScanResult(
                        code = StartScanResult.CODE_UNKNOWN,
                        errorMessage = "scanMode=${settings.scanMode}"
                    )
                }
            } catch (e: Exception) {
                StartScanResult(StartScanResult.CODE_SCAN_FAIL, e.message)
            }
        }
    }

    val performReload: () -> Unit = {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            lastScanMode = settings.scanMode
            val start = scanInternal()
            lastWifiEnabledState = ApiUtil.isWifiEnabled(context)

            when (start.code) {
                StartScanResult.CODE_SUCCESS, StartScanResult.CODE_SEND_FAIL -> {
                    cachedHistory = historyList
                    cachedSavedNetworks = try {
                        when (settings.scanMode) {
                            1 -> ShizukuUtil.getSavedWifiList()
                            2 -> AidlServiceHelper.getSavedWifiList(app)
                            3 -> if (ApiUtil.hasLocationPermission(context)) ApiUtil.getSavedWifiList(app) else emptyList()
                            else -> emptyList()
                        }
                    } catch (_: Exception) { emptyList() }

                    uiState = ScreenState.Success(start.code == StartScanResult.CODE_SUCCESS)

                    if (start.code == StartScanResult.CODE_SUCCESS) {
                        showScanResult = false
                        val totalTicks = MAX_SCAN_TIME / SCAN_INTERVAL
                        repeat(totalTicks) { tick ->
                            cachedScanResult = fetchRawResultsInternal()
                            trigger++

                            if (tick * SCAN_INTERVAL >= MIN_SCAN_TIME) {
                                showScanResult = true
                            }

                            delay(SCAN_INTERVAL.toLong())
                        }
                        showScanResult = true
                    } else {
                        // 发送失败的情况，只刷一次并做个简单的延迟
                        cachedScanResult = fetchRawResultsInternal()
                        trigger++
                        showScanResult = false
                        delay(MIN_SCAN_TIME.toLong())
                        showScanResult = true
                        trigger++
                    }
                    refreshJob = null
                }

                StartScanResult.CODE_WIFI_NOT_ENABLED -> {
                    uiState = ScreenState.Error(
                        context.getString(R.string.error_wifi_not_enabled),
                        StartScanResult.CODE_WIFI_NOT_ENABLED
                    )
                }

                StartScanResult.CODE_LOCATION_NOT_ENABLED -> {
                    uiState = ScreenState.Error(
                        context.getString(R.string.error_location_not_enabled),
                        StartScanResult.CODE_LOCATION_NOT_ENABLED
                    )
                }

                StartScanResult.CODE_LOCATION_NOT_ALLOWED -> {
                    uiState = ScreenState.Error(
                        context.getString(R.string.error_location_not_allowed),
                        StartScanResult.CODE_LOCATION_NOT_ALLOWED
                    )
                }

                else -> {
                    uiState = ScreenState.Error(start.errorMessage ?: "Error", start.code)
                }
            }
            refreshErrorKey++
        }
    }

    DisposableEffect(context) {
        val wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                    val currentEnabled = ApiUtil.isWifiEnabled(context)
                    if (currentEnabled != lastWifiEnabledState) {
                        lastWifiEnabledState = currentEnabled
                        if (currentEnabled) {
                            onWifiEnabledAction?.invoke()
                            onWifiEnabledAction = null
                        }
                        performReload()
                    }
                } else if (intent.action == WifiManager.NETWORK_STATE_CHANGED_ACTION || intent.action == "android.net.conn.CONNECTIVITY_CHANGE") {
                    trigger++
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction("android.net.conn.CONNECTIVITY_CHANGE")
        }
        context.registerReceiver(wifiReceiver, filter)
        onDispose { context.unregisterReceiver(wifiReceiver) }
    }

    LaunchedEffect(settings.scanMode) {
        if (!hasInitialScanned || lastScanMode != settings.scanMode) {
            hasInitialScanned = true
            performReload()
        }
    }
    LaunchedEffect(historyList) {
        cachedHistory = historyList
        cachedScanResult = cachedScanResult?.let { oldResult ->
            oldResult.copy(
                wifiList = oldResult.wifiList?.map { info ->
                    info.copy(pojieHistoryItem = historyList.find { h -> h.ssid == info.ssid })
                }
            )
        }
        trigger++
    }

    return remember(
        uiState,
        refreshJob?.isActive,
        trigger,
        refreshErrorKey,
        showScanResult,
        currentRunningTasks.size,
        onWifiEnabledAction
    ) {
        object : PojieWifiController {
            override val uiState = uiState
            override val isScanning = refreshJob?.isActive == true
            override val trigger = trigger
            override val refreshErrorKey = refreshErrorKey
            override val runningTasks = currentRunningTasks
            override val finishedInfo = currentFinishedTasks
            override fun reload() = performReload()
            override fun fetchResults(): ScanResult =
                if (!showScanResult) ScanResult() else cachedScanResult ?: ScanResult()

            override fun enableWifi() {
                if (ApiUtil.isWifiEnabled(context)) {
                    reload(); return
                }
                onWifiEnabledAction = { reload() }
                when (settings.enableMode) {
                    1 -> checkShizukuUI(app) { ShizukuUtil.setWifiEnabled(true) }
                    2 -> AidlServiceHelper.setWifiEnabled(app, true)
                    3 -> ApiUtil.setWifiEnabled(context, true)
                }
            }

            override fun applyLocation() {
                if (ApiUtil.requestLocationPermission(context as Activity) { reload() }) reload()
            }

            override fun gotoSettings() = onChangePage(3)
            override fun gotoAppSettings() {
                navTarget.value = "Settings"
            }

            override fun enableLocation() {
                if (ApiUtil.enableLocation(context) { reload() }) reload()
            }

            override fun disconnectWifi() {
                when (settings.enableMode) {
                    1 -> {
                        ShizukuUtil.disconnectWifi(); trigger++
                    }

                    2 -> {
                        AidlServiceHelper.disconnectWifi(app); trigger++
                    }
                }
            }
        }
    }
}