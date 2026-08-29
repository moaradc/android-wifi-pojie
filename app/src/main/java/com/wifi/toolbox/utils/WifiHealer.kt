package com.wifi.toolbox.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.GuardSettings
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * 自愈动作标识（用于统计与日志展示）
 */
object HealActions {
    const val REASSOCIATE = "reassociate"
    const val RECONNECT = "reconnect"
    const val DISABLE_ENABLE = "disable+enable"
    const val CMD_CONNECT = "cmd connect"
    const val WIFI_CYCLE = "wifi cycle"
}

/**
 * WiFi 自愈器：检测确认断网后执行重连。
 *
 * 五级递进动作（自轻到重）：
 * 1. reassociate    802.11 重协商，不断链，速度最快（约 1-3s），解决链路层假死
 * 2. reconnect      disconnect + reconnect 完整握手，对付绝大多数"假连接"（默认档）
 * 3. disable+enable 对当前网络禁用再启用，强制重新 DHCP，对付 IP 配置失效
 * 4. cmd connect    Android 11+ 的 cmd wifi connect-network 定向重连（免 UI）
 * 5. wifi cycle     WiFi 总开关循环（终极手段，5-10s，会短暂断开所有 WiFi 活动）
 *
 * 三条特权通道自动适配（与主程序破解模块共用同一套能力）：
 * - Shizuku   反射 IWifiManager（全签名适配复用 [ShizukuUtil.getWifiMethod]）
 * - Root AIDL libsu RootService（[AidlServiceHelper]）
 * - 系统 API  targetSdk=28 的免 root 通道（[ApiUtil]）
 *
 * 速度设计：轻量/标准档单动作快速完成；强力/终极档逐级执行并即时验证，
 * 一旦探测恢复立即停止升压（不浪费无谓的重动作）。
 */
