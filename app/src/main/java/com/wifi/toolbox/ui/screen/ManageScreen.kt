package com.wifi.toolbox.ui.screen

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.launch
import com.wifi.toolbox.ui.items.TagItem
import com.wifi.toolbox.ui.items.TagType
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

// ==================== Tab1：扫描 ====================

@Composable
private fun ScanTabPage(controller: ManagerController, app: ToolboxApp) {
    val context = LocalContext.current
    var expandedSsid by rememberSaveable { mutableStateOf<String?>(null) }

    // 进入页面自动扫一轮
    LaunchedEffect(Unit) { controller.refreshScan() }

    // 操作结果轻提示
    LaunchedEffect(controller.opMessage) {
        controller.opMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            controller.clearOpMessage()
        }
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
            if (controller.scanSource != 0) {
                Spacer(Modifier.width(8.dp))
                TagItem(
                    text = stringResource(R.string.mgr_scan_source) + " " +
                            managerChannelName(controller.scanSource),
                    type = TagType.Tertiary
                )
            }
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
                            onToggle = {
                                expandedSsid = if (expandedSsid == wifi.ssid) null else wifi.ssid
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 信号强度统一图标（颜色分级见 signalColor，避免依赖冷门图标变体） */

private fun signalColor(level: Int): Color = when {
    level >= -66 -> Color(0xFF2E7D32)
    level >= -77 -> Color(0xFFF9A825)
    else -> Color(0xFFC62828)
}

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
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val (band, channel) = freqToBandChannel(wifi.frequency)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { onToggle() }
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Wifi,
                    contentDescription = null,
                    tint = signalColor(wifi.level),
                    modifier = Modifier.size(22.dp)
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
                        TagItem(securitySummary(wifi.capabilities))
                        if (isCurrent) TagItem(
                            stringResource(R.string.mgr_current_mark),
                            TagType.Primary
                        )
                        if (wifi.savedInfo != null) TagItem(
                            stringResource(R.string.mgr_saved_mark),
                            TagType.Secondary
                        )
                        if (wifi.pojieHistoryItem?.password != null) TagItem(
                            stringResource(R.string.mgr_cracked_mark),
                            TagType.Tertiary
                        )
                        if (band.isNotEmpty()) TagItem("$band CH$channel")
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

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp)
        )
        Text(
            text = value.ifEmpty { "-" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
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
            if (controller.savedSource != 0) {
                Spacer(Modifier.width(8.dp))
                TagItem(
                    text = stringResource(R.string.mgr_scan_source) + " " +
                            managerChannelName(controller.savedSource),
                    type = TagType.Tertiary
                )
            }
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
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
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
                        entry.password.isEmpty() ->
                            stringResource(R.string.mgr_pwd_hidden)
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
                    // 系统有配置但密码不可见（Android 10+ 限制），给出解释性小标签
                    Text(
                        stringResource(R.string.mgr_pwd_hidden),
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

// ==================== Tab3：当前网络 + 诊断 ====================

@Composable
private fun NetworkTabPage(controller: ManagerController) {
    val info = controller.currentInfo
    val (band, channel) = freqToBandChannel(info.frequencyMhz)

    // 进入页面与手动刷新时读取当前网络
    LaunchedEffect(Unit) { controller.refreshCurrent() }
    val portalText = stringResource(R.string.mgr_portal)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---- 当前连接卡 ----
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (info.connected) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.mgr_tab_network),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.weight(1f))
                        if (info.connected) {
                            TagItem(
                                if (info.validated) stringResource(R.string.mgr_validated)
                                else stringResource(R.string.mgr_not_validated),
                                if (info.validated) TagType.Primary else TagType.Tertiary
                            )
                        }
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

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = info.ssid.ifEmpty {
                            stringResource(
                                if (info.connected) R.string.wifi_connected_generic
                                else R.string.not_connected
                            )
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (info.bssid.isNotEmpty()) {
                        Text(
                            text = info.bssid,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (info.connected && info.rssi > -200) {
                        Spacer(Modifier.height(12.dp))
                        SignalMeter(info.rssi)
                    }

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))

                    DetailRow(stringResource(R.string.mgr_lbl_rssi), "${info.rssi} dBm")
                    if (info.linkSpeedMbps > 0) {
                        DetailRow(
                            stringResource(R.string.mgr_lbl_speed),
                            "${info.linkSpeedMbps} Mbps"
                        )
                    }
                    if (info.frequencyMhz > 0) {
                        DetailRow(
                            stringResource(R.string.mgr_lbl_band),
                            "${info.frequencyMhz} MHz · $band"
                        )
                        if (channel > 0) {
                            DetailRow(
                                stringResource(R.string.mgr_lbl_channel),
                                channel.toString()
                            )
                        }
                    }
                    DetailRow(stringResource(R.string.mgr_lbl_ip), info.ipAddress)
                    DetailRow(stringResource(R.string.mgr_lbl_gateway), info.gateway)
                    DetailRow(
                        stringResource(R.string.mgr_lbl_dns),
                        info.dnsServers.joinToString(", ")
                    )
                    if (info.dhcpServer.isNotEmpty()) {
                        DetailRow(stringResource(R.string.mgr_lbl_dhcp), info.dhcpServer)
                    }
                    if (info.leaseDurationSec > 0) {
                        DetailRow(
                            stringResource(R.string.mgr_lbl_lease),
                            "${info.leaseDurationSec}s"
                        )
                    }
                }
            }
        }

        // ---- 诊断卡 ----
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.HealthAndSafety,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.mgr_diagnose),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.mgr_diagnose_tip),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { controller.runDiagnosis() },
                            enabled = !controller.diagnosing && info.connected
                        ) {
                            if (controller.diagnosing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.mgr_diagnose))
                            }
                        }
                    }

                    if (controller.diagnosisResults.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(10.dp))
                        controller.diagnosisResults.forEach { result ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (result.ok) Icons.Rounded.CheckCircle
                                    else Icons.Rounded.Cancel,
                                    contentDescription = null,
                                    tint = if (result.ok) Color(0xFF2E7D32) else Color(0xFFC62828),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = result.mode,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(88.dp)
                                )
                                Text(
                                    text = if (result.isPortal) portalText else result.detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
