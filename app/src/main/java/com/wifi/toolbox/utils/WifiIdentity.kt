package com.wifi.toolbox.utils

import android.content.Context
import android.net.wifi.SupplicantState
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
 * 2. cmd wifi status（Android 11+，特权 shell）
 * 3. dumpsys wifi（通用兜底，mWifiInfo 行含 SSID 与 Net ID）
 *
 * 【输出格式依据 AOSP 源码（packages/modules/Wifi WifiShellCommand#printWifiInfo
 * 与 ClientModeImpl#dump，Android 11~16 各版一致）】：
 * - `Wifi is connected to "SSID"`（getSSID()：UTF-8 SSID 带双引号，
 *    非 UTF-8 为十六进制串，未知为 <unknown ssid>）
 * - `WifiInfo: SSID: "SSID", BSSID: ..., ..., Net ID: 12, ...`
 * - dumpsys：`mWifiInfo SSID: "SSID", BSSID: ...`（SSID 可含逗号，须截到
 *   `, BSSID:` 而非逗号）
 * 特权 shell（Shizuku uid2000 / Root）持有 NETWORK_SETTINGS 权限，
 * SSID 不受定位开关屏蔽（WifiServiceImpl#getConnectionInfo 对
 * NETWORK_SETTINGS 持有者清除 REDACT 屏蔽）。
 *
 * 特权命令通道与 [WifiHealer] 同源（遵循设置「执行通道」，自动=Shizuku→
 * Root AIDL→本地 sh 逐级回退），但【不写】GuardState.lastShellChannel——
 * 该状态位仅用于守护页展示探测/自愈通道，UI 侧解析不应污染。
 */
object WifiIdentity {

    /** 无效占位：应用层/特权输出中被系统屏蔽的 SSID 标记 */
    private val BAD_SSIDS = setOf("<unknown ssid>", "<none>", "unknown ssid", "0x")

    /** 身份解析结果（含 supplicant 完成位与 BSSID） */
    data class Identity(
        val ssid: String,
        val netId: Int,
        /** 握手是否已完成（应用层 supplicantState==COMPLETED，或特权输出解析所得） */
        val supplicantCompleted: Boolean,
        /** 当前连接的 BSSID（应用层被匿名化/为空时经特权输出解析；空串=不可得） */
        val bssid: String = ""
    )

    /** 特权输出解析结果（cmd wifi status 与 dumpsys 同源的 WifiInfo 行格式） */
    internal data class ParsedInfo(val ssid: String, val bssid: String, val netId: Int)

    /** 解析当前 WiFi 身份：返回 (ssid, networkId)，ssid 为空串表示不可得 */
    suspend fun resolve(context: Context, app: ToolboxApp?): Pair<String, Int> {
        val d = resolveDetail(context, app)
        return d.ssid to d.netId
    }

