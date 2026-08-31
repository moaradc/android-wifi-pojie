package com.wifi.toolbox.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.WifiInfo
import com.wifi.toolbox.ui.items.NavContainer
import com.wifi.toolbox.ui.items.NavPage
import com.wifi.toolbox.ui.items.WifiIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.wifi.toolbox.ui.items.TagItem
import com.wifi.toolbox.ui.items.TagType
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.wifi.toolbox.utils.*

/**
 * WiFi 管理器：扫描 / 已保存 / 网络 三个完整功能页。
 *
 * 数据层见 [rememberManagerController]——查询型操作在指定通道结果为空时
 * 自动降级补一次其他通道，实际使用的通道在 UI 如实标注；已保存网络页的
 * 密码按来源分级展示（系统明文 > 本应用破解记录 > 不可见），Android 10+
 * 系统级密码受官方限制（getConfiguredNetworks 恒空），普通权限下破解记录
 * 是唯一确定可见的来源。
 */
@Composable
fun ManageScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ToolboxApp
    val controller = rememberManagerController(context, app)
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }

    // 认证网络（Captive Portal）提示：刚连接的 WiFi 被系统判定需要网页认证时
    // 弹窗说明，并提供跳转系统 WiFi 设置页按钮（认证需在系统侧完成）
    controller.portalSsid?.let { ssid ->
        AlertDialog(
            onDismissRequest = { controller.clearPortalSsid() },
            title = { Text(stringResource(R.string.mgr_portal_dialog_title)) },
            text = { Text(stringResource(R.string.mgr_portal_dialog_msg, ssid)) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    } catch (_: Exception) {
                    }
                    controller.clearPortalSsid()
                }) {
                    Text(stringResource(R.string.mgr_portal_dialog_go))
                }
            },
            dismissButton = {
                TextButton(onClick = { controller.clearPortalSsid() }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        NavContainer(
            pages = listOf(
                object : NavPage {
                    override val name = stringResource(R.string.scan)
                    override val selectedIcon = Icons.Filled.Radar
                    override val unselectedIcon = Icons.Outlined.Radar
                    override val content = @Composable {
                        ScanTabPage(controller, app)
                    }
                },
                object : NavPage {
                    override val name = stringResource(R.string.mgr_tab_saved)
                    override val selectedIcon = Icons.Filled.Dns
                    override val unselectedIcon = Icons.Outlined.Dns
                    override val content = @Composable {
                        SavedTabPage(controller)
                    }
                },
                object : NavPage {
                    override val name = stringResource(R.string.mgr_tab_network)
                    override val selectedIcon = Icons.Filled.Insights
                    override val unselectedIcon = Icons.Outlined.Insights
                    override val content = @Composable {
                        NetworkTabPage(controller)
                    }
                }
            ),
            selectedIndex = selectedIndex,
            onIndexChange = { selectedIndex = it },
            subtitle = stringResource(R.string.wifi_manager),
            onMenuClick = onMenuClick
        )
    }
}

// ==================== 来源通道标签与切换弹窗（扫描/已保存两页共用） ====================

/** Shizuku binder 是否存活（未装/未启动为 false；申请授权前需先存活） */
private fun shizukuBinderAlive(): Boolean = try {
    rikka.shizuku.Shizuku.pingBinder()
} catch (_: Exception) {
    false
}

/**
 * 「来源」标签（可点击）——点击弹出数据来源通道切换弹窗。
 *
 * 标签文本优先显示实际数据来源（有数据时如实标注）；无数据时显示
 * 当前指定通道（0=自动）——标签常驻显示，切换入口任何时刻可达。
 *
 * [refreshScanOnSwitch]：切换通道后只刷新当前停留页（省功耗）——扫描页
 * 切换→重扫（射频扫描仅在停留扫描页时触发）；已保存页切换→重读配置，
 * 扫描页进入时会自行重扫。
 */
@Composable
private fun SourceChannelTag(
    source: Int,
    controller: ManagerController,
    refreshScanOnSwitch: Boolean
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val label = when {
        source != 0 -> managerChannelName(source)
        controller.scanChannel != 0 -> managerChannelName(controller.scanChannel)
        else -> stringResource(R.string.mgr_channel_auto)
    }
    TagItem(
        text = stringResource(R.string.mgr_scan_source) + " " + label,
        type = TagType.Tertiary,
        modifier = Modifier.clickable { showPicker = true }
    )
    if (showPicker) {
        SourceChannelPickerDialog(
            controller = controller,
            onDismiss = { showPicker = false },
            refreshScanOnSwitch = refreshScanOnSwitch
        )
    }
}

/**
 * 数据来源通道切换弹窗。
 *
 * - 每通道附可用状态（可用 / 未运行 / 未授权 / 未绑定 / 缺定位权限 / 定位服务未开启）；
 * - 选择可用通道：立即切换（写破解设置 scanMode，两页同源）并刷新
 *   **当前停留页**（[refreshScanOnSwitch]：未进入的页面不运行其数据加载，
 *   进入时各自自动加载——扫描是射频级功耗，不应在未停留扫描页时触发）；
 * - 选择不可用通道：先申请对应权限（Shizuku 授权 / Root 绑定授权 /
 *   定位运行时权限 FINE+COARSE 双权限同请求），成功后切换；被拒/失败
 *   则保持原通道不切换。即使指定通道不可用，读取也有多通道静默回退
 *   兜底（channelOrder），不会因此取不到数据——弹窗内的权限申请只为
 *   让指定通道真正生效。
 * - 「自动」即 0：按 Shizuku → Root → 系统 API 顺序取首个可用；
 * - 系统 API 的可用 = 定位权限 + 系统定位开关双就绪（官方文档 Android 9+
 *   硬性条件 "Location services are enabled on the device"——权限≠开关，
 *   开关关闭时应用层读数恒为空，此前误报「可用」且切了仍静默降级
 *   Shizuku）：权限到手而开关未开时先引导开启（Play services 一键
 *   开启弹窗，无 GMS 跳系统定位设置页），开启后切换、不开不切换。
 */
