package com.wifi.toolbox.ui.items

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wifi.toolbox.R
import com.wifi.toolbox.utils.LogState
import kotlinx.coroutines.launch

@Composable
fun rememberLogState(): LogState = remember { LogState() }

@Composable
fun LogView(
    logState: LogState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val verticalScrollState = rememberScrollState()
    val horizontalScrollState = rememberScrollState()

    LaunchedEffect(logState.logs.size, logState.autoScroll) {
        if (logState.autoScroll) {
            verticalScrollState.animateScrollTo(verticalScrollState.maxValue)
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(verticalScrollState)
                .then(if (!logState.wordWrap) Modifier.horizontalScroll(horizontalScrollState) else Modifier)
        ) {
            SelectionContainer {
                val allLogs = logState.logs.joinToString("\n")
                Text(
                    text = allLogs,
                    fontFamily = FontFamily(
                        Font(resId = R.font.mono, weight = FontWeight.Normal)
                    ),
                    fontSize = 14.sp,
                    softWrap = logState.wordWrap,
                    style = TextStyle(
                        lineHeight = 19.sp,
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both
                        ), fontFeatureSettings = "liga 0, calt 0"
                    ),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp,8.dp,64.dp,8.dp)
                )
            }
        }

        LogActionsFab(
            logState = logState,
            context = context,
            verticalScrollState = verticalScrollState,
            fabVisible = true
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.LogActionsFab(
    logState: LogState,
    context: Context,
    verticalScrollState: ScrollState,
    fabVisible: Boolean,
    modifier: Modifier = Modifier
) {
    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val logCopyLabel = stringResource(R.string.log_copy_label)

    val menuItems = listOf(
        FabMenuItem(
            label = stringResource(R.string.log_auto_scroll),
            icon = Icons.Default.KeyboardDoubleArrowDown,
            isSelected = logState.autoScroll,
            onClick = { logState.autoScroll = !logState.autoScroll }
        ),
        FabMenuItem(
            label = stringResource(R.string.log_word_wrap),
            icon = Icons.AutoMirrored.Filled.WrapText,
            isSelected = logState.wordWrap,
            onClick = { logState.wordWrap = !logState.wordWrap }
        ),
        FabMenuItem(
            label = stringResource(R.string.log_clear),
            icon = Icons.Filled.DeleteSweep,
            onClick = { logState.clear() }
        ),
        FabMenuItem(
            label = stringResource(R.string.log_copy),
            icon = Icons.Filled.ContentCopy,
            onClick = {
                val clipboardManager =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = ClipData.newPlainText(logCopyLabel, logState.logs.joinToString("\n"))
                clipboardManager.setPrimaryClip(clipData)
            }
        ),
        FabMenuItem(
            label = stringResource(R.string.log_scroll_to_top),
            icon = Icons.Default.VerticalAlignTop,
            onClick = { coroutineScope.launch { verticalScrollState.animateScrollTo(0) } }
        ),
        FabMenuItem(
            label = stringResource(R.string.log_scroll_to_bottom),
            icon = Icons.Default.VerticalAlignBottom,
            onClick = {
                coroutineScope.launch {
                    verticalScrollState.animateScrollTo(
                        verticalScrollState.maxValue
                    )
                }
            }
        )
    )

    CustomFabMenu(
        expanded = fabMenuExpanded,
        onCheckedChange = { fabMenuExpanded = it },
        items = menuItems,
        visible = fabVisible,
        modifier = modifier
    )
}

@Preview(showBackground = true, device = "spec:width=1080px,height=1080px,dpi=480")
@Composable
fun LogViewPreview() {
    val logState = rememberLogState()
    LaunchedEffect(Unit) {
        logState.addLog("Log message 1")
        logState.addLog("Another log message", true)
        logState.addLog("Log message 2")
        logState.setLine("This is the last line, modified.")
    }
    LogView(logState = logState)
}