    /**
     * 解析当前 WiFi 身份与握手完成位。完成位取两处：
     * 1. 应用层 WifiInfo.getSupplicantState()（不受定位屏蔽，恒可读）；
     * 2. 特权输出中的 `Supplicant state: COMPLETED` 字段（WifiInfo.toString
     *    标准格式，cmd wifi status 与 dumpsys wifi 均含）。
     * 关联/握手阶段该位为 false——「关联成功但握手未完成」不能算连通。
     *
     * BSSID 同链路解析（真机反馈：未开启定位时网络页 BSSID 恒显「已隐藏」，
     * 而扫描页经 Shizuku 能显示——同一特权通道应一并兜底）：应用层
     * getBSSID() 在定位关闭时与 SSID 受同一套 REDACT 屏蔽，返回匿名化
     * 占位 02:00:00:00:00:00；特权 shell（NETWORK_SETTINGS 持有者）输出
     * 不屏蔽，WifiInfo 行同一行内即含真实 BSSID。
     */
    suspend fun resolveDetail(context: Context, app: ToolboxApp?): Identity {
        var ssid = ""
        var netId = -1
        var completed = false
        var bssid = ""
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val raw = info?.ssid?.removeSurrounding("\"").orEmpty()
            if (raw.isNotEmpty() && raw !in BAD_SSIDS) ssid = raw
            if (info != null) netId = info.networkId
            completed = info?.supplicantState == SupplicantState.COMPLETED
            // 应用层 BSSID：匿名化占位（定位关闭等场景）视同不可得，
            // 留待特权输出解析真实值
            val rawBssid = try { info?.bssid.orEmpty() } catch (_: Exception) { "" }
            if (rawBssid.isNotEmpty() && !rawBssid.equals(ANONYMIZED_BSSID, true)) {
                bssid = rawBssid
            }
        } catch (_: Exception) {
        }
        if (ssid.isEmpty() || netId == -1 || bssid.isEmpty()) {
            try {
                // Android 11+：cmd wifi status（一次输出同时含 SSID/BSSID/Net ID）
                if (ssid.isEmpty() || netId == -1 || bssid.isEmpty()) {
                    val status = privilegedExec(context, app, "cmd wifi status")
                    parseStatusOutput(status).let { p ->
                        if (ssid.isEmpty()) ssid = p.ssid
                        if (netId == -1) netId = p.netId
                        if (bssid.isEmpty()) bssid = p.bssid
                    }
                    completed = completed || parseSupplicantCompleted(status)
                }
                if (ssid.isEmpty() || netId == -1 || bssid.isEmpty()) {
                    // 通用兜底：dumpsys wifi 的 mWifiInfo 行（同一行含 SSID/BSSID/Net ID）
                    val dump = privilegedExec(context, app, "dumpsys wifi")
                    parseDumpsysOutput(dump).let { p ->
                        if (ssid.isEmpty()) ssid = p.ssid
                        if (netId == -1) netId = p.netId
                        if (bssid.isEmpty()) bssid = p.bssid
                    }
                    completed = completed || parseSupplicantCompleted(dump)
                }
            } catch (_: Exception) {
            }
        }
        return Identity(ssid, netId, completed, bssid)
    }

    /**
     * 解析 `cmd wifi status` 输出（AOSP WifiShellCommand#printWifiInfo）：
     *   Wifi is connected to "MyWiFi"
     *   WifiInfo: SSID: "MyWiFi", BSSID: aa:bb:cc:dd:ee:ff, ..., Net ID: 12, ...
     * 未连接时输出 `Wifi is not connected`。
     */
    internal fun parseStatusOutput(output: String): ParsedInfo {
        var ssid = ""
        // ① 主行：Wifi is connected to <getSSID()>（带引号/十六进制/unknown 三态）
        Regex("Wifi is connected to (.+)")
            .find(output)?.groupValues?.get(1)
            ?.let { cleanSsid(it) }?.let { if (it.isNotEmpty()) ssid = it }
        // ② WifiInfo 行兜底（SSID/BSSID/Net ID 同行，SSID 可含逗号 → 截到 ", BSSID:"）
        if (ssid.isEmpty()) {
            Regex("WifiInfo: SSID: (.*?), BSSID:")
                .find(output)?.groupValues?.get(1)
                ?.let { cleanSsid(it) }?.let { if (it.isNotEmpty()) ssid = it }
        }
        val netId = Regex("Net ID: (\\d+)")
            .find(output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        // BSSID 取 WifiInfo 行内真实 MAC（不取扫描结果区——此处解析的是连接信息）
        val bssid = parseBssidOfInfoLine(output)
        return ParsedInfo(ssid, bssid, netId)
    }

    /**
     * 解析 `dumpsys wifi` 输出（AOSP ClientModeImpl#dump）：
     *   mWifiInfo SSID: "MyWiFi", BSSID: aa:bb:cc:dd:ee:ff, ..., Net ID: 12, ...
     * 取第一条有效 mWifiInfo 行（多 ClientModeManager 场景下首个即主 STA）。
     */
    internal fun parseDumpsysOutput(output: String): ParsedInfo {
        var ssid = ""
        for (m in Regex("mWifiInfo SSID: (.*?), BSSID:").findAll(output)) {
            val s = cleanSsid(m.groupValues[1])
            if (s.isNotEmpty()) {
                ssid = s
                break
            }
        }
        val netId = Regex("Net ID: (\\d+)")
            .find(output)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val bssid = parseBssidOfInfoLine(output)
        return ParsedInfo(ssid, bssid, netId)
    }

    /**
     * 解析输出中 WifiInfo 行的 BSSID（WifiInfo.toString 标准格式
     * `SSID: ..., BSSID: aa:bb:cc:dd:ee:ff, ...`，cmd wifi status 与
     * dumpsys wifi 同源）。仅接受标准 MAC 形态并过滤匿名化占位——
     * 防止个别 ROM 特权输出仍被脱敏时把假地址当真值。
     */
    internal fun parseBssidOfInfoLine(output: String): String {
        Regex("BSSID: ([0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5})")
            .find(output)?.groupValues?.get(1)
            ?.takeIf { it.isNotEmpty() && !it.equals(ANONYMIZED_BSSID, true) }
            ?.let { return it }
        return ""
    }

    /**
     * 解析输出中的 supplicant 完成位：WifiInfo.toString 标准格式含
     * `Supplicant state: COMPLETED` 字段（cmd wifi status 的 WifiInfo 行
     * 与 dumpsys wifi 的 mWifiInfo 行均同源）。关联/握手阶段此值为
     * ASSOCIATED 等非完成态——即「关联成功但握手未完成」，不能算连通。
     */
    internal fun parseSupplicantCompleted(output: String): Boolean =
        Regex("Supplicant state:\\s*([A-Za-z_]+)", RegexOption.IGNORE_CASE)
            .find(output)?.groupValues?.get(1)
            ?.equals("completed", ignoreCase = true) == true

    /** 清洗 SSID：去首尾引号/空白，过滤系统屏蔽占位（<unknown ssid> 等） */
    private fun cleanSsid(raw: String): String {
        val s = raw.trim().removeSurrounding("\"").trim()
        // 非 UTF-8 SSID 为纯十六进制串（如 0a1b2c...），原样保留（系统设置同款行为）；
        // 过滤系统屏蔽占位与空串
        return if (s in BAD_SSIDS || s.isEmpty()) "" else s
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
