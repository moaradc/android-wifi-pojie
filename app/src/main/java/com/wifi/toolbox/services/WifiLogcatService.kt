package com.wifi.toolbox.services

import android.util.Log
import android.content.Context
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.PojieSettings
import com.wifi.toolbox.structs.WifiLogData
import com.wifi.toolbox.utils.AidlServiceHelper
import com.wifi.toolbox.utils.CommandRunner
import com.wifi.toolbox.utils.ShizukuUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * 通过命令行 logcat 流式监听 wpa_supplicant / WifiService 日志事件。
 *
 * 频道可靠性说明：logcat 能否读到日志取决于执行通道身份（root/shell 可读，
 * 普通应用自 Android 4.1 起无 READ_LOGS 被拒）且部分设备不输出
 * wpa_supplicant 日志。因此本服务只负责「能读到则提供事件」，连接结果
 * 判定由 ConnectWorker 并行接入的广播监听兜底，不再单点依赖本服务。
 */
class WifiLogcatService(
    private val context: Context,
    private val pojieSettings: PojieSettings,
    private val onChannelWarning: (String) -> Unit = {}
) : AutoCloseable {
    var stopFunc: Runnable? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** 通道异常提醒只报一次 */
    private val warned = AtomicBoolean(false)

    /** 最近一行日志到达时间（0=从未收到任何输出） */
    @Volatile
    private var lastLineAt = 0L

    private val _logFlow = MutableSharedFlow<WifiLogData>(extraBufferCapacity = 64)
    val logFlow = _logFlow.asSharedFlow()

    private var currentSsid: String? = null
    private var targetSsid: String? = null
    private var connectStartTime: Long = 0L
    private var handshakeStartTime: Long = 0L
    private var handshakeCount: Int = 0

    override fun close() {
        stopFunc?.run()
        stopFunc = null
        scope.cancel()
    }

    /**
     * 每次尝试前同步目标 SSID：当日志行里抓不到实际 SSID（设备日志格式
     * 差异）时，事件携带目标 SSID 兜底，避免成功事件因 ssid=null 被丢弃。
     */
    fun setTargetSsid(ssid: String) {
        targetSsid = ssid
    }

    private fun decodeHexSsid(hexSsid: String): String {
        if (!hexSsid.contains("\\x")) return hexSsid
        return try {
            val bais = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < hexSsid.length) {
                if (hexSsid[i] == '\\' && i + 3 < hexSsid.length && hexSsid[i + 1] == 'x') {
                    val hex = hexSsid.substring(i + 2, i + 4)
                    bais.write(hex.toInt(16))
                    i += 4
                } else {
                    bais.write(hexSsid[i].code)
                    i++
                }
            }
            bais.toString("UTF-8")
        } catch (_: Exception) {
            hexSsid
        }
    }

    private fun executeCommand(
        command: String,
        onOutputReceived: Consumer<String>?,
        onCommandFinished: Consumer<CommandRunner.CommandResult>?
    ): Runnable {
        val app = context.applicationContext as ToolboxApp
        return when (val method = pojieSettings.commandMethod) {
            0 -> throw Exception(context.getString(R.string.command_method_empty))
            1 -> ShizukuUtil.executeCommand(command, onOutputReceived, onCommandFinished)
            2 -> AidlServiceHelper.executeCommand(app, command, onOutputReceived, onCommandFinished)
            3 -> CommandRunner.executeCommand(command, true, onOutputReceived, onCommandFinished)
            else -> throw Exception(context.getString(R.string.tip_not_completed) + "(commandMethod=$method)")
        }
    }

    fun executeCommandSync(command: String): CommandRunner.CommandResult {
        val app = context.applicationContext as ToolboxApp
        return when (val method = pojieSettings.commandMethod) {
            0 -> throw Exception(context.getString(R.string.command_method_empty))
            1 -> ShizukuUtil.executeCommandSync(command)
            2 -> AidlServiceHelper.executeCommandSync(app, command)
            3 -> CommandRunner.executeCommandSync(command, true)
            else -> throw Exception(context.getString(R.string.tip_not_completed) + "(commandMethod=$method)")
        }
    }

    init {
        executeCommandSync("logcat -c")
        stopFunc = executeCommand(
            command = "logcat -s WifiService:D wpa_supplicant:D DhcpClient:D",
            onOutputReceived = { line ->
                lastLineAt = System.currentTimeMillis()
                Log.d("WifiLogcatService", line)
                when {
                    line.contains("Trying to associate with SSID") -> {
                        val match = Regex("SSID '(.*?)'").find(line)
                        if (match != null) {
                            currentSsid = decodeHexSsid(match.groupValues[1])
                            connectStartTime = System.currentTimeMillis()
                            handshakeStartTime = 0L
                            handshakeCount = 0
                        }
                    }

                    line.contains("WifiService: enableNetwork") -> {
                        connectStartTime = System.currentTimeMillis()
                        handshakeStartTime = 0L
                        handshakeCount = 0
                    }

                    line.contains("WPA: RX message 1 of 4-Way Handshake from") -> {
                        if (handshakeStartTime == 0L) handshakeStartTime =
                            System.currentTimeMillis()
                    }

                    line.contains("WPA: Key negotiation completed with") -> {
                        Log.d("WifiLogcatService", "连接成功")
                        emitConnectedEvent()
                    }

                    // wpa_supplicant 标准连接成功事件（跨版本稳定字符串），
                    // 与「Key negotiation completed」双模式互补提升设备覆盖面
                    line.contains("CTRL-EVENT-CONNECTED") -> {
                        Log.d("WifiLogcatService", "连接成功(CTRL-EVENT)")
                        emitConnectedEvent()
                    }

                    line.contains("Sending EAPOL-Key 2/4") -> {
                        handshakeCount++
                        val useTime =
                            if (handshakeStartTime > 0L) (System.currentTimeMillis() - handshakeStartTime).toInt() else 0
                        _logFlow.tryEmit(
                            WifiLogData(
                                WifiLogData.EVENT_HANDSHAKE,
                                connectStartTime,
                                effectiveSsid(),
                                useTime,
                                handshakeCount
                            )
                        )
                    }

                    line.contains("WPA: 4-Way Handshake failed") -> {
                        _logFlow.tryEmit(
                            WifiLogData(
                                WifiLogData.EVENT_CONNECT_FAILED, effectiveEventStart(), effectiveSsid()
                            )
                        )
                    }

                    line.contains("CTRL-EVENT-ASSOC-REJECT") -> {
                        _logFlow.tryEmit(
                            WifiLogData(
                                WifiLogData.EVENT_CONNECT_ERROR, effectiveEventStart(), effectiveSsid()
                            )
                        )
                    }
                }
            },
            onCommandFinished = { result ->
                // 常驻流式命令不应自行退出：立即结束 = 执行通道未连接/
                // 无权限/命令错误（如 AIDL 未绑定会在此立即回调），必须让用户
                // 知情，而非静默等超时。
                if (warned.compareAndSet(false, true)) {
                    onChannelWarning(
                        context.getString(R.string.logcat_stream_dead, result.exitCode)
                    )
                }
            })

        // 看门狗：30 秒零输出提示（部分设备/通道组合读不到任何日志，
        // 与命令退出不同，此时流仍存活但永远等不到事件）
        scope.launch {
            delay(30_000)
            if (lastLineAt == 0L && warned.compareAndSet(false, true)) {
                onChannelWarning(context.getString(R.string.logcat_no_output))
            }
        }
    }

    /** 成功事件：起始行未匹配时用行到达时间兜底，避免被新鲜度检查误丢 */
    private fun emitConnectedEvent() {
        _logFlow.tryEmit(
            WifiLogData(
                WifiLogData.EVENT_WIFI_CONNECTED,
                effectiveEventStart(),
                effectiveSsid()
            )
        )
    }

    /** 日志行里抓不到 SSID 时兜底用目标 SSID */
    private fun effectiveSsid(): String? = currentSsid ?: targetSsid

    /** 起始行（Trying to associate / enableNetwork）未匹配时兜底用当前时间 */
    private fun effectiveEventStart(): Long =
        if (connectStartTime > 0L) connectStartTime else System.currentTimeMillis()

}