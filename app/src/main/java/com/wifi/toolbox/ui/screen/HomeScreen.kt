package com.wifi.toolbox.ui.screen

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.ui.LocalNavTarget
import com.wifi.toolbox.ui.items.TagItem
import com.wifi.toolbox.utils.WifiIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class NetworkState(
    val wifiSsid: String,
    val ipList: List<IpInfo> = emptyList(),
    val isWifiConnected: Boolean = false
)

/**
 * 首页 WiFi 网络名解析缓存（跨重组/多次网络回调保留）。
 *
 * 【会话级缓存】缓存的生命周期 = 本次 WiFi 连接会话。两个失效信号：
 * 1. WiFi 断开（caps 无 WIFI transport）→ 清空；
 * 2. WiFi 网络会话句柄变化（[wifiNetHandle]）→ 切换网络/断开重连，旧名作废。
 *
 * 【为什么要会话句柄】Android 9+ 定位服务关闭时应用层 WifiInfo 被
 * WifiInfo.makeCopy 屏蔽：SSID 变 <unknown ssid> 且【networkId 同时变 -1】
 * （AOSP：mNetworkId = shouldRedactLocationSensitiveFields ? INVALID_NETWORK_ID）。
 * 定位关闭期间切换 WiFi 时 netId 匹配永远落空——而 Network.getNetworkHandle()
 * 每次连接都重新分配，与定位开关无关，是唯一可靠的会话边界信号。
 *
 * 解析优先级：① 应用层 WifiInfo 直读（受定位开关限制）
 * → ② 会话内缓存沿用 → ③ 特权命令解析（cmd wifi status →
 * dumpsys wifi，遵循「执行通道」设置；缓存空/60s 过期/新会话时尝试，
 * 10 秒节流；新会话会重置节流计时——切换网络是低频重要事件，必须立即解析）。
 */
private object HomeWifiCache {
    @Volatile var ssid: String = ""
    @Volatile var netId: Int = -1
    @Volatile var wifiNetHandle: Long = -1L  // WiFi 网络会话句柄（-1=未知）
    @Volatile var lastShellTryAt: Long = 0L   // 特权命令解析节流（网络回调高频）
    @Volatile var lastResolvedAt: Long = 0L   // 上次成功解析时刻（60s 过期刷新用）

