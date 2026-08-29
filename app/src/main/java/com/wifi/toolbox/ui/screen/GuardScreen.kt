package com.wifi.toolbox.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.services.GuardService
import com.wifi.toolbox.services.GuardState
import com.wifi.toolbox.structs.GuardSettings
import com.wifi.toolbox.ui.items.BannerTip
import com.wifi.toolbox.ui.items.NavContainer
import com.wifi.toolbox.ui.items.NavPage
import com.wifi.toolbox.utils.GuardStats
import com.wifi.toolbox.utils.rememberGuardSettings
import me.zhanghai.compose.preference.*
import java.util.concurrent.TimeUnit

/**
 * 网络守护入口页：WiFi 断网自动检测与重连
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as? ToolboxApp }
    val settings = rememberGuardSettings(context)
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }

    val pages = remember(settings.value) {
        listOf(
            object : NavPage {
                override val name = context.getString(R.string.guard_tab_status)
                override val selectedIcon = Icons.Filled.MonitorHeart
                override val unselectedIcon = Icons.Outlined.MonitorHeart
                override val content = @Composable {
                    StatusPage(settings.value, app)
                }
            },
            object : NavPage {
                override val name = context.getString(R.string.settings)
                override val selectedIcon = Icons.Filled.Tune
                override val unselectedIcon = Icons.Outlined.Tune
                override val content = @Composable {
                    GuardSettingsPage(settings)
                }
            },
            object : NavPage {
                override val name = context.getString(R.string.guard_tab_stats)
                override val selectedIcon = Icons.Filled.QueryStats
                override val unselectedIcon = Icons.Outlined.QueryStats
                override val content = @Composable {
                    StatsPage(app)
                }
            }
        )
    }

    NavContainer(
        pages = pages,
        selectedIndex = pageIndex,
        onIndexChange = { pageIndex = it },
        subtitle = stringResource(R.string.guard_name),
        onMenuClick = onMenuClick
    )
}

// ==================== 状态页 ====================

@Composable
private fun StatusPage(settings: GuardSettings, app: ToolboxApp?) {
    val context = LocalContext.current
    val running = GuardState.running
    val state = GuardState.currentState
    val lastCheck = GuardState.lastCheckTime
    val lastVerdict = GuardState.lastVerdict

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            // ---- 总开关 ----
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (running)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (running) R.string.guard_service_running
                                else R.string.guard_service_stopped
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(
                                R.string.guard_interval_now, settings.checkIntervalSec
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = running,
                        onCheckedChange = { on ->
                            if (on) {
                                GuardService.start(context)
                            } else {
                                GuardService.stop(context)
                            }
                        }
                    )
                }
            }
        }

        item {
            // ---- 当前状态卡 ----
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    val (label, color) = stateLabel(state)
                    Text(
                        text = label,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                    if (lastCheck > 0) {
                        val ago = (System.currentTimeMillis() - lastCheck) / 1000
                        Text(
                            text = stringResource(
                                R.string.guard_last_check,
                                formatAgo(ago)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    lastVerdict?.let { v ->
                        Spacer(Modifier.height(8.dp))
                        v.results.forEach { r ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${r.mode}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    if (r.ok) "✓ ${r.detail}" else "✗ ${r.detail}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (r.ok) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            // ---- 立即检测按钮 ----
            Button(
                onClick = {
                    try {
                        context.startService(
                            android.content.Intent(
                                context, GuardService::class.java
                            ).apply { action = GuardService.ACTION_RUN_CHECK }
                        )
                    } catch (_: Exception) {
                    }
                },
                enabled = running,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.guard_check_now))
            }
        }

        item {
            // ---- 实时日志 ----
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.guard_live_log),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    val logs = GuardState.logList()
                    if (logs.isEmpty()) {
                        Text(
                            stringResource(R.string.guard_no_log),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        logs.takeLast(50).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun stateLabel(state: String): Pair<String, Color> {
    return when (state) {
        GuardState.STATE_ONLINE ->
            stringResource(R.string.guard_state_online) to MaterialTheme.colorScheme.primary
        GuardState.STATE_SUSPECT ->
            stringResource(R.string.guard_state_suspect) to MaterialTheme.colorScheme.tertiary
        GuardState.STATE_HEALING ->
            stringResource(R.string.guard_state_healing) to MaterialTheme.colorScheme.tertiary
        GuardState.STATE_HEAL_FAILED ->
            stringResource(R.string.guard_state_heal_failed) to MaterialTheme.colorScheme.error
        GuardState.STATE_PORTAL ->
            stringResource(R.string.guard_state_portal) to MaterialTheme.colorScheme.error
        GuardState.STATE_LINK_DOWN ->
            stringResource(R.string.guard_state_link_down) to MaterialTheme.colorScheme.onSurfaceVariant
        else ->
            stringResource(R.string.guard_state_idle) to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun formatAgo(seconds: Long): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m${seconds % 60}s"
        else -> "${seconds / 3600}h${(seconds % 3600) / 60}m"
    }
}

// ==================== 设置页 ====================

@Composable
private fun GuardSettingsPage(settings: MutableState<GuardSettings>) {
    val s = settings.value
    val context = LocalContext.current

    val probeHttp = s.probeModes and GuardSettings.PROBE_HTTP_204 != 0
    val probeDns = s.probeModes and GuardSettings.PROBE_DNS != 0
    val probeIcmp = s.probeModes and GuardSettings.PROBE_ICMP != 0
    val probeValidated = s.probeModes and GuardSettings.PROBE_VALIDATED != 0

    val strategyValues = listOf(
        stringResource(R.string.guard_strategy_0),
        stringResource(R.string.guard_strategy_1),
        stringResource(R.string.guard_strategy_2),
        stringResource(R.string.guard_strategy_3),
        stringResource(R.string.guard_strategy_4)
    )
    val channelValues = listOf(
        stringResource(R.string.guard_channel_auto),
        stringResource(R.string.shizuku),
        stringResource(R.string.guard_channel_aidl),
        stringResource(R.string.guard_channel_api)
    )

    var showCustomInterval by remember { mutableStateOf(false) }
    var customIntervalText by remember { mutableStateOf(s.checkIntervalSec.toString()) }

    ProvidePreferenceLocals {
        LazyColumn(modifier = Modifier.fillMaxSize()) {

            // ---- 检测设置 ----
            item { PreferenceCategory(title = { Text(stringResource(R.string.guard_cat_probe)) }) }
            item {
                BannerTip(
                    text = stringResource(R.string.guard_probe_tip),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            item {
                CheckboxPreference(
                    value = probeHttp,
                    onValueChange = { on ->
                        settings.value = s.copy(
                            probeModes = toggleMask(s.probeModes, GuardSettings.PROBE_HTTP_204, on)
                        )
                    },
                    title = { Text(stringResource(R.string.guard_probe_http)) },
                    summary = { Text(stringResource(R.string.guard_probe_http_tip)) },
                    icon = { Icon(Icons.Filled.Language, null) }
                )
            }
            item {
                CheckboxPreference(
                    value = probeDns,
                    onValueChange = { on ->
                        settings.value = s.copy(
                            probeModes = toggleMask(s.probeModes, GuardSettings.PROBE_DNS, on)
                        )
                    },
                    title = { Text(stringResource(R.string.guard_probe_dns)) },
                    summary = { Text(stringResource(R.string.guard_probe_dns_tip)) },
                    icon = { Icon(Icons.Filled.Dns, null) }
                )
            }
            item {
                CheckboxPreference(
                    value = probeIcmp,
                    onValueChange = { on ->
                        settings.value = s.copy(
                            probeModes = toggleMask(s.probeModes, GuardSettings.PROBE_ICMP, on)
                        )
                    },
                    title = { Text(stringResource(R.string.guard_probe_icmp)) },
                    summary = { Text(stringResource(R.string.guard_probe_icmp_tip)) },
                    icon = { Icon(Icons.Filled.NetworkPing, null) }
                )
            }
            item {
                CheckboxPreference(
                    value = probeValidated,
                    onValueChange = { on ->
                        settings.value = s.copy(
                            probeModes = toggleMask(s.probeModes, GuardSettings.PROBE_VALIDATED, on)
                        )
                    },
                    title = { Text(stringResource(R.string.guard_probe_validated)) },
                    summary = { Text(stringResource(R.string.guard_probe_validated_tip)) },
                    icon = { Icon(Icons.Filled.Verified, null) }
                )
            }
            item {
                val state = remember { mutableStateOf(s.probeTimeoutMs.toFloat()) }
                SliderPreference(
                    state = state,
                    valueRange = 1000f..10000f,
                    valueSteps = 8,
                    title = { Text(stringResource(R.string.guard_probe_timeout)) },
                    summary = { Text(stringResource(R.string.guard_ms_value, state.value.toInt())) },
                    icon = { Icon(Icons.Filled.Timer, null) }
                )
                LaunchedEffect(state.value) {
                    settings.value = s.copy(probeTimeoutMs = state.value.toInt())
                }
            }
            item {
                val state = remember { mutableStateOf(s.failThreshold.toFloat()) }
                SliderPreference(
                    state = state,
                    valueRange = 1f..5f,
                    valueSteps = 3,
                    title = { Text(stringResource(R.string.guard_fail_threshold)) },
                    summary = { Text(stringResource(R.string.guard_fail_threshold_tip, state.value.toInt())) },
                    icon = { Icon(Icons.Filled.FilterAlt, null) }
                )
                LaunchedEffect(state.value) {
                    settings.value = s.copy(failThreshold = state.value.toInt())
                }
            }

            // ---- 检测时机 ----
            item { PreferenceCategory(title = { Text(stringResource(R.string.guard_cat_timing)) }) }
            item {
                val presetIndex = GuardSettings.INTERVAL_PRESETS.indexOf(s.checkIntervalSec)
                ListPreference(
                    value = if (presetIndex >= 0) presetIndex else 0,
                    onValueChange = { index ->
                        if (index in GuardSettings.INTERVAL_PRESETS.indices) {
                            settings.value = s.copy(
                                checkIntervalSec = GuardSettings.INTERVAL_PRESETS[index]
                            )
                        }
                    },
                    title = { Text(stringResource(R.string.guard_interval)) },
                    summary = {
                        Text(
                            if (presetIndex >= 0) stringResource(
                                R.string.guard_interval_value, s.checkIntervalSec
                            )
                            else stringResource(
                                R.string.guard_interval_custom_value, s.checkIntervalSec
                            )
                        )
                    },
                    icon = { Icon(Icons.Filled.Schedule, null) },
                    values = GuardSettings.INTERVAL_PRESETS.indices.toList(),
                    valueToText = { i: Int ->
                        AnnotatedString("${GuardSettings.INTERVAL_PRESETS[i]}s")
                    },
                    type = ListPreferenceType.DROPDOWN_MENU
                )
            }
            item {
                Preference(
                    title = { Text(stringResource(R.string.guard_interval_custom)) },
                    summary = { Text(stringResource(R.string.guard_interval_custom_tip)) },
                    icon = { Icon(Icons.Filled.Edit, null) },
                    onClick = {
                        customIntervalText = s.checkIntervalSec.toString()
                        showCustomInterval = true
                    }
                )
            }
            item {
                SwitchPreference(
                    value = s.checkOnNetworkChange,
                    onValueChange = {
                        settings.value = s.copy(checkOnNetworkChange = it)
                    },
                    title = { Text(stringResource(R.string.guard_check_on_change)) },
                    summary = { Text(stringResource(R.string.guard_check_on_change_tip)) },
                    icon = { Icon(Icons.Filled.Bolt, null) }
                )
            }
            item {
                SwitchPreference(
                    value = s.onlyWhenWifiConnected,
                    onValueChange = {
                        settings.value = s.copy(onlyWhenWifiConnected = it)
                    },
                    title = { Text(stringResource(R.string.guard_only_wifi)) },
                    summary = { Text(stringResource(R.string.guard_only_wifi_tip)) },
                    icon = { Icon(Icons.Filled.Wifi, null) }
                )
            }

            // ---- 自愈设置 ----
            item { PreferenceCategory(title = { Text(stringResource(R.string.guard_cat_heal)) }) }
            item {
                ListPreference(
                    value = s.healStrategy,
                    onValueChange = {
                        settings.value = s.copy(healStrategy = it)
                    },
                    title = { Text(stringResource(R.string.guard_heal_strategy)) },
                    summary = { Text(strategyValues[s.healStrategy]) },
                    icon = { Icon(Icons.Filled.Healing, null) },
                    values = strategyValues.indices.toList(),
                    valueToText = { i: Int -> AnnotatedString(strategyValues[i]) },
                    type = ListPreferenceType.DROPDOWN_MENU
                )
            }
            item {
                BannerTip(
                    text = stringResource(R.string.guard_strategy_tip),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            item {
                val state = remember { mutableStateOf(s.healVerifyTimeoutSec.toFloat()) }
                SliderPreference(
                    state = state,
                    valueRange = 5f..60f,
                    valueSteps = 10,
                    title = { Text(stringResource(R.string.guard_heal_verify_timeout)) },
                    summary = { Text(stringResource(R.string.guard_sec_value, state.value.toInt())) },
                    icon = { Icon(Icons.Filled.Timer, null) }
                )
                LaunchedEffect(state.value) {
                    settings.value = s.copy(healVerifyTimeoutSec = state.value.toInt())
                }
            }
            item {
                val state = remember { mutableStateOf(s.healCooldownBaseSec.toFloat()) }
                SliderPreference(
                    state = state,
                    valueRange = 5f..120f,
                    valueSteps = 22,
                    title = { Text(stringResource(R.string.guard_cooldown_base)) },
                    summary = { Text(stringResource(R.string.guard_cooldown_base_tip, state.value.toInt())) },
                    icon = { Icon(Icons.Filled.HourglassTop, null) }
                )
                LaunchedEffect(state.value) {
                    settings.value = s.copy(healCooldownBaseSec = state.value.toInt())
                }
            }
            item {
                ListPreference(
                    value = s.healChannel,
                    onValueChange = {
                        settings.value = s.copy(healChannel = it)
                    },
                    title = { Text(stringResource(R.string.guard_heal_channel)) },
                    summary = { Text(channelValues[s.healChannel]) },
                    icon = { Icon(Icons.Filled.Cable, null) },
                    values = channelValues.indices.toList(),
                    valueToText = { i: Int -> AnnotatedString(channelValues[i]) },
                    type = ListPreferenceType.DROPDOWN_MENU
                )
            }
            item {
                SwitchPreference(
                    value = s.skipWhenWifiDisconnected,
                    onValueChange = {
                        settings.value = s.copy(skipWhenWifiDisconnected = it)
                    },
                    title = { Text(stringResource(R.string.guard_skip_link_down)) },
                    summary = { Text(stringResource(R.string.guard_skip_link_down_tip)) },
                    icon = { Icon(Icons.Filled.LinkOff, null) }
                )
            }
            item {
                SwitchPreference(
                    value = s.skipOnCaptivePortal,
                    onValueChange = {
                        settings.value = s.copy(skipOnCaptivePortal = it)
                    },
                    title = { Text(stringResource(R.string.guard_skip_portal)) },
                    summary = { Text(stringResource(R.string.guard_skip_portal_tip)) },
                    icon = { Icon(Icons.Filled.VpnLock, null) }
                )
            }

            // ---- 通知 ----
            item { PreferenceCategory(title = { Text(stringResource(R.string.guard_cat_notify)) }) }
            item {
                SwitchPreference(
                    value = s.notifyOnHeal,
                    onValueChange = {
                        settings.value = s.copy(notifyOnHeal = it)
                    },
                    title = { Text(stringResource(R.string.guard_notify_heal)) },
                    summary = { Text(stringResource(R.string.guard_notify_heal_tip)) },
                    icon = { Icon(Icons.Filled.NotificationsActive, null) }
                )
            }
            item {
                SwitchPreference(
                    value = s.notifyOnHealFail,
                    onValueChange = {
                        settings.value = s.copy(notifyOnHealFail = it)
                    },
                    title = { Text(stringResource(R.string.guard_notify_fail)) },
                    summary = { Text(stringResource(R.string.guard_notify_fail_tip)) },
                    icon = { Icon(Icons.Filled.NotificationImportant, null) }
                )
            }
            item {
                SwitchPreference(
                    value = s.showPersistentNotification,
                    onValueChange = {
                        settings.value = s.copy(showPersistentNotification = it)
                    },
                    title = { Text(stringResource(R.string.guard_notify_persistent)) },
                    summary = { Text(stringResource(R.string.guard_notify_persistent_tip)) },
                    icon = { Icon(Icons.Filled.Notifications, null) }
                )
            }
        }
    }

    // ---- 自定义间隔对话框 ----
    if (showCustomInterval) {
        AlertDialog(
            onDismissRequest = { showCustomInterval = false },
            title = { Text(stringResource(R.string.guard_interval_custom)) },
            text = {
                OutlinedTextField(
                    value = customIntervalText,
                    onValueChange = { customIntervalText = it.filter { c -> c.isDigit() }.take(4) },
                    label = { Text(stringResource(R.string.guard_interval_custom_input)) },
                    supportingText = { Text(stringResource(R.string.guard_interval_custom_range)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    customIntervalText.toIntOrNull()?.let { sec ->
                        settings.value = s.copy(checkIntervalSec = sec.coerceIn(5, 3600))
                    }
                    showCustomInterval = false
                }) { Text(stringResource(R.string.btn_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomInterval = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

private fun toggleMask(current: Int, bit: Int, on: Boolean): Int =
    if (on) current or bit else current and bit.inv()

// ==================== 统计页 ====================

@Composable
private fun StatsPage(app: ToolboxApp?) {
    val stats = app?.guardStats
    var refreshKey by remember { mutableIntStateOf(0) }
    // 打开统计页时拉一次最新数据
    LaunchedEffect(Unit) { refreshKey++ }

    if (stats == null) return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    title = stringResource(R.string.guard_stat_checks),
                    value = stats.totalChecks.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = stringResource(R.string.guard_stat_failures),
                    value = stats.totalFailures.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatCard(
                    title = stringResource(R.string.guard_stat_heals),
                    value = stats.totalHeals.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = stringResource(R.string.guard_stat_recovered),
                    value = stats.totalRecovered.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ---- 动作有效率 ----
        if (stats.actionStats.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.guard_stat_action_rate),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        stats.actionStats.entries.sortedByDescending { it.value.first }
                            .forEach { entry ->
                                val action = entry.key
                                val count = entry.value
                                val rate = if (count.first > 0) count.second * 100 / count.first else 0
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            action,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            "$rate% (${count.second}/${count.first})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (rate >= 50) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.error
                                        )
                                    }
                                    LinearProgressIndicator(
                                        progress = { if (count.first > 0) count.second.toFloat() / count.first else 0f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp)
                                    )
                                }
                            }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.guard_stat_action_tip),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ---- 事件历史 ----
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.guard_stat_events),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(onClick = { stats.reset() }) {
                            Text(stringResource(R.string.guard_stat_reset))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (stats.events.isEmpty()) {
                        Text(
                            stringResource(R.string.guard_no_log),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        stats.events.take(30).forEach { e ->
                            Column(Modifier.padding(vertical = 4.dp)) {
                                Text(
                                    "${GuardStats.formatTime(e.time)}  ${e.ssid}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "${e.actions} → ${if (e.recovered) "✓" else "✗"} ${e.costMs / 1000}s (${e.failedProbes})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (e.recovered) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
