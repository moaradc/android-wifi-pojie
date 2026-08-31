package com.wifi.toolbox.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.services.GuardLog
import com.wifi.toolbox.services.GuardLogEntry
import com.wifi.toolbox.services.GuardService
import com.wifi.toolbox.services.GuardState
import com.wifi.toolbox.structs.GuardSettings
import com.wifi.toolbox.ui.items.BannerTip
import com.wifi.toolbox.ui.items.NavContainer
import com.wifi.toolbox.ui.items.NavPage
import com.wifi.toolbox.ui.items.TipIconButton
import com.wifi.toolbox.ui.items.checkShizukuUI
import com.wifi.toolbox.utils.GuardEvent
import com.wifi.toolbox.utils.GuardLogStore
import com.wifi.toolbox.utils.GuardStats
import com.wifi.toolbox.utils.KeepAliveHelper
import com.wifi.toolbox.utils.ProbeResult
import com.wifi.toolbox.utils.StoredLogFile
import com.wifi.toolbox.utils.WifiHealer
import com.wifi.toolbox.utils.rememberGuardSettings
import me.zhanghai.compose.preference.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // 折叠/展开状态提升到本层（NavContainer 切页会销毁非当前页组合，
    // 卡片内部 rememberSaveable 随之丢失——切页回来折叠状态被重置的真机
    // 反馈根因）；默认折叠（长列表默认展开挤占状态卡/统计瓦片空间）
    var liveLogExpanded by rememberSaveable { mutableStateOf(false) }
    var eventsExpanded by rememberSaveable { mutableStateOf(false) }

    val pages = remember(settings.value) {
        listOf(
            object : NavPage {
                override val name = context.getString(R.string.guard_tab_status)
                override val selectedIcon = Icons.Filled.MonitorHeart
                override val unselectedIcon = Icons.Outlined.MonitorHeart
                override val content = @Composable {
                    StatusPage(settings.value, app, liveLogExpanded) { liveLogExpanded = it }
                }
            },
            object : NavPage {
                override val name = context.getString(R.string.settings)
                override val selectedIcon = Icons.Filled.Tune
                override val unselectedIcon = Icons.Outlined.Tune
                override val content = @Composable {
                    GuardSettingsPage(settings, app)
                }
            },
            object : NavPage {
                override val name = context.getString(R.string.guard_tab_stats)
                override val selectedIcon = Icons.Filled.QueryStats
                override val unselectedIcon = Icons.Outlined.QueryStats
                override val content = @Composable {
                    StatsPage(app, settings.value.logDirUri, eventsExpanded) { eventsExpanded = it }
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
private fun StatusPage(
    settings: GuardSettings,
    app: ToolboxApp?,
    liveLogExpanded: Boolean,
    onLiveLogExpandedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val running = GuardState.running
    val state = GuardState.currentState
    val lastCheck = GuardState.lastCheckTime
    val lastVerdict = GuardState.lastVerdict

    // 1 秒滴答：驱动"上次检测 X 秒前"实时刷新
    // （原实现只在检测轮次写入 lastCheckTime 时重组，页面上的秒数会冻住不动）
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    // 打开页面时同步常驻通知：服务未运行时按「常驻守护通知」开关
    // 显示/移除"未运行"常驻通知（服务运行中的通知由前台服务管理）；
    // 同时按保留天数自动清理过期实时日志（服务未运行也能清）
    LaunchedEffect(settings.autoCleanDays) {
        GuardService.syncIdleNotification(context)
        if (settings.autoCleanDays > 0) GuardState.pruneLogs(settings.autoCleanDays)
    }

    val shellChannel = GuardState.lastShellChannel
    val healChannel = GuardState.lastHealChannel

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // ---- Hero 状态卡（Apple 系统状态页风格：大图标 + 一句话结论）----
        item { HeroStatusCard(state, lastCheck, nowMs) }

        // ---- 控制卡（iOS 分组列表风格：开关行 + 居中动作行）----
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column {
                    // 守护开关行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
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
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    // 立即检测行（iOS 分组列表动作行样式：居中主色文字）
                    val actionColor = if (running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = running) {
                                try {
                                    context.startService(
                                        android.content.Intent(
                                            context, GuardService::class.java
                                        ).apply { action = GuardService.ACTION_RUN_CHECK }
                                    )
                                } catch (_: Exception) {
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow, null,
                            Modifier.size(18.dp), tint = actionColor
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.guard_check_now),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = actionColor
                        )
                    }
                }
            }
        }

        // ---- 检测详情卡（分组行 + hairline 分隔线；保留展开/长按复制交互）----
        if (lastVerdict != null || shellChannel.isNotEmpty() || healChannel.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Column(Modifier.padding(vertical = 10.dp)) {
                        Text(
                            stringResource(R.string.guard_probe_detail_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 4.dp)
                        )
                        // 执行通道行（点击展开/收起，长按复制）
                        if (shellChannel.isNotEmpty() || healChannel.isNotEmpty()) {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                ChannelLine(shellChannel, healChannel)
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                        // 探测结果行（状态圆点 + 单行截断，点击展开）
                        lastVerdict?.let { v ->
                            v.results.forEachIndexed { i, r ->
                                Column(Modifier.padding(horizontal = 16.dp)) {
                                    ProbeResultLine(r)
                                }
                                if (i < v.results.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 16.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            // ---- 实时日志（复制/清空/保存/导出/管理/多选筛选） ----
            // 展开状态由 GuardScreen 层持有（切页不重置，默认折叠）
            LiveLogCard(
                context, settings.logDirUri,
                liveLogExpanded, onLiveLogExpandedChange
            )
        }
    }
}

/**
 * 状态视觉三元组：本地化标签 + 语义色 + 图标（Apple 系统状态页式大图标）。
 * 全部取自 MaterialTheme.colorScheme（在线 primary / 疑似·自愈 tertiary /
 * 失败·认证 error / 未运行·链路断开 secondary），跟随总设置的动态主题色/颜色种子。
 */
private data class StateVisual(val label: String, val color: Color, val icon: ImageVector)

@Composable
private fun stateVisual(state: String): StateVisual {
    return when (state) {
        GuardState.STATE_ONLINE -> StateVisual(
            stringResource(R.string.guard_state_online),
            MaterialTheme.colorScheme.primary, Icons.Filled.Wifi
        )
        GuardState.STATE_SUSPECT -> StateVisual(
            stringResource(R.string.guard_state_suspect),
            MaterialTheme.colorScheme.tertiary, Icons.Filled.WifiFind
        )
        GuardState.STATE_HEALING -> StateVisual(
            stringResource(R.string.guard_state_healing),
            MaterialTheme.colorScheme.tertiary, Icons.Filled.Autorenew
        )
        GuardState.STATE_HEAL_FAILED -> StateVisual(
            stringResource(R.string.guard_state_heal_failed),
            MaterialTheme.colorScheme.error, Icons.Filled.WifiOff
        )
        GuardState.STATE_PORTAL -> StateVisual(
            stringResource(R.string.guard_state_portal),
            MaterialTheme.colorScheme.error, Icons.Filled.VpnLock
        )
        GuardState.STATE_LINK_DOWN -> StateVisual(
            stringResource(R.string.guard_state_link_down),
            MaterialTheme.colorScheme.secondary, Icons.Filled.WifiOff
        )
        else -> StateVisual(
            stringResource(R.string.guard_state_idle),
            MaterialTheme.colorScheme.secondary, Icons.Filled.MonitorHeart
        )
    }
}

/**
 * Hero 状态卡（参考 Apple 系统状态页"All services are operating normally"模式）：
 * 居中大圆角图标 + 大号状态结论 + 上次检测时间，随状态整体着色。
 */
@Composable
private fun HeroStatusCard(state: String, lastCheckMs: Long, nowMs: Long) {
    val (label, color, icon) = stateVisual(state)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.10f)
        )
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, Modifier.size(36.dp), tint = color)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            if (lastCheckMs > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.guard_last_check,
                        formatAgo((nowMs - lastCheckMs) / 1000)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 当前通道行：
 * - 默认单行截断（省屏幕空间）；点击平滑动画展开为逐通道分行
 * - 长按复制通道内容（收起时长按复制全部，展开时长按复制对应通道行）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelLine(shellChannel: String, healChannel: String) {
    val context = LocalContext.current
    var expanded by rememberSaveable {
        mutableStateOf(false)
    }
    val probeLabel = stringResource(R.string.guard_channel_probe)
    val healLabel = stringResource(R.string.guard_channel_heal)

    fun copyText(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("guard_channel", text))
        Toast.makeText(
            context, context.getString(R.string.guard_channel_copied), Toast.LENGTH_SHORT
        ).show()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()  // 展开/收起的流畅高度动画
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = {
                    // 收起状态：长按复制全部通道信息
                    val text = listOf(
                        "$probeLabel: $shellChannel",
                        "$healLabel: $healChannel"
                    ).filter {
                        !it.endsWith(": ")
                    }.joinToString(" / ")
                    copyText(text)
                }
            )
            .padding(vertical = 2.dp)
    ) {
        if (expanded) {
            Text(
                text = stringResource(R.string.guard_channel_current_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (shellChannel.isNotEmpty()) {
                Text(
                    text = "$probeLabel: $shellChannel",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = { copyText(shellChannel) }
                    )
                )
            }
            if (healChannel.isNotEmpty()) {
                Text(
                    text = "$healLabel: $healChannel",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = { copyText(healChannel) }
                    )
                )
            }
        } else {
            val merged = listOf(
                "$probeLabel $shellChannel",
                "$healLabel $healChannel"
            ).filter { !it.endsWith(" ") }.joinToString(" / ")
            Text(
                text = stringResource(R.string.guard_channel_current, merged),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatAgo(seconds: Long): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m${seconds % 60}s"
        else -> "${seconds / 3600}h${(seconds % 3600) / 60}m"
    }
}

/**
 * 单条探测结果行（HTTP/DNS/ICMP/VALIDATED）：
 * - 默认单行截断（长 detail 如 ICMP 真实报错不撑爆卡片）
 * - 点击平滑动画展开完整 detail（可多行）
 * - 长按复制该项结果（如 "HTTP ✓ 204 116ms"）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProbeResultLine(result: ProbeResult) {
    val context = LocalContext.current
    var expanded by remember(result.mode) { mutableStateOf(false) }
    val statusColor = if (result.ok) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.error
    val mark = if (result.ok) "✓" else "✗"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()   // 展开/收起的流畅高度动画
            .combinedClickable(
                onClick = { expanded = !expanded },
                onLongClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(
                        ClipData.newPlainText("guard_probe", "${result.mode} $mark ${result.detail}")
                    )
                    Toast.makeText(
                        context,
                        context.getString(R.string.guard_probe_copied),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
            .padding(vertical = 6.dp)
    ) {
        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    result.mode,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    mark,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor
                )
            }
            // 完整 detail：不截断，可多行（点击收起）
            Text(
                result.detail,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = statusColor
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 状态圆点（Apple 系统状态页式绿点/红点）
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        result.mode,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    "$mark ${result.detail}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

// ==================== 实时日志卡片 ====================

/**
 * 日志显示筛选（多选位掩码）：
 * bit0 = 正常（INFO）；bit1 = 异常（WARN+ERROR）；bit2 = 自愈（HEAL）
 */
private const val LOG_FILTER_INFO = 1
private const val LOG_FILTER_ERROR = 2
private const val LOG_FILTER_HEAL = 4
private const val LOG_FILTER_ALL = LOG_FILTER_INFO or LOG_FILTER_ERROR or LOG_FILTER_HEAL

/** 日志级别 → 显示分组 */
private fun logGroupBit(level: Int): Int = when (level) {
    GuardLog.LEVEL_INFO -> LOG_FILTER_INFO
    GuardLog.LEVEL_HEAL -> LOG_FILTER_HEAL
    else -> LOG_FILTER_ERROR // WARN + ERROR 归入"异常"
}

/** 单条日志的可复制文本 */
private fun formatEntry(entry: GuardLogEntry): String =
    "[${GuardLog.formatTime(entry.time)}] ${entry.msg}"

/**
 * 实时日志卡片：
 * - 工具行：复制 / 保存 / 导出(分享) / 管理(已保存列表) / 清空
 * - 筛选多选：全部 / 正常 / 异常 / 自愈（可任意组合，全不选显示空）
 * - 按级别着色展示（最近 50 条）
 * - 保存位置：默认应用私有 log 目录，可在设置页改为 SAF 自选文件夹
 * - [expanded] 展开状态由 GuardScreen 层持有（切页不重置，默认折叠）
 */
@Composable
private fun LiveLogCard(
    context: Context,
    logDirUri: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val logs = GuardState.logList()
    var filterMask by rememberSaveable { mutableIntStateOf(LOG_FILTER_ALL) }
    var showManage by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(250),
        label = "liveLogChevron"
    )

    fun toast(msg: String) {
        toastMsg = msg
    }

    // Toast 用本地状态驱动，避免在组合期间直接调副作用
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }

    /** 当前筛选下要操作的日志列表（点击时实时读取，避免闭包过期） */
    fun visibleEntries(): List<GuardLogEntry> {
        val all = GuardState.logList()
        if (filterMask == 0) return emptyList()
        return all.filter { filterMask and logGroupBit(it.level) != 0 }
    }

    fun entriesToText(entries: List<GuardLogEntry>): String {
        if (entries.isEmpty()) return ""
        val head = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        return buildString {
            appendLine(context.getString(R.string.guard_log_file_header))
            appendLine(context.getString(R.string.guard_log_file_exported_at, head))
            appendLine("--------")
            entries.forEach { appendLine(formatEntry(it)) }
        }
    }

    /** 保存到当前设置位置，返回已保存文件（失败 null） */
    fun saveNow(): StoredLogFile? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        return GuardLogStore.save(
            context, logDirUri, "guard-$stamp.log", entriesToText(visibleEntries())
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            // ---- 标题 + 操作按钮行（长按任意按钮弹出名称提示气泡） ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.guard_live_log),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TipIconButton(
                    onClick = {
                        val entries = visibleEntries()
                        if (entries.isEmpty()) return@TipIconButton
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText(
                                "guard_log", entries.joinToString("\n") { formatEntry(it) }
                            )
                        )
                        toast(context.getString(R.string.guard_log_copied, entries.size))
                    },
                    tip = stringResource(R.string.guard_log_copy_desc),
                    icon = Icons.Outlined.ContentCopy
                )
                TipIconButton(
                    onClick = {
                        val saved = saveNow()
                        toast(
                            if (saved != null) context.getString(
                                R.string.guard_log_saved, saved.name
                            )
                            else context.getString(R.string.guard_log_save_fail)
                        )
                    },
                    tip = stringResource(R.string.guard_log_save_desc),
                    icon = Icons.Outlined.Save
                )
                TipIconButton(
                    onClick = {
                        val saved = saveNow()
                        if (saved != null) shareStoredFile(context, saved)
                        else toast(context.getString(R.string.guard_log_save_fail))
                    },
                    tip = stringResource(R.string.guard_log_export_desc),
                    icon = Icons.Outlined.IosShare
                )
                TipIconButton(
                    onClick = { showManage = true },
                    tip = stringResource(R.string.guard_log_manage_desc),
                    icon = Icons.Outlined.FolderOpen
                )
                TipIconButton(
                    onClick = {
                        GuardState.clearLogs()
                        toast(context.getString(R.string.guard_log_cleared))
                    },
                    tip = stringResource(R.string.guard_log_clear_desc),
                    icon = Icons.Outlined.DeleteSweep
                )
                // 展开 / 收起（箭头随状态旋转）
                TipIconButton(
                    onClick = { onExpandedChange(!expanded) },
                    tip = stringResource(R.string.guard_log_expand_desc),
                    icon = Icons.Outlined.ExpandMore,
                    iconModifier = Modifier.rotate(chevronRotation)
                )
            }

            // ---- 筛选行 + 日志正文（展开时显示，展开收起带动画） ----
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(tween(200)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeOut(tween(150))
            ) {
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        FilterChip(
                            selected = filterMask == LOG_FILTER_ALL,
                            onClick = { filterMask = LOG_FILTER_ALL },
                            label = { Text(stringResource(R.string.guard_log_filter_all)) }
                        )
                        FilterChip(
                            selected = filterMask and LOG_FILTER_INFO != 0,
                            onClick = { filterMask = filterMask xor LOG_FILTER_INFO },
                            label = { Text(stringResource(R.string.guard_log_filter_info)) }
                        )
                        FilterChip(
                            selected = filterMask and LOG_FILTER_ERROR != 0,
                            onClick = { filterMask = filterMask xor LOG_FILTER_ERROR },
                            label = { Text(stringResource(R.string.guard_log_filter_error)) }
                        )
                        FilterChip(
                            selected = filterMask and LOG_FILTER_HEAL != 0,
                            onClick = { filterMask = filterMask xor LOG_FILTER_HEAL },
                            label = { Text(stringResource(R.string.guard_log_filter_heal)) }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ---- 日志正文 ----
                    val filtered = if (filterMask == 0) emptyList()
                    else logs.filter { filterMask and logGroupBit(it.level) != 0 }
                    if (filtered.isEmpty()) {
                        Text(
                            stringResource(
                                if (filterMask == 0) R.string.guard_log_filter_none
                                else R.string.guard_no_log
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        filtered.takeLast(50).forEach { entry ->
                            Text(
                                formatEntry(entry),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = logLevelColor(entry.level),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showManage) {
        SavedLogsDialog(context = context, logDirUri = logDirUri, onDismiss = { showManage = false })
    }
}

/** 日志级别 → 颜色 */
@Composable
private fun logLevelColor(level: Int): Color {
    return when (level) {
        GuardLog.LEVEL_ERROR -> MaterialTheme.colorScheme.error
        GuardLog.LEVEL_HEAL -> MaterialTheme.colorScheme.tertiary
        GuardLog.LEVEL_WARN -> MaterialTheme.colorScheme.secondary
        else -> Color.Unspecified
    }
}

/** 通过 FileProvider 分享私有目录日志文件（系统分享面板） */
private fun shareLogFile(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: Exception) {
    }
}

/** 分享已保存日志（私有目录走 FileProvider，SAF 文档直接共享 content Uri） */
private fun shareStoredFile(context: Context, f: StoredLogFile) {
    try {
        if (f.isSaf && f.uri != null) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, f.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, null))
        } else if (f.file != null) {
            shareLogFile(context, f.file)
        }
    } catch (_: Exception) {
    }
}

/**
 * 已保存日志管理对话框：列出全部保存位置的日志文件（时间倒序，
 * 标注来源：应用目录 / 自选目录），支持单个分享 / 单个删除 / 全部清空。
 */
@Composable
private fun SavedLogsDialog(context: Context, logDirUri: String, onDismiss: () -> Unit) {
    var version by remember { mutableIntStateOf(0) }
    val files = remember(version, logDirUri) {
        GuardLogStore.list(context, logDirUri)
    }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.guard_log_manage)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (files.isEmpty()) {
                    Text(
                        stringResource(R.string.guard_log_manage_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    files.forEach { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "${f.sizeBytes / 1024}KB · ${fmt.format(Date(f.lastModified))} · " +
                                            stringResource(
                                                if (f.isSaf) R.string.guard_log_src_saf
                                                else R.string.guard_log_src_app
                                            ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { shareStoredFile(context, f) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Share,
                                    stringResource(R.string.guard_log_share_desc),
                                    Modifier.size(17.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    GuardLogStore.delete(context, f)
                                    version++
                                },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    stringResource(R.string.guard_log_delete_desc),
                                    Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (files.isNotEmpty()) {
                TextButton(onClick = {
                    GuardLogStore.clear(context, files)
                    version++
                }) { Text(stringResource(R.string.guard_log_delete_all)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        }
    )
}

// ==================== 设置页 ====================

@Composable
private fun GuardSettingsPage(settings: MutableState<GuardSettings>, app: ToolboxApp?) {
    val s = settings.value
    val context = LocalContext.current
    val stats = remember { app?.guardStats }

    val probeHttp = s.probeModes and GuardSettings.PROBE_HTTP_204 != 0
    val probeDns = s.probeModes and GuardSettings.PROBE_DNS != 0
    val probeIcmp = s.probeModes and GuardSettings.PROBE_ICMP != 0
    val probeValidated = s.probeModes and GuardSettings.PROBE_VALIDATED != 0

    // 高成功率档仅在存在成功统计时才出现在下拉菜单中
    val bestAction = remember(stats?.actionStats?.size, s.healStrategy) { stats?.bestAction() }
    // 注意：stringResource 只能在 Composable 上下文直接调用，不能放进 buildList/lambda
    val strategy6Label = bestAction?.let { stringResource(R.string.guard_strategy_6, it) }
    val strategyValues = listOf(
        stringResource(R.string.guard_strategy_0),
        stringResource(R.string.guard_strategy_1),
        stringResource(R.string.guard_strategy_2),
        stringResource(R.string.guard_strategy_3),
        stringResource(R.string.guard_strategy_4),
        stringResource(R.string.guard_strategy_5)
    ) + listOfNotNull(strategy6Label)
    // 保存的档位可能已不在菜单中（如高成功率档但统计被清零）→ 显示时回退标准档
    val strategyIndex = if (s.healStrategy < strategyValues.size) s.healStrategy else 2
    val channelValues = listOf(
        stringResource(R.string.guard_channel_auto),
        stringResource(R.string.shizuku),
        stringResource(R.string.guard_channel_aidl),
        stringResource(R.string.guard_channel_api)
    )

    var showCustomInterval by remember { mutableStateOf(false) }
    var customIntervalText by remember { mutableStateOf(s.checkIntervalSec.toString()) }
    var showLogTypeDialog by remember { mutableStateOf(false) }
    var showLogDirDialog by remember { mutableStateOf(false) }
    var showCustomActionsDialog by remember { mutableStateOf(false) }

    // 自定义档已选动作（LazyColumn 主体非 Composable，remember 需在函数体计算）
    val selectedActions = remember(s.customHealActions) {
        s.customHealActions.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    // SAF 文件夹选择器：选择后持久化读写授权并更新设置
    val dirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            settings.value = s.copy(logDirUri = uri.toString())
        }
    }

    ProvidePreferenceLocals {
        LazyColumn(modifier = Modifier.fillMaxSize()) {

            // ---- 检测设置 ----
            item { PreferenceCategory(title = { Text(stringResource(R.string.guard_cat_probe)) }) }
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
                    val v = state.value.toInt()
                    // 等值守卫：首次组合不写入，仅用户真正拖动后才持久化
                    if (v != s.probeTimeoutMs) {
                        settings.value = s.copy(probeTimeoutMs = v)
                    }
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
                    val v = state.value.toInt()
                    if (v != s.failThreshold) {
                        settings.value = s.copy(failThreshold = v)
                    }
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
                    value = strategyIndex,
                    onValueChange = { index ->
                        settings.value = s.copy(healStrategy = index)
                    },
                    title = { Text(stringResource(R.string.guard_heal_strategy)) },
                    summary = { Text(strategyValues[strategyIndex]) },
                    icon = { Icon(Icons.Filled.Healing, null) },
                    values = strategyValues.indices.toList(),
                    valueToText = { i: Int -> AnnotatedString(strategyValues[i]) },
                    type = ListPreferenceType.DROPDOWN_MENU
                )
            }
            // ---- 自定义档：自选动作组合（弹窗选择，仅在档位=5 时显示） ----
            if (s.healStrategy == 5) {
                item {
                    Preference(
                        title = { Text(stringResource(R.string.guard_custom_actions)) },
                        summary = {
                            Text(
                                if (selectedActions.isEmpty()) stringResource(R.string.guard_custom_actions_none)
                                else stringResource(R.string.guard_custom_actions_value, selectedActions.size)
                            )
                        },
                        icon = { Icon(Icons.Filled.Checklist, null) },
                        onClick = { showCustomActionsDialog = true }
                    )
                }
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
                    val v = state.value.toInt()
                    if (v != s.healVerifyTimeoutSec) {
                        settings.value = s.copy(healVerifyTimeoutSec = v)
                    }
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
                    val v = state.value.toInt()
                    if (v != s.healCooldownBaseSec) {
                        settings.value = s.copy(healCooldownBaseSec = v)
                    }
                }
            }
            item {
                // 退避次数限制（熔断）：无限制 / 2 / 3 / 5 / 10 次
                val presets = GuardSettings.MAX_ATTEMPTS_PRESETS
                val idx = presets.indexOf(s.healMaxAttempts)
                ListPreference(
                    value = if (idx >= 0) idx else 0,
                    onValueChange = { i ->
                        settings.value = s.copy(healMaxAttempts = presets[i])
                    },
                    title = { Text(stringResource(R.string.guard_max_attempts)) },
                    summary = {
                        Text(
                            if (s.healMaxAttempts == 0) stringResource(R.string.guard_max_attempts_unlimited)
                            else stringResource(R.string.guard_max_attempts_value, s.healMaxAttempts)
                        )
                    },
                    icon = { Icon(Icons.Filled.Repeat, null) },
                    values = presets.indices.toList(),
                    valueToText = { i: Int ->
                        AnnotatedString(
                            if (presets[i] == 0) context.getString(R.string.guard_max_attempts_unlimited)
                            else context.getString(R.string.guard_max_attempts_value, presets[i])
                        )
                    },
                    type = ListPreferenceType.DROPDOWN_MENU
                )
            }
            item {
                // 通道可用性检测 + 未授权时触发权限申请：
                // 选 Shizuku 未授权 → 弹 Shizuku 授权；选 RootAIDL 未连接 → 拉起 Root 服务（root 授权弹窗）
                var channelRefresh by remember { mutableIntStateOf(0) }
                val shizukuOk = remember(s.healChannel, channelRefresh) {
                    WifiHealer.isShizukuAvailable()
                }
                val aidlOk = try {
                    app?.aidl?.ipc != null
                } catch (_: Exception) {
                    false
                }
                val availability = when {
                    s.healChannel == 1 -> "Shizuku " + if (shizukuOk) "✓" else "✗"
                    s.healChannel == 2 -> "RootAIDL " + if (aidlOk) "✓" else "✗"
                    s.healChannel == 3 -> "API"
                    shizukuOk -> "Shizuku ✓"
                    aidlOk -> "RootAIDL ✓"
                    else -> "API"
                }
                ListPreference(
                    value = s.healChannel,
                    onValueChange = { ch ->
                        settings.value = s.copy(healChannel = ch)
                        // 选择未授权通道 → 立即发起权限申请
                        if (ch == 1 && !WifiHealer.isShizukuAvailable()) {
                            val a = context.applicationContext as? ToolboxApp
                            if (a != null) {
                                checkShizukuUI(a, onGranted = { channelRefresh++ })
                            }
                        }
                        if (ch == 2) {
                            val a = context.applicationContext as? ToolboxApp
                            if (a?.aidl?.ipc == null) {
                                try {
                                    a?.aidl?.startAIDLServiceRoot()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    },
                    title = { Text(stringResource(R.string.guard_heal_channel)) },
                    summary = { Text("${channelValues[s.healChannel]} · $availability") },
                    icon = { Icon(Icons.Filled.Cable, null) },
                    values = channelValues.indices.toList(),
                    valueToText = { i: Int -> AnnotatedString(channelValues[i]) },
                    type = ListPreferenceType.DROPDOWN_MENU
                )
            }
            item {
                // 通道说明：解答"探测到底用不用特权通道"的疑虑
                BannerTip(
                    text = stringResource(R.string.guard_channel_tip),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
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
                        // 服务运行中：热加载(ACTION_RELOAD)会即时重建前台通知；
                        // 服务未运行：直接同步"未运行"常驻通知（开=显示，关=移除）
                        if (!GuardState.running) {
                            GuardService.syncIdleNotification(context)
                        }
                    },
                    title = { Text(stringResource(R.string.guard_notify_persistent)) },
                    summary = { Text(stringResource(R.string.guard_notify_persistent_tip)) },
                    icon = { Icon(Icons.Filled.Notifications, null) }
                )
            }

            // ---- 后台保活 ----
            item { PreferenceCategory(title = { Text(stringResource(R.string.guard_cat_keepalive)) }) }
            item {
                SwitchPreference(
                    value = s.keepAliveWakeLock,
                    onValueChange = {
                        settings.value = s.copy(keepAliveWakeLock = it)
                        // 服务运行中：热加载即时生效；未运行：下次启动生效
                        if (GuardState.running) {
                            try {
                                context.startService(
                                    Intent(context, GuardService::class.java).apply {
                                        action = GuardService.ACTION_RELOAD
                                    }
                                )
                            } catch (_: Exception) {
                            }
                        }
                    },
                    title = { Text(stringResource(R.string.guard_keepalive_wakelock)) },
                    summary = { Text(stringResource(R.string.guard_keepalive_wakelock_tip)) },
                    icon = { Icon(Icons.Filled.Bolt, null) }
                )
            }
            item {
                // 心跳闹钟看门狗开关（免特权兜底，默认关：后台不检测时手动开）
                SwitchPreference(
                    value = s.keepAliveHeartbeat,
                    onValueChange = {
                        settings.value = s.copy(keepAliveHeartbeat = it)
                        // 服务运行中：热加载即时重排/取消闹钟；未运行：下次启动生效
                        if (GuardState.running) {
                            try {
                                context.startService(
                                    Intent(context, GuardService::class.java).apply {
                                        action = GuardService.ACTION_RELOAD
                                    }
                                )
                            } catch (_: Exception) {
                            }
                        }
                    },
                    title = { Text(stringResource(R.string.guard_keepalive_heartbeat)) },
                    summary = { Text(stringResource(R.string.guard_keepalive_heartbeat_tip)) },
                    icon = { Icon(Icons.Filled.Alarm, null) }
                )
            }
            if (s.keepAliveHeartbeat) {
                item {
                    // 心跳间隔：0=自动（跟随检测间隔、60 秒下限）；预设 1~15 分钟。
                    // Doze 中系统限流约 9 分钟一拍，更短会被静默推迟（不报错）
                    val hbPresets = GuardSettings.HEARTBEAT_INTERVAL_PRESETS
                    val hbLabels = hbPresets.map { sec ->
                        when {
                            sec == 0 -> context.getString(R.string.guard_heartbeat_auto)
                            sec >= 120 -> context.getString(
                                R.string.guard_heartbeat_min_value, sec / 60
                            )
                            else -> context.getString(R.string.guard_interval_value, sec)
                        }
                    }
                    val hbIndex = hbPresets.indexOf(s.heartbeatIntervalSec)
                    ListPreference(
                        value = if (hbIndex >= 0) hbIndex else 0,
                        onValueChange = { index ->
                            if (index in hbPresets.indices) {
                                // setter 自带热加载：服务运行中 ACTION_RELOAD 会按新间隔重排闹钟
                                settings.value = s.copy(
                                    heartbeatIntervalSec = hbPresets[index]
                                )
                            }
                        },
                        title = { Text(stringResource(R.string.guard_keepalive_heartbeat_interval)) },
                        summary = {
                            Text(
                                if (hbIndex >= 0) hbLabels[hbIndex]
                                else context.getString(
                                    R.string.guard_interval_value, s.heartbeatIntervalSec
                                )
                            )
                        },
                        icon = { Icon(Icons.Filled.Schedule, null) },
                        values = hbPresets.indices.toList(),
                        valueToText = { i: Int -> AnnotatedString(hbLabels[i]) },
                        type = ListPreferenceType.DROPDOWN_MENU
                    )
                }
            }
            item {
                // 说明卡片：心跳闹钟的定位与限制（据用户问答结论濃缩）
                BannerTip(
                    text = stringResource(R.string.guard_heartbeat_banner),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
            item {
                // 系统级：电池优化白名单（回前台时自动刷新状态）
                val batteryOk = remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { batteryOk.value = KeepAliveHelper.isIgnoringBatteryOptimizations(context) }
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            batteryOk.value = KeepAliveHelper.isIgnoringBatteryOptimizations(context)
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }
                Preference(
                    title = { Text(stringResource(R.string.guard_keepalive_battery)) },
                    summary = {
                        Text(
                            stringResource(
                                if (batteryOk.value) R.string.guard_keepalive_battery_on
                                else R.string.guard_keepalive_battery_off
                            )
                        )
                    },
                    icon = { Icon(Icons.Filled.BatteryStd, null) },
                    onClick = { KeepAliveHelper.requestBatteryExemption(context) }
                )
            }
            item {
                // Shizuku / Root 一键保活（点击执行 shell 命令并展示逐项结果）
                KeepAliveRow()
            }

            // ---- 日志 ----
            item { PreferenceCategory(title = { Text(stringResource(R.string.guard_cat_log)) }) }
            item {
                // 记录日志类型（多选，默认全部）
                Preference(
                    title = { Text(stringResource(R.string.guard_log_type)) },
                    summary = { Text(logTypeSummary(s.logLevels)) },
                    icon = { Icon(Icons.Filled.ReceiptLong, null) },
                    onClick = { showLogTypeDialog = true }
                )
            }
            item {
                // 日志保存位置：默认应用私有 log 目录，可选 SAF 文件夹并显示位置
                val safName = remember(s.logDirUri) {
                    GuardLogStore.safDirName(context, s.logDirUri)
                }
                val dirDisplay = if (s.logDirUri.isBlank()) {
                    stringResource(
                        R.string.guard_log_dir_private, GuardLogStore.privateDir(context).path
                    )
                } else if (safName != null) {
                    stringResource(R.string.guard_log_dir_custom, safName)
                } else {
                    stringResource(R.string.guard_log_dir_invalid)
                }
                Preference(
                    title = { Text(stringResource(R.string.guard_log_dir)) },
                    summary = { Text(dirDisplay) },
                    icon = { Icon(Icons.Filled.Folder, null) },
                    onClick = { showLogDirDialog = true }
                )
            }
            item {
                // 自动清理：超过保留天数的实时日志与事件历史自动删除
                // （实时日志在检测轮次与状态页打开时清理，事件在记录与统计页打开时清理）
                val presets = GuardSettings.AUTO_CLEAN_PRESETS
                val idx = presets.indexOf(s.autoCleanDays).let { if (it >= 0) it else 0 }
                ListPreference(
                    value = idx,
                    onValueChange = { i ->
                        settings.value = s.copy(autoCleanDays = presets[i])
                    },
                    title = { Text(stringResource(R.string.guard_auto_clean)) },
                    summary = {
                        Text(
                            if (s.autoCleanDays <= 0) stringResource(R.string.guard_auto_clean_off)
                            else stringResource(R.string.guard_auto_clean_value, s.autoCleanDays)
                        )
                    },
                    icon = { Icon(Icons.Filled.AutoDelete, null) },
                    values = presets.indices.toList(),
                    valueToText = { i: Int ->
                        AnnotatedString(
                            if (presets[i] <= 0) context.getString(R.string.guard_auto_clean_off)
                            else context.getString(R.string.guard_auto_clean_value, presets[i])
                        )
                    },
                    type = ListPreferenceType.DROPDOWN_MENU
                )
            }
            item {
                // 自动保存日志：守护运行时新日志自动追加到「日志保存位置」
                // （每天一个 guard-auto-日期.log 文件，自动保留最近 30 个；
                //   由 GuardService 每轮检测后落盘，服务停止时最终落盘一次）
                SwitchPreference(
                    value = s.autoSaveLog,
                    onValueChange = {
                        settings.value = s.copy(autoSaveLog = it)
                        // 服务运行中：热加载即时生效（下轮检测即开始追加）
                        if (GuardState.running) {
                            try {
                                context.startService(
                                    Intent(context, GuardService::class.java).apply {
                                        action = GuardService.ACTION_RELOAD
                                    }
                                )
                            } catch (_: Exception) {
                            }
                        }
                    },
                    title = { Text(stringResource(R.string.guard_auto_save)) },
                    summary = { Text(stringResource(R.string.guard_auto_save_tip)) },
                    icon = { Icon(Icons.Filled.SaveAlt, null) }
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

    // ---- 记录日志类型对话框（多选，默认全部） ----
    if (showLogTypeDialog) {
        var levels by remember { mutableIntStateOf(s.logLevels) }
        AlertDialog(
            onDismissRequest = { showLogTypeDialog = false },
            title = { Text(stringResource(R.string.guard_log_type)) },
            text = {
                Column {
                    val entries = listOf(
                        0 to stringResource(R.string.guard_level_info),
                        1 to stringResource(R.string.guard_level_warn),
                        2 to stringResource(R.string.guard_level_error),
                        3 to stringResource(R.string.guard_level_heal)
                    )
                    // 横向流式排列：放不下自动换行，不再每项独占一行
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        entries.forEach { (bit, label) ->
                            FilterChip(
                                selected = levels and (1 shl bit) != 0,
                                onClick = { levels = levels xor (1 shl bit) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    settings.value = s.copy(logLevels = levels)
                    showLogTypeDialog = false
                }) { Text(stringResource(R.string.btn_ok)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { levels = 0b1111 }) {
                        Text(stringResource(R.string.guard_log_filter_all))
                    }
                    TextButton(onClick = { showLogTypeDialog = false }) {
                        Text(stringResource(R.string.btn_cancel))
                    }
                }
            }
        )
    }

    // ---- 日志保存位置对话框 ----
    if (showLogDirDialog) {
        AlertDialog(
            onDismissRequest = { showLogDirDialog = false },
            title = { Text(stringResource(R.string.guard_log_dir)) },
            text = {
                Column {
                    Text(
                        if (s.logDirUri.isBlank()) stringResource(
                            R.string.guard_log_dir_private, GuardLogStore.privateDir(context).path
                        )
                        else stringResource(
                            R.string.guard_log_dir_custom,
                            GuardLogStore.safDirName(context, s.logDirUri) ?: "?"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = {
                        settings.value = s.copy(logDirUri = "")
                        showLogDirDialog = false
                    }) {
                        Icon(Icons.Filled.Folder, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.guard_log_dir_use_private))
                    }
                    TextButton(onClick = {
                        showLogDirDialog = false
                        try {
                            dirLauncher.launch(null)
                        } catch (_: Exception) {
                        }
                    }) {
                        Icon(Icons.Filled.DriveFileMove, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.guard_log_dir_pick))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDirDialog = false }) {
                    Text(stringResource(R.string.btn_close))
                }
            }
        )
    }

    // ---- 自定义档动作选择对话框（勾选列表，确认时按由轻到重固定顺序存储） ----
    if (showCustomActionsDialog) {
        var checked by remember { mutableStateOf(selectedActions) }
        AlertDialog(
            onDismissRequest = { showCustomActionsDialog = false },
            title = { Text(stringResource(R.string.guard_custom_actions)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.guard_custom_actions_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        GuardSettings.CUSTOM_ACTION_IDS.forEach { actionId ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        checked = if (actionId in checked) checked - actionId
                                        else checked + actionId
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = actionId in checked,
                                    onCheckedChange = { on ->
                                        checked = if (on) checked + actionId
                                        else checked - actionId
                                    }
                                )
                                Column {
                                    Text(
                                        customActionLabel(actionId),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        customActionTip(actionId),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // 按由轻到重固定顺序存储（planActions 执行顺序与显示一致）
                    val ordered = GuardSettings.CUSTOM_ACTION_IDS.filter { it in checked }
                    settings.value = s.copy(customHealActions = ordered.joinToString(","))
                    showCustomActionsDialog = false
                }) { Text(stringResource(R.string.btn_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showCustomActionsDialog = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

/** 自定义档动作 id → 本地化名称 */
@Composable
private fun customActionLabel(actionId: String): String = stringResource(
    when (actionId) {
        "reassociate" -> R.string.guard_action_reassociate
        "reconnect" -> R.string.guard_action_reconnect
        "disable+enable" -> R.string.guard_action_disable_enable
        "cmd connect" -> R.string.guard_action_cmd_connect
        else -> R.string.guard_action_wifi_cycle
    }
)

/** 自定义档动作 id → 本地化说明 */
@Composable
private fun customActionTip(actionId: String): String = stringResource(
    when (actionId) {
        "reassociate" -> R.string.guard_action_reassociate_tip
        "reconnect" -> R.string.guard_action_reconnect_tip
        "disable+enable" -> R.string.guard_action_disable_enable_tip
        "cmd connect" -> R.string.guard_action_cmd_connect_tip
        else -> R.string.guard_action_wifi_cycle_tip
    }
)

/**
 * Shizuku / Root 一键保活行：
 * - Shizuku 按钮经 uid 2000 shell 逐条执行保活命令；
 * - Root 按钮直接 su -c 单次 shell 跑完全部命令（独立通道，不依赖应用内 RootAIDL
 *   服务，有 Magisk/KernelSU 授权即可）；
 * - 恢复默认按钮撤销保活命令（白名单移除 + appops 重置 default），
 *   自动选通道：Shizuku 可用走 Shizuku，否则 su；
 * - 结果只显示最近一次操作的单条反馈（原 Shizuku/Root/恢复默认三行会叠加），
 *   「恢复默认」结果文案自说明（如「✓ 已恢复默认」），不加「恢复默认：」前缀；
 *   全部生效 ✓；部分生效时才列技术项（Doze/RUN_ANY/RUN/FGS）便于排障。
 */

/** 最近一次保活操作的结果（channel=null 表示「恢复默认」，文案自说明无需前缀） */
private data class KeepAliveOutcome(
    val channel: String?,
    val isRevert: Boolean,
    val result: KeepAliveHelper.KeepAliveResult
)

@Composable
private fun KeepAliveRow() {
    val context = LocalContext.current
    val app = context.applicationContext as? ToolboxApp
    val scope = rememberCoroutineScope()
    var shizukuRunning by remember { mutableStateOf(false) }
    var rootRunning by remember { mutableStateOf(false) }
    var revertRunning by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<KeepAliveOutcome?>(null) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }

    val pkg = context.packageName

    /** 语义化结果：全部生效 ✓；否则 生效 N/4 · 未生效项（技术名便于排障） */
    fun resultText(r: KeepAliveHelper.KeepAliveResult, okText: String, partialText: String,
                   failedText: String): String {
        if (r.all) return okText
        val failed = listOfNotNull(
            if (!r.doze) "Doze" else null,
            if (!r.runAnyBg) "RUN_ANY" else null,
            if (!r.runBg) "RUN" else null,
            if (!r.fgs) "FGS" else null
        )
        val n = 4 - failed.size
        val head = partialText.format(n)
        return if (failed.isEmpty()) head else "$head · ${failedText.format(failed.joinToString(", "))}"
    }

    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (WifiHealer.isShizukuAvailable()) {
                        shizukuRunning = true
                        scope.launch {
                            val r = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                KeepAliveHelper.applyViaShizuku(pkg)
                            }
                            outcome = KeepAliveOutcome("Shizuku", false, r)
                            shizukuRunning = false
                        }
                    } else {
                        val a = app
                        if (a != null) {
                            checkShizukuUI(a, onGranted = {
                                toastMsg = context.getString(R.string.guard_keepalive_granted)
                            })
                        }
                    }
                },
                enabled = !shizukuRunning
            ) {
                Text(
                    if (shizukuRunning) stringResource(R.string.guard_keepalive_running)
                    else stringResource(R.string.guard_keepalive_shizuku)
                )
            }
            Button(
                onClick = {
                    // 直接 su -c：无需 RootAIDL 服务，Magisk/KernelSU 授权即可
                    rootRunning = true
                    scope.launch {
                        val r = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            KeepAliveHelper.applyViaSu(pkg)
                        }
                        outcome = KeepAliveOutcome("Root", false, r)
                        rootRunning = false
                        // su 授权失败（无 Magisk/KernelSU 或已拒绝）：脚本未真正执行
                        // （首段标记 ---DOZE--- 未出现在输出中）
                        if (!r.raw.contains("---DOZE---")) {
                            toastMsg = context.getString(R.string.guard_keepalive_su_failed)
                        }
                    }
                },
                enabled = !rootRunning
            ) {
                Text(
                    if (rootRunning) stringResource(R.string.guard_keepalive_running)
                    else stringResource(R.string.guard_keepalive_root)
                )
            }
        }
        // 恢复默认（撤销保活命令）：Shizuku 可用则 Shizuku，否则 su
        OutlinedButton(
            onClick = {
                revertRunning = true
                scope.launch {
                    val viaShizuku = WifiHealer.isShizukuAvailable()
                    val r = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (viaShizuku) KeepAliveHelper.revertViaShizuku(pkg)
                        else KeepAliveHelper.revertViaSu(pkg)
                    }
                    outcome = KeepAliveOutcome(null, true, r)
                    revertRunning = false
                    if (!viaShizuku && !r.raw.contains("---DOZE---")) {
                        toastMsg = context.getString(R.string.guard_keepalive_su_failed)
                    }
                }
            },
            enabled = !revertRunning,
            modifier = Modifier.padding(top = 4.dp)
        ) {
            Text(
                if (revertRunning) stringResource(R.string.guard_keepalive_running)
                else stringResource(R.string.guard_keepalive_revert)
            )
        }
        val allOk = stringResource(R.string.guard_keepalive_all_ok)
        val partial = stringResource(R.string.guard_keepalive_partial)
        val failedItems = stringResource(R.string.guard_keepalive_failed_items)
        val revertDone = stringResource(R.string.guard_keepalive_revert_done)
        val revertPartial = stringResource(R.string.guard_keepalive_revert_partial)
        val revertFailed = stringResource(R.string.guard_keepalive_revert_failed)
        // 单条结果行：只显示最近一次操作（Shizuku/Root 带通道前缀；恢复默认文案自说明）
        outcome?.let { o ->
            val text = if (o.isRevert)
                resultText(o.result, revertDone, revertPartial, revertFailed)
            else
                resultText(o.result, allOk, partial, failedItems)
            Text(
                if (o.channel != null) "${o.channel}: $text" else text,
                style = MaterialTheme.typography.bodySmall,
                color = if (o.result.all) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** 记录日志类型 → 摘要文本（全部 / 用 · 连接的类型名） */
@Composable
private fun logTypeSummary(mask: Int): String {
    if (mask == 0) return stringResource(R.string.guard_log_type_none)
    if (mask == 0b1111) return stringResource(R.string.guard_log_filter_all)
    // stringResource 需在 Composable 上下文直接调用，先取值再拼列表
    val info = stringResource(R.string.guard_level_info)
    val warn = stringResource(R.string.guard_level_warn)
    val error = stringResource(R.string.guard_level_error)
    val heal = stringResource(R.string.guard_level_heal)
    val names = listOfNotNull(
        if (mask and 1 != 0) info else null,
        if (mask and 2 != 0) warn else null,
        if (mask and 4 != 0) error else null,
        if (mask and 8 != 0) heal else null
    )
    return names.joinToString(" · ")
}

private fun toggleMask(current: Int, bit: Int, on: Boolean): Int =
    if (on) current or bit else current and bit.inv()

// ==================== 统计页 ====================

@Composable
private fun StatsPage(
    app: ToolboxApp?,
    logDirUri: String,
    eventsExpanded: Boolean,
    onEventsExpandedChange: (Boolean) -> Unit
) {
    val stats = app?.guardStats
    var refreshKey by remember { mutableIntStateOf(0) }
    // 打开统计页时拉一次最新数据 + 自动清理过期事件（服务未运行也能清）
    LaunchedEffect(Unit) {
        stats?.autoPrune()
        refreshKey++
    }

    if (stats == null) return

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // ---- Apple Health「收藏」风格大数字瓦片 2×2 ----
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricTile(
                    value = stats.totalChecks.toString(),
                    label = stringResource(R.string.guard_stat_checks),
                    icon = Icons.Outlined.FactCheck,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    value = stats.totalFailures.toString(),
                    label = stringResource(R.string.guard_stat_failures),
                    icon = Icons.Outlined.CloudOff,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricTile(
                    value = stats.totalHeals.toString(),
                    label = stringResource(R.string.guard_stat_heals),
                    icon = Icons.Outlined.Healing,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    value = stats.totalRecovered.toString(),
                    label = stringResource(R.string.guard_stat_recovered),
                    icon = Icons.Outlined.TaskAlt,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        // 全部统计清零按钮在事件历史卡片工具行内（与「清空事件」相邻，带二次确认）

        // ---- 动作有效率卡（标题行含总成功率） ----
        if (stats.actionStats.isNotEmpty()) {
            item {
                ActionRateCard(stats)
            }
        }

        // ---- 事件历史（单卡合并：标题 + 实时日志同款工具行 + 卡内滚动列表） ----
        // 展开状态由 GuardScreen 层持有（切页不重置，默认折叠）
        item {
            EventHistoryCard(stats, logDirUri, eventsExpanded, onEventsExpandedChange)
        }
    }
}

/**
 * 大数字统计瓦片（参考 Apple Health「收藏」卡片）：
 * 左上角彩色小图标（圆角方块底）+ 大号数字 + 灰色小标签。
 */
@Composable
private fun MetricTile(
    value: String,
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, Modifier.size(19.dp), tint = tint)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                value,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 自愈动作有效率卡：标题行右侧显示总成功率（恢复/自愈），
 * 每动作一行（名称 + 次数 + 百分比）+ 细圆角进度条。
 */
@Composable
private fun ActionRateCard(stats: GuardStats) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.guard_stat_action_rate),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (stats.totalHeals > 0) {
                    val rate = stats.totalRecovered * 100 / stats.totalHeals
                    Text(
                        stringResource(R.string.guard_stat_success_rate, rate),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            stats.actionStats.entries.sortedByDescending { it.value.first }
                .forEach { entry ->
                    val action = entry.key
                    val count = entry.value
                    val rate = if (count.first > 0) count.second * 100 / count.first else 0
                    val rateColor = if (rate >= 50) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                action,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${count.second}/${count.first}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "$rate%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = rateColor
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (count.first > 0) count.second.toFloat() / count.first else 0f
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = rateColor,
                            trackColor = rateColor.copy(alpha = 0.15f)
                        )
                    }
                }
        }
    }
}

/**
 * 事件历史卡片（与实时日志卡片同款交互，无筛选）：
 * - 工具行：复制 / 保存 / 分享 / 管理(已保存文件列表) / 清空事件（仅事件，不清统计） /
 *   清零（全部统计+事件，带二次确认）
 * - 保存文件名前缀 guard-events- 与实时日志 guard- 区分
 * - 事件列表卡内滚动显示（全部最近 100 条，时间新→旧）
 * - [expanded] 展开状态由 GuardScreen 层持有（切页不重置，默认折叠）
 */
@Composable
private fun EventHistoryCard(
    stats: GuardStats,
    logDirUri: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val events = stats.events
    var showManage by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(250),
        label = "eventsChevron"
    )

    fun toast(msg: String) {
        toastMsg = msg
    }

    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }

    /** 单条事件的单行文本（复制/保存用） */
    fun eventLine(e: GuardEvent): String {
        val result = if (e.recovered) "✓ 恢复" else "✗ 未恢复"
        return "[${GuardStats.formatTime(e.time)}] ${e.ssid.ifEmpty { "?" }} · ${e.actions} → $result · 耗时 ${e.costMs / 1000}s · 失败项: ${e.failedProbes}"
    }

    /** 全部事件文本（时间正序旧→新，与日志文件阅读习惯一致） */
    fun eventsToText(): String {
        if (events.isEmpty()) return ""
        val head = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        return buildString {
            appendLine(context.getString(R.string.guard_events_file_header))
            appendLine(context.getString(R.string.guard_log_file_exported_at, head))
            appendLine("--------")
            events.reversed().forEach { appendLine(eventLine(it)) }
        }
    }

    fun saveNow(): StoredLogFile? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        return GuardLogStore.save(
            context, logDirUri, "guard-events-$stamp.log", eventsToText()
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            // ---- 标题 + 操作按钮行（与实时日志卡片同款，长按弹出名称提示气泡） ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.guard_stat_events),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TipIconButton(
                    onClick = {
                        if (events.isEmpty()) return@TipIconButton
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText(
                                "guard_events",
                                events.reversed().joinToString("\n") { eventLine(it) }
                            )
                        )
                        toast(context.getString(R.string.guard_events_copied, events.size))
                    },
                    tip = stringResource(R.string.guard_log_copy_desc),
                    icon = Icons.Outlined.ContentCopy
                )
                TipIconButton(
                    onClick = {
                        val saved = saveNow()
                        toast(
                            if (saved != null) context.getString(
                                R.string.guard_log_saved, saved.name
                            )
                            else context.getString(R.string.guard_log_save_fail)
                        )
                    },
                    tip = stringResource(R.string.guard_log_save_desc),
                    icon = Icons.Outlined.Save
                )
                TipIconButton(
                    onClick = {
                        val saved = saveNow()
                        if (saved != null) shareStoredFile(context, saved)
                        else toast(context.getString(R.string.guard_log_save_fail))
                    },
                    tip = stringResource(R.string.guard_log_export_desc),
                    icon = Icons.Outlined.IosShare
                )
                TipIconButton(
                    onClick = { showManage = true },
                    tip = stringResource(R.string.guard_log_manage_desc),
                    icon = Icons.Outlined.FolderOpen
                )
                TipIconButton(
                    onClick = {
                        stats.clearEvents()
                        toast(context.getString(R.string.guard_events_cleared))
                    },
                    tip = stringResource(R.string.guard_log_clear_desc),
                    icon = Icons.Outlined.DeleteSweep
                )
                // 全部统计清零（统计+事件，与「清空事件」区分；带二次确认）
                TipIconButton(
                    onClick = { showResetConfirm = true },
                    tip = stringResource(R.string.guard_stat_reset_desc),
                    icon = Icons.Outlined.RestartAlt
                )
                // 展开 / 收起（箭头随状态旋转）
                TipIconButton(
                    onClick = { onExpandedChange(!expanded) },
                    tip = stringResource(R.string.guard_log_expand_desc),
                    icon = Icons.Outlined.ExpandMore,
                    iconModifier = Modifier.rotate(chevronRotation)
                )
            }

            // ---- 事件列表（展开时显示，展开收起带动画；卡内滚动，iOS 分组列表风格） ----
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(tween(200)),
                exit = shrinkVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeOut(tween(150))
            ) {
                if (events.isEmpty()) {
                    Text(
                        stringResource(R.string.guard_no_events),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        events.forEachIndexed { idx, e ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        GuardStats.formatTime(e.time),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        e.ssid,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${e.actions} → ${if (e.recovered) "✓" else "✗"} ${e.costMs / 1000}s (${e.failedProbes})",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (e.recovered) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                            if (idx < events.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManage) {
        SavedLogsDialog(context = context, logDirUri = logDirUri, onDismiss = { showManage = false })
    }

    // 全部统计清零二次确认（清空后不可恢复）
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.guard_stat_reset)) },
            text = { Text(stringResource(R.string.guard_stat_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    stats.reset()
                    showResetConfirm = false
                }) {
                    Text(stringResource(R.string.guard_stat_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
