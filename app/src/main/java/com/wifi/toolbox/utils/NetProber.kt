package com.wifi.toolbox.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import com.wifi.toolbox.structs.GuardSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.util.Locale

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
 * - HTTP 204   AOSP 标准探测（与系统 NetworkMonitor 同源），端点可自选预设
 *   （[GuardSettings.httpEndpoint]，任一端点返回 204 即在线、任一成功即刻返回）
 * - DNS        检测运营商 DNS 黑洞/劫持（204 通但 DNS 死的疑难场景）
 * - ICMP       特权 shell ping（源 IP 绑定 WiFi 接口，应用层全挂时的仲裁手段）
 * - VALIDATED  系统 NET_CAPABILITY_VALIDATED 能力位（最近一次系统验证结论，零开销）
 */
object NetProber {

    // HTTP 204 探测端点（国内厂商端点经联网考证 + 连通性验证 2026-09，
    // 阿里云香港点位每端点 3 次）：小米/华为/vivo/高通/Google/Cloudflare
    // 均 204 稳定；OPPO 现行域名为 conn2.oppomobile.com（旧 conn.oppomobile.com
    // 已全球 DNS 失效 NXDOMAIN）；魅族 conn.flyme.cn 返回 403 不收录；
    // www.google.cn 解析到 Google 全球 IP 境内大概率不通不收录；
    // Cloudflare cp 境内可达性一般，仅作境外档备用端点。
    private const val EP_MIUI = "http://connect.rom.miui.com/generate_204"
    private const val EP_HUAWEI = "http://connectivitycheck.platform.hicloud.com/generate_204"
    private const val EP_VIVO = "http://wifi.vivo.com.cn/generate_204"
    private const val EP_OPPO = "http://conn2.oppomobile.com/generate_204"
    private const val EP_QUALCOMM = "http://www.qualcomm.cn/generate_204"
    private const val EP_GSTATIC = "http://connectivitycheck.gstatic.com/generate_204"
    private const val EP_CLOUDFLARE = "http://cp.cloudflare.com/generate_204"

    // HTTP 探测任务常驻 scope（fire-and-forget）：任一端点成功后弃置仍在途的
    // 被墙端点（由其自身超时收尾），本轮探测即刻返回，不再等最慢端点超时
    private val httpProbeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * HTTP 204 探测端点预设（与 [GuardSettings.httpEndpoint] 档位一一对应），
     * 每档含主端点 + 备用端点，任一返回 204 即在线。
     */
    fun httpEndpoints(preset: Int): List<String> = when (preset) {
        1 -> listOf(EP_MIUI, EP_QUALCOMM)
        2 -> listOf(EP_HUAWEI, EP_QUALCOMM)
        3 -> listOf(EP_VIVO, EP_QUALCOMM)
        4 -> listOf(EP_OPPO, EP_QUALCOMM)
        5 -> listOf(EP_QUALCOMM, EP_MIUI)
        6 -> listOf(EP_GSTATIC, EP_CLOUDFLARE)
        7 -> listOf(EP_CLOUDFLARE, EP_QUALCOMM)
        else -> autoHttpEndpoints()
    }

    /** 智能档（默认）：按设备厂商匹配国内端点（境外同样可达），未匹配回退 高通+Google */
    private fun autoHttpEndpoints(): List<String> {
        val m = Build.MANUFACTURER.lowercase(Locale.US)
        val vendor = when {
            m.contains("xiaomi") || m.contains("redmi") -> EP_MIUI
            m.contains("huawei") || m.contains("honor") -> EP_HUAWEI
            m.contains("vivo") || m.contains("iqoo") -> EP_VIVO
            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") -> EP_OPPO
            else -> null
        }
        return if (vendor != null) listOf(vendor, EP_QUALCOMM)
        else listOf(EP_QUALCOMM, EP_GSTATIC)
    }

    // DNS 探测域名：境外公共域 + 境内可解析域，避免单点故障误判
    private val DNS_TARGETS = listOf("www.google.com", "www.baidu.com")

    // ICMP 探测目标：公共 DNS 任播 IP，长期稳定回应 ICMP，境内外均可直达
    // （阿里 DNS / 腾讯 DNSPod 任播，不依赖任何单一运营商）
    private const val ICMP_PRIMARY = "223.5.5.5"
    private const val ICMP_BACKUP = "119.29.29.29"
    private const val ICMP_TIMEOUT = 3

