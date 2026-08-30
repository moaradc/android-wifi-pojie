package com.wifi.toolbox.services.pojie

import android.content.Context
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
import kotlinx.coroutines.flow.merge
import java.util.Locale
import kotlin.coroutines.resumeWithException

/**
 * 负责管理单次连接并获取结果
 */
@Suppress("DEPRECATION")
class ConnectWorker(
    private val service: PojieService
) {
    companion object {
        /**
         * 当前存活的 API29 WifiNetworkSpecifier 请求（进程级单例）。
         *
         * specifier 连接由「已注册的回调」维持：成功后不注销才能保性连接不拆；
         * 若放任多个请求共存，新旧两个 specifier 请求会争夺同一 WiFi 射频
         * 造成来回切换。因此新请求发出前先注销旧请求，成功后由本字段持行
         * （连接保持），直到进程退出或下一次破解请求替换。
         */
        @Volatile
        var activeApi29Callback: ConnectivityManager.NetworkCallback? = null

        /** 主动释放当前存活的 specifier 请求（断开其维持的连接） */
        fun releaseActiveApi29Request(context: Context) {
            activeApi29Callback?.let {
                ApiUtil.cancelWifiRequest(context, it)
            }
            activeApi29Callback = null
        }
    }

    private var readLogMode = 0
    private var logcatService: WifiLogcatService? = null
    private var broadcastService: WifiBroadcastService? = null
    private var connectWifiApi29Callback: ConnectivityManager.NetworkCallback? = null

    /**
     * 初始化日志收集服务
     *
     * Logcat 模式同时启动广播监听作为兜底：logcat 能否读到 wpa_supplicant
     * 日志取决于执行通道身份与设备输出策略（部分通道无权限、部分设备
     * 不输出），单点依赖会导致结果判定永远等不到事件（真机反馈：直接
     * 尝试卡在「运行中 0/1 0.0%」无限重连）。广播监听不依赖任何特权，
     * 成功/失败判定由它兜底，logcat 保留握手计数/计时能力。
     */
    fun initLogServices(settings: PojieSettings) {
        readLogMode = settings.readLogMode
        when (readLogMode) {
            0 -> throw Exception(service.getString(R.string.log_mode_empty))
            1 -> {
                logcatService = WifiLogcatService(service, settings) { service.log(it) }
                broadcastService = WifiBroadcastService(service)
                service.log(service.getString(R.string.log_logcat_started))
                service.log(service.getString(R.string.log_broadcast_fallback))
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
                        // 替换进程内旧的 specifier 请求：释放旧连接，防止新旧请求争夺射频
                        releaseActiveApi29Request(service)
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
                                    // 成功的连接必须靠已注册的回调维持，登记为进程级存活请求
                                    ConnectWorker.activeApi29Callback = connectWifiApi29Callback
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
                                if (ConnectWorker.activeApi29Callback != null) {
                                    ConnectWorker.activeApi29Callback = null
                                }
                            }
                        }
                    } else throw Exception(service.getString(R.string.device_too_old))
                } else {
                    val flow = when (readLogMode) {
                        1 -> {
                            // 双流合并：logcat 事件（握手计数/计时）+ 广播事件
                            // （成功/失败判定兜底），先到先判
                            logcatService?.setTargetSsid(task.ssid)
                            broadcastService?.setTargetSsid(task.ssid)
                            val logcatFlow = logcatService?.logFlow
                            val broadcastFlow = broadcastService?.logFlow
                            when {
                                logcatFlow != null && broadcastFlow != null ->
                                    merge(logcatFlow, broadcastFlow)

                                else -> logcatFlow ?: broadcastFlow
                            }
                        }

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
            // 失败/取消路径：注销请求（若 onUnavailable 已自注销，内部幂等吞掉）
            try {
                ApiUtil.cancelWifiRequest(service, connectWifiApi29Callback!!)
            } catch (_: Exception) {
            }
            connectWifiApi29Callback = null
            if (ConnectWorker.activeApi29Callback != null) {
                ConnectWorker.activeApi29Callback = null
            }
        } else {
            try {
                when (settings.enableMode) {
                    1 -> ShizukuUtil.disconnectWifi()
                    2 -> ApiUtil.disconnectWifi(service)
                }
            } catch (_: Exception) {
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