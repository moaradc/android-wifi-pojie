package com.wifi.toolbox.ui.items.pojie

import android.os.Parcelable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.WifiInfo
import com.wifi.toolbox.ui.items.BannerTip
import com.wifi.toolbox.utils.AidlServiceHelper
import com.wifi.toolbox.utils.ApiUtil
import com.wifi.toolbox.utils.PojieWifiController
import com.wifi.toolbox.utils.ShizukuUtil
import com.wifi.toolbox.utils.rememberPojieSettings
import kotlinx.parcelize.Parcelize

data class StartScanResult(
    val code: Int = CODE_UNKNOWN, val errorMessage: String? = null
) {
    companion object {
        const val CODE_SUCCESS = 0
        const val CODE_SCAN_FAIL = -1
        const val CODE_WIFI_NOT_ENABLED = -2
        const val CODE_LOCATION_NOT_ENABLED = -3
        const val CODE_LOCATION_NOT_ALLOWED = -4
        const val CODE_SEND_FAIL = -5
        const val CODE_NOT_SET = -6
        const val CODE_SERVICE_NOT_BOUND = -7
        const val CODE_UNKNOWN = -8
    }
}

@Parcelize
data class ScanResult(
    val code: Int = CODE_UNKNOWN,
    val errorMessage: String? = null,
    var wifiList: List<WifiInfo>? = null
) : Parcelable {
    companion object {
        const val CODE_SUCCESS = 0
        const val CODE_UNKNOWN = -1
    }
}

sealed class PojieListItem {
    abstract val key: String

    data object WifiConnectedBanner : PojieListItem() {
        override val key = "item-top1"
    }

    data object SendFailedBanner : PojieListItem() {
        override val key = "item-top2"
    }

    data class WifiItem(val wifi: WifiInfo) : PojieListItem() {
        override val key = "item-${wifi.ssid}"
    }

    data object ManualInputBanner : PojieListItem() {
        override val key = "item-bottom"
    }
}

sealed class ScreenState : Parcelable {
    @Parcelize
    object Idle : ScreenState()

    @Parcelize
    data class Success(val sendSucceed: Boolean) : ScreenState()

    @Parcelize
    data class Error(val message: String, val type: Int) : ScreenState()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RunListView(
    controller: PojieWifiController,
    onStartClick: (WifiInfo) -> Unit = {},
    onStopClick: (String) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val runningTasks = controller.runningTasks

    var showManualInput by remember { mutableStateOf(false) }
    var manualSsid by remember { mutableStateOf("") }

    val context = LocalContext.current
    val app = context.applicationContext as ToolboxApp
    val historyList by app.pojieHistory.historyFlow.collectAsState(initial = emptyList())
    var pojieSettings by rememberPojieSettings(context)

    LaunchedEffect(Unit) {
        if (controller.uiState is ScreenState.Idle) controller.reload()
    }

    LaunchedEffect(runningTasks.size) {
        if (runningTasks.isNotEmpty()) listState.animateScrollToItem(0)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.wifi_list),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )

