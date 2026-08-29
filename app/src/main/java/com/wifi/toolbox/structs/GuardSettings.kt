package com.wifi.toolbox.structs

import android.content.SharedPreferences

/**
 * 网络守护（WiFi 断网自愈）全部可配置项
 *
 * 设计原则：不同设备的故障方式不同（有的假连接、有的掉线、有的只认总开关），
 * 因此检测策略、自愈策略、时机参数全部可配置、可组合。
 */
data class GuardSettings(
    // ==================== 检测策略 ====================
    /**
     * 连通性检测策略（可多选叠加，任一通过即视为在线，全部失败才算断网）：
     * bit0 = HTTP 204 探测（绑定 WiFi 网络，防蜂窝回落误判）
     * bit1 = DNS 解析探测（UDP 53，检测域名劫持/运营商黑洞）
     * bit2 = ICMP ping 探测（Shizuku/root shell 执行 ping -I wlan0）
     * bit3 = 系统 NET_CAPABILITY_VALIDATED 能力位
     */
    val probeModes: Int = PROBE_MODES_DEFAULT,

    /** 检测超时（毫秒/每次探测） */
    val probeTimeoutMs: Int = PROBE_TIMEOUT_MS_DEFAULT,

    /**
     * 连续失败多少次才判定为"断网"（防抖，避免单次抖动触发重连）
     */
    val failThreshold: Int = FAIL_THRESHOLD_DEFAULT,

    // ==================== 检测时机 ====================
    /**
     * 定时检测间隔（秒）。预设：10/30/60/120/300，或自定义 5-3600
     */
    val checkIntervalSec: Int = CHECK_INTERVAL_SEC_DEFAULT,

    /**
     * 网络切换时立即检测（WiFi 重连/网络变化广播触发即时探测）
     */
    val checkOnNetworkChange: Boolean = CHECK_ON_NETWORK_CHANGE_DEFAULT,

    /**
     * 仅在 WiFi 处于连接状态时守护（未连 WiFi 时不探测、不统计）
     */
    val onlyWhenWifiConnected: Boolean = ONLY_WHEN_WIFI_CONNECTED_DEFAULT,

    // ==================== 自愈策略 ====================
    /**
     * 自愈动作策略（单个选项，代表整体方案档位）：
     * 0 = 不自动重连（只检测+统计+通知）
     * 1 = 轻量档：reassociate 重协商（速度最快，不断链）
     * 2 = 标准档：disconnect + reconnect 完整重连（对付大多数假连接）
     * 3 = 强力档：逐步升压（轻量→标准→关闭再启用网络→WiFi 总开关）
     * 4 = 终极档：强力档基础上仍失败则 cmd wifi connect-network 定向重连
     */
    val healStrategy: Int = HEAL_STRATEGY_DEFAULT,

    /**
     * 重连后等待网络验证的超时（秒），超时视为本次自愈失败
     */
    val healVerifyTimeoutSec: Int = HEAL_VERIFY_TIMEOUT_SEC_DEFAULT,

    /**
     * 自愈期间使用指数退避：连续自愈失败后，等待时间按此基数翻倍
     * （秒）。防止路由器已断电/光猫故障时的无效轰炸。
     */
    val healCooldownBaseSec: Int = HEAL_COOLDOWN_BASE_SEC_DEFAULT,

    /**
     * 最大冷却倍数上限（backoff 上限 = cooldownBase * 2^maxBackoffPower）
     */
    val maxBackoffPower: Int = MAX_BACKOFF_POWER_DEFAULT,

    /**
     * 局域网豁免：如果 WiFi 本身已断开（非假连接），不做重连，
     * 因为可能用户主动断开/路由器关机
     */
    val skipWhenWifiDisconnected: Boolean = SKIP_WHEN_WIFI_DISCONNECTED_DEFAULT,

    /**
     * 检测到 Captive Portal（认证页）时不执行自愈（重连也无法解决认证问题）
     */
    val skipOnCaptivePortal: Boolean = SKIP_ON_CAPTIVE_PORTAL_DEFAULT,

    // ==================== 通知与统计 ====================
    /** 自愈执行时发系统通知提醒 */
    val notifyOnHeal: Boolean = NOTIFY_ON_HEAL_DEFAULT,

    /** 自愈失败（重连后仍不通）时发通知 */
    val notifyOnHealFail: Boolean = NOTIFY_ON_HEAL_FAIL_DEFAULT,

    /** 守护服务自身常驻通知（前台服务最低要求，低优先级无声音） */
    val showPersistentNotification: Boolean = SHOW_PERSISTENT_NOTIFICATION_DEFAULT,

    /**
     * 自愈执行通道：
     * 0 = 自动（Shizuku 可用选 Shizuku，否则 Root AIDL，最后系统 API）
     * 1 = 仅 Shizuku
     * 2 = 仅 Root AIDL
     * 3 = 仅系统 API（targetSdk=28 免 root 老通道，兼容性好但能力有限）
     */
    val healChannel: Int = HEAL_CHANNEL_DEFAULT,

    /** 开机自启守护服务（配合前台服务恢复，需系统不杀） */
    val startOnBoot: Boolean = START_ON_BOOT_DEFAULT
) {
    companion object {
        // ---- 键名 ----
        const val PROBE_MODES_KEY = "guard_probe_modes"
        const val PROBE_TIMEOUT_MS_KEY = "guard_probe_timeout_ms"
        const val FAIL_THRESHOLD_KEY = "guard_fail_threshold"
        const val CHECK_INTERVAL_SEC_KEY = "guard_check_interval_sec"
        const val CHECK_ON_NETWORK_CHANGE_KEY = "guard_check_on_network_change"
        const val ONLY_WHEN_WIFI_CONNECTED_KEY = "guard_only_when_wifi_connected"
        const val HEAL_STRATEGY_KEY = "guard_heal_strategy"
        const val HEAL_VERIFY_TIMEOUT_SEC_KEY = "guard_heal_verify_timeout_sec"
        const val HEAL_COOLDOWN_BASE_SEC_KEY = "guard_heal_cooldown_base_sec"
        const val MAX_BACKOFF_POWER_KEY = "guard_max_backoff_power"
        const val SKIP_WHEN_WIFI_DISCONNECTED_KEY = "guard_skip_when_wifi_disconnected"
        const val SKIP_ON_CAPTIVE_PORTAL_KEY = "guard_skip_on_captive_portal"
        const val NOTIFY_ON_HEAL_KEY = "guard_notify_on_heal"
        const val NOTIFY_ON_HEAL_FAIL_KEY = "guard_notify_on_heal_fail"
        const val SHOW_PERSISTENT_NOTIFICATION_KEY = "guard_show_persistent_notification"
        const val HEAL_CHANNEL_KEY = "guard_heal_channel"
        const val START_ON_BOOT_KEY = "guard_start_on_boot"

        // ---- 默认值 ----
        const val PROBE_MODES_DEFAULT = 0b0011 // HTTP 204 + DNS
        const val PROBE_TIMEOUT_MS_DEFAULT = 4000
        const val FAIL_THRESHOLD_DEFAULT = 2
        const val CHECK_INTERVAL_SEC_DEFAULT = 30
        const val CHECK_ON_NETWORK_CHANGE_DEFAULT = true
        const val ONLY_WHEN_WIFI_CONNECTED_DEFAULT = true
        const val HEAL_STRATEGY_DEFAULT = 2
        const val HEAL_VERIFY_TIMEOUT_SEC_DEFAULT = 20
        const val HEAL_COOLDOWN_BASE_SEC_DEFAULT = 30
        const val MAX_BACKOFF_POWER_DEFAULT = 4
        const val SKIP_WHEN_WIFI_DISCONNECTED_DEFAULT = true
        const val SKIP_ON_CAPTIVE_PORTAL_DEFAULT = true
        const val NOTIFY_ON_HEAL_DEFAULT = true
        const val NOTIFY_ON_HEAL_FAIL_DEFAULT = true
        const val SHOW_PERSISTENT_NOTIFICATION_DEFAULT = true
        const val HEAL_CHANNEL_DEFAULT = 0
        const val START_ON_BOOT_DEFAULT = false

        const val PREFS_NAME = "settings_guard"

        // ---- 检测位掩码 ----
        const val PROBE_HTTP_204 = 1
        const val PROBE_DNS = 2
        const val PROBE_ICMP = 4
        const val PROBE_VALIDATED = 8

        /** 检测预设时间间隔（秒） */
        val INTERVAL_PRESETS = listOf(10, 30, 60, 120, 300)

        fun from(prefs: SharedPreferences): GuardSettings {
            return GuardSettings(
                probeModes = prefs.getInt(PROBE_MODES_KEY, PROBE_MODES_DEFAULT),
                probeTimeoutMs = prefs.getInt(PROBE_TIMEOUT_MS_KEY, PROBE_TIMEOUT_MS_DEFAULT),
                failThreshold = prefs.getInt(FAIL_THRESHOLD_KEY, FAIL_THRESHOLD_DEFAULT),
                checkIntervalSec = prefs.getInt(
                    CHECK_INTERVAL_SEC_KEY, CHECK_INTERVAL_SEC_DEFAULT
                ).coerceIn(5, 3600),
                checkOnNetworkChange = prefs.getBoolean(
                    CHECK_ON_NETWORK_CHANGE_KEY, CHECK_ON_NETWORK_CHANGE_DEFAULT
                ),
                onlyWhenWifiConnected = prefs.getBoolean(
                    ONLY_WHEN_WIFI_CONNECTED_KEY, ONLY_WHEN_WIFI_CONNECTED_DEFAULT
                ),
                healStrategy = prefs.getInt(HEAL_STRATEGY_KEY, HEAL_STRATEGY_DEFAULT),
                healVerifyTimeoutSec = prefs.getInt(
                    HEAL_VERIFY_TIMEOUT_SEC_KEY, HEAL_VERIFY_TIMEOUT_SEC_DEFAULT
                ).coerceIn(5, 120),
                healCooldownBaseSec = prefs.getInt(
                    HEAL_COOLDOWN_BASE_SEC_KEY, HEAL_COOLDOWN_BASE_SEC_DEFAULT
                ).coerceIn(5, 600),
                maxBackoffPower = prefs.getInt(MAX_BACKOFF_POWER_KEY, MAX_BACKOFF_POWER_DEFAULT)
                    .coerceIn(1, 8),
                skipWhenWifiDisconnected = prefs.getBoolean(
                    SKIP_WHEN_WIFI_DISCONNECTED_KEY, SKIP_WHEN_WIFI_DISCONNECTED_DEFAULT
                ),
                skipOnCaptivePortal = prefs.getBoolean(
                    SKIP_ON_CAPTIVE_PORTAL_KEY, SKIP_ON_CAPTIVE_PORTAL_DEFAULT
                ),
                notifyOnHeal = prefs.getBoolean(NOTIFY_ON_HEAL_KEY, NOTIFY_ON_HEAL_DEFAULT),
                notifyOnHealFail = prefs.getBoolean(
                    NOTIFY_ON_HEAL_FAIL_KEY, NOTIFY_ON_HEAL_FAIL_DEFAULT
                ),
                showPersistentNotification = prefs.getBoolean(
                    SHOW_PERSISTENT_NOTIFICATION_KEY, SHOW_PERSISTENT_NOTIFICATION_DEFAULT
                ),
                healChannel = prefs.getInt(HEAL_CHANNEL_KEY, HEAL_CHANNEL_DEFAULT),
                startOnBoot = prefs.getBoolean(START_ON_BOOT_KEY, START_ON_BOOT_DEFAULT)
            )
        }
    }
}
