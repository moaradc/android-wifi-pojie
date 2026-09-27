package com.wifi.toolbox.ui.screen

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifi.toolbox.R
import com.wifi.toolbox.services.NetLogState
import com.wifi.toolbox.services.NetworkLogService
import com.wifi.toolbox.structs.NetLogSettings
import com.wifi.toolbox.ui.items.TipIconButton
import com.wifi.toolbox.utils.GuardLogStore
import com.wifi.toolbox.utils.NetLogStore
import com.wifi.toolbox.utils.rememberNetLogSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网络日志页：独立捕获功能的入口。
 *
 * 两页结构：
 * - 捕获：录制状态 + 控制（开始/停止/立即快照）+ 实时事件预览
 * - 会话：设置 + 历史会话列表（分享 zip / 删除）
 */
@Composable
fun NetLogScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val settings = rememberNetLogSettings(context)
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }

    val pageList = remember(context) {
        listOf(
            object : com.wifi.toolbox.ui.items.NavPage {
                override val name = context.getString(R.string.netlog_tab_capture)
                override val selectedIcon = Icons.Filled.PlayArrow
                override val unselectedIcon = Icons.Filled.PlayArrow
                override val content = @Composable {
                    CapturePage(settings.value, { settings.value = it })
                }
            },
            object : com.wifi.toolbox.ui.items.NavPage {
                override val name = context.getString(R.string.netlog_tab_sessions)
                override val selectedIcon = Icons.Filled.Folder
                override val unselectedIcon = Icons.Filled.Folder
                override val content = @Composable {
                    SessionsPage(settings.value, { settings.value = it })
                }
            }
        )
    }

    com.wifi.toolbox.ui.items.NavContainer(
        pages = pageList,
        selectedIndex = pageIndex,
        onIndexChange = { pageIndex = it },
        subtitle = stringResource(R.string.netlog_name),
        onMenuClick = onMenuClick
    )
}

// ==================== 捕获页 ====================

