package com.wifi.toolbox.utils

import android.content.Context
import android.net.wifi.WifiManager
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.GuardSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 当前 WiFi 身份（SSID + networkId）多级解析（守护自愈与主页状态卡共用）。
 *
 * Android 9+ 应用层 WifiInfo 的 SSID 需要 ACCESS_FINE_LOCATION 运行时授权
 * 且定位服务开启，否则返回 &lt;unknown ssid&gt;——定位开关一变，UI 上的
 * 网络名就会在「WiFi 已连接」与「已连接 xxx」之间来回跳变。解析链：
 *
 * 1. 应用层 WifiInfo（免特权，最快，受定位限制）
 * 2. cmd wifi status（Android 11+，特权 shell 输出 Current network: #n "SSID"）
 * 3. dumpsys wifi（通用兜底，mWifiInfo 行含 SSID 与 Net ID）
 *
 * 特权命令通道与 [WifiHealer] 同源（遵循设置「执行通道」，自动=Shizuku→
 * Root AIDL→本地 sh 逐级回退），但【不写】GuardState.lastShellChannel——
 * 该状态位仅用于守护页展示探测/自愈通道，UI 侧解析不应污染。
 */
object WifiIdentity {

    /** 解析当前 WiFi 身份：返回 (ssid, networkId)，ssid 为空串表示不可得 */
    suspend fun resolve(context: Context, app: ToolboxApp?): Pair<String, Int> {
        var ssid = ""
        var netId = -1
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val raw = info?.ssid?.removeSurrounding("\"").orEmpty()
            if (raw.isNotEmpty() && raw != "<unknown ssid>") ssid = raw
            if (info != null) netId = info.networkId
        } catch (_: Exception) {
        }
        if (ssid.isEmpty() || netId == -1) {
            try {
                // Android 11+：cmd wifi status 输出形如 Current network: #0 "SSID"
                if (ssid.isEmpty()) {
                    val status = privilegedExec(context, app, "cmd wifi status")
                    ssid = Regex("Current network:.*?\"([^\"]+)\"")
                        .find(status)?.groupValues?.get(1)
                        ?.removeSurrounding("\"")?.trim().orEmpty()
                }
                if (ssid.isEmpty() || netId == -1) {
                    // 通用兜底：dumpsys wifi 的 mWifiInfo 行（含 SSID 与 Net ID）
                    val dump = privilegedExec(context, app, "dumpsys wifi")
                    if (ssid.isEmpty()) {
                        ssid = Regex("mWifiInfo SSID: ([^,\\r\\n]+)")
                            .find(dump)?.groupValues?.get(1)
                            ?.removeSurrounding("\"")?.trim().orEmpty()
                        if (ssid == "<unknown ssid>") ssid = ""
                    }
                    if (netId == -1) {
                        netId = Regex("mWifiInfo.*?Net ID: (\\d+)")
                            .find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    }
                }
            } catch (_: Exception) {
            }
        }
        return ssid to netId
    }

    /** 遵循设置「执行通道」执行特权命令（不污染 GuardState 通道展示状态） */
    private suspend fun privilegedExec(
        context: Context,
        app: ToolboxApp?,
        command: String
    ): String = withContext(Dispatchers.IO) {
        val channel = healChannel(context, app)
        try {
            when (channel) {
                1 -> if (WifiHealer.isShizukuAvailable()) {
                    ShizukuUtil.executeCommandSync(command).output
                } else localShell(command)

                2 -> if (app?.aidl?.ipc != null) {
                    AidlServiceHelper.executeCommandSync(app, command).output
                } else localShell(command)

                3 -> localShell(command)   // 仅系统 API：无特权 shell

                else -> when {
                    WifiHealer.isShizukuAvailable() ->
                        ShizukuUtil.executeCommandSync(command).output
                    app?.aidl?.ipc != null ->
                        AidlServiceHelper.executeCommandSync(app, command).output
                    else -> localShell(command)
                }
            }
        } catch (_: Exception) {
            localShell(command)
        }
    }

    /** 读取守护设置「执行通道」（0=自动 1=Shizuku 2=Root AIDL 3=仅系统 API） */
    private fun healChannel(context: Context, app: ToolboxApp?): Int {
        val a = app ?: return GuardSettings.HEAL_CHANNEL_DEFAULT
        return try {
            context.getSharedPreferences(GuardSettings.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(GuardSettings.HEAL_CHANNEL_KEY, GuardSettings.HEAL_CHANNEL_DEFAULT)
        } catch (_: Exception) {
            GuardSettings.HEAL_CHANNEL_DEFAULT
        }
    }

    /** 应用沙箱内直接执行（cmd/dumpsys 无权限时输出为空，安全回退） */
    private fun localShell(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            out
        } catch (_: Exception) {
            ""
        }
    }
}
