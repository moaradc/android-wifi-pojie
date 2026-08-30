@file:Suppress("DEPRECATION")

package com.wifi.toolbox.utils

import android.net.wifi.WifiConfiguration
import com.wifi.toolbox.IToolboxCallback
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.WifiInfo
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.function.Consumer
import com.wifi.toolbox.R

object AidlServiceHelper {
    fun getWifiScanResults(app: ToolboxApp): List<WifiInfo> {
        val result =
            app.aidl.ipc?.wifiScanResults ?: throw Exception(app.getString(R.string.error_aidl_not_bound))

        val results = mutableListOf<WifiInfo>()
        result.forEach {
            val ssid = it.getString("ssid")!!
            val bssid = it.getString("bssid")!!
            val level = it.getInt("level")
            val capabilities = it.getString("capabilities")!!

            results.add(WifiInfo(ssid, level, bssid, capabilities))
        }
        results.sortByDescending { it.level }
        return results
    }

    fun getSavedWifiList(app: ToolboxApp): List<WifiConfiguration> {
        val result =
            app.aidl.ipc?.savedWifiList ?: throw Exception(app.getString(R.string.error_aidl_not_bound))
        return result.map {
            WifiConfiguration().apply {
                networkId = it.getInt("networkId")
                SSID = it.getString("SSID")
                preSharedKey = it.getString("preSharedKey")
            }
        }
    }

    /**
     * 执行命令
     * @param app ToolboxApp上下文
     * @param command 命令文本
     * @param onOutputReceived 当接收到输出时的回调函数
     * @param onCommandFinished 当命令全部结束时的回调函数
     * @return 停止执行的函数
     */
    fun executeCommand(
        app: ToolboxApp,
        command: String,
        onOutputReceived: Consumer<String>?,
        onCommandFinished: Consumer<CommandRunner.CommandResult>?
    ): Runnable {
        val callback = object : IToolboxCallback.Stub() {
            override fun onOutput(line: String) {
                onOutputReceived?.accept(line)
            }

            override fun onFinished(output: String, exitCode: Int) {
                onCommandFinished?.accept(CommandRunner.CommandResult(output, exitCode))
            }
        }

        val taskId = app.aidl.ipc?.executeCommand(command, callback) ?: -1

        if (taskId == -1) {
            onCommandFinished?.accept(CommandRunner.CommandResult("", -1))
        }

        return Runnable {
            app.aidl.ipc?.stopCommand(taskId)
        }
    }

    /**
     * 同步执行命令，等待全部执行完毕后返回输出结果
     * @param app ToolboxApp上下文
     * @param command 命令文本
     * @return CompletableFuture 异步返回命令执行的完整输出
     */
    fun executeCommandSync(app: ToolboxApp, command: String): CommandRunner.CommandResult {
        val future = CompletableFuture<CommandRunner.CommandResult?>()

        executeCommand(app, command, null) { future.complete(it) }

        try {
            return future.get() ?: CommandRunner.CommandResult("", -1)
        } catch (e: ExecutionException) {
            throw RuntimeException(e)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }

    fun connectToWifi(app: ToolboxApp, ssid: String, password: String): Int {
        return app.aidl.ipc?.connectToWifi(ssid, password)
            ?: throw Exception(app.getString(R.string.error_aidl_not_bound))
    }

    fun startWifiScan(app: ToolboxApp, allowUseCommand: Boolean = false): Boolean {
        return app.aidl.ipc?.startWifiScan(allowUseCommand)
            ?: throw Exception(app.getString(R.string.error_aidl_not_bound))
    }

    fun setWifiEnabled(app: ToolboxApp, enabled: Boolean) {
        app.aidl.ipc?.setWifiEnabled(enabled)
            ?: throw Exception(app.getString(R.string.error_aidl_not_bound))
    }

    fun disconnectWifi(app: ToolboxApp) {
        app.aidl.ipc?.disconnectWifi()
            ?: throw Exception(app.getString(R.string.error_aidl_not_bound))
    }

    fun enableNetwork(app: ToolboxApp, netId: Int) {
        app.aidl.ipc?.enableNetwork(netId)
            ?: throw Exception(app.getString(R.string.error_aidl_not_bound))
    }

    fun getNetIdBySsid(app: ToolboxApp, ssid: String): Int {
        return app.aidl.ipc?.getNetIdBySsid(ssid)
            ?: throw Exception(app.getString(R.string.error_aidl_not_bound))
    }

    fun forgetNetwork(app: ToolboxApp, netId: Int) {
        app.aidl.ipc?.forgetNetwork(netId)
            ?: throw Exception(app.getString(R.string.error_aidl_not_bound))
    }
}