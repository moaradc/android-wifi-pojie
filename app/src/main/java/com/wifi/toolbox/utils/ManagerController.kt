@file:Suppress("DEPRECATION")

package com.wifi.toolbox.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import androidx.compose.runtime.*
import com.wifi.toolbox.R
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
    val security: String = "",          // 加密类型摘要（OPEN/WPA2/WPA3/…）
    val hasPojieRecord: Boolean = false,// 是否存在本应用破解成功记录（含已同时入系统配置的）
    val pojieTime: Long = 0L            // 最近一次破解成功时间（「最近破解优先」排序用）
)

/** 当前连接网络详情（Tab3 展示模型，免定位字段优先） */
data class CurrentNetworkInfo(
    val connected: Boolean,
    val ssid: String,                   // WifiIdentity 三级解析（定位无关兜底）
    val netId: Int,                     // 当前连接的 networkId（定位关闭时经特权通道解析，不可得为 -1）
    val bssid: String,                  // 受定位开关限制，可能为空
    val rssi: Int,                      // dBm，免定位
    val linkSpeedMbps: Int,             // 协商速率，免定位
    val frequencyMhz: Int,              // 免定位
    val ipAddress: String,
    val gateway: String,
    val dnsServers: List<String>,
    val dhcpServer: String,
    val leaseDurationSec: Int,
    val validated: Boolean,             // 系统最近一次网络验证结论（WiFi）
    // ---- 移动数据网络（全部免权限 API：ConnectivityManager 能力位 + 运营商名） ----
    val mobileConnected: Boolean = false,   // 蜂窝网络是否已连接（TRANSPORT_CELLULAR + INTERNET）
    val mobileValidated: Boolean = false,   // 蜂窝系统验证结论（NET_CAPABILITY_VALIDATED）
    val mobileRoaming: Boolean = false,     // 是否漫游（NET_CAPABILITY_NOT_ROAMING 取反）
    val mobileCarrier: String = ""          // 运营商名（networkOperatorName 兜底 simOperatorName）
)

/** 网络页多网络条目（WiFi 与移动数据统一模型）
 *
 * 一般用户同一时刻只连一个网络（WiFi 或移动数据），但两者可同开，
 * 个别系统（双 STA）甚至能同时连接多个 WiFi——网络页按「一卡一网」
 * 全部列出；多于一个已连接网络时卡片自动折叠分割线下方的详情。
 */
data class NetworkEntry(
    val isWifi: Boolean,                // true=WiFi 网络；false=移动数据网络
    val connected: Boolean,             // WiFi 占位卡（未连接）为 false
    val handle: Long,                   // networkHandle（展开状态记忆与列表 key）
    val title: String,                  // WiFi: SSID；移动数据: 运营商名
    val bssid: String = "",             // WiFi 专属（可能被系统匿名化为 02:00:…）
    val validated: Boolean = false,     // 系统验证结论（NET_CAPABILITY_VALIDATED）
    val portal: Boolean = false,        // 需网页认证（NET_CAPABILITY_CAPTIVE_PORTAL）
    // ---- WiFi 专属 ----
    val rssi: Int = -200,
    val linkSpeedMbps: Int = -1,
    val frequencyMhz: Int = 0,
    val ipAddress: String = "",
    val gateway: String = "",
    val dnsServers: List<String> = emptyList(),
    val dhcpServer: String = "",
    val leaseDurationSec: Int = 0,
    // ---- 移动数据专属 ----
    val carrier: String = "",           // 运营商名（title 为空时兼作标题）
    val roaming: Boolean = false
)

/** WifiInfo.getBSSID() 在定位服务关闭等场景返回的匿名化占位 MAC（非真实 BSSID） */
const val ANONYMIZED_BSSID = "02:00:00:00:00:00"

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

