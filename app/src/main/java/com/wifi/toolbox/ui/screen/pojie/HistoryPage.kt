package com.wifi.toolbox.ui.screen.pojie

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.ui.items.TagItem
import com.wifi.toolbox.ui.items.TagType
import com.wifi.toolbox.structs.PojieHistoryItem

@Composable
fun HistoryPage() {
    val app = LocalContext.current.applicationContext as ToolboxApp
    val historyList by app.pojieHistory.historyFlow.collectAsState()

    val sortedList = remember(historyList) {
        historyList.sortedWith(
            compareByDescending<PojieHistoryItem> { it.password != null }
                .thenByDescending { it.lasttime }
        )
    }

    var pendingDeleteItem by remember { mutableStateOf<PojieHistoryItem?>(null) }

    if (sortedList.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.history_nothing),
                textAlign = TextAlign.Center,
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(sortedList) { _, item ->
                HistoryItem(
                    item = item,
                    modifier = Modifier.clickable {},
                    onDeleteClick = { pendingDeleteItem = item }
                )
            }
        }
    }

    pendingDeleteItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeleteItem = null },
            title = { Text(stringResource(R.string.confirm_delete)) },
            text = { Text(stringResource(R.string.confirm_delete_tip, item.ssid)) },
            confirmButton = {
                Button(
                    onClick = {
                        app.pojieHistory.deleteHistory(item.ssid)
                        pendingDeleteItem = null
                    },
                ) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteItem = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            }
        )
    }
}

@Composable
fun HistoryItem(
    modifier: Modifier = Modifier,
    item: PojieHistoryItem,
    onDeleteClick: () -> Unit
) {
    val timeStr = remember(item.lasttime) {
        if (item.lasttime <= 0L) null
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(item.lasttime))
    }

    Row(
        modifier = modifier
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Wifi,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.ssid,
                    style = MaterialTheme.typography.bodyLarge
                )
                item.password?.let { pwd ->
                    TagItem(
                        text = stringResource(R.string.password_string, pwd),
                        type = TagType.Tertiary
                    )
                }
            }
            Text(
                text = stringResource(
                    R.string.progress_number_string,
                    item.progress,
                    item.passwords.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (timeStr != null) {
                Text(
                    text = stringResource(R.string.last_try_time_string, timeStr),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        IconButton(onClick = onDeleteClick) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.delete),
            )
        }
    }
}