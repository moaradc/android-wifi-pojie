package com.wifi.toolbox.services.pojie

import android.net.ConnectivityManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.services.*
import com.wifi.toolbox.structs.*
import com.wifi.toolbox.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.coroutines.resumeWithException

/**
 * 负责管理单次连接并获取结果
 */
@Suppress("DEPRECATION")
class ConnectWorker(
    private val service: PojieService
) {
    private var readLogMode = 0
    private var logcatService: WifiLogcatService? = null
    private var broadcastService: WifiBroadcastService? = null
    private var connectWifiApi29Callback: ConnectivityManager.NetworkCallback? = null

    /**
     * 初始化日志收集服务
     */
    fun initLogServices(settings: PojieSettings) {
        readLogMode = settings.readLogMode
        when (readLogMode) {
            0 -> throw Exception(service.getString(R.string.log_mode_empty))
            1 -> {
                logcatService = WifiLogcatService(service, settings)
                service.log(service.getString(R.string.log_logcat_started))
            }

            2 -> {
                broadcastService = WifiBroadcastService(service)
                service.log(service.getString(R.string.log_broadcast_started))
            }
        }
    }

    /**
     * 关闭所有服务并释放资源
     */
    fun closeServices() {
        logcatService?.close()
        broadcastService?.close()
    }

    /**
     * 执行具体的任务逻辑
     */
    suspend fun performTaskLogic(
        app: ToolboxApp, task: SinglePojieTask, settings: PojieSettings
    ): Int = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        val connectMode = settings.connectMode


        val targetNetId = if (task.password.isEmpty()) try {
            val savedList = when (connectMode) {
                1 -> ShizukuUtil.getSavedWifiList()
                2 -> AidlServiceHelper.getSavedWifiList(app)
                3 -> ApiUtil.getSavedWifiList(app)
                4 -> throw Exception(service.getString(R.string.error_api29_empty_pass))
                else -> emptyList()
            }
            savedList.find {
                it.SSID == "\"${task.ssid}\"" || it.SSID == task.ssid
            }?.networkId ?: throw Exception()
        } catch (_: Exception) {
            throw Exception(service.getString(R.string.error_saved_pass_failed))
        } else -1


        when (connectMode) {
            0 -> throw Exception(service.getString(R.string.connect_mode_empty))
            1 -> {
                if (task.password.isEmpty()) ShizukuUtil.enableNetwork(targetNetId)
                else ShizukuUtil.connectToWifi(task.ssid, task.password)
            }

            2 -> {
                if (task.password.isEmpty()) AidlServiceHelper.enableNetwork(app, targetNetId)
                else AidlServiceHelper.connectToWifi(app, task.ssid, task.password)
            }

            3 -> {
                if (task.password.isEmpty()) {
                    ApiUtil.enableNetwork(app, targetNetId)
                } else {
                    val netId = ApiUtil.connectToWifiApi28(service, task.ssid, task.password)
                    if (netId == -1) throw Exception(service.getString(R.string.connect_wifi_failed))
                }
            }

            else -> if (connectMode != 4) throw Exception(service.getString(R.string.tip_not_completed) + "(connectMode=$connectMode)")
        }

        try {
            withTimeout(app.pojieConfig.maxTryTime.toLong()) {
                if (connectMode == 4) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        suspendCancellableCoroutine { continuation ->
                            launch(Dispatchers.Main) {
                                try {
                                    connectWifiApi29Callback =
                                        connectToWifiApi29(task.ssid, task.password) { success ->
                                            if (continuation.isActive) {
                                                continuation.resume(
                                                    if (success) SinglePojieTask.RESULT_SUCCESS else SinglePojieTask.RESULT_FAILED,
                                                    null
                                                )
                                            }
                                        }
                                } catch (e: Exception) {
                                    if (continuation.isActive) continuation.resumeWithException(e)
                                }
                            }

                            continuation.invokeOnCancellation {
                                connectWifiApi29Callback?.let {
                                    ApiUtil.cancelWifiRequest(
                                        service,
                                        it
                                    )
                                }
                                connectWifiApi29Callback = null
                            }
                        }
                    } else throw Exception(service.getString(R.string.device_too_old))
                } else {
                    val flow = when (readLogMode) {
                        1 -> logcatService?.logFlow
                        2 -> {
                            if (app.pojieConfig.failureFlag == 2) {
                                throw Exception(service.getString(R.string.broadcast_not_support_handshake))
                            }
                            broadcastService?.setTargetSsid(task.ssid)
                            broadcastService?.logFlow
                        }

                        else -> null
                    }

                    if (flow == null) throw Exception(service.getString(R.string.log_flow_uninitialized))

                    var finalResult = -1
                    flow.first { data ->
                        finalResult = checkLogDataSync(data, app, task, startTime, connectMode)
                        finalResult != -1
                    }
                    finalResult
                }
            }
        } catch (_: TimeoutCancellationException) {
            SinglePojieTask.RESULT_TIMEOUT
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            service.log(service.getString(R.string.error_string, e.message ?: ""))
            SinglePojieTask.RESULT_ERROR
        }
    }

    private fun checkLogDataSync(
        data: WifiLogData,
        app: ToolboxApp,
        task: SinglePojieTask,
        startTime: Long,
        connectMode: Int
    ): Int {
        val isMatch =
            connectMode == 4 || (data.ssid == task.ssid && data.eventStartTime >= startTime)
        if (!isMatch) return -1

        return when (data.event) {
            WifiLogData.EVENT_WIFI_CONNECTED -> if (connectMode != 4) SinglePojieTask.RESULT_SUCCESS else -1
            WifiLogData.EVENT_CONNECT_FAILED -> {
                if (readLogMode == 2 || System.currentTimeMillis() - data.eventStartTime > 2000) SinglePojieTask.RESULT_FAILED else -1
            }

            WifiLogData.EVENT_HANDSHAKE -> {
                if (checkHandshakeFailure(
                        data,
                        app.pojieConfig
                    )
                ) SinglePojieTask.RESULT_FAILED else -1
            }

            WifiLogData.EVENT_CONNECT_ERROR -> SinglePojieTask.RESULT_ERROR_TRANSIENT
            else -> -1
        }
    }

    private fun checkHandshakeFailure(data: WifiLogData, config: PojieConfig): Boolean {
        return when (config.failureFlag) {
            1 -> data.handshakeUseTime > config.timeout
            2 -> data.handshakeCount > config.maxHandshakeCount
            else -> false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun connectToWifiApi29(
        ssid: String, pass: String, callback: (Boolean) -> Unit
    ): ConnectivityManager.NetworkCallback? {
        val foregroundActivity = ActivityStack.get()
        return if (foregroundActivity != null) {
            withContext(Dispatchers.Main) {
                ApiUtil.connectToWifiApi29(foregroundActivity, ssid, pass, callback)
            }
        } else {
            ApiUtil.connectToWifiApi29(service, ssid, pass, callback)
        }
    }

    fun cleanConnection(settings: PojieSettings) {
        if (connectWifiApi29Callback != null) {
            ApiUtil.cancelWifiRequest(service, connectWifiApi29Callback!!)
            connectWifiApi29Callback = null
        } else {
            when (settings.enableMode) {
                1 -> ShizukuUtil.disconnectWifi()
                2 -> ApiUtil.disconnectWifi(service)
            }
        }
    }

    fun forgetNetwork(settings: PojieSettings, ssid: String): Boolean {
        val app = service.applicationContext as ToolboxApp
        val netId = when (settings.connectMode) {
            1 -> ShizukuUtil.getNetIdBySsid(ssid)
            2 -> AidlServiceHelper.getNetIdBySsid(app, ssid)
            3 -> ApiUtil.getNetIdBySsid(service, ssid)
            else -> -1
        }
        if (netId == -1) return false
        return when (settings.connectMode) {
            1 -> {
                ShizukuUtil.forgetNetwork(netId)
                true
            }

            2 -> {
                AidlServiceHelper.forgetNetwork(app, netId)
                true
            }

            3 -> ApiUtil.forgetNetwork(service, netId)
            else -> false
        }
    }

    fun getLogTime(): String {
        val df = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return "[${df.format(java.util.Date())}]"
    }
}