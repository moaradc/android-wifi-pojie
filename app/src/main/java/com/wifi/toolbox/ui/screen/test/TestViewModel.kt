package com.wifi.toolbox.ui.screen.test

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.utils.AidlServiceHelper
import com.wifi.toolbox.utils.CommandRunner
import com.wifi.toolbox.utils.LogState
import com.wifi.toolbox.utils.ShizukuUtil

class TestViewModel : ViewModel() {
    val logState = LogState()
    var selectedTabIndex by mutableIntStateOf(0)
    var logCardExpanded by mutableStateOf(true)

    var shellCommand by mutableStateOf("")
    var shellMethodIndex by mutableIntStateOf(0)
    var isCommandRunning by mutableStateOf(false)
    private var stopCommandRunnable: Runnable? = null

    fun runShellCommand(app: ToolboxApp, onFinishTip: (Int) -> String) {
        if (isCommandRunning) {
            stopCommandRunnable?.run()
            isCommandRunning = false
            stopCommandRunnable = null
            return
        }

        isCommandRunning = true
        val commandToExecute = shellCommand
        val onOutput: (String) -> Unit = { logState.addLog(it) }
        val onFinish: (CommandRunner.CommandResult) -> Unit = { result ->
            logState.addLog(onFinishTip(result.exitCode))
            isCommandRunning = false
            stopCommandRunnable = null
        }

        stopCommandRunnable = when (shellMethodIndex) {
            0 -> CommandRunner.executeCommand(commandToExecute, false, onOutput, onFinish)
            1 -> ShizukuUtil.executeCommand(commandToExecute, onOutput, onFinish)
            2 -> AidlServiceHelper.executeCommand(app, commandToExecute, onOutput, onFinish)
            3 -> CommandRunner.executeCommand(commandToExecute, true, onOutput, onFinish)
            else -> null
        }
    }

    override fun onCleared() {
        stopCommandRunnable?.run()
        super.onCleared()
    }
}