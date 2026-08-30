@file:Suppress("DEPRECATION")

package com.wifi.toolbox.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import androidx.compose.runtime.*
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.GuardSettings
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.structs.WifiInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * WiFi 管理器统一数据控制器（扫描 / 已保存网络 / 当前网络详情与诊断）。
 *
 * 【通道体系】与破解页共用同一套语义（PojieSettings.scanMode）：
 * 1 = Shizuku 反射 IWifiManager；2 = Root AIDL（libsu RootService）；
 * 3 = 系统应用层 API（targetSdk=28 豁免）。查询型操作（读列表/读详情）
 * 在指定通道结果为空时**自动降级补一次其他通道**——管理器是只读查询，
 * 静默降级比弹授权引导更符合场景；实际使用的通道记录在 source 中如实展示。
 *
 * 【已保存网络密码的分级可见性】（Android 10+ 官方限制：
 * getConfiguredNetworks() 对所有应用返回空列表，包括 targetSdk<29 的应用，
 * 见 developer.android.com/about/versions/10/privacy/changes）：
 * - Root 通道：getPrivilegedConfiguredNetworks 反射，preSharedKey 明文可得；
 * - Shizuku（shell uid，持 NETWORK_SETTINGS）：同上，但部分 ROM 受限；
 * - 系统 API：仅 Android 9 及以下有效；
 * - 本地破解历史（PojieHistoryManager）：应用自身数据库，**无任何权限要求**，
 *   这是普通权限下唯一确定可见的密码来源。
 */

/** 已保存网络条目（系统配置与破解记录合并后的展示模型） */
data class SavedNetworkEntry(
    val ssid: String,
    val networkId: Int,                 // -1 = 仅破解记录，系统里无对应配置
    val password: String,               // 特权明文或破解成功密码；空 = 均不可得
    val passwordFromPojie: Boolean,     // 密码来源是否为本地破解记录
    val fromSystem: Boolean,            // 是否读到了系统保存的配置
    val security: String = ""           // 加密类型摘要（OPEN/WPA2/WPA3/…）
)

/** 当前连接网络详情（Tab3 展示模型，免定位字段优先） */
data class CurrentNetworkInfo(
    val connected: Boolean,
    val ssid: String,                   // WifiIdentity 三级解析（定位无关兜底）
    val bssid: String,                  // 受定位开关限制，可能为空
    val rssi: Int,                      // dBm，免定位
    val linkSpeedMbps: Int,             // 协商速率，免定位
    val frequencyMhz: Int,              // 免定位
    val ipAddress: String,
    val gateway: String,
    val dnsServers: List<String>,
    val dhcpServer: String,
    val leaseDurationSec: Int,
    val validated: Boolean              // 系统最近一次网络验证结论
)

/** 通道名（UI 展示） */
fun managerChannelName(channel: Int): String = when (channel) {
    1 -> "Shizuku"
    2 -> "Root"
    3 -> "API"
    else -> "-"
}

/** capabilities 摘要为加密类型短标签 */
fun securitySummary(capabilities: String): String {
    val has = { k: String -> capabilities.contains(k) }
    return when {
        has("WPA3") && has("SAE") -> "WPA3"
        has("WPA3") -> "WPA3"
        has("WPA2") -> "WPA2"
        has("WPA-") -> "WPA"
        has("EAP") -> "EAP"
        capabilities.contains("ESS") -> "OPEN"
        else -> "?"
    }
}

/** 频率(MHz)→(频段标签, 信道号)；信道号换算依据 IEEE 802.11 标准 */
fun freqToBandChannel(freqMhz: Int): Pair<String, Int> = when {
    freqMhz <= 0 -> "" to 0
    freqMhz in 2412..2484 -> "2.4G" to ((freqMhz - 2407) / 5).let { if (freqMhz == 2484) 14 else it }
    freqMhz in 5160..5885 -> "5G" to (freqMhz - 5000) / 5
    freqMhz in 5955..7115 -> "6G" to (freqMhz - 5950) / 5
    else -> "${freqMhz / 1000}G" to 0
}

/** 信号分级（与 WiFiAnalyzer 等主流工具一致的 dBm 分档） */
fun signalLevel(rssi: Int): Int = when {
    rssi >= -55 -> 4   // 极好
    rssi >= -66 -> 3   // 良好
    rssi >= -77 -> 2   // 一般
    rssi >= -88 -> 1   // 较弱
    else -> 0          // 微弱
}

interface ManagerController {
    // ---- Tab1 扫描 ----
    val scanNetworks: List<WifiInfo>
    val scanLoading: Boolean
    val scanSource: Int                 // 实际数据来源通道（0=无数据）
    val scanErrorKey: Int               // 空态原因码：-1=有数据 0=未扫描 1=WiFi关 2=无结果
    fun refreshScan()

