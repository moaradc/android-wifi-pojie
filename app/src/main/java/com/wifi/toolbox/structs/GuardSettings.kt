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
     * 5 = 自定义档：用户在 [customHealActions] 中自选动作组合
     * 6 = 高成功率档：动态选取统计中累计自愈成功最高的单一动作
     */
    val healStrategy: Int = HEAL_STRATEGY_DEFAULT,

    /**
     * 自定义档动作序列（","分隔的动作 id，如 "reassociate,disable+enable"；
     * 动作 id 自身含"+"与空格，故用逗号分隔），按由轻到重顺序执行：
     * 仅在 healStrategy = 5 时生效；空串回退为标准档动作
     */
    val customHealActions: String = CUSTOM_HEAL_ACTIONS_DEFAULT,

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
     * 连续自愈次数上限（熔断阈值，参考 Circuit Breaker 模式）：
     * 0 = 无限制（旧版行为，一直退避重试）；
     * N = 连续 N 次自愈失败后停止自愈动作（检测继续），
     * 直到网络恢复在线或手动“立即检测”时复位计数。
     * 网络调研（AWS Prescriptive Guidance / ByteByteGo）：无限重试会导致
     * 恶性循环与资源耗尽，重试机制应包含最大次数限制。
     */
    val healMaxAttempts: Int = HEAL_MAX_ATTEMPTS_DEFAULT,

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
     * 记录哪些类型的日志到实时日志（位掩码，默认全部）：
     * bit0 = INFO 正常（在线检测结果等）
     * bit1 = WARN 警告（跳过/豁免类事件）
     * bit2 = ERROR 错误（断网判定/自愈失败）
     * bit3 = HEAL 自愈过程
     * 取代旧版 verboseLog 布尔开关（旧值自动迁移）。
     */
    val logLevels: Int = LOG_LEVELS_DEFAULT,

    /**
     * 日志保存目录（SAF tree URI 字符串；空串 = 应用私有目录 filesDir/log）。
     * 通过系统文件管理器（ACTION_OPEN_DOCUMENT_TREE）选择。
     */
    val logDirUri: String = LOG_DIR_URI_DEFAULT,

    /**
     * 特权执行通道（自愈动作与 ICMP ping 探测的 shell 命令共用）：
     * 0 = 自动（Shizuku 可用选 Shizuku，否则 Root AIDL，最后本地 sh）
     * 1 = 仅 Shizuku
     * 2 = 仅 Root AIDL
     * 3 = 仅系统 API（targetSdk=28 免 root 老通道；shell 命令降级应用内本地执行）
     * HTTP 204 / DNS / 能力位探测走应用层 API，不使用特权通道。
     */
    val healChannel: Int = HEAL_CHANNEL_DEFAULT,

    /** 开机自启守护服务（配合前台服务恢复，需系统不杀） */
    val startOnBoot: Boolean = START_ON_BOOT_DEFAULT
) {
    /** 某级别日志是否被记录（logLevels 位掩码：bit=level） */
    fun logLevelsEnabled(level: Int): Boolean = logLevels and (1 shl level) != 0

    /** 旧 verboseLog 语义兼容：INFO 级（在线检测结果）是否记录 */
    fun verboseLogEnabled(level: Int): Boolean = logLevelsEnabled(level)

    companion object {
        // ---- 键名 ----
        const val PROBE_MODES_KEY = "guard_probe_modes"
        const val PROBE_TIMEOUT_MS_KEY = "guard_probe_timeout_ms"
        const val FAIL_THRESHOLD_KEY = "guard_fail_threshold"
        const val CHECK_INTERVAL_SEC_KEY = "guard_check_interval_sec"
        const val CHECK_ON_NETWORK_CHANGE_KEY = "guard_check_on_network_change"
        const val ONLY_WHEN_WIFI_CONNECTED_KEY = "guard_only_when_wifi_connected"
        const val HEAL_STRATEGY_KEY = "guard_heal_strategy"
        const val CUSTOM_HEAL_ACTIONS_KEY = "guard_custom_heal_actions"
        const val HEAL_VERIFY_TIMEOUT_SEC_KEY = "guard_heal_verify_timeout_sec"
        const val HEAL_COOLDOWN_BASE_SEC_KEY = "guard_heal_cooldown_base_sec"
        const val MAX_BACKOFF_POWER_KEY = "guard_max_backoff_power"
        const val HEAL_MAX_ATTEMPTS_KEY = "guard_heal_max_attempts"
        const val SKIP_WHEN_WIFI_DISCONNECTED_KEY = "guard_skip_when_wifi_disconnected"
        const val SKIP_ON_CAPTIVE_PORTAL_KEY = "guard_skip_on_captive_portal"
        const val NOTIFY_ON_HEAL_KEY = "guard_notify_on_heal"
        const val NOTIFY_ON_HEAL_FAIL_KEY = "guard_notify_on_heal_fail"
        const val SHOW_PERSISTENT_NOTIFICATION_KEY = "guard_show_persistent_notification"
        const val VERBOSE_LOG_KEY = "guard_verbose_log" // 旧版布尔开关（迁移源）
        const val LOG_LEVELS_KEY = "guard_log_levels"
        const val LOG_DIR_URI_KEY = "guard_log_dir_uri"
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
        const val CUSTOM_HEAL_ACTIONS_DEFAULT = ""
        const val HEAL_VERIFY_TIMEOUT_SEC_DEFAULT = 20
        const val HEAL_COOLDOWN_BASE_SEC_DEFAULT = 30
        const val MAX_BACKOFF_POWER_DEFAULT = 4
        /** 默认无限重试（保持旧行为）；可选 2/3/5/10 次上限 */
        const val HEAL_MAX_ATTEMPTS_DEFAULT = 0
        const val SKIP_WHEN_WIFI_DISCONNECTED_DEFAULT = true
        const val SKIP_ON_CAPTIVE_PORTAL_DEFAULT = true
        const val NOTIFY_ON_HEAL_DEFAULT = true
        const val NOTIFY_ON_HEAL_FAIL_DEFAULT = true
        const val SHOW_PERSISTENT_NOTIFICATION_DEFAULT = true
        const val VERBOSE_LOG_DEFAULT = true
        /** 默认记录全部类型（正常+警告+错误+自愈） */
        const val LOG_LEVELS_DEFAULT = 0b1111
        const val LOG_DIR_URI_DEFAULT = ""
        const val HEAL_CHANNEL_DEFAULT = 0
        const val START_ON_BOOT_DEFAULT = false

        /** 熔断次数上限预设（0 = 无限制） */
        val MAX_ATTEMPTS_PRESETS = listOf(0, 2, 3, 5, 10)

        /** 自定义档可选动作 id（由轻到重，与 WifiHealer 五级动作一致） */
        val CUSTOM_ACTION_IDS = listOf(
            "reassociate", "reconnect", "disable+enable", "cmd connect", "wifi cycle"
        )

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
                customHealActions = prefs.getString(
                    CUSTOM_HEAL_ACTIONS_KEY, CUSTOM_HEAL_ACTIONS_DEFAULT
                ).orEmpty(),
                healVerifyTimeoutSec = prefs.getInt(
                    HEAL_VERIFY_TIMEOUT_SEC_KEY, HEAL_VERIFY_TIMEOUT_SEC_DEFAULT
                ).coerceIn(5, 120),
                healCooldownBaseSec = prefs.getInt(
                    HEAL_COOLDOWN_BASE_SEC_KEY, HEAL_COOLDOWN_BASE_SEC_DEFAULT
                ).coerceIn(5, 600),
                maxBackoffPower = prefs.getInt(MAX_BACKOFF_POWER_KEY, MAX_BACKOFF_POWER_DEFAULT)
                    .coerceIn(1, 8),
                healMaxAttempts = prefs.getInt(
                    HEAL_MAX_ATTEMPTS_KEY, HEAL_MAX_ATTEMPTS_DEFAULT
                ).coerceIn(0, 99),
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
                // 迁移：旧版 verboseLog=false → 仅记录 异常+自愈；否则全部记录
                logLevels = if (prefs.contains(LOG_LEVELS_KEY)) {
                    prefs.getInt(LOG_LEVELS_KEY, LOG_LEVELS_DEFAULT)
                } else if (prefs.contains(VERBOSE_LOG_KEY) &&
                    !prefs.getBoolean(VERBOSE_LOG_KEY, VERBOSE_LOG_DEFAULT)
                ) {
                    0b1110
                } else {
                    LOG_LEVELS_DEFAULT
                },
                logDirUri = prefs.getString(LOG_DIR_URI_KEY, LOG_DIR_URI_DEFAULT).orEmpty(),
                healChannel = prefs.getInt(HEAL_CHANNEL_KEY, HEAL_CHANNEL_DEFAULT),
                startOnBoot = prefs.getBoolean(START_ON_BOOT_KEY, START_ON_BOOT_DEFAULT)
            )
        }
    }
}
