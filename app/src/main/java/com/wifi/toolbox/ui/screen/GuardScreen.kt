package com.wifi.toolbox.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import com.wifi.toolbox.ui.items.checkShizukuUI
import com.wifi.toolbox.utils.GuardLogStore
import com.wifi.toolbox.utils.GuardStats
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
                    GuardSettingsPage(settings, app)
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

    // 1 秒滴答：驱动"上次检测 X 秒前"实时刷新
    // （原实现只在检测轮次写入 lastCheckTime 时重组，页面上的秒数会冻住不动）
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

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
                        val ago = (nowMs - lastCheck) / 1000
                        Text(
                            text = stringResource(
                                R.string.guard_last_check,
                                formatAgo(ago)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // ---- 通道可见性：默认单行截断，点击动画展开全部，长按复制 ----
                    val shellChannel = GuardState.lastShellChannel
                    val healChannel = GuardState.lastHealChannel
                    if (shellChannel.isNotEmpty() || healChannel.isNotEmpty()) {
                        ChannelLine(shellChannel, healChannel)
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
            // ---- 实时日志（复制/清空/保存/导出/管理/多选筛选） ----
            LiveLogCard(context, settings.logDirUri)
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
 */
@Composable
private fun LiveLogCard(context: Context, logDirUri: String) {
    val logs = GuardState.logList()
    var filterMask by rememberSaveable { mutableIntStateOf(LOG_FILTER_ALL) }
    var showManage by remember { mutableStateOf(false) }
    var toastMsg by remember { mutableStateOf<String?>(null) }

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
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            // ---- 标题 + 操作按钮行 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.guard_live_log),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        val entries = visibleEntries()
                        if (entries.isEmpty()) return@IconButton
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText(
                                "guard_log", entries.joinToString("\n") { formatEntry(it) }
                            )
                        )
                        toast(context.getString(R.string.guard_log_copied, entries.size))
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        stringResource(R.string.guard_log_copy_desc),
                        Modifier.size(17.dp)
                    )
                }
                IconButton(
                    onClick = {
                        val saved = saveNow()
                        toast(
                            if (saved != null) context.getString(
                                R.string.guard_log_saved, saved.name
                            )
                            else context.getString(R.string.guard_log_save_fail)
                        )
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Outlined.Save,
                        stringResource(R.string.guard_log_save_desc),
                        Modifier.size(17.dp)
                    )
                }
                IconButton(
                    onClick = {
                        val saved = saveNow()
                        if (saved != null) shareStoredFile(context, saved)
                        else toast(context.getString(R.string.guard_log_save_fail))
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Outlined.IosShare,
                        stringResource(R.string.guard_log_export_desc),
                        Modifier.size(17.dp)
                    )
                }
                IconButton(
                    onClick = { showManage = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        stringResource(R.string.guard_log_manage_desc),
                        Modifier.size(17.dp)
                    )
                }
                IconButton(
                    onClick = {
                        GuardState.clearLogs()
                        toast(context.getString(R.string.guard_log_cleared))
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        stringResource(R.string.guard_log_clear_desc),
                        Modifier.size(17.dp)
                    )
                }
            }

            // ---- 筛选行（多选） ----
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
            // ---- 自定义档：自选动作组合（仅在档位=5 时显示） ----
            if (s.healStrategy == 5) {
                item {
                    BannerTip(
                        text = stringResource(R.string.guard_custom_actions_tip),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                val selectedActionsRef = selectedActions
                GuardSettings.CUSTOM_ACTION_IDS.forEach { actionId ->
                    item {
                        CheckboxPreference(
                            value = actionId in selectedActions,
                            onValueChange = { on ->
                                val next = if (on) selectedActionsRef + actionId
                                else selectedActionsRef - actionId
                                // 按由轻到重固定顺序存储
                                val ordered = GuardSettings.CUSTOM_ACTION_IDS
                                    .filter { it in next }
                                settings.value = s.copy(
                                    customHealActions = ordered.joinToString(",")
                                )
                            },
                            title = { Text(customActionLabel(actionId)) },
                            summary = { Text(customActionTip(actionId)) }
                        )
                    }
                }
            }
            // ---- 高成功率档当前优选信息 ----
            if (s.healStrategy == 6) {
                item {
                    BannerTip(
                        text = stringResource(
                            R.string.guard_strategy_best_tip,
                            bestAction ?: "reconnect"
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
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
                BannerTip(
                    text = stringResource(R.string.guard_max_attempts_tip),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
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
                BannerTip(
                    text = stringResource(R.string.guard_log_dir_tip),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
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
                    Text(
                        stringResource(R.string.guard_log_type_tip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    val entries = listOf(
                        0 to stringResource(R.string.guard_level_info),
                        1 to stringResource(R.string.guard_level_warn),
                        2 to stringResource(R.string.guard_level_error),
                        3 to stringResource(R.string.guard_level_heal)
                    )
                    entries.forEach { (bit, label) ->
                        FilterChip(
                            selected = levels and (1 shl bit) != 0,
                            onClick = { levels = levels xor (1 shl bit) },
                            label = { Text(label) },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
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

        // ---- 事件历史（标题卡 + 每条事件独立 item，修复长列表布局异常） ----
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Column(
                    Modifier.padding(
                        start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.guard_stat_events),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { stats.reset() },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(stringResource(R.string.guard_stat_reset))
                        }
                    }
                    if (stats.events.isEmpty()) {
                        Text(
                            stringResource(R.string.guard_no_log),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        stats.events.take(30).forEachIndexed { index, e ->
            item(key = "event-$index-${e.time}") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Column(
                        Modifier.padding(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp
                        )
                    ) {
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
