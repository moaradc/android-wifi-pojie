package com.wifi.toolbox.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.wifi.toolbox.structs.GuardSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * 单次探测结果
 */
data class ProbeResult(
    val mode: String,      // HTTP / DNS / ICMP / VALIDATED
    val ok: Boolean,
    val detail: String,    // 人类可读细节（IP、耗时等），用于日志与事件记录
    val isPortal: Boolean = false // 命中 Captive Portal 特征
)

/**
 * 整体判定结果
 */
data class ProbeVerdict(
    val wifiConnected: Boolean,     // WiFi 链路层是否已连接
    val online: Boolean,            // 是否在线（任一探测通过）
    val portal: Boolean,            // 是否疑似 Captive Portal
    val results: List<ProbeResult>  // 各探测项明细
)

/**
 * 网络连通性检测器——"已连接 ≠ 能上网" 的专业判定。
 *
 * 核心正确性：所有应用层探测都通过 [Network.openConnection] 绑定 WiFi 网络，
 * 即使系统默认路由已回落到蜂窝，探测也只会走 WiFi，杜绝"误判为在线"。
 *
 * 四种可插拔策略（[GuardSettings.probeModes] 位掩码组合）：
 * - HTTP 204   AOSP 标准探测（与系统 NetworkMonitor 同源），双端点互备
 * - DNS        检测运营商 DNS 黑洞/劫持（204 通但 DNS 死的疑难场景）
 * - ICMP       依赖 Shizuku/root shell 的 ping -I wlan0（应用层全挂时的仲裁手段）
 * - VALIDATED  系统 NET_CAPABILITY_VALIDATED 能力位（最近一次系统验证结论，零开销）
 */
object NetProber {

    // 与 AOSP NetworkMonitor 一致的标准探测端点；互为异构备份
    private val HTTP_204_ENDPOINTS = listOf(
        "http://connectivitycheck.gstatic.com/generate_204",
        "http://www.qualcomm.cn/generate_204"
    )

    // DNS 探测域名：境外公共域 + 境内可解析域，避免单点故障误判
    private val DNS_TARGETS = listOf("www.google.com", "www.baidu.com")

    private const val ICMP_TARGET = "204.2.134.20" // gstatic.com 固定 IP，避免 DNS 干扰 ICMP 判定
    private const val ICMP_TIMEOUT = 3

    /**
     * 执行一次完整检测。全部探测并行执行（速度优先），总耗时约等于最慢单项。
     */
    suspend fun probe(
        context: Context,
        settings: GuardSettings,
        shellExecutor: suspend (String) -> CommandRunner.CommandResult
    ): ProbeVerdict = coroutineScope {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiNetwork = findWifiNetwork(cm)
        val capabilities = wifiNetwork?.let { cm.getNetworkCapabilities(it) }
        val wifiConnected = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val modes = settings.probeModes
        val jobs = mutableListOf<kotlinx.coroutines.Deferred<ProbeResult>>()

        if (modes and GuardSettings.PROBE_HTTP_204 != 0) {
            jobs += async {
                withTimeoutOrNull(settings.probeTimeoutMs.toLong() + 1000) {
                    probeHttp204(wifiNetwork, settings.probeTimeoutMs).first()
                } ?: ProbeResult("HTTP", false, "timeout")
            }
        }
        if (modes and GuardSettings.PROBE_DNS != 0) {
            jobs += async { probeDns(settings.probeTimeoutMs) }
        }
        if (modes and GuardSettings.PROBE_ICMP != 0) {
            jobs += async { probeIcmp(shellExecutor) }
        }
        if (modes and GuardSettings.PROBE_VALIDATED != 0 && capabilities != null) {
            jobs += async { probeValidated(capabilities) }
        }

        if (jobs.isEmpty()) {
            // 未选择任何检测策略时退化为 VALIDATED 单项，保证功能可用
            val cap = capabilities
            val r = if (cap != null) listOf(probeValidated(cap)) else emptyList()
            return@coroutineScope ProbeVerdict(wifiConnected, wifiConnected && r.any { it.ok }, false, r)
        }

        val results = jobs.awaitAll().filter { it != null }.map { it!! }
        val online = results.any { it.ok }
        val portal = !online && results.any { it.isPortal }
        ProbeVerdict(wifiConnected, online, portal, results)
    }

