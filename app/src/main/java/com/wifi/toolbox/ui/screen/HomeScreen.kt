package com.wifi.toolbox.ui.screen

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.ui.LocalNavTarget
import com.wifi.toolbox.ui.items.TagItem
import com.wifi.toolbox.utils.WifiIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * 首页 WiFi 身份缓存（跨重组/多次网络回调保留）：
 * Android 9+ 应用层 WifiInfo 的 SSID 受定位开关限制（关闭时恒为
 * <unknown ssid>），导致状态卡在「WiFi已连接」与「已连接 xxx」间跳变。
 * 同一网络（netId 一致）沿用缓存结果 + 特权通道兑底（cmd wifi status
 * 不受定位限制），保证显示稳定一致。
 */
private object HomeWifiCache {
    var ssid: String = ""
    var netId: Int = -1
    var lastShellTryAt: Long = 0L   // 特权通道解析节流（网络回调高频触发）
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

        suspend fun update() {
            val newState = withContext(Dispatchers.IO) {
                val wifiManager =
                    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val activeNetwork = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(activeNetwork)

                var ssid = context.getString(R.string.not_connected)
                var isWifi = false

                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    isWifi = true
                    val info = wifiManager.connectionInfo
                    val rawSsid = info?.ssid?.removeSurrounding("\"").orEmpty()
                    val netId = info?.networkId ?: -1
                    var resolved =
                        if (rawSsid.isNotEmpty() && rawSsid != "<unknown ssid>") rawSsid else ""
                    // ① 定位开关导致 SSID 被系统屏蔽时：同一网络（netId 一致）
                    //    沿用缓存，消除「WiFi已连接/已连接 xxx」来回跳变
                    if (resolved.isEmpty() && netId >= 0 &&
                        HomeWifiCache.netId == netId && HomeWifiCache.ssid.isNotEmpty()
                    ) {
                        resolved = HomeWifiCache.ssid
                    }
                    // ② 仍拿不到 → 特权通道兑底（cmd wifi status 不受定位开关限制）；
                    //    onCapabilitiesChanged 随信号强度高频回调，10s 节流
                    if (resolved.isEmpty() &&
                        System.currentTimeMillis() - HomeWifiCache.lastShellTryAt > 10_000
                    ) {
                        HomeWifiCache.lastShellTryAt = System.currentTimeMillis()
                        resolved = WifiIdentity.resolve(context.applicationContext, app).first
                    }
                    if (resolved.isNotEmpty()) {
                        HomeWifiCache.ssid = resolved
                        HomeWifiCache.netId = netId
                    }
                    ssid = if (resolved.isEmpty()) context.getString(R.string.wifi_connected_generic)
                    else "${context.getString(R.string.wifi_connected_to)} $resolved"
                } else if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    ssid = context.getString(R.string.mobile_data)
                }
                // WiFi 已断开：清缓存，重连后重新解析（避免残留旧网络名）
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    HomeWifiCache.ssid = ""
                    HomeWifiCache.netId = -1
                }
                NetworkState(ssid, getAllIpAddresses(), isWifi)
            }
            value = newState
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                launch { update() }
            }

            override fun onLost(network: Network) {
                launch { update() }
            }

            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) {
                launch { update() }
            }

            override fun onLinkPropertiesChanged(network: Network, l: LinkProperties) {
                launch { update() }
            }
        }

        update()
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

                // Apple 主屏风格网格：4 列等宽图标瓦片（圆角方块图标 + 下方短标签），
                // 按压无涟漪、瓦片弹性缩放（iOS 图标按压反馈）；末行左对齐（iOS 排列习惯）
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        QuickActionTile(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.tile_pojie),
                            icon = Icons.Rounded.VpnKey,
                            baseColor = Color(0xFFFFD8E4),
                            darkColor = Color(0xFF5E2A38),
                            contentColor = if (isDark) Color(0xFFFFD8E4) else Color(0xFF6E2838),
                            isDark = isDark,
                            onClick = { navTarget.value = "Pojie" }
                        )
                        QuickActionTile(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.tile_guard),
                            icon = Icons.Rounded.MonitorHeart,
                            baseColor = Color(0xFFD3F4DC),
                            darkColor = Color(0xFF1F3D28),
                            contentColor = if (isDark) Color(0xFFD3F4DC) else Color(0xFF2F6B3F),
                            isDark = isDark,
                            onClick = { navTarget.value = "Guard" }
                        )
                        QuickActionTile(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.tile_manager),
                            icon = Icons.Rounded.Dns,
                            baseColor = Color(0xFFFDE495),
                            darkColor = Color(0xFF4A3E15),
                            contentColor = if (isDark) Color(0xFFFDE495) else Color(0xFF5C4912),
                            isDark = isDark,
                            onClick = { navTarget.value = "Viewer" }
                        )
                        QuickActionTile(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.nav_test),
                            icon = Icons.Rounded.Science,
                            baseColor = Color(0xFFF2D9FA),
                            darkColor = Color(0xFF3E1C4A),
                            contentColor = if (isDark) Color(0xFFF2D9FA) else Color(0xFF4A148C),
                            isDark = isDark,
                            onClick = { navTarget.value = "Test" }
                        )
                    }
                    Row(Modifier.fillMaxWidth()) {
                        QuickActionTile(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.nav_settings),
                            icon = Icons.Rounded.Settings,
                            baseColor = Color(0xFFFFE0C8),
                            darkColor = Color(0xFF4A2C20),
                            contentColor = if (isDark) Color(0xFFFFE0C8) else Color(0xFF5D4037),
                            isDark = isDark,
                            onClick = { navTarget.value = "Settings" }
                        )
                        // 空位保持列宽对齐（iOS 主屏末行左对齐）
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
                        Spacer(Modifier.weight(1f))
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

/**
 * Apple 主屏风格快捷操作瓦片：60dp 圆角方块图标 + 下方短标签。
 * 按压反馈对齐 iOS：无涟漪，图标瓦片弹性缩放（collectIsPressedAsState 驱动）。
 */
@Composable
private fun QuickActionTile(
    modifier: Modifier = Modifier,
    title: String,
    icon: ImageVector,
    baseColor: Color,
    darkColor: Color,
    contentColor: Color,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "tileScale"
    )
    val bgColor = if (isDark) darkColor.copy(alpha = 0.4f) else baseColor

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(bgColor, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = title,
                tint = contentColor,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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