@Composable
private fun CapturePage(
    settings: NetLogSettings,
    onSettingsChange: (NetLogSettings) -> Unit
) {
    val context = LocalContext.current
    val running = NetLogState.running
    val clipboard = LocalClipboardManager.current

    // Toast 用本地状态驱动（组合期间不直接调副作用，与守护实时日志同做法）
    var toastMsg by remember { mutableStateOf<String?>(null) }
    fun toast(msg: String) {
        toastMsg = msg
    }
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toastMsg = null
        }
    }
    val eventCount = NetLogState.eventCount
    val logLines = NetLogState.logLines
    val logCapped = NetLogState.logCapped
    val channel = NetLogState.channel
    // 1s 滴答：录制时长实时刷新（elapsedText 读取 tick 触发本组合域重组）
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(running) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick++
        }
    }
    val elapsedText = if (running && NetLogState.startedAt > 0 && tick >= 0) {
        formatDuration(System.currentTimeMillis() - NetLogState.startedAt)
    } else ""

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ---- 录制状态卡 ----
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 录制指示灯（颜色在 Composable 上下文取好再传入：
                        // Canvas 的 DrawScope 非组合上下文，不能读 MaterialTheme）
                        val dotColor = if (running) Color(0xFFEF5350)
                        else MaterialTheme.colorScheme.outlineVariant
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier.size(12.dp)
                        ) {
                            drawCircle(color = dotColor)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    if (running) R.string.netlog_recording
                                    else R.string.netlog_stopped
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (running && NetLogState.startedAt > 0) {
                                    stringResource(
                                        R.string.netlog_duration_events,
                                        elapsedText,
                                        eventCount
                                    )
                                } else {
                                    stringResource(R.string.netlog_hint)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (running) {
                            IconButton(onClick = { NetworkLogService.stop(context) }) {
                                Icon(
                                    Icons.Filled.Stop, stringResource(R.string.netlog_stop),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // 统计行：通道 / 系统日志行数 / 封顶状态
                    StatRow(
                        stringResource(R.string.netlog_channel_label),
                        if (channel.isEmpty()) "-" else channel
                    )
                    if (running) {
                        StatRow(
                            stringResource(R.string.netlog_logcat_lines),
                            if (logCapped) {
                                stringResource(R.string.netlog_log_capped, logLines)
                            } else {
                                logLines.toString()
                            }
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // 开始 / 停止 主按钮
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        androidx.compose.material3.Button(
                            onClick = { NetworkLogService.start(context) },
                            enabled = !running,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.netlog_start))
                        }
                        androidx.compose.material3.OutlinedButton(
                            onClick = { NetworkLogService.stop(context) },
                            enabled = running,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.netlog_stop))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 立即快照（会话中随时手动抓一轮系统状态）
                    androidx.compose.material3.OutlinedButton(
                        onClick = { NetworkLogService.snapshotNow(context) },
                        enabled = running,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.CameraAlt, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.netlog_snapshot_now))
                    }
                }
            }
        }

        // ---- 实时事件预览 ----
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.netlog_live_preview),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            stringResource(R.string.netlog_preview_count, NetLogState.previewList().size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 复制 / 清空（图标与长按提示与守护实时日志一致）
                        TipIconButton(
                            onClick = {
                                // 优先复制当前会话的完整时间线（会话结束后仍可复制），
                                // 无会话文件时回退实时预览缓冲
                                val text = run {
                                    val name = NetLogState.sessionName
                                    if (name.isNotEmpty()) {
                                        val dir = File(NetLogStore.root(context), name)
                                        val lines = NetLogStore.readTimelineTail(dir, 10_000)
                                        if (lines.isNotEmpty()) return@run lines
                                    }
                                    NetLogState.previewList()
                                }
                                if (text.isEmpty()) return@TipIconButton
                                clipboard.setText(AnnotatedString(text.joinToString("\n")))
                                toast(context.getString(R.string.netlog_preview_copied, text.size))
                            },
                            tip = stringResource(R.string.guard_log_copy_desc),
                            icon = Icons.Outlined.ContentCopy
                        )
                        TipIconButton(
                            onClick = {
                                NetLogState.clearPreview()
                                toast(context.getString(R.string.netlog_preview_cleared))
                            },
                            tip = stringResource(R.string.guard_log_clear_desc),
                            icon = Icons.Outlined.DeleteSweep
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    val preview = NetLogState.previewList()
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                    ) {
                        if (preview.isEmpty()) {
                            Text(
                                stringResource(R.string.netlog_preview_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .align(Alignment.Center)
                            )
                        } else {
                            val scroll = rememberScrollState()
                            // 新事件自动滚到底部
                            LaunchedEffect(preview.size) {
                                scroll.animateScrollTo(scroll.maxValue)
                            }
                            Column(
                                modifier = Modifier
                                    .padding(10.dp)
                                    .verticalScroll(scroll)
                            ) {
                                preview.forEach { line ->
                                    Text(
                                        line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

// ==================== 会话页 ====================

@Composable
private fun SessionsPage(
    settings: NetLogSettings,
    onSettingsChange: (NetLogSettings) -> Unit
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val sessions = remember(version) {
        NetLogStore.listSessions(context)
    }

    // ---- 日志保存位置（SAF 自选文件夹，与网络守护同机制）----
    var showLogDirDialog by remember { mutableStateOf(false) }
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
            onSettingsChange(settings.copy(logDirUri = uri.toString()))
            showLogDirDialog = false
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // ---- 设置卡 ----
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    SettingSwitchRow(
                        title = stringResource(R.string.netlog_setting_syslog),
                        subtitle = stringResource(R.string.netlog_setting_syslog_desc),
                        checked = settings.captureSystemLog
                    ) { onSettingsChange(settings.copy(captureSystemLog = it)) }

                    SettingSwitchRow(
                        title = stringResource(R.string.netlog_setting_snapshot),
                        subtitle = stringResource(R.string.netlog_setting_snapshot_desc),
                        checked = settings.snapshotOnEvent
                    ) { onSettingsChange(settings.copy(snapshotOnEvent = it)) }

                    SettingSwitchRow(
                        title = stringResource(R.string.netlog_setting_probe),
                        subtitle = stringResource(R.string.netlog_setting_probe_desc),
                        checked = settings.probeTimeline
                    ) { onSettingsChange(settings.copy(probeTimeline = it)) }

                    SettingSwitchRow(
                        title = stringResource(R.string.netlog_auto_save),
                        subtitle = stringResource(R.string.netlog_auto_save_tip),
                        checked = settings.autoSaveLog
                    ) { onSettingsChange(settings.copy(autoSaveLog = it)) }

                    // 日志保存位置：点击弹选择框（默认私有 / SAF 自选文件夹）
                    val safName = remember(settings.logDirUri) {
                        GuardLogStore.safDirName(context, settings.logDirUri)
                    }
                    val dirDisplay = if (settings.logDirUri.isBlank()) {
                        stringResource(
                            R.string.guard_log_dir_private, NetLogStore.root(context).path
                        )
                    } else if (safName != null) {
                        stringResource(R.string.guard_log_dir_custom, safName)
                    } else {
                        stringResource(R.string.guard_log_dir_invalid)
                    }
                    SettingClickRow(
                        title = stringResource(R.string.guard_log_dir),
                        subtitle = dirDisplay
                    ) { showLogDirDialog = true }

                    SettingStepperRow(
                        title = stringResource(R.string.netlog_setting_keep),
                        subtitle = stringResource(R.string.netlog_setting_keep_desc),
                        value = settings.keepSessions,
                        unit = stringResource(R.string.netlog_unit_sessions),
                        min = 0, max = 50,
                        onValueChange = { onSettingsChange(settings.copy(keepSessions = it)) }
                    )

                    SettingStepperRow(
                        title = stringResource(R.string.netlog_setting_maxlog),
                        subtitle = stringResource(R.string.netlog_setting_maxlog_desc),
                        value = settings.maxLogMb,
                        unit = "MB",
                        min = 1, max = 200,
                        onValueChange = { onSettingsChange(settings.copy(maxLogMb = it)) }
                    )
                }
            }
        }

        // ---- 会话列表 ----
        item {
            Text(
                stringResource(R.string.netlog_sessions_title, sessions.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
        }

        if (sessions.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.netlog_sessions_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            items(sessions, key = { it.name }) { session ->
                SessionCard(
                    session = session,
                    onDeleted = { version++ }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }

    // ---- 日志保存位置对话框（结构对齐守护设置页同款对话框）----
    if (showLogDirDialog) {
        AlertDialog(
            onDismissRequest = { showLogDirDialog = false },
            title = { Text(stringResource(R.string.guard_log_dir)) },
            text = {
                Column {
                    Text(
                        if (settings.logDirUri.isBlank()) stringResource(
                            R.string.guard_log_dir_private, NetLogStore.root(context).path
                        )
                        else stringResource(
                            R.string.guard_log_dir_custom,
                            GuardLogStore.safDirName(context, settings.logDirUri) ?: "?"
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        onSettingsChange(settings.copy(logDirUri = ""))
                        showLogDirDialog = false
                    }) {
                        Text(stringResource(R.string.guard_log_dir_use_private))
                    }
                    TextButton(onClick = {
                        try {
                            dirLauncher.launch(null)
                        } catch (_: Exception) {
                        }
                    }) {
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

@Composable
private fun SessionCard(session: File, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val meta = remember(session) { NetLogStore.readMeta(session) }
    val size = remember(session) { NetLogStore.sessionSize(session) }
    var expanded by remember(session) { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        session.name.removePrefix("session_"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${NetLogStore.formatSize(size)}" +
                                (meta?.optString("channel")?.takeIf { it.isNotEmpty() }
                                    ?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        val zip = NetLogStore.zipSession(context, session)
                        if (zip != null) {
                            shareZip(context, zip)
                        }
                    }
                ) {
                    Icon(
                        Icons.Outlined.Share,
                        stringResource(R.string.netlog_session_share),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = {
                        NetLogStore.deleteSession(session)
                        onDeleted()
                    }
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        stringResource(R.string.netlog_session_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(8.dp))
                // 展开详情：meta 摘要 + 时间线尾部预览
                meta?.let { m ->
                    listOf(
                        "device" to m.optString("device"),
                        "android" to m.optString("android"),
                        "duration" to formatDuration((m.optLong("durationSec")) * 1000),
                        "events" to m.optInt("timelineEvents").toString(),
                        "logcat" to m.optInt("logcatLines").toString()
                    ).filter { it.second.isNotEmpty() && it.second != "0" }
                        .forEach { (k, v) -> StatRow(k, v) }
                }
                val tail = remember(session, expanded) {
                    NetLogStore.readTimelineTail(session, 30)
                }
                if (tail.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        tail.forEach { line ->
                            Text(
                                line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingStepperRow(
    title: String,
    subtitle: String,
    value: Int,
    unit: String,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (value == 0 && min == 0) stringResource(R.string.netlog_unlimited)
            else "$value $unit",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = { onValueChange((value - 1).coerceAtLeast(min)) }) { Text("−") }
        TextButton(onClick = { onValueChange((value + 1).coerceAtMost(max)) }) { Text("＋") }
    }
}

/** 点击型设置行（保存位置等弹对话框项用；样式与开关/步进行一致） */
@Composable
private fun SettingClickRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Filled.Folder, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

// ==================== 工具 ====================

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s < 3600) "%d:%02d".format(s / 60, s % 60)
    else "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

private fun shareZip(context: Context, zip: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", zip
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, zip.name))
    } catch (_: Exception) {
    }
}
