package com.wifi.toolbox.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.edit
import com.wifi.toolbox.structs.GuardSettings

/**
 * 网络守护设置状态：Compose 可观察 + SharedPreferences 自动持久化。
 * 写入后通知运行中的 GuardService 热加载（无需重启服务）。
 */
@Composable
fun rememberGuardSettings(context: Context): MutableState<GuardSettings> {
    val prefs = remember(context) {
        context.getSharedPreferences(GuardSettings.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val state = remember(prefs) { mutableStateOf(GuardSettings.from(prefs)) }

    return remember(state) {
        object : MutableState<GuardSettings> {
            override var value: GuardSettings
                get() = state.value
                set(s) {
                    state.value = s
                    prefs.edit {
                        putInt(GuardSettings.PROBE_MODES_KEY, s.probeModes)
                        putInt(GuardSettings.PROBE_TIMEOUT_MS_KEY, s.probeTimeoutMs)
                        putInt(GuardSettings.FAIL_THRESHOLD_KEY, s.failThreshold)
                        putInt(GuardSettings.CHECK_INTERVAL_SEC_KEY, s.checkIntervalSec)
                        putBoolean(GuardSettings.CHECK_ON_NETWORK_CHANGE_KEY, s.checkOnNetworkChange)
                        putBoolean(GuardSettings.ONLY_WHEN_WIFI_CONNECTED_KEY, s.onlyWhenWifiConnected)
                        putInt(GuardSettings.HEAL_STRATEGY_KEY, s.healStrategy)
                        putInt(GuardSettings.HEAL_VERIFY_TIMEOUT_SEC_KEY, s.healVerifyTimeoutSec)
                        putInt(GuardSettings.HEAL_COOLDOWN_BASE_SEC_KEY, s.healCooldownBaseSec)
                        putInt(GuardSettings.MAX_BACKOFF_POWER_KEY, s.maxBackoffPower)
                        putBoolean(GuardSettings.SKIP_WHEN_WIFI_DISCONNECTED_KEY, s.skipWhenWifiDisconnected)
                        putBoolean(GuardSettings.SKIP_ON_CAPTIVE_PORTAL_KEY, s.skipOnCaptivePortal)
                        putBoolean(GuardSettings.NOTIFY_ON_HEAL_KEY, s.notifyOnHeal)
                        putBoolean(GuardSettings.NOTIFY_ON_HEAL_FAIL_KEY, s.notifyOnHealFail)
                        putBoolean(GuardSettings.SHOW_PERSISTENT_NOTIFICATION_KEY, s.showPersistentNotification)
                        putInt(GuardSettings.HEAL_CHANNEL_KEY, s.healChannel)
                        putBoolean(GuardSettings.START_ON_BOOT_KEY, s.startOnBoot)
                    }
                    // 热加载通知
                    try {
                        context.startService(
                            android.content.Intent(
                                context, com.wifi.toolbox.services.GuardService::class.java
                            ).apply { action = com.wifi.toolbox.services.GuardService.ACTION_RELOAD }
                        )
                    } catch (_: Exception) {
                    }
                }
            override fun component1() = value
            override fun component2(): (GuardSettings) -> Unit = { value = it }
        }
    }
}