    // ---- Tab2 已保存 ----
    val savedEntries: List<SavedNetworkEntry>
    val savedLoading: Boolean
    val savedSource: Int                // 特权读取通道（0=仅本地破解记录）
    fun refreshSaved()
    fun connectSaved(entry: SavedNetworkEntry)
    fun forgetSaved(entry: SavedNetworkEntry)
    fun deletePojieRecord(ssid: String)
    val opMessage: String?              // 最近一次操作结果（连接/忘记）
    fun clearOpMessage()

    // ---- Tab3 当前网络 ----
    val currentInfo: CurrentNetworkInfo
    fun refreshCurrent()
    val diagnosing: Boolean
    val diagnosisResults: List<com.wifi.toolbox.utils.ProbeResult>
    fun runDiagnosis()
}

private const val SCAN_POLL_INTERVAL = 600L
private const val SCAN_POLL_COUNT = 5

@Composable
fun rememberManagerController(context: Context, app: ToolboxApp): ManagerController {
    val scope = rememberCoroutineScope()
    val settingsState = rememberPojieSettings(context)
    val historyList by app.pojieHistory.historyFlow.collectAsState(initial = emptyList())

    // ---- Tab1 状态 ----
    var scanNetworksState by remember { mutableStateOf<List<WifiInfo>>(emptyList()) }
    var scanLoadingState by remember { mutableStateOf(false) }
    var scanSourceState by remember { mutableIntStateOf(0) }
    var scanErrorKeyState by remember { mutableIntStateOf(0) }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    // 已保存列表与破解历史缓存（扫描列表的「已保存/已破解」标记用）
    var savedCache by remember { mutableStateOf<List<WifiConfiguration>>(emptyList()) }

    // ---- Tab2 状态 ----
    var savedEntriesState by remember { mutableStateOf<List<SavedNetworkEntry>>(emptyList()) }
    var savedLoadingState by remember { mutableStateOf(false) }
    var savedSourceState by remember { mutableIntStateOf(0) }
    var opMessageState by remember { mutableStateOf<String?>(null) }

    // ---- Tab3 状态 ----
    var currentInfoState by remember {
        mutableStateOf(CurrentNetworkInfo(false, "", "", 0, 0, 0, "", "", emptyList(), "", 0, false))
    }
    var diagnosingState by remember { mutableStateOf(false) }
    var diagnosisResultsState by remember { mutableStateOf<List<ProbeResult>>(emptyList()) }

    /** 候选通道序列：指定通道优先，其余可用通道按特权级别补位（0=未设置时自动选） */
    fun channelOrder(): List<Int> {
        val primary = settingsState.value.scanMode
        val rest = mutableListOf<Int>()
        if (WifiHealer.isShizukuAvailable()) rest += 1
        if (app.aidl.ipc != null) rest += 2
        if (ApiUtil.hasLocationPermission(context)) rest += 3
        val effectivePrimary = if (primary != 0) primary else rest.firstOrNull() ?: 3
        return listOf(effectivePrimary) + rest.filter { it != effectivePrimary }
    }

    fun fetchScanOnce(): List<WifiInfo> {
        for (ch in channelOrder()) {
            try {
                val list = when (ch) {
                    1 -> ShizukuUtil.getWifiScanResults()
                    2 -> AidlServiceHelper.getWifiScanResults(app)
                    3 -> ApiUtil.getScanResults(context)
                    else -> emptyList()
                }
                if (list.isNotEmpty()) {
                    scanSourceState = ch
                    return list
                }
            } catch (_: Exception) {
                // 该通道不可用（未授权/未绑定），继续降级
            }
        }
        return emptyList()
    }

    fun mergeMarks(raw: List<WifiInfo>): List<WifiInfo> = raw
        .filter { it.ssid.isNotEmpty() }
        .distinctBy { it.ssid }
        .map { info ->
            info.copy(
                savedInfo = savedCache.find {
                    it.SSID.removeSurrounding("\"") == info.ssid
                },
                pojieHistoryItem = historyList.find { it.ssid == info.ssid }
            )
        }

    @android.annotation.SuppressLint("MissingPermission")
    fun performScan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            scanLoadingState = true
            scanErrorKeyState = 0
            try {
                val triggerChannel = channelOrder().first()
                // 已保存列表先行（合并标记用）
                savedCache = try {
                    when (triggerChannel) {
                        1 -> ShizukuUtil.getSavedWifiList()
                        2 -> AidlServiceHelper.getSavedWifiList(app)
                        else -> emptyList()
                    }
                } catch (_: Exception) { emptyList() }

                if (!ApiUtil.isWifiEnabled(context)) {
                    scanErrorKeyState = 1
                    scanNetworksState = emptyList()
                    scanLoadingState = false
                    return@launch
                }

                // 触发系统扫描（失败不阻断——上次结果仍可读）
                try {
                    when (triggerChannel) {
                        1 -> ShizukuUtil.startWifiScan(settingsState.value.allowScanUseCommand)
                        2 -> AidlServiceHelper.startWifiScan(app, settingsState.value.allowScanUseCommand)
                        else -> ApiUtil.startScan(context)
                    }
                } catch (_: Exception) {
                }

                // 轮询读取（同破解页节奏：扫描是异步的，结果逐步就绪）
                repeat(SCAN_POLL_COUNT) {
                    delay(SCAN_POLL_INTERVAL)
                    val raw = fetchScanOnce()
                    if (raw.isNotEmpty()) scanNetworksState = mergeMarks(raw)
                }
                if (scanNetworksState.isEmpty()) scanErrorKeyState = 2
                else scanErrorKeyState = -1
            } catch (_: Exception) {
                scanErrorKeyState = 2
            }
            scanLoadingState = false
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun performSaved() {
        scope.launch {
            savedLoadingState = true
            val configs = mutableListOf<WifiConfiguration>()
            var src = 0
            for (ch in channelOrder()) {
                try {
                    val r: List<WifiConfiguration> = when (ch) {
                        1 -> ShizukuUtil.getSavedWifiList()
                        2 -> AidlServiceHelper.getSavedWifiList(app)
                        3 -> ApiUtil.getSavedWifiList(context)
                        else -> emptyList()
                    }
                    if (r.isNotEmpty()) {
                        configs.addAll(r)
                        src = ch
                        break
                    }
                } catch (_: Exception) {
                }
            }
            savedSourceState = src
            savedCache = configs.toList()

            // 破解成功记录（本地库，无任何权限要求）
            val cracked = historyList.filter { !it.password.isNullOrEmpty() }
            val entries = mutableListOf<SavedNetworkEntry>()
            configs.forEach { cfg ->
                val ssid = cfg.SSID?.removeSurrounding("\"").orEmpty()
                if (ssid.isEmpty()) return@forEach
                val systemPwd = cfg.preSharedKey?.removeSurrounding("\"").orEmpty()
                val hist = cracked.find { it.ssid == ssid }
                val pwd = systemPwd.ifEmpty { hist?.password.orEmpty() }
                entries.add(
                    SavedNetworkEntry(
                        ssid = ssid,
                        networkId = cfg.networkId,
                        password = pwd,
                        passwordFromPojie = systemPwd.isEmpty() && hist != null,
                        fromSystem = true
                    )
                )
            }
            cracked.forEach { h ->
                if (entries.none { it.ssid == h.ssid }) {
                    entries.add(
                        SavedNetworkEntry(
                            ssid = h.ssid,
                            networkId = -1,
                            password = h.password.orEmpty(),
                            passwordFromPojie = true,
                            fromSystem = false
                        )
                    )
                }
            }
            savedEntriesState = entries
            savedLoadingState = false
        }
    }

    fun readCurrent() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiNetwork = NetProber.findWifiNetwork(cm)
        val caps = wifiNetwork?.let { cm.getNetworkCapabilities(it) }
        val lp: LinkProperties? = wifiNetwork?.let { cm.getLinkProperties(it) }
        val info = try { wm.connectionInfo } catch (_: Exception) { null }
        @Suppress("DEPRECATION")
        val dhcp = try { wm.dhcpInfo } catch (_: Exception) { null }

        val ip = lp?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }
            ?.address?.hostAddress ?: intToIp(dhcp?.ipAddress ?: 0)
        val gateway = lp?.routes?.firstNotNullOfOrNull { it.gateway?.hostAddress }
            ?: intToIp(dhcp?.gateway ?: 0)
        val dns = (lp?.dnsServers?.mapNotNull { it.hostAddress } ?: buildList {
            if (dhcp != null && dhcp.dns1 != 0) add(intToIp(dhcp.dns1))
            if (dhcp != null && dhcp.dns2 != 0) add(intToIp(dhcp.dns2))
        }).distinct()

        currentInfoState = CurrentNetworkInfo(
            connected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
            ssid = "",   // 异步解析填充（WifiIdentity 含特权兜底）
            bssid = try { info?.bssid.orEmpty() } catch (_: Exception) { "" },
            rssi = try { info?.rssi ?: -200 } catch (_: Exception) { -200 },
            linkSpeedMbps = try { info?.linkSpeed ?: -1 } catch (_: Exception) { -1 },
            frequencyMhz = try { info?.frequency ?: 0 } catch (_: Exception) { 0 },
            ipAddress = ip,
            gateway = gateway,
            dnsServers = dns,
            dhcpServer = if (dhcp != null && dhcp.serverAddress != 0) intToIp(dhcp.serverAddress) else "",
            leaseDurationSec = dhcp?.leaseDuration ?: 0,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        )

        // SSID 三级解析（应用层→cmd→dumpsys，定位关闭也能拿到）
        scope.launch {
            val (ssid, _) = WifiIdentity.resolve(context.applicationContext, app)
            currentInfoState = currentInfoState.copy(ssid = ssid)
        }
    }

    // 首次进入各 Tab 的懒加载由 UI 层 LaunchedEffect 触发
    LaunchedEffect(Unit) {
        readCurrent()
    }
    // 破解历史变化时刷新合并标记与已保存列表
    LaunchedEffect(historyList) {
        if (savedEntriesState.isNotEmpty() || scanNetworksState.isNotEmpty()) {
            scanNetworksState = mergeMarks(scanNetworksState)
        }
    }

    return remember {
        object : ManagerController {
            override val scanNetworks get() = scanNetworksState
            override val scanLoading get() = scanLoadingState
            override val scanSource get() = scanSourceState
            override val scanErrorKey get() = scanErrorKeyState
            override fun refreshScan() = performScan()

            override val savedEntries get() = savedEntriesState
            override val savedLoading get() = savedLoadingState
            override val savedSource get() = savedSourceState
            override fun refreshSaved() = performSaved()
            override fun connectSaved(entry: SavedNetworkEntry) {
                scope.launch {
                    try {
                        val channel = channelOrder().first()
                        when {
                            entry.networkId >= 0 -> when (channel) {
                                1 -> ShizukuUtil.enableNetwork(entry.networkId)
                                2 -> AidlServiceHelper.enableNetwork(app, entry.networkId)
                                else -> ApiUtil.enableNetwork(context, entry.networkId)
                            }
                            entry.password.isNotEmpty() -> when (channel) {
                                1 -> ShizukuUtil.connectToWifi(entry.ssid, entry.password)
                                2 -> AidlServiceHelper.connectToWifi(app, entry.ssid, entry.password)
                                else -> ApiUtil.connectToWifiApi28(context, entry.ssid, entry.password)
                            }
                        }
                        opMessageState = "✓ ${entry.ssid}"
                    } catch (e: Exception) {
                        opMessageState = "✗ ${e.message}"
                    }
                    readCurrent()
                }
            }
            override fun forgetSaved(entry: SavedNetworkEntry) {
                scope.launch {
                    try {
                        val channel = channelOrder().first()
                        val ok = when (channel) {
                            1 -> {
                                ShizukuUtil.forgetNetwork(entry.networkId); true
                            }
                            2 -> {
                                AidlServiceHelper.forgetNetwork(app, entry.networkId); true
                            }
                            else -> ApiUtil.forgetNetwork(context, entry.networkId)
                        }
                        opMessageState = (if (ok) "✓ " else "✗ ") + entry.ssid
                    } catch (e: Exception) {
                        opMessageState = "✗ ${e.message}"
                    }
                    performSaved()
                }
            }
            override fun deletePojieRecord(ssid: String) {
                app.pojieHistory.deleteHistory(ssid)
                scope.launch { performSaved() }
            }
            override val opMessage get() = opMessageState
            override fun clearOpMessage() { opMessageState = null }

            override val currentInfo get() = currentInfoState
            override fun refreshCurrent() = readCurrent()
            override val diagnosing get() = diagnosingState
            override val diagnosisResults get() = diagnosisResultsState
            override fun runDiagnosis() {
                if (diagnosing) return
                scope.launch {
                    diagnosingState = true
                    diagnosisResultsState = emptyList()
                    try {
                        val healer = WifiHealer(context.applicationContext, app)
                        // 诊断全开：HTTP 204 + DNS + ICMP + VALIDATED
                        val probeSettings = GuardSettings(
                            probeModes = GuardSettings.PROBE_HTTP_204 or
                                    GuardSettings.PROBE_DNS or
                                    GuardSettings.PROBE_ICMP or
                                    GuardSettings.PROBE_VALIDATED,
                            probeTimeoutMs = 4000
                        )
                        val verdict = NetProber.probe(context, probeSettings) { cmd ->
                            healer.shellExec(cmd)
                        }
                        diagnosisResultsState = verdict.results
                    } catch (_: Exception) {
                    }
                    diagnosingState = false
                }
            }
        }
    }
}

/** DhcpInfo 的 int IP → 点分十进制 */
internal fun intToIp(value: Int): String {
    if (value == 0) return ""
    return buildString {
        append(value and 0xFF).append('.')
        append(value shr 8 and 0xFF).append('.')
        append(value shr 16 and 0xFF).append('.')
        append(value shr 24 and 0xFF)
    }
}
