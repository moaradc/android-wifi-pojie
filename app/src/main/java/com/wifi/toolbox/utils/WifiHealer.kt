package com.wifi.toolbox.utils

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.R
import com.wifi.toolbox.services.GuardState
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

/** 特权执行通道名（日志/状态展示用，与设置项对应） */
object GuardChannels {
    const val SHIZUKU = "Shizuku"
    const val ROOT_AIDL = "RootAIDL"
    const val API = "API"
    const val SHELL = "Shell"
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

    /**
     * 当前可用的 shell 执行通道（供 ICMP 探测复用）：Shizuku → Root AIDL → 本地 sh。
     * 返回 [CommandRunner.ShellOutcome]，携带实际使用的通道名（写入全局状态供 UI 展示，
     * 解决"已授权 Shizuku 但不知道是否真的被使用"的可见性问题）。
     */
    suspend fun shellExec(command: String): CommandRunner.ShellOutcome {
        val a = app ?: return CommandRunner.ShellOutcome("", -1, GuardChannels.SHELL)
        val outcome: CommandRunner.ShellOutcome = try {
            if (isShizukuAvailable()) {
                val r = ShizukuUtil.executeCommandSync(command)
                CommandRunner.ShellOutcome(r.output, r.exitCode, GuardChannels.SHIZUKU)
            } else if (a.aidl.ipc != null) {
                val r = AidlServiceHelper.executeCommandSync(a, command)
                CommandRunner.ShellOutcome(r.output, r.exitCode, GuardChannels.ROOT_AIDL)
            } else {
                val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true).start()
                val out = process.inputStream.bufferedReader().readText()
                process.waitFor()
                CommandRunner.ShellOutcome(out, process.exitValue(), GuardChannels.SHELL)
            }
        } catch (_: Exception) {
            CommandRunner.ShellOutcome("", -1, GuardChannels.SHELL)
        }
        GuardState.lastShellChannel = outcome.channel
        return outcome
    }

    /**
     * 执行自愈动作序列。
     *
     * @param verify 每个动作后的即时验证（轻量单项探测，返回 true 则停止升压）
     * @param actionStats 历史动作统计（高成功率档动态选优的依据）
     * @return 实际执行的动作列表（按执行顺序）
     */
    suspend fun heal(
        settings: GuardSettings,
        ssid: String,
        netId: Int,
        verify: suspend () -> Boolean,
        log: (String) -> Unit,
        actionStats: Map<String, Pair<Int, Int>> = emptyMap()
    ): List<String> {
        val plan = planActions(settings, actionStats)
        val executed = mutableListOf<String>()
        for (action in plan) {
            val start = System.currentTimeMillis()
            val ok = try {
                executeAction(action, ssid, netId, log)
            } catch (e: Exception) {
                log(context.getString(R.string.guard_log_action_error, action, e.message ?: ""))
                false
            }
            executed += action
            // 动作日志携带实际执行通道（runPrivileged 执行时写入 GuardState.lastHealChannel）
            val channel = GuardState.lastHealChannel.ifEmpty { "-" }
            log(
                context.getString(
                    if (ok) R.string.guard_log_action_ok else R.string.guard_log_action_fail,
                    action, channel, System.currentTimeMillis() - start
                )
            )
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
                log(context.getString(R.string.guard_log_recovered_stop))
                break
            }
        }
        return executed
    }

    /**
     * 根据策略档位生成动作计划（由轻到重）。
     *
     * 档位 5（自定义）：[GuardSettings.customHealActions] 中"+"连接的动作 id，
     * 按内置顺序重排（保证由轻到重）；空/无效回退标准档。
     * 档位 6（高成功率）：从历史统计中取累计成功最高的单一动作，
     * 直接执行该动作（已验证对本机最有效）；无统计时回退标准档。
     */
    fun planActions(
        settings: GuardSettings,
        actionStats: Map<String, Pair<Int, Int>> = emptyMap()
    ): List<String> {
        return when (settings.healStrategy) {
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
            5 -> parseCustomActions(settings.customHealActions)
                .ifEmpty { listOf(HealActions.RECONNECT) }
            6 -> bestActionFrom(actionStats)
                ?.let { listOf(it) }
                ?: listOf(HealActions.RECONNECT)
            else -> listOf(HealActions.RECONNECT)
        }
    }

    /**
     * 解析自定义动作串："," 分隔（动作 id 中含"+"/空格，不能用其作分隔符），
     * 过滤非法 id，按由轻到重的固定顺序重排
     */
    private fun parseCustomActions(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val chosen = raw.split(',').map { it.trim() }.filter {
            it in setOf(
                HealActions.REASSOCIATE, HealActions.RECONNECT,
                HealActions.DISABLE_ENABLE, HealActions.CMD_CONNECT,
                HealActions.WIFI_CYCLE
            )
        }.toSet()
        // 固定由轻到重顺序输出
        return listOf(
            HealActions.REASSOCIATE, HealActions.RECONNECT,
            HealActions.DISABLE_ENABLE, HealActions.CMD_CONNECT,
            HealActions.WIFI_CYCLE
        ).filter { it in chosen }
    }

    /** 累计成功次数最高的动作（次键成功率）；无数据返回 null */
    private fun bestActionFrom(stats: Map<String, Pair<Int, Int>>): String? {
        return stats.entries
            .filter { it.value.second > 0 }
            .maxWithOrNull(
                compareByDescending<Map.Entry<String, Pair<Int, Int>>> { it.value.second }
                    .thenByDescending {
                        if (it.value.first > 0) it.value.second.toFloat() / it.value.first else 0f
                    }
            )?.key
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
     * 实际选中的通道写入 [GuardState.lastHealChannel]（UI 与日志可见）。
     */
    private suspend fun runPrivileged(
        shizuku: suspend () -> Boolean,
        aidl: suspend () -> Boolean,
        api: suspend () -> Boolean
    ): Boolean {
        val channel = app?.guardHealChannel() ?: 0
        return try {
            when (channel) {
                1 -> if (isShizukuAvailable()) {
                    GuardState.lastHealChannel = GuardChannels.SHIZUKU
                    shizuku()
                } else false
                2 -> if (app?.aidl?.ipc != null) {
                    GuardState.lastHealChannel = GuardChannels.ROOT_AIDL
                    aidl()
                } else false
                3 -> {
                    GuardState.lastHealChannel = GuardChannels.API
                    api()
                }
                else -> {
                    if (isShizukuAvailable()) {
                        GuardState.lastHealChannel = GuardChannels.SHIZUKU
                        shizuku()
                    } else if (app?.aidl?.ipc != null) {
                        GuardState.lastHealChannel = GuardChannels.ROOT_AIDL
                        aidl()
                    } else {
                        GuardState.lastHealChannel = GuardChannels.API
                        api()
                    }
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