    fun clear() {
        ssid = ""
        netId = -1
        wifiNetHandle = -1L
        lastResolvedAt = 0L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ToolboxApp
    val navTarget = LocalNavTarget.current
    val scrollState = rememberScrollState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    val isDark = MaterialTheme.colorScheme.background.run {
        val luminance = (red * 0.2126f + green * 0.7152f + blue * 0.0722f)
        luminance < 0.5f
    }

    val networkState by produceState(initialValue = NetworkState(stringResource(R.string.not_connected))) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // 串行化多次网络回调触发的刷新，防止慢的特权解析结果乱序回写 UI
        val updateMutex = Mutex()

        suspend fun update() {
            val newState = withContext(Dispatchers.IO) {
                val wifiManager =
                    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val activeNetwork = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(activeNetwork)

                var ssid = context.getString(R.string.not_connected)
                var isWifi = false

                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        isWifi = true
                        val info = wifiManager.connectionInfo
                        val rawSsid = info?.ssid?.trim('"')
                        val netId = info?.networkId ?: -1
                        // WiFi 网络会话句柄：每次连接重新分配，与定位开关无关。
                        // 变化 = 切换网络或断开重连 → 旧网名作废，立即重新解析
                        val sessionHandle = try {
                            activeNetwork?.networkHandle ?: -1L
                        } catch (_: Exception) { -1L }
                        val newSession =
                            sessionHandle != -1L && sessionHandle != HomeWifiCache.wifiNetHandle
                        // ① 优先：应用层 WifiInfo 直读（现版本方式）
                        var resolved =
                            if (!rawSsid.isNullOrEmpty() && rawSsid != "<unknown ssid>") rawSsid else ""
                        if (resolved.isNotEmpty()) {
                            HomeWifiCache.ssid = resolved
                            HomeWifiCache.netId = netId
                            HomeWifiCache.wifiNetHandle = sessionHandle
                            HomeWifiCache.lastResolvedAt = System.currentTimeMillis()
                        } else {
                            // 换网检测1（定位开启时）：netId 可用，与缓存不一致即已切换网络
                            if (netId >= 0 && HomeWifiCache.netId >= 0 &&
                                HomeWifiCache.netId != netId
                            ) {
                                HomeWifiCache.clear()
                            }
                            // 换网检测2（任何状态）：会话句柄变化 = 切换/重连，
                            // 旧名属于上一个会话，不可信，作废重解析
                            if (newSession && HomeWifiCache.ssid.isNotEmpty()) {
                                HomeWifiCache.clear()
                            }
                            // ② 会话级缓存沿用：不要求 netId 匹配（定位关闭时 netId 被
                            // 屏蔽为 -1，按 netId 匹配会永远落空导致显示振荡）
                            if (HomeWifiCache.ssid.isNotEmpty()) {
                                resolved = HomeWifiCache.ssid
                            }
                            // ③ 特权命令解析：缓存为空 / 超过 60s 过期 / 新会话 时尝试；
                            // 新会话重置节流计时（低频重要事件，不受刚执行过的限制）
                            val now = System.currentTimeMillis()
                            val cacheEmpty = HomeWifiCache.ssid.isEmpty()
                            val cacheStale = !cacheEmpty &&
                                now - HomeWifiCache.lastResolvedAt > 60_000
                            if (newSession) {
                                HomeWifiCache.lastShellTryAt = 0L
                            }
                            if ((cacheEmpty || cacheStale) &&
                                now - HomeWifiCache.lastShellTryAt > 10_000
                            ) {
                                HomeWifiCache.lastShellTryAt = now
                                val (shellSsid, shellNetId) = WifiIdentity.resolve(
                                    context.applicationContext, app
                                )
                                if (shellSsid.isNotEmpty()) {
                                    // 命中（或换网刷新出新名）：更新缓存与显示
                                    HomeWifiCache.ssid = shellSsid
                                    HomeWifiCache.netId = shellNetId
                                    HomeWifiCache.wifiNetHandle = sessionHandle
                                    HomeWifiCache.lastResolvedAt = now
                                    resolved = shellSsid
                                }
                            }
                        }
                        // ④ 兜底：显示「WiFi 已连接」；拿到则「已连接到 xxx」
                        ssid = if (resolved.isEmpty())
                            context.getString(R.string.wifi_connected_generic)
                        else "${context.getString(R.string.wifi_connected_to)} $resolved"
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        ssid = context.getString(R.string.mobile_data)
                    }
                }
                // WiFi 已断开：清缓存，重连后按 ①→③ 重新解析（防残留旧网络名）
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    HomeWifiCache.clear()
                }
                NetworkState(ssid, getAllIpAddresses(), isWifi)
            }
            value = newState
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                launch { updateMutex.withLock { update() } }
            }

            override fun onLost(network: Network) {
                launch { updateMutex.withLock { update() } }
            }

            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) {
                launch { updateMutex.withLock { update() } }
            }

            override fun onLinkPropertiesChanged(network: Network, l: LinkProperties) {
                launch { updateMutex.withLock { update() } }
            }
        }

        updateMutex.withLock { update() }
        cm.registerDefaultNetworkCallback(callback)

        awaitDispose {
            cm.unregisterNetworkCallback(callback)
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column(modifier = Modifier.padding(0.dp, 8.dp)) {
                        Text(
                            text = stringResource(R.string.home),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            AnimatedVisibility(
                visible = true,
                enter = slideInVertically() + fadeIn()
            ) {
                InfoCard(networkState, isDark)
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                val isAidlAlive = app.aidl.ipc != null
                AnimatedVisibility(
                    visible = isAidlAlive,
                    enter = expandVertically(tween(300)) + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    AidlStatusCard(isDark)
                }

                Text(
                    text = stringResource(R.string.quick_actions),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeCardItem(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.nav_pojie),
                            icon = Icons.Rounded.VpnKey,
                            baseColor = Color(0xFFFFD8E4),
                            darkColor = Color(0xFF5E2A38),
                            contentColor = if (isDark) Color(0xFFFFD8E4) else Color(0xFF6E2838),
                            isDark = isDark,
                            onClick = { navTarget.value = "Pojie" }
                        )
                        HomeCardItem(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.nav_manager),
                            icon = Icons.Rounded.Dns,
                            baseColor = Color(0xFFFDE495),
                            darkColor = Color(0xFF4A3E15),
                            contentColor = if (isDark) Color(0xFFFDE495) else Color(0xFF5C4912),
                            isDark = isDark,
                            onClick = { navTarget.value = "Viewer" }
                        )
                    }

                    // 网络守护：横向卡片样式与现版本一致（绿色系），置于竖卡组下首位
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeCardItem(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.guard_name),
                            icon = Icons.Rounded.MonitorHeart,
                            baseColor = Color(0xFFD3F4DC),
                            darkColor = Color(0xFF1F3D28),
                            contentColor = if (isDark) Color(0xFFD3F4DC) else Color(0xFF2F6B3F),
                            isDark = isDark,
                            isHorizontal = true,
                            onClick = { navTarget.value = "Guard" }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeCardItem(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.nav_test),
                            icon = Icons.Rounded.Science,
                            baseColor = Color(0xFFF2D9FA),
                            darkColor = Color(0xFF3E1C4A),
                            contentColor = if (isDark) Color(0xFFF2D9FA) else Color(0xFF4A148C),
                            isDark = isDark,
                            isHorizontal = true,
                            onClick = { navTarget.value = "Test" }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HomeCardItem(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.nav_settings),
                            icon = Icons.Rounded.Settings,
                            baseColor = Color(0xFFFFE0C8),
                            darkColor = Color(0xFF4A2C20),
                            contentColor = if (isDark) Color(0xFFFFE0C8) else Color(0xFF5D4037),
                            isDark = isDark,
                            isHorizontal = true,
                            onClick = { navTarget.value = "Settings" }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun InfoCard(state: NetworkState, isDark: Boolean) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainer

    // 跟随总设置动态主题色/颜色种子（原硬编码蓝色不随主题变化）
    val textColor = MaterialTheme.colorScheme.onSurface
    val subTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val iconTint = MaterialTheme.colorScheme.primary

    val context = LocalContext.current

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.isWifiConnected) Icons.Rounded.Wifi else Icons.Rounded.SignalCellularAlt,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.network_status),
                    style = MaterialTheme.typography.labelLarge,
                    color = iconTint,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = state.wifiSsid,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.ipList.isEmpty()) {
                    Text(
                        stringResource(R.string.no_active_ip),
                        style = MaterialTheme.typography.bodyMedium,
                        color = subTextColor
                    )
                } else {
                    state.ipList.forEach { ip ->
                        Row(
                            modifier = Modifier
                                .padding(2.dp)
                                .clickable {
                                    copyToClipboard(context, ip.address)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Lan,
                                contentDescription = null,
                                tint = subTextColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = ip.address,
                                style = MaterialTheme.typography.bodyMedium,
                                color = subTextColor,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )
                            TagItem(ip.from)
                        }
                    }
                }
            }
        }
    }


}