@Composable
private fun SourceChannelPickerDialog(
    controller: ManagerController,
    onDismiss: () -> Unit,
    refreshScanOnSwitch: Boolean
) {
    val context = LocalContext.current
    val app = context.applicationContext as ToolboxApp
    val scope = rememberCoroutineScope()
    // Root 绑定等待中（绑定期间禁用全部选项防并发触发）
    var bindingRoot by remember { mutableStateOf(false) }
    // 权限申请等异步事件后的可用状态重算信号（Shizuku/定位授权非 Compose
    // 状态，需手动触发重组刷新状态文本）
    var availTick by remember { mutableIntStateOf(0) }

    val shizukuAlive = remember(availTick) { shizukuBinderAlive() }
    val shizukuGranted = remember(availTick) { WifiHealer.isShizukuAvailable() }
    val rootBound = app.aidl.ipc != null      // Compose 状态：绑定完成自动重组
    // API 通道可用 = 定位权限 + 系统定位开关双就绪：开关是官方文档明载的
    // 硬性条件（Android 9 起 "Location services are enabled on the device"），
    // 只查权限会把「定位服务未开启」误报成「可用」——真机「切了系统 API
    // 标签仍显示 Shizuku」的根因：权限≠开关，开关关闭时应用层读数恒为
    // 空，读取侧静默降级到特权通道（标签如实显示实际数据源）
    val apiPerm = remember(availTick) { ApiUtil.hasLocationPermission(context) }
    val apiLocOn = remember(availTick) { ApiUtil.isLocationEnabled(context) }
    val current = controller.scanChannel

    // 「自动」当前实际会选用的通道（与 channelOrder 的自动顺序一致）
    val autoPick = when {
        shizukuGranted -> managerChannelName(1)
        rootBound -> managerChannelName(2)
        else -> managerChannelName(3)
    }

    fun applyAndDismiss(mode: Int) {
        controller.setScanChannel(mode)
        // 两页数据同源（channelOrder/fetchSavedConfigs 均读 scanMode），但
        // 只刷新当前停留页：未进入的页面不运行其数据加载（扫描页的加载
        // 含触发系统射频扫描，已保存页进入时会自行重读）
        if (refreshScanOnSwitch) controller.refreshScan()
        else controller.refreshSaved()
        Toast.makeText(
            context,
            context.getString(
                R.string.mgr_channel_switched,
                if (mode == 0) context.getString(R.string.mgr_channel_auto)
                else managerChannelName(mode)
            ),
            Toast.LENGTH_SHORT
        ).show()
        onDismiss()
    }

    // 永久拒绝（系统不再弹权限框）时引导去应用设置的兜底弹窗
    var showLocationSettings by remember { mutableStateOf(false) }

    // 定位开关引导（一键开启弹窗）的回收：开启成功 → 切换；取消 → 不切换
    // （避免「切了却读不到数、标签来回跳」——读取层的降级兜底仍会保住数据）
    val locSvcLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        availTick++
        if (result.resultCode == Activity.RESULT_OK && ApiUtil.isLocationEnabled(context)) {
            applyAndDismiss(3)
        } else {
            Toast.makeText(
                context, context.getString(R.string.mgr_channel_loc_service_off),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 无 GMS（国产 ROM 常见）或不可解析时：Toast 提示后跳系统定位设置页，
    // 用户开启后返回重新选择即可（此时状态行已如实显示「定位服务未开启」）
    fun gotoLocationSettings() {
        Toast.makeText(
            context, context.getString(R.string.mgr_channel_loc_service_goto),
            Toast.LENGTH_LONG
        ).show()
        try {
            context.startActivity(
                Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 引导开启系统定位开关：优先 Play services「一键开启」可解析弹窗
     * （checkLocationSettings → ResolvableApiException → resolution），
     * 结果由 [locSvcLauncher] 回收；不可解析时跳系统设置页。此处不做切换。
     */
    fun promptEnableLocation() {
        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, 10000
            ).build()
            val request = LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .setAlwaysShow(true)
                .build()
            LocationServices.getSettingsClient(context).checkLocationSettings(request)
                .addOnSuccessListener {
                    // 与弹窗预检查的竞态兜底：走到这里说明系统认为已开启
                    if (ApiUtil.isLocationEnabled(context)) applyAndDismiss(3)
                    else gotoLocationSettings()
                }
                .addOnFailureListener { ex ->
                    val sender = (ex as? ResolvableApiException)?.resolution?.intentSender
                    if (sender != null) {
                        try {
                            locSvcLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            return@addOnFailureListener
                        } catch (_: Exception) {
                        }
                    }
                    gotoLocationSettings()
                }
        } catch (_: Exception) {
            gotoLocationSettings()
        }
    }

    // FINE+COARSE 双权限同请求：官方文档明确 Android 12+ 单独请求
    // ACCESS_FINE_LOCATION 会被部分版本系统直接忽略（不弹任何对话框）
    // ——真机反馈「切系统API未申请定位权限」的根因；且 targetSdk=28 下
    // COARSE（近似定位）已足以解锁 Wi-Fi 扫描/身份 API（Android 10 隐私
    // 变更官方规则：targetSdk≤28 声明 COARSE 或 FINE 任一即可）
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        availTick++
        val fine = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fine || coarse) {
            // 权限到手仍需定位开关就绪（Android 9+ 官方硬性条件）：
            // 开着直接切换；关着先引导开启，开启后切换
            if (ApiUtil.isLocationEnabled(context)) applyAndDismiss(3)
            else promptEnableLocation()
        } else {
            // 区分普通拒绝（可再次弹窗）与永久拒绝（只能去设置手动开）
            val act = context as? Activity
            val canAskAgain = act != null && (
                act.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) ||
                act.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            if (canAskAgain) {
                Toast.makeText(
                    context, context.getString(R.string.mgr_channel_perm_denied),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                showLocationSettings = true
            }
        }
    }

    fun selectChannel(mode: Int) {
        when (mode) {
            0 -> applyAndDismiss(0)

            1 -> when {
                // 已授权：直接切换
                shizukuGranted -> applyAndDismiss(1)
                // binder 不在线：无法拉起授权（Shizuku 未装/未启动），
                // 不切换——静默回退虽能兜底，但指定通道应真实生效
                !shizukuAlive -> Toast.makeText(
                    context, context.getString(R.string.mgr_channel_shizuku_offline),
                    Toast.LENGTH_SHORT
                ).show()
                // 已运行未授权：立即申请 Shizuku 授权
                else -> app.shizuku.request(
                    { availTick++; applyAndDismiss(1) },
                    {
                        availTick++
                        Toast.makeText(
                            context, context.getString(R.string.mgr_channel_perm_denied),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }

            2 -> when {
                rootBound -> applyAndDismiss(2)
                else -> {
                    // 绑定 Root AIDL 服务——libsu RootService 首次绑定会触发
                    // 系统/超级用户应用的 Root 授权弹窗；绑定是异步的，
                    // 轮询等待（最长 10 秒，授权弹窗需用户操作）
                    bindingRoot = true
                    app.aidl.startAIDLServiceRoot()
                    scope.launch {
                        try {
                            var ok = false
                            var waited = 0
                            while (waited < 10000) {
                                delay(500)
                                waited += 500
                                if (app.aidl.ipc != null) {
                                    ok = true
                                    break
                                }
                            }
                            bindingRoot = false
                            availTick++
                            if (ok) applyAndDismiss(2)
                            else Toast.makeText(
                                context,
                                context.getString(R.string.mgr_channel_root_bind_fail),
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (_: Exception) {
                            bindingRoot = false
                        }
                    }
                }
            }

            3 -> when {
                // 实时判定（remember 缓存从系统设置页返回后可能滞后）：
                // 权限缺 → 申请；权限有开关关 → 引导开启；都就绪 → 切换
                !ApiUtil.hasLocationPermission(context) -> locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                ApiUtil.isLocationEnabled(context) -> applyAndDismiss(3)
                else -> promptEnableLocation()
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!bindingRoot) onDismiss() },
        title = { Text(stringResource(R.string.mgr_channel_dialog_title)) },
        text = {
            Column {
                // 自动
                ChannelRow(
                    name = stringResource(R.string.mgr_channel_auto),
                    status = stringResource(R.string.mgr_channel_auto_using, autoPick),
                    statusInfo = true,
                    selected = current == 0,
                    enabled = !bindingRoot,
                    onClick = { selectChannel(0) }
                )
                // Shizuku
                ChannelRow(
                    name = stringResource(R.string.shizuku_iwifimanager),
                    status = when {
                        shizukuGranted -> stringResource(R.string.mgr_channel_status_ok)
                        shizukuAlive -> stringResource(R.string.mgr_channel_shizuku_unauthorized)
                        else -> stringResource(R.string.mgr_channel_shizuku_offline)
                    },
                    statusInfo = shizukuGranted,
                    selected = current == 1,
                    enabled = !bindingRoot,
                    onClick = { selectChannel(1) }
                )
                // Root AIDL
                ChannelRow(
                    name = stringResource(R.string.aidl_iwifimanager),
                    status = when {
                        rootBound -> stringResource(R.string.mgr_channel_status_ok)
                        bindingRoot -> stringResource(R.string.mgr_channel_root_binding)
                        else -> stringResource(R.string.mgr_channel_root_unbound)
                    },
                    statusInfo = rootBound,
                    selected = current == 2,
                    enabled = !bindingRoot,
                    showProgress = bindingRoot,
                    onClick = { selectChannel(2) }
                )
                // 系统 API（可用 = 定位权限 + 系统定位开关双就绪，缺一
                // 分别如实显示对应状态而非笼统的「缺定位权限」）
                ChannelRow(
                    name = stringResource(R.string.api_wifimanager),
                    status = when {
                        !apiPerm -> stringResource(R.string.mgr_channel_api_no_location)
                        !apiLocOn -> stringResource(R.string.mgr_channel_api_loc_off)
                        else -> stringResource(R.string.mgr_channel_status_ok)
                    },
                    statusInfo = apiPerm && apiLocOn,
                    selected = current == 3,
                    enabled = !bindingRoot,
                    onClick = { selectChannel(3) }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = { if (!bindingRoot) onDismiss() },
                enabled = !bindingRoot
            ) {
                Text(stringResource(R.string.btn_cancel))
            }
        }
    )

    // 永久拒绝兜底：定位权限被系统设为「不再询问」时，运行时申请会
    // 静默秒拒（不弹框）——只能引导用户去应用详情页手动开启
    if (showLocationSettings) {
        AlertDialog(
            onDismissRequest = { showLocationSettings = false },
            title = { Text(stringResource(R.string.mgr_channel_loc_settings_title)) },
            text = { Text(stringResource(R.string.mgr_channel_loc_settings_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            )
                        )
                    } catch (_: Exception) {
                    }
                    showLocationSettings = false
                    // 返回后重新打开弹窗可看到最新可用状态；手动开启后
                    // 再选系统 API 会直接切换（权限已就绪）
                    onDismiss()
                }) {
                    Text(stringResource(R.string.mgr_channel_go_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocationSettings = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

/**
 * 通道选项行：单选圆点 + 名称 + 可用状态（不可用为错误色），
 * 绑定等待中右侧显示进度指示。
 */
@Composable
private fun ChannelRow(
    name: String,
    status: String,
    statusInfo: Boolean,
    selected: Boolean,
    enabled: Boolean,
    showProgress: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 6.dp)
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = if (statusInfo) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
        if (showProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
        }
    }
}

// ==================== Tab1：扫描 ====================

@Composable
private fun ScanTabPage(controller: ManagerController, app: ToolboxApp) {
    val context = LocalContext.current
    var expandedSsid by rememberSaveable { mutableStateOf<String?>(null) }
    // 未保存加密网络的连接密码输入对话框（目标 SSID 非空即弹出）
    var pwdDialogSsid by rememberSaveable { mutableStateOf<String?>(null) }
    var pwdDialogSec by rememberSaveable { mutableStateOf("") }
    var pwdInput by rememberSaveable { mutableStateOf("") }
    // 已连接网络的「忘记网络」确认对话框（真机反馈：已连接卡片需要忘记入口，
    // 重复连接无意义但忘记是高频需求——如改密码后重连）
    var confirmForget by rememberSaveable { mutableStateOf<String?>(null) }

    // 进入页面自动扫一轮；离开（切页/退出管理器）立即取消在途扫描
    // ——扫描是射频级功耗，未停留在扫描页时不应继续轮询（真机反馈：省功耗）
    DisposableEffect(Unit) {
        controller.refreshScan()
        onDispose { controller.stopScan() }
    }

    // 操作结果轻提示
    LaunchedEffect(controller.opMessage) {
        controller.opMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            controller.clearOpMessage()
        }
    }

    // 已连接网络的忘记确认（与已保存页同款二次确认：删除系统配置不可撤销）
    confirmForget?.let { ssid ->
        AlertDialog(
            onDismissRequest = { confirmForget = null },
            title = { Text(stringResource(R.string.mgr_forget)) },
            text = {
                Text(stringResource(R.string.mgr_forget_confirm, ssid))
            },
            confirmButton = {
                TextButton(onClick = {
                    // networkId 从扫描列表合并的系统配置取（当前连接的网络
                    // 必有系统配置；列表未刷新的瞬态下不显示按钮，不会走到这）
                    val cfg = controller.scanNetworks
                        .find { it.ssid == ssid }?.savedInfo
                    if (cfg != null) {
                        controller.forgetSaved(
                            SavedNetworkEntry(
                                ssid = ssid,
                                networkId = cfg.networkId,
                                password = "",
                                passwordFromPojie = false,
                                fromSystem = true
                            )
                        )
                    }
                    confirmForget = null
                }) {
                    Text(stringResource(R.string.mgr_forget))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    // 扫描页卡片「连接」入口：已保存/留有破解密码/开放网络直接连接，
    // 其余弹密码输入。连接成功后系统自动保存配置（无需重复保存）。
    fun connectFromScan(wifi: WifiInfo) {
        val cfg = wifi.savedInfo
        val histPwd = wifi.pojieHistoryItem?.password
        val sec = securitySummary(wifi.capabilities)
        when {
            // 系统已有配置（含密码不可见的 Android 10+ 场景）：networkId 直连
            cfg != null -> controller.connectSaved(
                SavedNetworkEntry(
                    ssid = wifi.ssid,
                    networkId = cfg.networkId,
                    password = cfg.preSharedKey?.removeSurrounding("\"").orEmpty()
                        .ifEmpty { histPwd.orEmpty() },
                    passwordFromPojie = cfg.preSharedKey.isNullOrEmpty() && histPwd != null,
                    fromSystem = true,
                    security = sec
                )
            )

            // 本应用破解记录留有密码：直接带密码连接
            histPwd != null -> controller.connectSaved(
                SavedNetworkEntry(
                    ssid = wifi.ssid, networkId = -1, password = histPwd,
                    passwordFromPojie = true, fromSystem = false, security = sec
                )
            )

            // 开放网络：无需密码（connectSaved 按空密码 OPEN 配置直连）
            sec == "OPEN" -> controller.connectSaved(
                SavedNetworkEntry(
                    ssid = wifi.ssid, networkId = -1, password = "",
                    passwordFromPojie = false, fromSystem = false, security = sec
                )
            )

            // 未保存加密网络：弹密码输入框
            else -> {
                pwdInput = ""
                pwdDialogSec = sec
                pwdDialogSsid = wifi.ssid
            }
        }
    }

    // 密码输入对话框（未保存的加密网络）
    pwdDialogSsid?.let { ssid ->
        AlertDialog(
            onDismissRequest = { pwdDialogSsid = null },
            title = { Text(stringResource(R.string.mgr_pwd_dialog_title, ssid)) },
            text = {
                OutlinedTextField(
                    value = pwdInput,
                    onValueChange = { pwdInput = it },
                    label = { Text(stringResource(R.string.mgr_pwd_field_label)) },
                    // 长度规则随加密类型提示（系统同款校验规则），不合规即时标红
                    supportingText = {
                        Text(
                            stringResource(
                                if (pwdDialogSec == "WEP") R.string.mgr_pwd_hint_wep
                                else R.string.mgr_pwd_hint_wpa
                            )
                        )
                    },
                    isError = pwdInput.isNotEmpty() &&
                            passwordLengthErrorRes(pwdDialogSec, pwdInput) != null,
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val lenErr = passwordLengthErrorRes(pwdDialogSec, pwdInput)
                    when {
                        pwdInput.isEmpty() -> Toast.makeText(
                            context,
                            context.getString(R.string.mgr_pwd_empty),
                            Toast.LENGTH_SHORT
                        ).show()

                        // 长度不合规：具体提示原因并保留对话框供立即修正
                        // （原缺陷：直接下发请求后收到「连接请求失败：添加
                        // 网络失败」，冗长且无指导意义）
                        lenErr != null -> Toast.makeText(
                            context,
                            context.getString(lenErr),
                            Toast.LENGTH_SHORT
                        ).show()

                        else -> {
                            controller.connectSaved(
                                SavedNetworkEntry(
                                    ssid = ssid, networkId = -1, password = pwdInput,
                                    passwordFromPojie = false, fromSystem = false,
                                    security = pwdDialogSec
                                )
                            )
                            pwdDialogSsid = null
                        }
                    }
                }) {
                    Text(stringResource(R.string.mgr_connect))
                }
            },
            dismissButton = {
                TextButton(onClick = { pwdDialogSsid = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        // 工具栏：数量 + 来源 + 刷新
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.mgr_networks_count, controller.scanNetworks.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            // 来源标签（可点击）：弹出通道切换对话框（可用状态 + 不可用时申请权限）
            SourceChannelTag(
                source = controller.scanSource,
                controller = controller,
                refreshScanOnSwitch = true
            )
            Spacer(Modifier.weight(1f))
            if (controller.scanLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.mgr_refreshing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                IconButton(onClick = { controller.refreshScan() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                }
            }
        }

        when {
            controller.scanErrorKey == 1 -> {
                EmptyHint(
                    icon = Icons.Rounded.WifiOff,
                    title = stringResource(R.string.mgr_wifi_off),
                    tip = stringResource(R.string.mgr_wifi_off_tip),
                    actionText = stringResource(R.string.mgr_turn_on_wifi),
                    onAction = {
                        try {
                            ApiUtil.setWifiEnabled(context, true)
                        } catch (_: Exception) {
                        }
                    }
                )
            }

            controller.scanNetworks.isEmpty() -> {
                EmptyHint(
                    icon = Icons.Rounded.WifiFind,
                    title = stringResource(
                        if (controller.scanLoading) R.string.mgr_refreshing
                        else R.string.mgr_no_results
                    ),
                    tip = stringResource(R.string.mgr_no_results_tip),
                    actionText = null, onAction = null
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(controller.scanNetworks, key = { it.ssid }) { wifi ->
                        ScanNetworkCard(
                            wifi = wifi,
                            expanded = expandedSsid == wifi.ssid,
                            isCurrent = controller.isCurrentNetwork(wifi.ssid, -1),
                            connecting = controller.connectingSsid == wifi.ssid,
                            connectEnabled = controller.connectingSsid == null,
                            onToggle = {
                                expandedSsid = if (expandedSsid == wifi.ssid) null else wifi.ssid
                            },
                            onConnect = { connectFromScan(wifi) },
                            onForget = { confirmForget = wifi.ssid }
                        )
                    }
                }
            }
        }
    }
}

/** 信号强度标签（四档分界与 AOSP config_wifiRssiLevelThresholds 一致：[-88,-77,-66,-55]） */

/**
 * 当前网络卡片高亮色：primaryContainer 与 surfaceContainer 按 35:65 混合——
 * 仍跟随主题取色，但饱和度/明度显著低于纯 primaryContainer（真机反馈
 * 原高亮过于鲜艳）。两个页面（扫描/已保存）统一使用同一高亮。
 */
@Composable
private fun currentNetworkContainerColor(): Color = lerp(
    MaterialTheme.colorScheme.surfaceContainer,
    MaterialTheme.colorScheme.primaryContainer,
    0.35f
)

@Composable
private fun signalLabel(level: Int): String = stringResource(
    when (signalLevel(level)) {
        4 -> R.string.mgr_signal_4
        3 -> R.string.mgr_signal_3
        2 -> R.string.mgr_signal_2
        1 -> R.string.mgr_signal_1
        else -> R.string.mgr_signal_1
    }
)

@Composable
private fun ScanNetworkCard(
    wifi: WifiInfo,
    expanded: Boolean,
    isCurrent: Boolean,
    connecting: Boolean,
    connectEnabled: Boolean,
    onToggle: () -> Unit,
    onConnect: () -> Unit,
    onForget: () -> Unit
) {
    val context = LocalContext.current
    val (band, channel) = freqToBandChannel(wifi.frequency)

    // Card(onClick) 重载：水波纹自动裁剪到卡片圆角（此前 Modifier.clickable
    // 的波纹是矩形/圆形外扩，与圆角卡片形状不符）
    Card(
        onClick = onToggle,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) currentNetworkContainerColor()
            else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        // animateContentSize：展开/收起高度平滑过渡（无弹跳）。此前用
        // AnimatedVisibility(expandVertically) 在已连接状态切换（配色/「当前」
        // 标签插入触发重组）时会出现卡片塌方（内容叠压、布局崩坏），
        // 改为常驻组合 + 容器尺寸动画后状态切换只是普通重组，布局稳定。
        Column(
            Modifier
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 四格信号图标（与密码字典破解运行页同一组件同一分档算法）
                WifiIcon(
                    level = signalLevel(wifi.level),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = wifi.ssid,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 头部标签只保留加密类型/已保存/已破解（真机反馈：
                        // 「2.4G CH3」「当前」信息冗余——频段信道在展开详情
                        // 里仍有完整行，当前连接由卡片高亮色表达）
                        TagItem(securitySummary(wifi.capabilities))
                        if (wifi.savedInfo != null) TagItem(
                            stringResource(R.string.mgr_saved_mark),
                            TagType.Secondary
                        )
                        if (wifi.pojieHistoryItem?.password != null) TagItem(
                            stringResource(R.string.mgr_cracked_mark),
                            TagType.Tertiary
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${wifi.level} dBm",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = signalLabel(wifi.level),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (expanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    DetailRow("SSID", wifi.ssid)
                    DetailRow("BSSID", wifi.bssid)
                    DetailRow(stringResource(R.string.mgr_lbl_rssi), "${wifi.level} dBm")
                    if (wifi.frequency > 0) {
                        DetailRow(
                            stringResource(R.string.mgr_lbl_band),
                            "${wifi.frequency} MHz · $band"
                        )
                        DetailRow(stringResource(R.string.mgr_lbl_channel), channel.toString())
                    }
                    DetailRow("Security", wifi.capabilities)
                    if (!isCurrent) {
                        // 未连接：连接按钮（已保存/已留密码/开放网络直连，未保存
                        // 加密网络弹密码输入；连接成功系统自动保存配置）
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = onConnect,
                            enabled = connectEnabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (connecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.mgr_connecting_btn),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.mgr_connect),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    } else if (wifi.savedInfo != null) {
                        // 已连接且有系统配置：忘记网络按钮（真机反馈——已连接
                        // 卡片重复连接无意义，但「忘记」（如改密码后重连）是
                        // 高频需求；仅系统有配置时显示，忘记需 networkId）
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = onForget,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.mgr_forget),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        SmallActionButton(
                            icon = Icons.Outlined.ContentCopy,
                            label = stringResource(R.string.mgr_copy) + " SSID",
                            modifier = Modifier.weight(1f)
                        ) {
                            copyText(context, wifi.ssid)
                        }
                        Spacer(Modifier.width(8.dp))
                        SmallActionButton(
                            icon = Icons.Outlined.ContentCopy,
                            label = stringResource(R.string.mgr_copy) + " BSSID",
                            modifier = Modifier.weight(1f)
                        ) {
                            copyText(context, wifi.bssid)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    labelColor: Color? = null,
    valueColor: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = labelColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp)
        )
        Text(
            text = value.ifEmpty { "-" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = valueColor ?: Color.Unspecified,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SmallActionButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun EmptyHint(
    icon: ImageVector,
    title: String,
    tip: String,
    actionText: String?,
    onAction: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            tip,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionText) }
        }
    }
}

private fun copyText(context: android.content.Context, text: String) {
    val clipboard =
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", text))
    Toast.makeText(
        context,
        context.getString(R.string.mgr_copied),
        Toast.LENGTH_SHORT
    ).show()
}

// ==================== Tab2：已保存网络 ====================

/** 已保存页筛选：0=全部 1=系统保存 2=破解记录 */
private val SAVED_FILTER_LABELS = intArrayOf(
    R.string.mgr_filter_all, R.string.mgr_filter_system, R.string.mgr_filter_pojie
)

/** 已保存页排序：0=名称A→Z 1=名称Z→A 2=最近破解优先 */
private val SAVED_SORT_LABELS = intArrayOf(
    R.string.mgr_sort_az, R.string.mgr_sort_za, R.string.mgr_sort_recent
)

/** 字母分组（letter=' ' 表示平铺模式——「最近破解」排序不分组，隐藏索引栏） */
private data class SavedSection(val letter: Char, val entries: List<SavedNetworkEntry>)

private fun buildSavedSections(
    entries: List<SavedNetworkEntry>, sortIdx: Int
): List<SavedSection> {
    if (sortIdx == 2) {
        // 最近破解优先：破解记录按时间倒序在前，其余按名称 A-Z 平铺
        val (cracked, rest) = entries.partition { it.hasPojieRecord }
        val list = cracked.sortedByDescending { it.pojieTime } +
                rest.sortedBy { it.ssid.lowercase() }
        return if (list.isEmpty()) emptyList() else listOf(SavedSection(' ', list))
    }
    val grouped = entries.groupBy { PinyinIndex.sectionKey(it.ssid) }
    return if (sortIdx == 1) {
        grouped.toSortedMap(compareByDescending { it }).map { (l, e) ->
            SavedSection(l, e.sortedByDescending { it.ssid.lowercase() })
        }
    } else {
        grouped.toSortedMap().map { (l, e) ->
            SavedSection(l, e.sortedBy { it.ssid.lowercase() })
        }
    }
}

/** 每个分组首项在 LazyColumn 中的 flat 下标（含 stickyHeader 占位） */
private fun sectionFirstIndexMap(sections: List<SavedSection>): Map<Char, Int> {
    val map = LinkedHashMap<Char, Int>()
    var cursor = 0
    for (s in sections) {
        if (s.letter != ' ') {
            map[s.letter] = cursor
            cursor++
        }
        cursor += s.entries.size
    }
    return map
}

/** flat 下标 → 所属分组字母 */
private fun letterAtFlatIndex(sections: List<SavedSection>, flatIndex: Int): Char? {
    var cursor = 0
    for (s in sections) {
        if (s.letter != ' ') {
            if (flatIndex == cursor) return s.letter
            cursor++
        }
        if (flatIndex < cursor + s.entries.size) return s.letter
        cursor += s.entries.size
    }
    return sections.lastOrNull()?.letter
}

/** 索引栏拖到无条目的字母时就近落位到最近的有条目分组 */
private fun scrollTargetFor(
    letter: Char, firstIndex: Map<Char, Int>
): Int? {
    firstIndex[letter]?.let { return it }
    val ordered = PinyinIndex.RAIL_LETTERS
    val pos = ordered.indexOf(letter)
    if (pos == -1) return null
    for (d in 1 until ordered.size) {
        if (pos + d < ordered.size) firstIndex[ordered[pos + d]]?.let { return it }
        if (pos - d >= 0) firstIndex[ordered[pos - d]]?.let { return it }
    }
    return null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedTabPage(controller: ManagerController) {
    val context = LocalContext.current
    var revealSsid by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmForget by rememberSaveable { mutableStateOf<String?>(null) }
    var filterIdx by rememberSaveable { mutableIntStateOf(0) }
    var sortIdx by rememberSaveable { mutableIntStateOf(0) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val railScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        controller.refreshSaved()
        controller.refreshCurrent()
    }
    LaunchedEffect(controller.opMessage) {
        controller.opMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            controller.clearOpMessage()
        }
    }

    // 忘记确认对话框
    confirmForget?.let { ssid ->
        val entry = controller.savedEntries.find { it.ssid == ssid }
        if (entry != null) {
            AlertDialog(
                onDismissRequest = { confirmForget = null },
                title = { Text(stringResource(R.string.mgr_forget)) },
                text = {
                    Text(
                        stringResource(R.string.mgr_forget_confirm, ssid)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        controller.forgetSaved(entry)
                        confirmForget = null
                    }) {
                        Text(stringResource(R.string.mgr_forget))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmForget = null }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            )
        }
    }

    val allEntries = controller.savedEntries
    val filtered = remember(allEntries, filterIdx) {
        when (filterIdx) {
            1 -> allEntries.filter { it.fromSystem }
            2 -> allEntries.filter { it.hasPojieRecord }
            else -> allEntries
        }
    }
    val sections = remember(filtered, sortIdx) { buildSavedSections(filtered, sortIdx) }
    val firstIndex = remember(sections) { sectionFirstIndexMap(sections) }
    val railPresent = remember(sections) {
        sections.map { it.letter }.filter { it != ' ' }.toSet()
    }

    // 当前滚动位置对应的分组字母（驱动索引栏高亮）
    val currentLetter by remember(sections) {
        derivedStateOf { letterAtFlatIndex(sections, listState.firstVisibleItemIndex) }
    }
    var railLetter by remember { mutableStateOf<Char?>(null) }

    Column(Modifier.fillMaxSize()) {
        // 行1：数量 + 来源 + 刷新
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.mgr_networks_count, filtered.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
            // 来源标签（可点击）：与扫描页同一通道切换弹窗（本页切换只重读
            // 配置，不触发射频扫描——扫描页进入时自会重扫）
            SourceChannelTag(
                source = controller.savedSource,
                controller = controller,
                refreshScanOnSwitch = false
            )
            Spacer(Modifier.weight(1f))
            if (controller.savedLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(onClick = { controller.refreshSaved() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                }
            }
        }

        // 行2：筛选 + 排序
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SAVED_FILTER_LABELS.forEachIndexed { i, res ->
                FilterChip(
                    selected = filterIdx == i,
                    onClick = { filterIdx = i },
                    label = {
                        Text(
                            stringResource(res),
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(
                    onClick = { sortMenuOpen = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Rounded.SortByAlpha,
                        contentDescription = stringResource(R.string.mgr_sort_hint)
                    )
                }
                DropdownMenu(
                    expanded = sortMenuOpen,
                    onDismissRequest = { sortMenuOpen = false }
                ) {
                    SAVED_SORT_LABELS.forEachIndexed { i, res ->
                        DropdownMenuItem(
                            text = { Text(stringResource(res)) },
                            trailingIcon = {
                                if (sortIdx == i) {
                                    Icon(
                                        Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            },
                            onClick = {
                                sortIdx = i
                                sortMenuOpen = false
                            }
                        )
                    }
                }
            }
        }

        when {
            controller.savedLoading && allEntries.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            sections.isEmpty() -> {
                EmptyHint(
                    icon = Icons.Rounded.Dns,
                    title = stringResource(
                        if (allEntries.isEmpty()) R.string.mgr_saved_empty
                        else R.string.mgr_filter_empty
                    ),
                    tip = stringResource(
                        if (allEntries.isEmpty()) R.string.mgr_saved_empty_tip
                        else R.string.mgr_filter_empty_tip
                    ),
                    actionText = null, onAction = null
                )
            }

            else -> {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 30.dp, bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        sections.forEach { section ->
                            if (section.letter != ' ') {
                                stickyHeader(key = "h_${section.letter}") {
                                    LetterHeader(section.letter, section.entries.size)
                                }
                            }
                            items(section.entries, key = { it.ssid }) { entry ->
                                SavedNetworkCard(
                                    entry = entry,
                                    isCurrent = controller.isCurrentNetwork(
                                        entry.ssid, entry.networkId
                                    ),
                                    revealed = revealSsid == entry.ssid,
                                    connecting = controller.connectingSsid == entry.ssid,
                                    connectEnabled = controller.connectingSsid == null,
                                    onToggleReveal = {
                                        revealSsid = if (revealSsid == entry.ssid) null else entry.ssid
                                    },
                                    onConnect = { controller.connectSaved(entry) },
                                    onForget = { confirmForget = entry.ssid },
                                    onDeleteRecord = { controller.deletePojieRecord(entry.ssid) }
                                )
                            }
                        }
                    }

                    // 右侧 A-Z 索引栏（仅字母分组排序时显示）
                    if (sortIdx != 2 && sections.isNotEmpty()) {
                        AlphabetRail(
                            letters = PinyinIndex.RAIL_LETTERS,
                            present = railPresent,
                            active = railLetter ?: currentLetter,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 4.dp)
                        ) { ch, dragging ->
                            railLetter = if (dragging) ch else null
                            if (ch != null) {
                                scrollTargetFor(ch, firstIndex)?.let { target ->
                                    railScope.launch { listState.scrollToItem(target) }
                                }
                            }
                        }
                    }

                    // 拖动索引栏时的中央大字母气泡
                    railLetter?.let { ch ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(84.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = ch.toString(),
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 字母分组吸顶标题 */
@Composable
private fun LetterHeader(letter: Char, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = letter.toString(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.mgr_networks_count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 右侧 A-Z 索引栏（通讯录风格）：
 * - 当前分组字母高亮放大（滚动位置驱动）
 * - 拖动/点击任意字母滚动列表到对应分组（无条目字母就近落位）
 * - 有条目的字母正常显示，无条目的字母淡显
 */
@Composable
private fun AlphabetRail(
    letters: List<Char>,
    present: Set<Char>,
    active: Char?,
    modifier: Modifier = Modifier,
    onTouch: (Char?, Boolean) -> Unit
) {
    var railHeightPx by remember { mutableFloatStateOf(0f) }

    fun pick(y: Float): Char? {
        if (railHeightPx <= 0f) return null
        val idx = (y / railHeightPx * letters.size).toInt()
            .coerceIn(0, letters.size - 1)
        return letters[idx]
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(22.dp)
            .onGloballyPositioned { railHeightPx = it.size.height.toFloat() }
            .pointerInput(letters) {
                detectTapGestures { offset ->
                    pick(offset.y)?.let { onTouch(it, false) }
                }
            }
            .pointerInput(letters) {
                detectDragGestures(
                    onDragStart = { offset ->
                        pick(offset.y)?.let { onTouch(it, true) }
                    },
                    onDrag = { change, _ ->
                        pick(change.position.y)?.let { onTouch(it, true) }
                    },
                    onDragEnd = { onTouch(null, false) },
                    onDragCancel = { onTouch(null, false) }
                )
            }
    ) {
        letters.forEach { ch ->
            val isActive = active == ch
            val isPresent = ch in present
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = ch.toString(),
                    fontSize = if (isActive) 12.sp else 9.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isActive -> MaterialTheme.colorScheme.primary
                        isPresent -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                    }
                )
            }
        }
    }
}

@Composable
private fun SavedNetworkCard(
    entry: SavedNetworkEntry,
    isCurrent: Boolean,
    revealed: Boolean,
    connecting: Boolean,
    connectEnabled: Boolean,
    onToggleReveal: () -> Unit,
    onConnect: () -> Unit,
    onForget: () -> Unit,
    onDeleteRecord: () -> Unit
) {
    val context = LocalContext.current

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) currentNetworkContainerColor()
            else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // 标题行：SSID + 来源徽标
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = entry.ssid,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isCurrent) TagItem(
                    stringResource(R.string.mgr_current_mark),
                    TagType.Primary
                )
                if (entry.fromSystem) TagItem(
                    stringResource(R.string.mgr_saved_mark),
                    TagType.Secondary
                )
                if (entry.hasPojieRecord) TagItem(
                    stringResource(R.string.mgr_cracked_mark),
                    TagType.Tertiary
                )
            }

            Spacer(Modifier.height(10.dp))

            // 密码行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.mgr_password) + " · ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = when {
                        // 密码不可得时值区显示占位符——解释文案只在右侧标签出现一次
                        // （历史缺陷：这里与右侧标签重复显示两遍「不可见…」）
                        entry.password.isEmpty() -> "-"
                        revealed -> entry.password
                        else -> "•".repeat(entry.password.length.coerceIn(6, 12))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (entry.password.isNotEmpty()) {
                    IconButton(onClick = onToggleReveal, modifier = Modifier.size(30.dp)) {
                        Icon(
                            if (revealed) Icons.Rounded.VisibilityOff
                            else Icons.Rounded.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { copyText(context, entry.password) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            Icons.Rounded.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else if (entry.passwordFromPojie.not() && entry.fromSystem) {
                    // 密码不可见的解释性小标签（唯一一处）：开放网络如实说明
                    // 无需密码；加密网络说明需特权通道（Android 10+ 官方限制，
                    // Root/Shizuku 特权通道大多可见、系统 API 恒不可见）
                    Text(
                        stringResource(
                            if (entry.security == "OPEN") R.string.mgr_pwd_open
                            else R.string.mgr_pwd_hidden
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // 操作行
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onConnect,
                    enabled = connectEnabled,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    if (connecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.mgr_connecting_btn),
                            style = MaterialTheme.typography.labelMedium
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Link,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.mgr_connect),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                if (entry.networkId >= 0) {
                    OutlinedButton(
                        onClick = onForget,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.mgr_forget),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onDeleteRecord,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.mgr_delete_record),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

// ==================== Tab3：网络（多网络同显，3 秒自动刷新） ====================

@Composable
private fun NetworkTabPage(controller: ManagerController) {
    val entries = controller.networkEntries
    val connectedCount = entries.count { it.connected }

    // 多于一个已连接网络（WiFi+移动数据同开，或双 STA 双 WiFi）时，
    // 每张卡自动折叠分割线下方的信号强度/链路速率等详情，点击卡片展开
    val multi = connectedCount > 1
    val expandedKeys = remember { mutableStateMapOf<Long, Boolean>() }

    // 进入页面立即读一次，此后每 3 秒自动刷新（信号强度/链路速率/
    // 验证状态等动态数据不再需要手动刷新）
    LaunchedEffect(Unit) {
        controller.refreshCurrent()
        while (true) {
            delay(3000)
            controller.refreshCurrent()
        }
    }
    val portalText = stringResource(R.string.mgr_portal)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- 概览行：已连接网络数 + 自动刷新提示 + 手动刷新 ----
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.mgr_networks_count, connectedCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.mgr_net_auto_refresh, 3),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { controller.refreshCurrent() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // ---- 每网络一卡：WiFi（可多个）与移动数据同屏全部列出 ----
        items(entries, key = { (if (it.isWifi) "w" else "c") + it.handle }) { entry ->
            NetworkCard(
                entry = entry,
                collapsible = multi && entry.connected,
                expanded = if (multi && entry.connected)
                    expandedKeys[entry.handle] == true
                else true,
                onToggle = {
                    expandedKeys[entry.handle] = !(expandedKeys[entry.handle] == true)
                },
                portalText = portalText
            )
        }
    }
}

/**
 * 网络页单网络卡片（WiFi 与移动数据同一组件渲染）：
 * - 头部：类型图标 + 类型名（WiFi 网络 / 移动数据网络）+ 认证/验证标签；
 * - 标题行：WiFi 为 SSID、移动数据为运营商名；
 * - 分割线下方详情（WiFi：信号强度/链路速率/频段/信道/IP 等；移动数据：
 *   状态/运营商/漫游）在「多网络同显」时自动折叠，点击卡片展开/收起——
 *   高度变化用 animateContentSize 无弹跳平滑过渡（与扫描页卡片同款成熟
 *   方案），指示箭头同步无弹跳旋转 180°（真机反馈：展开/收起需要流畅
 *   动画）；单网络时常显全部详情。
 */
@Composable
private fun NetworkCard(
    entry: NetworkEntry,
    collapsible: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    portalText: String
) {
    val (band, channel) = freqToBandChannel(entry.frequencyMhz)
    // 箭头随展开态平滑旋转（无弹跳，与卡片高度动画同一节奏语言）
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "netCardArrow"
    )

    Card(
        onClick = onToggle,
        enabled = collapsible,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
                .padding(18.dp)
        ) {
            // 头部：类型 + 状态标签 + 折叠指示（箭头随展开态平滑旋转）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (entry.isWifi) {
                        if (entry.connected) Icons.Rounded.Wifi else Icons.Rounded.WifiOff
                    } else Icons.Rounded.SignalCellular4Bar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (entry.isWifi) R.string.mgr_net_wifi else R.string.mgr_lbl_mobile
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (entry.connected) {
                    if (entry.portal) {
                        TagItem(portalText, TagType.Tertiary)
                    } else {
                        TagItem(
                            if (entry.validated) stringResource(R.string.mgr_validated)
                            else stringResource(R.string.mgr_not_validated),
                            if (entry.validated) TagType.Primary else TagType.Tertiary
                        )
                    }
                }
                if (collapsible) {
                    Icon(
                        Icons.Rounded.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(arrowRotation)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            // 标题行：WiFi=SSID（空则按连接状态兜底）；移动数据=运营商名
            Text(
                text = entry.title.ifEmpty {
                    stringResource(
                        when {
                            !entry.isWifi -> R.string.mgr_lbl_mobile
                            entry.connected -> R.string.wifi_connected_generic
                            else -> R.string.not_connected
                        }
                    )
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = heavierOnSurface()
            )
            // 副标题：WiFi=BSSID；移动数据=连接状态
            if (entry.isWifi) {
                // 定位服务关闭等场景 WifiInfo.getBSSID() 返回匿名化占位 MAC
                // （02:00:00:00:00:00），并非真实 BSSID——如实提示而非展示假地址
                if (entry.bssid.isNotEmpty()) {
                    val isAnonymized =
                        entry.bssid.equals(ANONYMIZED_BSSID, ignoreCase = true)
                    Text(
                        text = if (isAnonymized)
                            stringResource(R.string.mgr_bssid_hidden)
                        else entry.bssid,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    text = stringResource(
                        if (entry.validated) R.string.mgr_mobile_connected_validated
                        else R.string.mgr_mobile_connected
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (entry.isWifi && entry.connected && entry.rssi > -200) {
                Spacer(Modifier.height(12.dp))
                SignalMeter(entry.rssi)
            }

            // ---- 分割线下方详情（多网络时自动折叠，点击卡片展开） ----
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))

                if (entry.isWifi) {
                    NetDetailRow(
                        stringResource(R.string.mgr_lbl_rssi),
                        "${entry.rssi} dBm"
                    )
                    if (entry.linkSpeedMbps > 0) {
                        NetDetailRow(
                            stringResource(R.string.mgr_lbl_speed),
                            "${entry.linkSpeedMbps} Mbps"
                        )
                    }
                    if (entry.frequencyMhz > 0) {
                        NetDetailRow(
                            stringResource(R.string.mgr_lbl_band),
                            "${entry.frequencyMhz} MHz · $band"
                        )
                        if (channel > 0) {
                            NetDetailRow(
                                stringResource(R.string.mgr_lbl_channel),
                                channel.toString()
                            )
                        }
                    }
                    NetDetailRow(stringResource(R.string.mgr_lbl_ip), entry.ipAddress)
                    NetDetailRow(stringResource(R.string.mgr_lbl_gateway), entry.gateway)
                    NetDetailRow(
                        stringResource(R.string.mgr_lbl_dns),
                        entry.dnsServers.joinToString(", ")
                    )
                    if (entry.dhcpServer.isNotEmpty()) {
                        NetDetailRow(stringResource(R.string.mgr_lbl_dhcp), entry.dhcpServer)
                    }
                    if (entry.leaseDurationSec > 0) {
                        NetDetailRow(
                            stringResource(R.string.mgr_lbl_lease),
                            "${entry.leaseDurationSec}s"
                        )
                    }
                } else {
                    NetDetailRow(
                        stringResource(R.string.mgr_lbl_state),
                        stringResource(
                            if (entry.validated) R.string.mgr_mobile_connected_validated
                            else R.string.mgr_mobile_connected
                        )
                    )
                    if (entry.carrier.isNotEmpty()) {
                        NetDetailRow(stringResource(R.string.mgr_lbl_carrier), entry.carrier)
                    }
                    NetDetailRow(
                        stringResource(R.string.mgr_lbl_roaming),
                        stringResource(if (entry.roaming) R.string.mgr_yes else R.string.mgr_no)
                    )
                }
            }
        }
    }
}

/** 文字「加重一丢丢」：亮色主题向纯黑、暗色主题向纯白各拉近一小步——
 *  * 只提升一档对比度而不改变层级（亮色 onSurface≈#1C1B1F 暗色≈#E6E0E9）
 *  * 真机反馈：网络卡片名称与实时信息文字偏浅，稍微加重 */
@Composable
private fun heavierOnSurface(): Color {
    val base = MaterialTheme.colorScheme.onSurface
    return lerp(base, if (isSystemInDarkTheme()) Color.White else Color.Black, 0.15f)
}

/** 次级实时信息（信号/速率/频段等）基数更浅，同样微调加重（比例稍大） */
@Composable
private fun heavierOnSurfaceVariant(): Color {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    return lerp(base, if (isSystemInDarkTheme()) Color.White else Color.Black, 0.24f)
}

/** 网络卡片分割线下方详情行：标签/数值均比默认加重一丢丢（真机反馈） */
@Composable
private fun NetDetailRow(label: String, value: String) {
    DetailRow(
        label, value,
        labelColor = heavierOnSurfaceVariant(),
        valueColor = heavierOnSurface()
    )
}

/** 四段信号仪表条（dBm 分级着色，与主流分析工具一致） */
@Composable
private fun SignalMeter(rssi: Int) {
    val level = signalLevel(rssi)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
        ) {
            val segColors = listOf(
                Color(0xFFC62828), Color(0xFFF9A825),
                Color(0xFF7CB342), Color(0xFF2E7D32)
            )
            segColors.forEachIndexed { index, color ->
                val active = index < level
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            if (active) color
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
                if (index < 3) Spacer(Modifier.width(3.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = signalLabel(rssi),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
