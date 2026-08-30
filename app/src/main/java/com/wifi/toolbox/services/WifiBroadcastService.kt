@file:Suppress("DEPRECATION")
package com.wifi.toolbox.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.WifiLogData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class WifiBroadcastService(private val context: Context) : AutoCloseable {

    private val app = context.applicationContext as ToolboxApp
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _logFlow = MutableSharedFlow<WifiLogData>(extraBufferCapacity = 64)
    val logFlow = _logFlow.asSharedFlow()

    private var connectStartTime: Long = 0L
    private var handshakeTimeoutJob: Job? = null
    private var currentSsid: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return

            if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION == action) {
                val newState =
                    intent.getParcelableExtra<SupplicantState>(WifiManager.EXTRA_NEW_STATE)
                val error = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1)

                if (error == WifiManager.ERROR_AUTHENTICATING) {
                    // 认证失败 = 密码错误，属定论信号：不再限定判定模式。
                    // 历史实现仅在「密码超时」(failureFlag=0)模式下生效，其余
                    // 模式只能干等整体超时才推进，拖慢破解循环。
                    cancelTimeout()
                    emitEvent(WifiLogData.EVENT_CONNECT_FAILED)
                } else if (newState == SupplicantState.COMPLETED) {
                    // 官方语义：COMPLETED = 全部认证流程完成，WPA2 下即 4 次
                    // 握手成功 —— 密码正确的最早可靠信号，无需等待网络层
                    // CONNECTED（DHCP 完成），判定更早且不受 DHCP 慢影响。
                    cancelTimeout()
                    emitConnectedEvent()
                } else if (newState == SupplicantState.FOUR_WAY_HANDSHAKE && app.pojieConfig.failureFlag == 1) {
                    startHandshakeTimeoutMonitor()
                }
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION == action) {
                val info = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                if (info != null && info.isConnected) {
                    // 网络层 CONNECTED 作为 COMPLETED 的兜底信号（若 COMPLETED
                    // 广播因时序被过滤，此处仍能给出成功判定）
                    cancelTimeout()
                    emitConnectedEvent()
                }
            }
        }
    }

    init {
        val filter = IntentFilter()
        filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        context.registerReceiver(receiver, filter)
    }

    fun setTargetSsid(ssid: String) {
        currentSsid = ssid
        connectStartTime = System.currentTimeMillis()
        cancelTimeout()
    }

    private fun startHandshakeTimeoutMonitor() {
        if (handshakeTimeoutJob != null) return

        val timeout = app.pojieConfig.timeout.toLong()
        handshakeTimeoutJob = scope.launch {
            delay(timeout)
            if (isActive) {
                _logFlow.tryEmit(
                    WifiLogData(
                        WifiLogData.EVENT_HANDSHAKE,
                        connectStartTime,
                        currentSsid,
                        (timeout + 1000).toInt(),
                        99
                    )
                )
            }
        }
    }

    private fun cancelTimeout() {
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
    }

    private fun emitEvent(eventId: Int) {
        _logFlow.tryEmit(
            WifiLogData(
                eventId,
                connectStartTime,
                currentSsid
            )
        )
    }

    /**
     * 成功事件：优先携带实际连接 SSID（定位可用时可读），用于将实际网络与
     * 目标比对，防止尝试期间系统自动重连他网造成误判成功；读不到实际
     * SSID 时沿用目标 SSID 假设（与历史行为一致）。
     */
    private fun emitConnectedEvent() {
        _logFlow.tryEmit(
            WifiLogData(
                WifiLogData.EVENT_WIFI_CONNECTED,
                connectStartTime,
                readActualConnectedSsid() ?: currentSsid
            )
        )
    }

    private fun readActualConnectedSsid(): String? = try {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ssid = wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")
        if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") null else ssid
    } catch (_: Exception) {
        null
    }

    override fun close() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        cancelTimeout()
        scope.cancel()
    }
}