@Composable
fun AidlStatusCard(isDark: Boolean) {
    val bgColor = if (isDark) Color(0xFF004D40).copy(alpha = 0.3f) else Color(0xFFE0F2F1)
    val titleColor = if (isDark) Color(0xFF80CBC4) else Color(0xFF00695C)
    val iconColor = if (isDark) Color(0xFF4DB6AC) else Color(0xFF00897B)


    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(R.string.aidl_service_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = titleColor
                )
                Text(
                    text = stringResource(R.string.aidl_service_running_tip),
                    style = MaterialTheme.typography.bodySmall,
                    color = titleColor.copy(alpha = 0.8f)
                )
            }
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(32.dp)
            )
        }
    }


}

@Composable
fun HomeCardItem(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    baseColor: Color,
    darkColor: Color,
    contentColor: Color,
    isDark: Boolean,
    isHorizontal: Boolean = false,
    onClick: () -> Unit
) {
    val finalBgColor = if (isDark) darkColor.copy(alpha = 0.4f) else baseColor


    Surface(
        onClick = onClick,
        color = finalBgColor,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.height(if (isHorizontal) 72.dp else 110.dp)
    ) {
        if (isHorizontal) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor
                    )
                }
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
            }
        }
    }


}

data class IpInfo(
    val address: String,
    val from: String
)

fun getAllIpAddresses(): List<IpInfo> {
    val ipList = mutableListOf<IpInfo>()
    try {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            if (!intf.isUp) continue


            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                val hostAddr = addr.hostAddress ?: continue

                if (addr is Inet4Address) {
                    ipList.add(IpInfo(hostAddr, intf.displayName ?: intf.name))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        return emptyList()
    }
    return ipList.sortedBy {
        when {
            it.address.startsWith("192") -> 0
            it.address.startsWith("10.") -> 1
            it.address.startsWith("172") -> 2
            it.address.startsWith("127") -> 9
            else -> 5
        }
    }

}

fun copyToClipboard(context: Context, text: String) {
    val clipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("", text)
    clipboard.setPrimaryClip(clip)
}