class WifiHealer(
    private val context: Context,
    private val app: ToolboxApp?
) {

    /** 当前可用的 shell 执行通道（供 ICMP 探测复用）：Shizuku → Root AIDL → 本地 sh */
    suspend fun shellExec(command: String): CommandRunner.CommandResult {
        val a = app ?: return CommandRunner.CommandResult("", -1)
        return try {
            if (isShizukuAvailable()) {
                ShizukuUtil.executeCommandSync(command)
            } else if (a.aidl.ipc != null) {
                AidlServiceHelper.executeCommandSync(a, command)
            } else {
                val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true).start()
                val out = process.inputStream.bufferedReader().readText()
                process.waitFor()
                CommandRunner.CommandResult(out, process.exitValue())
            }
        } catch (_: Exception) {
            CommandRunner.CommandResult("", -1)
        }
    }

    /**
     * 执行自愈动作序列。
     *
     * @param verify 每个动作后的即时验证（轻量单项探测，返回 true 则停止升压）
     * @return 实际执行的动作列表（按执行顺序）
     */
    suspend fun heal(
        settings: GuardSettings,
        ssid: String,
        netId: Int,
        verify: suspend () -> Boolean,
        log: (String) -> Unit
    ): List<String> {
        val plan = planActions(settings.healStrategy)
        val executed = mutableListOf<String>()
        for (action in plan) {
            val start = System.currentTimeMillis()
            val ok = try {
                executeAction(action, ssid, netId, log)
            } catch (e: Exception) {
                log("动作 $action 异常: ${e.message}")
                false
            }
            executed += action
            log("动作 $action ${if (ok) "已执行" else "通道失败"} (${System.currentTimeMillis() - start}ms)")
            if (!ok) continue // 通道失败（如该版本无此命令），直接升下一级

            // 动作成功发出后等待网络生效再验证
            var recovered = false
            val verifyDeadline = System.currentTimeMillis() + VERIFY_WAIT_MS
            while (System.currentTimeMillis() < verifyDeadline) {
                delay(VERIFY_POLL_MS)
                if (verify()) {
                    recovered = true
                    break
                }
            }
            if (recovered) {
                log("探测恢复，停止升压")
                break
            }
        }
        return executed
    }

    /** 根据策略档位生成动作计划（由轻到重） */
    fun planActions(strategy: Int): List<String> {
        return when (strategy) {
            0 -> emptyList()                       // 只检测不重连
            1 -> listOf(HealActions.REASSOCIATE)   // 轻量
            2 -> listOf(HealActions.RECONNECT)     // 标准
            3 -> listOf(                           // 强力：逐级升压
                HealActions.REASSOCIATE,
                HealActions.RECONNECT,
                HealActions.DISABLE_ENABLE,
                HealActions.WIFI_CYCLE
            )
            4 -> listOf(                           // 终极：强力 + cmd 定向重连
                HealActions.REASSOCIATE,
                HealActions.RECONNECT,
                HealActions.DISABLE_ENABLE,
                HealActions.CMD_CONNECT,
                HealActions.WIFI_CYCLE
            )
            else -> listOf(HealActions.RECONNECT)
        }
    }

    private suspend fun executeAction(
        action: String,
        ssid: String,
        netId: Int,
        log: (String) -> Unit
    ): Boolean {
        return when (action) {
            HealActions.REASSOCIATE -> runPrivileged(
                shizuku = { shizukuSimpleAction("reassociate") },
                aidl = { aidlExec("cmd wifi force-reassociate ${netId.takeIf { it > 0 } ?: 0}") },
                api = {
                    @Suppress("DEPRECATION")
                    (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                        .reassociate()
                }
            )

            HealActions.RECONNECT -> runPrivileged(
                shizuku = { shizukuSimpleAction("reconnect") },
                aidl = { aidlExec("cmd wifi reconnect") },
                api = {
                    @Suppress("DEPRECATION")
                    (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                        .reconnect()
                }
            )

            HealActions.DISABLE_ENABLE -> runPrivileged(
                shizuku = { shizukuDisableEnable(netId) },
                aidl = {
                    if (netId == -1) false
                    else {
                        aidlExec("cmd wifi disable-network $netId") &&
                                (delay(800).let { aidlExec("cmd wifi enable-network $netId") })
                    }
                },
                api = {
                    if (netId == -1) false
                    else {
                        @Suppress("DEPRECATION")
                        val wm = context.applicationContext
                            .getSystemService(Context.WIFI_SERVICE) as WifiManager
                        wm.disableNetwork(netId)
                        delay(800)
                        wm.enableNetwork(netId, true)
                    }
                }
            )

            HealActions.CMD_CONNECT -> {
                if (Build.VERSION.SDK_INT >= 30 && ssid.isNotEmpty()) {
                    runPrivileged(
                        shizuku = { shizukuExecBool("cmd wifi connect-network \"$ssid\" wpa2") },
                        aidl = { aidlExec("cmd wifi connect-network \"$ssid\" wpa2") },
                        api = { false }
                    )
                } else false
            }

            HealActions.WIFI_CYCLE -> runPrivileged(
                shizuku = {
                    ShizukuUtil.setWifiEnabled(false)
                    delay(1500)
                    ShizukuUtil.setWifiEnabled(true)
                    true
                },
                aidl = {
                    val a = app ?: return@runPrivileged false
                    AidlServiceHelper.setWifiEnabled(a, false)
                    delay(1500)
                    AidlServiceHelper.setWifiEnabled(a, true)
                    true
                },
                api = {
                    val ok1 = ApiUtil.setWifiEnabled(context, false)
                    delay(1500)
                    val ok2 = ApiUtil.setWifiEnabled(context, true)
                    ok1 && ok2
                }
            )

            else -> false
        }
    }

    /**
     * 按设置的通道（自动/Shizuku/RootAIDL/系统API）执行动作。
     * 自动模式：Shizuku 优先 → Root AIDL → 系统 API 逐级回退。
     */
    private suspend fun runPrivileged(
        shizuku: suspend () -> Boolean,
        aidl: suspend () -> Boolean,
        api: suspend () -> Boolean
    ): Boolean {
        val channel = app?.guardHealChannel() ?: 0
        return try {
            when (channel) {
                1 -> if (isShizukuAvailable()) shizuku() else false
                2 -> if (app?.aidl?.ipc != null) aidl() else false
                3 -> api()
                else -> {
                    if (isShizukuAvailable()) shizuku()
                    else if (app?.aidl?.ipc != null) aidl()
                    else api()
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val VERIFY_WAIT_MS = 12_000L   // 单动作后等待网络生效上限
        private const val VERIFY_POLL_MS = 3_000L    // 生效轮询间隔

        /** Shizuku 服务在线且已授权 */
        fun isShizukuAvailable(): Boolean {
            return try {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
        }
    }

    // ==================== Shizuku 通道动作 ====================

    private fun shizukuWifiService(): Any {
        val binder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        return Class.forName("android.net.wifi.IWifiManager\$Stub").run {
            getMethod("asInterface", android.os.IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(binder))!!
        }
    }

    /** 执行无参/单包名参的 IWifiManager 动作（reassociate/reconnect） */
    private fun shizukuSimpleAction(name: String): Boolean {
        return try {
            val service = shizukuWifiService()
            val method: Method = ShizukuUtil.getWifiMethod(service, name)
            when (method.parameterTypes.size) {
                1 -> method.invoke(service, ShizukuUtil.PACKAGE_NAME)
                else -> method.invoke(service)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun shizukuDisableEnable(netId: Int): Boolean {
        if (netId == -1) return false
        return try {
            val service = shizukuWifiService()
            val disable = ShizukuUtil.getWifiMethod(service, "disableNetwork")
            when (disable.parameterTypes.size) {
                2 -> disable.invoke(service, netId, ShizukuUtil.PACKAGE_NAME)
                else -> disable.invoke(service, netId)
            }
            delay(800)
            val enable = ShizukuUtil.getWifiMethod(service, "enableNetwork")
            when (enable.parameterTypes.size) {
                3 -> enable.invoke(service, netId, true, ShizukuUtil.PACKAGE_NAME)
                else -> enable.invoke(service, netId, true)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun shizukuExecBool(command: String): Boolean {
        return try {
            ShizukuUtil.executeCommandSync(command).exitCode == 0
        } catch (_: Exception) {
            false
        }
    }

    // ==================== Root AIDL 通道动作 ====================

    private suspend fun aidlExec(command: String): Boolean {
        val a = app ?: return false
        return try {
            if (a.aidl.ipc == null) return false
            AidlServiceHelper.executeCommandSync(a, command).exitCode == 0
        } catch (_: Exception) {
            false
        }
    }
}

/** 从 App 读当前守护通道设置 */
private fun ToolboxApp.guardHealChannel(): Int {
    return try {
        getSharedPreferences("settings_guard", Context.MODE_PRIVATE)
            .getInt(GuardSettings.HEAL_CHANNEL_KEY, GuardSettings.HEAL_CHANNEL_DEFAULT)
    } catch (_: Exception) {
        GuardSettings.HEAL_CHANNEL_DEFAULT
    }
}
