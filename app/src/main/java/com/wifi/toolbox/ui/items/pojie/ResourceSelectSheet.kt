package com.wifi.toolbox.ui.items.pojie

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import com.wifi.toolbox.structs.WifiInfo
import com.wifi.toolbox.ui.items.LogView
import com.wifi.toolbox.utils.*
import kotlinx.coroutines.isActive

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
fun ResourceSelectSheet(
    show: Boolean,
    wifiInfo: WifiInfo,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    if (!show) return

    val context = LocalContext.current
    val logState = remember { LogState() }
    var showLog by remember { mutableStateOf(false) }
    val allResources = remember { PojieStore.getAll(context) }
    var selectedIds by remember { mutableStateOf(listOf<String>()) }
    var isRunning by remember { mutableStateOf(false) }
    var resultList by remember { mutableStateOf(listOf<String>()) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sortedList = remember(selectedIds) {
        val selected = selectedIds.mapNotNull { id -> allResources.find { it.id == id } }
        val unselected = allResources.filter { it.id !in selectedIds }
        selected + unselected
    }

    val recalculatingTip = stringResource(R.string.log_config_changed_recalculating)

    LaunchedEffect(selectedIds) {
        if (selectedIds.isEmpty()) {
            resultList = emptyList()
            isRunning = false
            return@LaunchedEffect
        }

        isRunning = true
        resultList = emptyList()
        logState.clear()
        logState.addLog(recalculatingTip)

        try {
            val selectedResources =
                selectedIds.mapNotNull { id -> allResources.find { it.id == id } }
            val results = ResourcesRunner.run(
                context = context,
                resources = selectedResources,
                wifiInfo = wifiInfo,
                onProgress = { _, _ -> },
                onLog = { if (isActive) logState.addLog(it) }
            )
            if (isActive) {
                resultList = results
            }
        } finally {
            if (isActive) {
                isRunning = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = stringResource(R.string.title_select_password_resource),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
        ) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(sortedList, key = { it.id }) { res ->
                    val isSelected = res.id in selectedIds
                    PojieResourceItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .clickable {
                                selectedIds = if (isSelected) {
                                    selectedIds - res.id
                                } else {
                                    selectedIds + res.id
                                }
                            },
                        res = res,
                        checkbox = isSelected
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showLog = !showLog }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (showLog) stringResource(R.string.btn_collapse_log) else stringResource(R.string.btn_expand_log),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                        val angle by animateFloatAsState(if (showLog) 180f else 0f)
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(angle)
                        )
                    }
                }

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Button(
                        enabled = !isRunning && resultList.isNotEmpty(),
                        onClick = {
                            onConfirm(resultList)
                            onDismiss()
                        }
                    ) {
                        Text(
                            if (isRunning) stringResource(R.string.status_running_dots)
                            else if (selectedIds.isEmpty()) stringResource(R.string.no_choose_tip)
                            else stringResource(R.string.btn_finish_with_count, resultList.size)
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showLog,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            LogView(
                logState = logState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
        }
    }
}