/** 已保存配置是否为开放（无密码）网络：未设置任何密钥管理，或仅 NONE */
private fun isOpenNetwork(cfg: WifiConfiguration): Boolean = try {
    val akm = cfg.allowedKeyManagement
    akm.cardinality() == 0 ||
            (akm.cardinality() == 1 && akm.get(WifiConfiguration.KeyMgmt.NONE))
} catch (_: Exception) {
    false
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
    val connectingSsid: String?         // 正在连接中的 SSID（按钮置 loading）
    val opMessage: String?              // 最近一次操作结果（连接/忘记）
    fun clearOpMessage()

    // ---- Tab3 当前网络 ----
    val currentInfo: CurrentNetworkInfo
    val networkEntries: List<NetworkEntry>   // 全部已连接网络（WiFi 可多个 + 移动数据）
    fun refreshCurrent()
    val diagnosing: Boolean
    val diagnosisResults: List<com.wifi.toolbox.utils.ProbeResult>
    fun runDiagnosis()

    // ---- 认证网络（Captive Portal）检测 ----
    /** 刚连接成功且被系统判定需要网页认证的 SSID（非空即弹窗提示） */
    val portalSsid: String?
    fun clearPortalSsid()

    /** 指定网络是否为当前连接（SSID 或 netId 双匹配，定位关闭时 netId 经特权解析） */
    fun isCurrentNetwork(ssid: String, networkId: Int): Boolean
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
    var connectingSsidState by remember { mutableStateOf<String?>(null) }

    // ---- Tab3 状态 ----
    var currentInfoState by remember {
        mutableStateOf(CurrentNetworkInfo(false, "", -1, "", 0, 0, 0, "", "", emptyList(), "", 0, false))
    }
    var diagnosingState by remember { mutableStateOf(false) }
    var diagnosisResultsState by remember { mutableStateOf<List<ProbeResult>>(emptyList()) }

    // ---- Tab3 多网络列表 + 认证网络检测 ----
    var networkEntriesState by remember { mutableStateOf<List<NetworkEntry>>(emptyList()) }
    var portalSsidState by remember { mutableStateOf<String?>(null) }
    var portalJob by remember { mutableStateOf<Job?>(null) }

    // ---- 当前 WiFi 身份会话缓存（networkHandle 变化 = 新连接会话） ----
    var identitySsidState by remember { mutableStateOf("") }
    var identityNetIdState by remember { mutableIntStateOf(-1) }
    var identityHandleState by remember { mutableLongStateOf(0L) }
    var lastIdentityResolveAt by remember { mutableLongStateOf(0L) }
    var identityJob by remember { mutableStateOf<Job?>(null) }
    var retryReadJob by remember { mutableStateOf<Job?>(null) }
    var liveRefreshJob by remember { mutableStateOf<Job?>(null) }

    val badSsids = setOf("<unknown ssid>", "<none>", "unknown ssid", "0x")

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

    /**
     * 重读已保存列表（挂起版：供忘记网络后的轮询确认复用）。
     *
     * [silent] = true 时不切换 loading 状态（轮询期间反复闪转圈会打扰阅读），
     * 仅更新列表内容。
     */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun performSavedNow(silent: Boolean) {
        if (!silent) savedLoadingState = true
        try {
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

            // 破解成功记录（本地库，无任何权限要求）。
            // 直读 StateFlow.value：compose 状态首帧可能尚未传播首次发射，
            // 历史上导致破解记录偶发不显示。
            val historyNow = app.pojieHistory.historyFlow.value
            val cracked = historyNow.filter { !it.password.isNullOrEmpty() }
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
                        fromSystem = true,
                        security = if (isOpenNetwork(cfg)) "OPEN" else "",
                        hasPojieRecord = hist != null,
                        pojieTime = hist?.lasttime ?: 0L
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
                            fromSystem = false,
                            hasPojieRecord = true,
                            pojieTime = h.lasttime
                        )
                    )
                }
            }
            savedEntriesState = entries
        } finally {
            if (!silent) savedLoadingState = false
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun performSaved() {
        scope.launch { performSavedNow(silent = false) }
    }

    /** API 29+ 的 per-network WifiInfo（双 STA 时区分主/次 WiFi；低版本无此机制） */
    fun perNetworkWifiInfo(caps: NetworkCapabilities): android.net.wifi.WifiInfo? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null
        return try {
            caps.transportInfo as? android.net.wifi.WifiInfo
        } catch (_: Exception) {
            null
        }
    }

    /** 主 WiFi 条目直接镜像 currentInfoState（全部字段已解析好，避免重复逻辑） */
    fun primaryEntryFromCurrent(cm: ConnectivityManager): NetworkEntry {
        val info = currentInfoState
        val handle = try {
            cm.allNetworks.firstOrNull { n ->
                try {
                    cm.getNetworkCapabilities(n)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                } catch (_: Exception) {
                    false
                }
            }?.networkHandle ?: 0L
        } catch (_: Exception) {
            0L
        }
        return NetworkEntry(
            isWifi = true, connected = info.connected,
            handle = handle,
            title = info.ssid, bssid = info.bssid,
            validated = info.validated,
            rssi = info.rssi, linkSpeedMbps = info.linkSpeedMbps,
            frequencyMhz = info.frequencyMhz,
            ipAddress = info.ipAddress, gateway = info.gateway,
            dnsServers = info.dnsServers, dhcpServer = info.dhcpServer,
            leaseDurationSec = info.leaseDurationSec
        )
    }

    /**
     * 枚举全部已连接网络（免权限 API）：
     * - WiFi：TRANSPORT_WIFI + INTERNET，个别系统（双 STA）可同时多个；
     *   主 WiFi 复用 [readCurrent] 已解析的身份/详情（SSID 特权解析、DHCP 兑底等），
     *   其余 WiFi 经 per-network TransportInfo（API 29+）+ LinkProperties 读取；
     * - 移动数据：TRANSPORT_CELLULAR + INTERNET。
     * WiFi 未连接时补一张占位卡（如实展示「未连接」，而非隐藏整个区域）。
     */
    fun buildNetworkEntries(
        cm: ConnectivityManager,
        wifiConnected: Boolean,
        primarySsid: String,
        primaryNetId: Int,
        carrier: String
    ): List<NetworkEntry> {
        val entries = mutableListOf<NetworkEntry>()

        // 全部 WiFi 网络（带 INTERNET 能力位，排除 P2P/测试网络）
        val wifiNets: List<Pair<Network, NetworkCapabilities>> = try {
            cm.allNetworks.mapNotNull { n ->
                try {
                    val c = cm.getNetworkCapabilities(n)
                    if (c != null &&
                        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ) n to c else null
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }

        // 主 WiFi 判定（与 wm.connectionInfo 对齐）：netId 匹配 > SSID 匹配 > 第一个
        val primaryIndex = wifiNets.indexOfFirst { (_, c) ->
            val ti = perNetworkWifiInfo(c)
            when {
                primaryNetId >= 0 && ti != null ->
                    try { ti.networkId == primaryNetId } catch (_: Exception) { false }
                primarySsid.isNotEmpty() && ti != null ->
                    ti.ssid?.removeSurrounding("\"") == primarySsid
                else -> false
            }
        }.let { if (it >= 0) it else 0 }

        if (wifiNets.isEmpty()) {
            if (wifiConnected) {
                // 连接中但能力位尚无 INTERNET（瞬时态）：仍以 currentInfo 展示主 WiFi
                entries += primaryEntryFromCurrent(cm)
            } else {
                // WiFi 未连接占位卡（无分割线下方详情）
                entries += NetworkEntry(isWifi = true, connected = false, handle = 0L, title = "")
            }
        } else {
            wifiNets.forEachIndexed { idx, (net, caps) ->
                val handle = try { net.networkHandle } catch (_: Exception) { 0L }
                if (idx == primaryIndex) {
                    // 主 WiFi：复用 currentInfo 的完整解析结果（身份/DHCP/验证结论）
                    entries += primaryEntryFromCurrent(cm).copy(
                        handle = handle,
                        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        portal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                    )
                } else {
                    // 次 WiFi（双 STA）：per-network TransportInfo + LinkProperties
                    val ti = perNetworkWifiInfo(caps)
                    val lp = try { cm.getLinkProperties(net) } catch (_: Exception) { null }
                    val ssid = ti?.ssid?.removeSurrounding("\"")
                        ?.takeIf { it.isNotEmpty() && it !in badSsids } ?: ""
                    entries += NetworkEntry(
                        isWifi = true, connected = true, handle = handle,
                        title = ssid, bssid = try { ti?.bssid.orEmpty() } catch (_: Exception) { "" },
                        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        portal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                        rssi = try { ti?.rssi ?: -200 } catch (_: Exception) { -200 },
                        linkSpeedMbps = try { ti?.linkSpeed ?: -1 } catch (_: Exception) { -1 },
                        frequencyMhz = try { ti?.frequency ?: 0 } catch (_: Exception) { 0 },
                        ipAddress = lp?.linkAddresses
                            ?.firstOrNull { it.address is java.net.Inet4Address }
                            ?.address?.hostAddress ?: "",
                        gateway = lp?.routes?.firstNotNullOfOrNull { it.gateway?.hostAddress } ?: "",
                        dnsServers = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
                    )
                }
            }
        }

        // 全部移动数据网络（一般 0 或 1 个，多卡场景如实全部列出）
        val cellNets: List<Pair<Network, NetworkCapabilities>> = try {
            cm.allNetworks.mapNotNull { n ->
                try {
                    val c = cm.getNetworkCapabilities(n)
                    if (c != null &&
                        c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    ) n to c else null
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
        cellNets.forEach { (net, caps) ->
            entries += NetworkEntry(
                isWifi = false, connected = true,
                handle = try { net.networkHandle } catch (_: Exception) { 0L },
                title = carrier,
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                portal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                carrier = carrier,
                roaming = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) != true
            )
        }
        return entries
    }

    /**
     * 连接成功后的认证网络（Captive Portal）检测：系统 NetworkMonitor
     * 判定需网页认证会给网络打上 NET_CAPABILITY_CAPTIVE_PORTAL 能力位
     * （即系统通知栏「登录到网络」的同一信号源）。连接后轮询约 16 秒：
     * 命中 → [portalSsidState] 置为目标 SSID，UI 弹窗说明并引导跳系统
     * WiFi 设置；期间网络已验证（正常网络）或断开则静默结束。
     */
    fun startPortalCheck(ssid: String) {
        portalJob?.cancel()
        portalJob = scope.launch {
            try {
                repeat(9) {
                    delay(1800)
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                            as ConnectivityManager
                    val caps = try {
                        NetProber.findWifiNetwork(cm)
                            ?.let { cm.getNetworkCapabilities(it) }
                    } catch (_: Exception) {
                        null
                    } ?: return@launch          // WiFi 已断开
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                        portalSsidState = ssid
                        return@launch
                    }
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        return@launch            // 正常可上网网络
                    }
                }
            } catch (_: Exception) {
            }
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

        val connected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        // networkHandle 每次连接重新分配（与定位开关无关）：句柄变化即新会话
        val handle = try { wifiNetwork?.networkHandle ?: 0L } catch (_: Exception) { 0L }

        // 应用层直读（定位可用时最快最准）
        val quickSsid = try {
            info?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() && it !in badSsids } ?: ""
        } catch (_: Exception) { "" }
        val quickNetId = try { info?.networkId ?: -1 } catch (_: Exception) { -1 }

        if (!connected) {
            identitySsidState = ""
            identityNetIdState = -1
            identityHandleState = 0L
        } else if (handle != 0L && handle != identityHandleState) {
            // 新 WiFi 会话：清旧身份，异步经特权通道解析（定位关闭时唯一途径）
            identitySsidState = ""
            identityNetIdState = -1
            if (System.currentTimeMillis() - lastIdentityResolveAt > 3000) {
                lastIdentityResolveAt = System.currentTimeMillis()
                identityHandleState = handle
                identityJob?.cancel()
                identityJob = scope.launch {
                    val (s, n) = WifiIdentity.resolve(context.applicationContext, app)
                    if (identityHandleState == handle) {
                        identitySsidState = s
                        identityNetIdState = n
                    }
                }
            } else {
                // 节流期内的新会话：稍后自动重读一次，防止身份停留空值
                retryReadJob?.cancel()
                retryReadJob = scope.launch {
                    delay(3200)
                    readCurrent()
                }
            }
        }

        val cacheValid = connected && handle != 0L && handle == identityHandleState
        val ip = lp?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }
            ?.address?.hostAddress ?: intToIp(dhcp?.ipAddress ?: 0)
        val gateway = lp?.routes?.firstNotNullOfOrNull { it.gateway?.hostAddress }
            ?: intToIp(dhcp?.gateway ?: 0)
        val dns = (lp?.dnsServers?.mapNotNull { it.hostAddress } ?: buildList {
            if (dhcp != null && dhcp.dns1 != 0) add(intToIp(dhcp.dns1))
            if (dhcp != null && dhcp.dns2 != 0) add(intToIp(dhcp.dns2))
        }).distinct()

        // ---- 移动数据网络（全部免权限 API，异常一律降级为空值不影响 WiFi 展示） ----
        // 蜂窝 Network：TRANSPORT_CELLULAR + INTERNET 能力位（无需任何权限）
        val mobileCaps = try {
            cm.allNetworks.firstNotNullOfOrNull { n ->
                try {
                    cm.getNetworkCapabilities(n)?.takeIf {
                        it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    }
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) { null }
        // 运营商名：networkOperatorName（当前注册网络）优先，simOperatorName 兜底；
        // 两者均无需 READ_PHONE_STATE，未插 SIM / 未注册时为空
        val mobileCarrier = try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE)
                    as? android.telephony.TelephonyManager
            (tm?.networkOperatorName?.takeIf { it.isNotBlank() }
                    ?: tm?.simOperatorName.orEmpty())
        } catch (_: Exception) { "" }

        currentInfoState = CurrentNetworkInfo(
            connected = connected,
            ssid = quickSsid.ifEmpty { if (cacheValid) identitySsidState else "" },
            netId = if (quickNetId >= 0) quickNetId else if (cacheValid) identityNetIdState else -1,
            bssid = try { info?.bssid.orEmpty() } catch (_: Exception) { "" },
            rssi = try { info?.rssi ?: -200 } catch (_: Exception) { -200 },
            linkSpeedMbps = try { info?.linkSpeed ?: -1 } catch (_: Exception) { -1 },
            frequencyMhz = try { info?.frequency ?: 0 } catch (_: Exception) { 0 },
            ipAddress = ip,
            gateway = gateway,
            dnsServers = dns,
            dhcpServer = if (dhcp != null && dhcp.serverAddress != 0) intToIp(dhcp.serverAddress) else "",
            leaseDurationSec = dhcp?.leaseDuration ?: 0,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            mobileConnected = mobileCaps != null,
            mobileValidated = mobileCaps
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            mobileRoaming = mobileCaps != null &&
                    mobileCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) != true,
            mobileCarrier = mobileCarrier
        )

        // ---- 网络页多网络列表：一卡一网，WiFi（可多个）+ 移动数据同时列出 ----
        networkEntriesState = buildNetworkEntries(
            cm, connected, quickSsid, quickNetId, mobileCarrier
        )
    }

    /** 实时监听 WiFi 状态变化：广播 + NetworkCallback 双通道，防抖 500ms 后重读。
     *
     * 历史缺陷：currentInfo 仅在进入页面/手动刷新时读取——系统开关 WiFi 重连后
     * 已保存页「当前」高亮不更新。 */
    fun scheduleLiveRefresh() {
        liveRefreshJob?.cancel()
        liveRefreshJob = scope.launch {
            delay(500)
            readCurrent()
        }
    }

    DisposableEffect(Unit) {
        val intentFilter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                scheduleLiveRefresh()
            }
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleLiveRefresh()
            }

            override fun onLost(network: Network) {
                scheduleLiveRefresh()
            }
        }
        // 蜂窝独立回调实例（同一 NetworkCallback 对象不允许注册两个请求）
        val cellularCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scheduleLiveRefresh()
            }

            override fun onLost(network: Network) {
                scheduleLiveRefresh()
            }
        }
        // targetSdk=28：系统广播注册无需 RECEIVER_EXPORTED/NOT_EXPORTED 标志
        @Suppress("UnspecifiedRegisterReceiverFlag")
        try {
            context.registerReceiver(receiver, intentFilter)
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                networkCallback
            )
            // 蜂窝网络回调：网络页移动数据行实时刷新（连接/断开/验证状态变化）
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                cellularCallback
            )
        } catch (_: Exception) {
        }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            try { cm.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
            try { cm.unregisterNetworkCallback(cellularCallback) } catch (_: Exception) {}
        }
    }

    // 首次进入各 Tab 的懒加载由 UI 层 LaunchedEffect 触发
    LaunchedEffect(Unit) {
        readCurrent()
    }
    // 破解历史变化：刷新扫描列表合并标记；破解成功集合变化时重读已保存列表
    // （直接监听 historyList 会因每次尝试都写进度而频繁触发特权读取，故仅
    // 监听「破解成功 SSID 集合」的变化）
    var lastCrackedKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(historyList) {
        if (scanNetworksState.isNotEmpty()) {
            scanNetworksState = mergeMarks(scanNetworksState)
        }
        val key = historyList
            .filter { !it.password.isNullOrEmpty() }
            .map { it.ssid }
            .sorted()
            .joinToString("|")
        if (lastCrackedKey != null && key != lastCrackedKey) {
            performSaved()
        }
        lastCrackedKey = key
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
            override val connectingSsid get() = connectingSsidState

            /**
             * 连接已保存网络——完整生命周期：
             * 1. WiFi 关闭时先开启并等待就绪（历史缺陷：直接下发连接命令，
             *    命令静默失败却总提示「√」）；
             * 2. 下发连接（系统配置 networkId 优先，仅破解记录时用密码新建配置）；
             * 3. 轮询验证真实连接结果（SSID/netId 双匹配，定位关闭时经特权
             *    通道解析；历史缺陷：请求发出即报成功）；
             * 4. 范围外早失败：约 7s 后用扫描结果预检，目标不在附近且尚未连上
             *    则明确报「不在范围内」，不必等满 20s；
             * 5. 超时给出失败原因（不在范围内 / 密码已更改）。
             */
            override fun connectSaved(entry: SavedNetworkEntry) {
                if (connectingSsidState != null) return
                scope.launch {
                    connectingSsidState = entry.ssid
                    opMessageState = context.getString(R.string.mgr_connecting, entry.ssid)
                    var finalMsg: String? = null
                    try {
                        // 1) WiFi 关闭 → 先开启并等待就绪
                        if (!ApiUtil.isWifiEnabled(context)) {
                            try {
                                when (channelOrder().first()) {
                                    1 -> ShizukuUtil.setWifiEnabled(true)
                                    2 -> AidlServiceHelper.setWifiEnabled(app, true)
                                    else -> ApiUtil.setWifiEnabled(context, true)
                                }
                            } catch (_: Exception) {
                                try { ApiUtil.setWifiEnabled(context, true) } catch (_: Exception) {}
                            }
                            var wifiOn = false
                            var waited = 0
                            while (waited < 10000) {
                                if (ApiUtil.isWifiEnabled(context)) {
                                    wifiOn = true; break
                                }
                                delay(400)
                                waited += 400
                            }
                            if (!wifiOn) {
                                finalMsg = context.getString(R.string.mgr_connect_fail_wifi)
                                return@launch
                            }
                            // WiFi 刚开启，系统需要时间初始化 WiFi 栈
                            delay(1000)
                        }

                        // 2) 下发连接请求
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

                                // 未保存的开放网络：无需密码，三通道 connectToWifi
                                // 空密码均构建 OPEN 配置（allowedKeyManagement 置位 0）
                                entry.security == "OPEN" -> when (channel) {
                                    1 -> ShizukuUtil.connectToWifi(entry.ssid, "")
                                    2 -> AidlServiceHelper.connectToWifi(app, entry.ssid, "")
                                    else -> ApiUtil.connectToWifiApi28(context, entry.ssid, "")
                                }

                                else -> {
                                    finalMsg = context.getString(R.string.mgr_connect_no_password)
                                    return@launch
                                }
                            }
                        } catch (e: Exception) {
                            finalMsg = context.getString(R.string.mgr_connect_fail_request, e.message ?: "?")
                            return@launch
                        }

                        // 3~5) 等待并验证真实连接结果
                        finalMsg = awaitConnectionResult(entry)
                    } finally {
                        connectingSsidState = null
                        if (finalMsg != null) opMessageState = finalMsg
                        readCurrent()
                        performSaved()
                        // 连接成功（目标已成当前网络）→ 异步检测认证网络，
                        // 命中 NET_CAPABILITY_CAPTIVE_PORTAL 时弹窗说明并引导
                        // 跳系统 WiFi 设置完成网页认证
                        if (isCurrentNetwork(entry.ssid, entry.networkId)) {
                            startPortalCheck(entry.ssid)
                        }
                    }
                }
            }

            /** 轮询等待连接到目标网络；返回结果文案 */
            private suspend fun awaitConnectionResult(entry: SavedNetworkEntry): String {
                val timeoutMs = 20000L
                val startAt = System.currentTimeMillis()
                var scanTriggered = false
                var rangeChecked = false
                var lastPrivilegedAt = 0L
                while (true) {
                    if (!ApiUtil.isWifiEnabled(context)) {
                        return context.getString(R.string.mgr_connect_fail_offline)
                    }
                    // 快速校验：应用层 WifiInfo（定位可用时 SSID/netId 均可靠）
                    try {
                        val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                        val info = wm.connectionInfo
                        val s = info?.ssid?.removeSurrounding("\"")
                            ?.takeIf { it.isNotEmpty() && it !in badSsids }
                        if (s == entry.ssid) {
                            return context.getString(R.string.mgr_connect_ok, entry.ssid)
                        }
                        val n = try { info?.networkId ?: -1 } catch (_: Exception) { -1 }
                        if (n >= 0 && entry.networkId >= 0 && n == entry.networkId) {
                            return context.getString(R.string.mgr_connect_ok, entry.ssid)
                        }
                    } catch (_: Exception) {
                    }
                    // 特权解析（定位关闭时唯一途径，3s 节流）
                    if (System.currentTimeMillis() - lastPrivilegedAt > 3000) {
                        lastPrivilegedAt = System.currentTimeMillis()
                        try {
                            val (s, n) = WifiIdentity.resolve(context.applicationContext, app)
                            if (s == entry.ssid ||
                                (n >= 0 && entry.networkId >= 0 && n == entry.networkId)
                            ) {
                                return context.getString(R.string.mgr_connect_ok, entry.ssid)
                            }
                        } catch (_: Exception) {
                        }
                    }
                    val elapsed = System.currentTimeMillis() - startAt
                    // 范围外预检：3s 触发一次扫描，7s 读结果——不在附近且未连上 → 早失败
                    if (!scanTriggered && elapsed > 3000) {
                        scanTriggered = true
                        try {
                            when (channelOrder().first()) {
                                1 -> ShizukuUtil.startWifiScan(false)
                                2 -> AidlServiceHelper.startWifiScan(app, false)
                                else -> ApiUtil.startScan(context)
                            }
                        } catch (_: Exception) {
                        }
                    }
                    if (!rangeChecked && elapsed > 7000) {
                        rangeChecked = true
                        try {
                            val scans = when (channelOrder().first()) {
                                1 -> ShizukuUtil.getWifiScanResults()
                                2 -> AidlServiceHelper.getWifiScanResults(app)
                                else -> if (ApiUtil.hasLocationPermission(context))
                                    ApiUtil.getScanResults(context) else emptyList()
                            }
                            if (scans.isNotEmpty() && scans.none { it.ssid == entry.ssid }) {
                                return context.getString(R.string.mgr_connect_fail_range, entry.ssid)
                            }
                        } catch (_: Exception) {
                        }
                    }
                    if (elapsed >= timeoutMs) {
                        return context.getString(R.string.mgr_connect_fail_timeout)
                    }
                    delay(800)
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
                    // 历史缺陷：系统侧删除是异步生效的，忘记后立即重读常仍读到旧
                    // 配置，列表不更新（需切换页面才刷新）。轮询重读确认移除——
                    // 目标 SSID 不再以「系统保存」身份出现即完成；同 SSID 若还有
                    // 破解记录会保留为纯记录条目（按钮变为「删除记录」），属预期。
                    // silent 轮询不闪 loading。最长约 4.2s。
                    repeat(6) { attempt ->
                        delay(if (attempt == 0) 500L else 700L)
                        performSavedNow(silent = true)
                        val stillThere = savedEntriesState.any {
                            it.ssid == entry.ssid && it.fromSystem
                        }
                        if (!stillThere) return@launch
                    }
                }
            }
            override fun deletePojieRecord(ssid: String) {
                app.pojieHistory.deleteHistory(ssid)
                scope.launch { performSaved() }
            }
            override val opMessage get() = opMessageState
            override fun clearOpMessage() { opMessageState = null }

            override val currentInfo get() = currentInfoState
            override val networkEntries get() = networkEntriesState
            override fun refreshCurrent() = readCurrent()
            override val diagnosing get() = diagnosingState
            override val diagnosisResults get() = diagnosisResultsState
            override val portalSsid get() = portalSsidState
            override fun clearPortalSsid() {
                portalJob?.cancel()
                portalSsidState = null
            }
            override fun isCurrentNetwork(ssid: String, networkId: Int): Boolean {
                val info = currentInfoState
                if (!info.connected) return false
                if (info.ssid.isNotEmpty() && info.ssid == ssid) return true
                return info.netId >= 0 && networkId >= 0 && info.netId == networkId
            }
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