        SplitButtonLayout(leadingButton = {
            SplitButtonDefaults.LeadingButton(
                onClick = { controller.reload() }, colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    Icons.Rounded.Autorenew,
                    contentDescription = null,
                    modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize)
                )
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.refresh))
            }
        }, trailingButton = {
            SplitButtonDefaults.TrailingButton(
                checked = expanded,
                onCheckedChange = { expanded = it },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
            ) {
                val rotation by animateFloatAsState(if (expanded) 180f else 0f)
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier
                        .size(SplitButtonDefaults.TrailingIconSize)
                        .graphicsLayer { rotationZ = rotation })
            }
            DropdownMenu(expanded, { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tip_not_completed)) },
                    onClick = { expanded = false })
            }
        })
    }

    when (val s = controller.uiState) {
        is ScreenState.Success -> {
            val res = remember(controller.trigger) { controller.fetchResults() }
            val scannedList = res.wifiList ?: emptyList()

            val fullList = remember(scannedList, runningTasks.toList(), controller.trigger) {
                val runningSsids = runningTasks.map { it.ssid }.toSet()

                val partRunning = runningTasks.map { task ->
                    val realTimeInfo = scannedList.find { it.ssid == task.ssid }
                    val matchedHistory = historyList.find { it.ssid == task.ssid }

                    WifiInfo(
                        ssid = task.ssid,
                        bssid = "",
                        level = realTimeInfo?.level ?: 0,
                        capabilities = realTimeInfo?.capabilities ?: "",
                        pojieHistoryItem = matchedHistory,
                        savedInfo = realTimeInfo?.savedInfo
                    )
                }

                val partScanned =
                    scannedList.filter { it.ssid !in runningSsids }.sortedByDescending { it.level }

                partRunning + partScanned
            }

            val state = if (controller.isScanning && fullList.isEmpty()) 0
            else if (!controller.isScanning && fullList.isEmpty()) 1
            else 2

            val isWifiConnected = remember(controller.trigger) {
                ApiUtil.isWifiConnected(context)
            }

            LaunchedEffect(isWifiConnected) {
                if (isWifiConnected) {
                    listState.animateScrollToItem(0)
                }
            }

            val displayList = remember(fullList, isWifiConnected, controller.isScanning, s) {
                mutableListOf<PojieListItem>().apply {
                    if (isWifiConnected) {
                        add(PojieListItem.WifiConnectedBanner)
                    }

                    if (!s.sendSucceed) {
                        add(PojieListItem.SendFailedBanner)
                    }

                    addAll(fullList.map { PojieListItem.WifiItem(it) })

                    if (!controller.isScanning || fullList.isNotEmpty()) {
                        add(PojieListItem.ManualInputBanner)
                    }
                }
            }

            AnimatedContent(state, label = "ListContent") { targetState ->
                when (targetState) {
                    0 -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        ContainedLoadingIndicator(Modifier.size(60.dp))
                    }

                    1 -> Column(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Rounded.Inbox,
                                    null,
                                    Modifier.size(96.dp),
                                    tint = MaterialTheme.colorScheme.outlineVariant
                                )
                                Text(stringResource(R.string.empty_list), style = MaterialTheme.typography.bodyLarge)
                            }
                        }

                        BannerTip(
                            title = stringResource(R.string.manual_input_tip_title),
                            text = stringResource(R.string.manual_input_tip_content),
                            trailingIcon = true,
                            modifier = Modifier
                                .padding(8.dp)
                                .fillMaxWidth(),
                            onBannerClick = { showManualInput = true })
                    }

                    2 -> Column(Modifier.fillMaxSize()) {
                        AnimatedVisibility(
                            visible = controller.isScanning,
                            enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top)
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            items(displayList, key = { it.key }) { item ->
                                when (item) {
                                    is PojieListItem.WifiConnectedBanner -> {
                                        BannerTip(
                                            title = stringResource(R.string.current_connected_wifi),
                                            text = stringResource(R.string.interference_tip),
                                            trailingIcon = true,
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .animateItem(),
                                            onBannerClick = { controller.disconnectWifi() })
                                    }

                                    is PojieListItem.SendFailedBanner -> {
                                        BannerTip(
                                            text = stringResource(R.string.scan_failed_old_data),
                                            modifier = Modifier
                                                .padding(4.dp)
                                                .animateItem()
                                        )
                                    }

                                    is PojieListItem.WifiItem -> {
                                        WifiPojieItem(
                                            modifier = Modifier.animateItem(),
                                            wifi = item.wifi,
                                            runningInfo = runningTasks.find { it.ssid == item.wifi.ssid },
                                            onStartClick = onStartClick,
                                            onStopClick = onStopClick,
                                            finishedInfo = controller.finishedInfo[item.wifi.ssid]
                                        )
                                    }

                                    is PojieListItem.ManualInputBanner -> {
                                        BannerTip(
                                            title = stringResource(R.string.manual_input_tip_title),
                                            text = stringResource(R.string.manual_input_tip_content),
                                            trailingIcon = true,
                                            modifier = Modifier
                                                .padding(8.dp)
                                                .animateItem(),
                                            onBannerClick = { showManualInput = true })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        is ScreenState.Error -> {
            val icon = when (s.type) {
                StartScanResult.CODE_WIFI_NOT_ENABLED -> Icons.Rounded.WifiOff
                StartScanResult.CODE_SCAN_FAIL -> Icons.Rounded.ErrorOutline
                StartScanResult.CODE_LOCATION_NOT_ENABLED -> Icons.Rounded.LocationOff
                StartScanResult.CODE_LOCATION_NOT_ALLOWED -> Icons.Rounded.WrongLocation
                StartScanResult.CODE_NOT_SET, StartScanResult.CODE_SERVICE_NOT_BOUND ->
                    painterResource(id = R.drawable.ic_settings_b_roll)

                else -> Icons.Rounded.BugReport
            }
            ErrorTip(
                icon = icon,
                message = s.message,
                refreshTrigger = controller.refreshErrorKey,
                onManualInputClick = { showManualInput = true }) {
                when (s.type) {
                    StartScanResult.CODE_WIFI_NOT_ENABLED -> {
                        Button(onClick = { controller.enableWifi() }) {
                            Icon(
                                Icons.Rounded.TouchApp,
                                null,
                                Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.enable_wifi))
                        }
                    }

                    StartScanResult.CODE_LOCATION_NOT_ENABLED -> {
                        Button(onClick = { controller.enableLocation() }) {
                            Icon(
                                Icons.Rounded.LocationOn,
                                null,
                                Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.enable_location))
                        }
                    }

                    StartScanResult.CODE_LOCATION_NOT_ALLOWED -> {
                        Button(onClick = { controller.applyLocation() }) {
                            Icon(
                                Icons.Rounded.VerifiedUser,
                                null,
                                Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.apply_permission))
                        }
                    }

                    StartScanResult.CODE_NOT_SET -> {
                        Button(onClick = { controller.gotoSettings() }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowForward,
                                null,
                                Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.go_to_settings))
                        }
                    }

                    StartScanResult.CODE_SERVICE_NOT_BOUND -> {
                        Button(onClick = { controller.gotoAppSettings() }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowForward,
                                null,
                                Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.go_to_settings))
                        }
                    }

                    else -> {
                        Button(onClick = { controller.reload() }) {
                            Icon(
                                Icons.Rounded.Refresh, null, Modifier.size(ButtonDefaults.IconSize)
                            )
                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }
        }

        else -> {}
    }

    if (showManualInput) {
        AlertDialog(
            onDismissRequest = { showManualInput = false },
            title = { Text(stringResource(R.string.manual_input_name)) },
            text = {
                OutlinedTextField(
                    value = manualSsid,
                    onValueChange = { manualSsid = it },
                    label = { Text(stringResource(R.string.ssid)) },
                    supportingText = { Text(stringResource(R.string.input_ssid_tip)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = manualSsid.isNotBlank(),
                    onClick = {
                        val currentSsid = manualSsid
                        val savedList = try {
                            when (pojieSettings.scanMode) {
                                1 -> ShizukuUtil.getSavedWifiList()
                                2 -> AidlServiceHelper.getSavedWifiList(app)
                                3 -> if (ApiUtil.hasLocationPermission(context)) ApiUtil.getSavedWifiList(
                                    app
                                ) else emptyList()

                                else -> emptyList()
                            }
                        } catch (_: Exception) {
                            emptyList()
                        }

                        val matchedSaved =
                            savedList.find { it.SSID == "\"$currentSsid\"" || it.SSID == currentSsid }

                        val matchedHistory = historyList.find { it.ssid == currentSsid }

                        val finalWifiInfo = WifiInfo(
                            ssid = currentSsid,
                            savedInfo = matchedSaved,
                            pojieHistoryItem = matchedHistory
                        )

                        onStartClick(finalWifiInfo)
                        manualSsid = ""
                        showManualInput = false
                    }
                ) {
                    Text(stringResource(R.string.btn_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showManualInput = false
                }) { Text(stringResource(R.string.btn_cancel)) }
            })
    }
}

@Composable
fun ErrorTip(
    icon: Any,
    message: String,
    refreshTrigger: Long = 0L,
    onManualInputClick: () -> Unit,
    button: @Composable (RowScope.() -> Unit)? = null
) {
    AnimatedContent(
        targetState = refreshTrigger, transitionSpec = {
            (fadeIn(animationSpec = tween(300)) + slideInVertically(
                animationSpec = tween(
                    400,
                    easing = LinearOutSlowInEasing
                )
            ) { 40 }).togetherWith(fadeOut(animationSpec = snap()))
        }, label = "ErrorTipAnimation"
    ) { _ ->
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (icon) {
                is ImageVector -> {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                is Painter -> {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onManualInputClick) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.manual_input))
                }
                button?.invoke(this)
            }
        }
    }
}