    /**
     * 执行一次完整检测。全部探测并行执行（速度优先），总耗时约等于最慢单项。
     */
    suspend fun probe(
        context: Context,
        settings: GuardSettings,
        shellExecutor: suspend (String) -> CommandRunner.ShellOutcome
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
                    // 端点由预设决定（settings.httpEndpoint），任一返回 204 即在线
                    //（互备取值，第十六轮修复 .first() 只取首端点的 bug）；
                    // 全失败时优先暴露 Portal 特征（302/200+body，对 Portal
                    // 判定与日志诊断更有价值）；两者皆无取首个失败明细。
                    val rs = probeHttp204(
                        wifiNetwork, settings.probeTimeoutMs,
                        httpEndpoints(settings.httpEndpoint)
                    )
                    rs.firstOrNull { it.ok }
                        ?: rs.firstOrNull { it.isPortal }
                        ?: rs.firstOrNull()
                        ?: ProbeResult("HTTP", false, "timeout")
                } ?: ProbeResult("HTTP", false, "timeout")
            }
        }
        if (modes and GuardSettings.PROBE_DNS != 0) {
            jobs += async { probeDns(wifiNetwork, settings.probeTimeoutMs) }
        }
        if (modes and GuardSettings.PROBE_ICMP != 0) {
            jobs += async { probeIcmp(cm, wifiNetwork, shellExecutor) }
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
     * HTTP 204 探测：并行探测预设端点列表，任一返回 204 即在线。
     * 额外识别两类 Portal 特征：302 重定向（跳认证页）、200 + body（推送页）。
     *
     * 任一端点成功即刻返回（fire-and-forget 弃置仍在途的被墙端点，由其自身
     * 超时收尾）——旧实现 awaitAll 等最慢端点，境内被墙的 gstatic 会把每轮
     * HTTP 探测拖满超时；全失败时收齐全部结果以暴露 Portal 特征。
     */
    private suspend fun probeHttp204(
        wifiNetwork: Network?,
        timeoutMs: Int,
        endpoints: List<String>
    ): List<ProbeResult> = coroutineScope {
        if (endpoints.isEmpty()) return@coroutineScope emptyList()
        val channel = Channel<ProbeResult>(Channel.UNLIMITED)
        // 探测任务挂在常驻 scope（非本协程子级）：任一成功后无需等其余端点完成
        endpoints.forEach { url ->
            httpProbeScope.launch {
                channel.send(probeOne204(url, wifiNetwork, timeoutMs))
            }
        }
        val results = mutableListOf<ProbeResult>()
        while (results.size < endpoints.size && results.none { it.ok }) {
            results += channel.receive()
        }
        results
    }

    /** 单端点探测一次；detail 带端点短名（日志可定位是哪个端点应答） */
    private fun probeOne204(
        url: String,
        wifiNetwork: Network?,
        timeoutMs: Int
    ): ProbeResult {
        val label = hostLabel(url)
        val start = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
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
                code == 204 -> ProbeResult("HTTP", true, "$label $code ${cost}ms")
                code in 300..399 -> ProbeResult(
                    "HTTP", false, "$label $code ${cost}ms", isPortal = true
                )
                else -> ProbeResult("HTTP", false, "$label $code ${cost}ms")
            }
        } catch (e: Exception) {
            ProbeResult("HTTP", false, "$label ${e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    /** 端点短名（日志/事件里标识是哪个端点应答） */
    private fun hostLabel(url: String): String = when {
        url.contains("miui.com") -> "miui"
        url.contains("hicloud.com") -> "hicloud"
        url.contains("vivo.com") -> "vivo"
        url.contains("oppomobile.com") -> "oppo"
        url.contains("qualcomm.cn") -> "qualcomm"
        url.contains("gstatic.com") -> "gstatic"
        url.contains("cloudflare.com") -> "cloudflare"
        else -> "http"
    }

    /**
     * DNS 探测：通过 [Network.getAllByName] 绑定 WiFi 网络解析域名。
     * 若不绑网，WiFi 假连接、系统默认路由已回落蜂窝时，
     * 默认解析会走蜂窝成功，把"WiFi 外网断"误判为在线；
     * 绑定后与 HTTP 探测同源，才能暴露运营商 DNS 黑洞/劫持类故障。
     */
    private suspend fun probeDns(wifiNetwork: Network?, timeoutMs: Int): ProbeResult =
        withTimeoutOrNull(timeoutMs.toLong() + 1500) {
            val start = System.currentTimeMillis()
            try {
                // 域名全挂但 HTTP 通 = DNS 服务器问题；HTTP 也挂 = 链路问题。
                // 两个维度给根因定位提供依据。
                val resolved = DNS_TARGETS.map { target ->
                    try {
                        // 绑定 WiFi 网络解析（Network.getAllByName），
                        // 防止蜂窝回落时 DNS 走蜂窝成功导致误判在线
                        val addrs = if (wifiNetwork != null) {
                            wifiNetwork.getAllByName(target)
                        } else {
                            InetAddress.getAllByName(target)
                        }
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
     * ICMP 探测：通过特权 shell 执行 ping。
     *
     * 绑定方式的正确性关键（修复 "ICMP 永远 exit=2" 的真机反馈）：
     * - 旧实现 `ping -I wlan0` 走 SO_BINDTODEVICE，该套接字选项需要 CAP_NET_RAW
     *   特权能力；Shizuku 的 shell(uid 2000) 与应用进程都没有此能力，
     *   内核 <5.6 直接返回 EPERM，iputils 以 error(2) 退出 —— 即恒定 exit=2。
     * - 现改用 **源 IP 绑定**（`ping -I <WiFi 接口 IPv4>`）：底层是普通 bind()，
     *   无需任何特权；内核按源地址强制选择 wlan 路由，同样能防止探测回落蜂窝。
     * - 源 IP 不可用时回退接口名绑定（内核 ≥5.6 或真 root 下可用），
     *   再不可用才回退不绑定（detail 中如实标注）。
     * - 目标改为公共 DNS 任播 IP（阿里/腾讯），而非境外单点，保证境内可靠回应。
     * - 探测失败时输出 ping 的真实报错行（不再只给 "exit=2"），便于定位。
     */
    private suspend fun probeIcmp(
        cm: ConnectivityManager,
        wifiNetwork: Network?,
        shellExecutor: suspend (String) -> CommandRunner.ShellOutcome
    ): ProbeResult {
        return try {
            // 应用层免权限读取 WiFi 接口信息（接口名 + IPv4 源地址）
            var srcIp: String? = null
            var iface: String? = null
            if (wifiNetwork != null) {
                try {
                    val lp = cm.getLinkProperties(wifiNetwork)
                    iface = lp?.interfaceName
                    srcIp = lp?.linkAddresses
                        ?.firstOrNull {
                            it.address is Inet4Address && !it.address.isLoopbackAddress
                        }?.address?.hostAddress
                } catch (_: Exception) {
                }
            }

            // 绑定优先级：源IP(免特权) > 接口名(需CAP_NET_RAW) > 不绑定
            val bindValue: String? = srcIp ?: iface
            var target = ICMP_PRIMARY
            var unboundUsed = false
            var outcome = runPing(shellExecutor, bindValue, target)

            if (outcome.exitCode != 0 && isBindRejected(outcome.output)) {
                // 绑定方式被系统拒绝（权限/能力位/接口不存在）→ 降级为不绑定重试
                unboundUsed = true
                outcome = runPing(shellExecutor, null, target, unbound = true)
            } else if (outcome.exitCode == 1) {
                // ping 本身工作正常但无应答：换备用任播目标再试一次，排除单点不回应
                target = ICMP_BACKUP
                outcome = runPing(shellExecutor, bindValue, target)
            }

            val detail = if (outcome.exitCode == 0) {
                val rtt = Regex("time=[0-9.]+ ?ms").find(outcome.output)?.value
                    ?: outcome.output.lineSequence()
                        .firstOrNull { it.contains("ttl=") }?.trim() ?: "ok"
                val bindNote = if (unboundUsed) " (unbound)" else ""
                "$target $rtt$bindNote"
            } else {
                // 失败时展示 ping 的真实报错行，不再只给 "exit=2" 无法定位
                val err = firstErrorLine(outcome.output)
                if (err.isNullOrEmpty()) "exit=${outcome.exitCode}" else err
            }
            // 通道不在 detail 中标注（状态卡"当前通道"行已实时展示，
            // 避免每行重复 [Shizuku] 造成的信息噪音）
            ProbeResult("ICMP", outcome.exitCode == 0, detail)
        } catch (e: Exception) {
            ProbeResult("ICMP", false, e.javaClass.simpleName)
        }
    }

    /** 执行一次 ping；unbound=true 时不绑定任何接口（保底手段） */
    private suspend fun runPing(
        shellExecutor: suspend (String) -> CommandRunner.ShellOutcome,
        bindValue: String?,
        target: String,
        unbound: Boolean = false
    ): CommandRunner.ShellOutcome {
        val cmd = buildString {
            append("ping -c 1 -W $ICMP_TIMEOUT")
            if (!unbound && bindValue != null) append(" -I $bindValue")
            append(" $target")
        }
        return shellExecutor(cmd)
    }

    /** ping 输出中第一条真实报错行（过滤 PING 头与统计行） */
    private fun firstErrorLine(output: String): String? {
        return output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstOrNull { line ->
                !line.startsWith("PING ") && !line.startsWith("--- ")
            }
    }

    /** 判断失败是否为绑定方式被拒绝（权限/能力位/接口不存在），可安全降级重试 */
    private fun isBindRejected(output: String): Boolean {
        val low = output.lowercase()
        return low.contains("operation not permitted") ||
                low.contains("so_bindtodevice") ||
                low.contains("unknown iface") ||
                low.contains("no such device") ||
                low.contains("cannot assign requested address")
    }

    /** 系统能力位探测：NET_CAPABILITY_VALIDATED 表示系统最近一次验证通过 */
    private fun probeValidated(capabilities: NetworkCapabilities): ProbeResult {
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return ProbeResult("VALIDATED", validated, if (validated) "system" else "unvalidated")
    }
}
