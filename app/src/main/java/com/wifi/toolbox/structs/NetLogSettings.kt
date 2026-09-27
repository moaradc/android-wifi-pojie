package com.wifi.toolbox.structs

import android.content.SharedPreferences

/**
 * 网络日志（独立捕获功能）全部可配置项。
 *
 * 设计原则：网络日志是纯观察功能，绝不执行任何改变网络状态的命令；
 * 通道设置独立于网络守护的「执行通道」（守护自愈与日志捕获互不干扰）。
 */
data class NetLogSettings(
    /**
     * 捕获系统日志流（logcat 网络标签过滤流，写入会话 logcat_network.txt）
     * - Shizuku 通道：uid 2000 可读全系统网络标签日志
     * - Root 通道：全量日志（同一标签集）
     * - 本地 Shell：仅本应用自身日志（Android 4.1+ 无 READ_LOGS 的普通应用
     *   读不到系统 ring buffer，如实记录降级）
     */
    val captureSystemLog: Boolean = true,

    /** 网络事件发生时自动抓取系统状态快照（dumpsys/ip 命令组） */
    val snapshotOnEvent: Boolean = true,

    /**
     * 主动探测时间线（每 15s HTTP+DNS 轮询一次，结果写入 timeline）。
     * 默认关闭：持续探测有耗电与流量成本（约 0.5MB/天），仅在需要
     * 定量「通/不通」曲线时开启。
     */
    val probeTimeline: Boolean = false,

    /**
     * 日志保存位置（与网络守护同机制）：空 = 应用私有目录（filesDir/netlog，
     * 会话根目录）；否则为 SAF tree URI（系统文件管理器选择的自选文件夹）。
     * 自动保存的每日文件写入此位置；SAF 失效时自动回退私有目录。
     */
    val logDirUri: String = "",

    /**
     * 自动保存日志（与网络守护同机制）：记录时自动将实时事件追加到
     * 日志保存位置（netlog-auto-yyyyMMdd.txt，每天一个，保留最近 30 个）。
     * 会话本身的完整目录（timeline.jsonl 等）不受此开关影响，始终保存。
     */
    val autoSaveLog: Boolean = false,

    /** 会话保留个数（超出自动清理最旧的，0 = 不限） */
    val keepSessions: Int = KEEP_SESSIONS_DEFAULT,

    /** 单会话 logcat 流大小上限（MB，达到即停流并记录，防塞满磁盘） */
    val maxLogMb: Int = MAX_LOG_MB_DEFAULT,

    /**
     * 捕获执行通道：0 = 自动（Shizuku → Root AIDL → 本地 Shell）
     * 1 = 仅 Shizuku，2 = 仅 Root AIDL，3 = 仅本地 Shell
     */
    val channel: Int = CHANNEL_DEFAULT
) {
    companion object {
        const val PREFS_NAME = "settings_netlog"
        const val CAPTURE_SYSTEM_LOG_KEY = "captureSystemLog"
        const val SNAPSHOT_ON_EVENT_KEY = "snapshotOnEvent"
        const val PROBE_TIMELINE_KEY = "probeTimeline"
        const val LOG_DIR_URI_KEY = "logDirUri"
        const val AUTO_SAVE_LOG_KEY = "autoSaveLog"
        const val KEEP_SESSIONS_KEY = "keepSessions"
        const val MAX_LOG_MB_KEY = "maxLogMb"
        const val CHANNEL_KEY = "channel"

        const val KEEP_SESSIONS_DEFAULT = 10
        const val MAX_LOG_MB_DEFAULT = 20
        const val CHANNEL_DEFAULT = 0

        fun from(prefs: SharedPreferences): NetLogSettings = NetLogSettings(
            captureSystemLog = prefs.getBoolean(
                CAPTURE_SYSTEM_LOG_KEY, true
            ),
            snapshotOnEvent = prefs.getBoolean(
                SNAPSHOT_ON_EVENT_KEY, true
            ),
            probeTimeline = prefs.getBoolean(
                PROBE_TIMELINE_KEY, false
            ),
            logDirUri = prefs.getString(
                LOG_DIR_URI_KEY, ""
            ) ?: "",
            autoSaveLog = prefs.getBoolean(
                AUTO_SAVE_LOG_KEY, false
            ),
            keepSessions = prefs.getInt(
                KEEP_SESSIONS_KEY, KEEP_SESSIONS_DEFAULT
            ).coerceIn(0, 100),
            maxLogMb = prefs.getInt(
                MAX_LOG_MB_KEY, MAX_LOG_MB_DEFAULT
            ).coerceIn(1, 200),
            channel = prefs.getInt(
                CHANNEL_KEY, CHANNEL_DEFAULT
            )
        )
    }
}