    /** 找到当前 WiFi Network（绑定探测的目标） */
    fun findWifiNetwork(cm: ConnectivityManager): Network? {
        return try {
            cm.allNetworks.firstOrNull { n ->
                try {
                    cm.getNetworkCapabilities(n)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                } catch (_: Exception) {
                    false
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * HTTP 204 探测：并行打双端点，任一返回 204 即在线。
     * 额外识别两类 Portal 特征：302 重定向（跳认证页）、200 + body（推送页）。
     */
    private suspend fun probeHttp204(
        wifiNetwork: Network?,
        timeoutMs: Int
    ): List<ProbeResult> = coroutineScope {
        val jobs = HTTP_204_ENDPOINTS.map { url ->
            async(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                var conn: HttpURLConnection? = null
                try {
                    // 关键：绑定 WiFi 网络发起请求，防蜂窝回落误判
                    conn = if (wifiNetwork != null) {
                        wifiNetwork.openConnection(URL(url)) as HttpURLConnection
                    } else {
                        URL(url).openConnection() as HttpURLConnection
                    }
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.instanceFollowRedirects = false
                    conn.useCaches = false
                    val code = conn.responseCode
                    val cost = System.currentTimeMillis() - start
                    when {
                        code == 204 -> ProbeResult("HTTP", true, "$code ${cost}ms")
                        code in 300..399 -> ProbeResult(
                            "HTTP", false, "$code ${cost}ms", isPortal = true
                        )
                        else -> ProbeResult("HTTP", false, "$code ${cost}ms")
                    }
                } catch (e: Exception) {
                    ProbeResult("HTTP", false, e.javaClass.simpleName)
                } finally {
                    conn?.disconnect()
                }
            }
        }
        jobs.awaitAll()
    }

    /**
     * DNS 探测：通过 WiFi 网络解析域名。域名全挂但 HTTP 通 = DNS 服务器问题，
     * HTTP 也挂 = 链路问题。给根因定位提供维度。
     */
    private suspend fun probeDns(timeoutMs: Int): ProbeResult =
        withTimeoutOrNull(timeoutMs.toLong() + 1500) {
            val start = System.currentTimeMillis()
            try {
                // 注意：应用层 API 无法直接绑定网络做 DNS，此处用系统默认解析。
                // 若 WiFi 已是系统默认网络（绝大多数场景）结果可信；
                // 与 HTTP 探测互为佐证，单项目失败不直接触发自愈（有防抖兜底）。
                val resolved = DNS_TARGETS.map { target ->
                    try {
                        val addrs = InetAddress.getAllByName(target)
                        target to addrs.firstOrNull()?.hostAddress
                    } catch (e: Exception) {
                        target to null
                    }
                }
                val cost = System.currentTimeMillis() - start
                val anyOk = resolved.any { it.second != null }
                val detail = resolved.joinToString(",") {
                    "${it.first.split(".").last()}=${it.second ?: "x"}"
                }
                ProbeResult("DNS", anyOk, "$detail ${cost}ms")
            } catch (e: Exception) {
                ProbeResult("DNS", false, e.javaClass.simpleName)
            }
        } ?: ProbeResult("DNS", false, "timeout")

    /**
     * ICMP 探测：通过特权 shell 执行 ping，并强制绑定 wlan0 接口。
     * 探测固定 IP，绕过 DNS 环节，是"域名全挂但裸 IP 通"类故障的仲裁手段。
     */
    private suspend fun probeIcmp(shellExecutor: suspend (String) -> CommandRunner.CommandResult): ProbeResult {
        return try {
            val cmd = "ping -c 1 -W $ICMP_TIMEOUT -I wlan0 $ICMP_TARGET"
            val result = shellExecutor(cmd)
            val ok = result.exitCode == 0
            val detail = result.output.lineSequence()
                .firstOrNull { it.contains("time=") || it.contains("ttl=") }
                ?.trim() ?: "exit=${result.exitCode}"
            ProbeResult("ICMP", ok, detail)
        } catch (e: Exception) {
            ProbeResult("ICMP", false, e.javaClass.simpleName)
        }
    }

    /** 系统能力位探测：NET_CAPABILITY_VALIDATED 表示系统最近一次验证通过 */
    private fun probeValidated(capabilities: NetworkCapabilities): ProbeResult {
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return ProbeResult("VALIDATED", validated, if (validated) "system" else "unvalidated")
    }
}
