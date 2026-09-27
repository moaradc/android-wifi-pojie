package com.wifi.toolbox.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.NetLogSettings
import com.wifi.toolbox.utils.AidlServiceHelper
import com.wifi.toolbox.utils.CommandRunner
import com.wifi.toolbox.utils.NetLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import rikka.shizuku.api.Shizuku
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Consumer

/**
 * 网络日志（独立捕获服务）：完整记录一次网络事件的全过程证据链。
 *
 * 与网络守护（GuardService）完全独立、互不干扰：
 * - 纯观察纪律：本服务绝不执行任何改变网络状态的命令，全部命令只读
 *   （logcat / dumpsys / ip addr / ip route / cat /proc/net/wireless）
 * - 守护照常运行；两者的日志各自独立存储
 *
 * 三层捕获（按事件到达速度分层）：
 * - T0 应用层监听（零权限、毫秒级）：ConnectivityManager.NetworkCallback
 *   （AVAILABLE/LOSING/LOST/VALIDATED 跃迁）+ WiFi 状态广播
 *   （开关/连接状态机/supplicant）+ RSSI 跳变 + 屏幕亮灭 —— 事件时刻锚点
 * - T1 系统日志流（Shizuku/Root）：logcat 网络标签长驻流
 *   （WifiClientModeImpl/ConnectivityService/NetworkMonitor/DhcpClient 等
 *   连接状态机的每一步），EOF 自动重连并记录 GAP
 * - T1 事件快照（Shizuku/Root）：T0 事件触发 dumpsys 命令组存档——
 *   断连原因码/NetworkMonitor 探测详情等只在内存存活几秒，须抢在过期前落盘
 *
 * 会话产物见 [NetLogStore]。
 */
class NetworkLogService : android.app.Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: android.content.SharedPreferences
    private var settings = NetLogSettings()

    /** 当前会话目录；null = 未在记录 */
    private var sessionDir: File? = null
    private var sessionStartWall = 0L
    private var sessionStartElapsed = 0L

    // ==================== 捕获组件状态 ====================

    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null

    /** logcat 流取消器；null = 流未启动或已停止 */
    private var logcatCancel: Runnable? = null
    private val logcatRunning = AtomicBoolean(false)

    /** logcat 流重连退避（毫秒）：3s → 6s → 12s → 60s 封顶，EOF 重置到 3s */
    private var logcatReconnectDelay = 3_000L
    private val logcatLineCount = AtomicLong(0)

    /** 各网络的上次 VALIDATED 状态（跃迁才记时间线，防 capabilities 高频刷屏） */
    private val lastValidated = HashMap<Network, Boolean>()

    /** 上次记录的 RSSI（dBm）与时刻：跳变 ≥6dB 或超时 60s 才记 */
    private var lastRssi = Int.MIN_VALUE
    private var lastRssiAt = 0L

    /** 快照防抖：上次快照时刻（任何原因 5s 内合并） */
    private var lastSnapshotAt = 0L
    private var snapshotJob: Job? = null

    private var probeJob: Job? = null

    /**
     * 时间线落盘单线程队列：广播回调在主线程，磁盘 IO 不允许阻塞 UI；
     * 单线程同时保证时间线严格有序（多线程会交错乱序）。
     */
    private val diskExecutor =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "netlog-disk").apply { isDaemon = true }
        }

    private val stopped = AtomicBoolean(false)

    // ==================== 生命周期 ====================

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(NetLogSettings.PREFS_NAME, MODE_PRIVATE)
        settings = NetLogSettings.from(prefs)
        NetLogState.running = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SNAPSHOT -> {
                // 手动「立即抓快照」：无防抖限制（用户意图优先）；
                // 未在录制时静默忽略——绝不能因点快照而误开录制
                if (sessionDir != null) {
                    scope.launch { captureSnapshot("manual") }
                }
            }
            ACTION_RELOAD -> if (sessionDir != null) {
                // 会话进行中热加载设置；未运行时不需要
                settings = NetLogSettings.from(prefs)
            }
        }

        // 只有裸启动（用户点「开始记录」）才开启新会话：
        // 快照/重载等子命令 intent 到达时若会话未开，不做任何事
        if (intent?.action == null && sessionDir == null) {
            startSession()
        }

        // 会话进行中（含刚开的）维持前台；子命令空转路径直接自停
        // （startService 拉起无前台化义务，stopSelf 即走，不残留通知）
        if (sessionDir != null) {
            startAsForeground()
        } else {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession()
        // 等待时间线队列排空再关（最终几行事件不丢）
        try {
            diskExecutor.shutdown()
            diskExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
        scope.cancel()
        NetLogState.running = false
        super.onDestroy()
    }

    // ==================== 会话开始/停止 ====================

    private fun startSession() {
        stopped.set(false)
        sessionDir = NetLogStore.newSessionDir(this)
        sessionStartWall = System.currentTimeMillis()
        sessionStartElapsed = SystemClock.elapsedRealtime()

        NetLogState.running = true
        NetLogState.sessionName = sessionDir!!.name
        NetLogState.startedAt = sessionStartWall
        NetLogState.eventCount = 0
        NetLogState.logLines = 0
        NetLogState.logCapped = false
        NetLogState.channel = resolveChannelName()
        NetLogState.lastError = ""
        NetLogState.clearPreview()

        timeline(
            "session", "info",
            "capture start · channel=${NetLogState.channel}"
        )

        registerT0Listeners()

        if (settings.captureSystemLog) {
            captureBootContextIfBroken()
            startLogcatStream()
        }
        if (settings.probeTimeline) {
            startProbeLoop()
        }
    }

    private fun stopSession() {
        if (stopped.getAndSet(true)) return
        val dir = sessionDir ?: return

        unregisterT0Listeners()
        logcatCancel?.run()
        logcatCancel = null
        logcatRunning.set(false)
        probeJob?.cancel()
        probeJob = null
        snapshotJob?.cancel()
        snapshotJob = null

        val cost = SystemClock.elapsedRealtime() - sessionStartElapsed
        timeline("session", "info", "capture stop · ${cost / 1000}s")

        // 元数据：设备指纹 + Android 版本 + 通道 + 统计（事后分析必需的上下文）
        val meta = JSONObject().apply {
            put("device", "${Build.BRAND} ${Build.MODEL}")
            put("android", "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            put("channel", NetLogState.channel)
            put("startedAt", sessionStartWall)
            put("stoppedAt", System.currentTimeMillis())
            put("durationSec", cost / 1000)
            put("timelineEvents", NetLogState.eventCount)
            put("logcatLines", logcatLineCount.get())
            put("logCapped", NetLogState.logCapped)
            put("logTags", LOG_TAGS)
            put("captureSystemLog", settings.captureSystemLog)
            put("snapshotOnEvent", settings.snapshotOnEvent)
            put("probeTimeline", settings.probeTimeline)
        }
        try {
            NetLogStore.writeMeta(dir, meta)
        } catch (_: Exception) {
        }

        // 会话轮转（保留最近 keepSessions 个）
        try {
            NetLogStore.pruneSessions(this, settings.keepSessions)
        } catch (_: Exception) {
        }

        NetLogState.running = false
        sessionDir = null
    }

    // ==================== T0：应用层监听（零权限、毫秒级） ====================

    private fun registerT0Listeners() {
        // 1) 网络回调：覆盖所有网络（不限 transport），回调内按需过滤
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val wifi = isWifi(network)
                    timeline(
                        "net", "info",
                        "AVAILABLE ${describe(network)}${extraInfo(network)}"
                    )
                    if (wifi) maybeSnapshot("net-available")
                }

                override fun onLosing(network: Network, maxMsToLive: Int) {
                    timeline(
                        "net", "warn",
                        "LOSING ${describe(network)} (score drop, ${maxMsToLive}ms)"
                    )
                    maybeSnapshot("net-losing")
                }

                override fun onLost(network: Network) {
                    timeline("net", "error", "LOST ${describe(network)}")
                    lastValidated.remove(network)
                    maybeSnapshot("net-lost")
                }

                override fun onUnavailable() {
                    timeline("net", "error", "UNAVAILABLE (request timed out)")
                }

                override fun onCapabilitiesChanged(
                    network: Network, capabilities: NetworkCapabilities
                ) {
                    // capabilities 变化高频（含 RSSI 波动），只记验证/portal 跃迁
                    val validated = capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    val portal = capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
                    )
                    val prev = lastValidated[network]
                    if (prev == null || prev != validated) {
                        lastValidated[network] = validated
                        timeline(
                            "net", if (validated) "info" else "warn",
                            if (validated) "VALIDATED ${describe(network)}"
                            else if (portal) "CAPTIVE PORTAL ${describe(network)}"
                            else "NOT VALIDATED ${describe(network)}"
                        )
                        if (validated) maybeSnapshot("net-validated")
                    }
                }
            }
            cm.registerNetworkCallback(request, cb)
            netCallback = cb
        } catch (e: Exception) {
            timeline("net", "error", "registerNetworkCallback failed: ${e.message}")
        }

        // 2) WiFi 状态广播：总开关 / 连接状态机 / supplicant / RSSI
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN
                        )
                        val desc = when (state) {
                            WifiManager.WIFI_STATE_ENABLED -> "ENABLED"
                            WifiManager.WIFI_STATE_ENABLING -> "ENABLING"
                            WifiManager.WIFI_STATE_DISABLING -> "DISABLING"
                            WifiManager.WIFI_STATE_DISABLED -> "DISABLED"
                            else -> "UNKNOWN"
                        }
                        timeline(
                            "wifi", if (state == WifiManager.WIFI_STATE_DISABLED) "error" else "info",
                            "WIFI STATE $desc"
                        )
                        if (state == WifiManager.WIFI_STATE_DISABLED ||
                            state == WifiManager.WIFI_STATE_DISABLING
                        ) maybeSnapshot("wifi-off")
                    }

                    WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val info = intent.getParcelableExtra<android.net.NetworkInfo>(
                            WifiManager.EXTRA_NETWORK_INFO
                        )
                        @Suppress("DEPRECATION")
                        val supplicant = intent.getParcelableExtra<android.net.wifi.SupplicantState>(
                            WifiManager.EXTRA_SUPPLICANT_STATE
                        )
                        if (info?.state == android.net.NetworkInfo.State.CONNECTED) {
                            val ssid = cleanSsid(info.extraInfo)
                            timeline("wifi", "info", "NETWORK CONNECTED · SSID=$ssid")
                        } else if (info?.state == android.net.NetworkInfo.State.DISCONNECTED) {
                            timeline("wifi", "error", "NETWORK DISCONNECTED")
                            maybeSnapshot("wifi-disconnected")
                        } else if (supplicant != null && supplicant.isValidState()) {
                            // 只记握手关键步，防刷屏
                            if (supplicant in SUPPLICANT_KEY_STATES) {
                                timeline("wifi", "info", "supplicant=$supplicant")
                            }
                        }
                    }

                    WifiManager.RSSI_CHANGED_ACTION -> {
                        val rssi = intent.getIntExtra(
                            WifiManager.EXTRA_NEW_RSSI, Int.MIN_VALUE
                        )
                        if (rssi == Int.MIN_VALUE) return
                        val now = SystemClock.elapsedRealtime()
                        val jump = lastRssi != Int.MIN_VALUE &&
                                kotlin.math.abs(rssi - lastRssi) >= RSSI_JUMP_DB
                        val stale = now - lastRssiAt > RSSI_MAX_INTERVAL_MS
                        if (jump || stale) {
                            lastRssi = rssi
                            lastRssiAt = now
                            timeline(
                                "rssi", if (jump) "warn" else "info",
                                "RSSI $rssi dBm"
                            )
                            if (jump && rssi <= RSSI_CRITICAL) {
                                maybeSnapshot("rssi-drop")
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
        }
        registerReceiver(receiver, filter)
        wifiReceiver = receiver

        // 3) 屏幕亮灭（后台断连类问题的关键背景）
        val sr = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                timeline(
                    "screen", "info",
                    if (intent.action == Intent.ACTION_SCREEN_ON) "SCREEN ON" else "SCREEN OFF"
                )
            }
        }
        val sf = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(sr, sf)
        screenReceiver = sr
    }

    private fun unregisterT0Listeners() {
        netCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        netCallback = null
        wifiReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        wifiReceiver = null
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        screenReceiver = null
    }

    // ==================== T1：系统日志流（logcat） ====================

    /**
     * 启动 logcat 网络标签长驻流。
     *
     * -T 1：只取启动后的新行（历史存量的价值在快照里按需抓取，不整段灌入）。
     * 通道选择遵循设置（自动时 Shizuku → Root → 本地 Shell 降级）。
     */
    private fun startLogcatStream() {
        if (!logcatRunning.compareAndSet(false, true)) return
        val dir = sessionDir ?: run { logcatRunning.set(false); return }

        val command = "$LOGCAT_BIN -v threadtime -T 1 -s $LOG_TAGS"
        val channel = NetLogState.channel
        val lineCounter = AtomicInteger(0)

        val onLine = Consumer<String> { line ->
            if (stopped.get()) return@Consumer
            try {
                NetLogStore.appendLogcat(dir, line)
                val n = logcatLineCount.incrementAndGet()
                NetLogState.logLines = n.toInt()
                // 大小封顶检查（每 200 行一次，避免每行查文件长度）
                if (lineCounter.incrementAndGet() >= 200) {
                    lineCounter.set(0)
                    val size = File(dir, "logcat_network.txt").length()
                    if (size > settings.maxLogMb * 1L * 1024 * 1024 && !NetLogState.logCapped) {
                        NetLogState.logCapped = true
                        timeline(
                            "logcat", "warn",
                            "size cap reached (${NetLogStore.formatSize(size)}), stream stopped"
                        )
                        logcatCancel?.run()
                        logcatCancel = null
                    }
                }
            } catch (_: Exception) {
            }
        }

        val onFinish = Consumer<CommandRunner.CommandResult> {
            logcatRunning.set(false)
            if (stopped.get() || sessionDir == null || NetLogState.logCapped) return@Consumer
            // EOF = Shizuku 重启/进程被杀：记 GAP 后退避重连
            val gapSec = logcatReconnectDelay / 1000
            timeline("logcat", "warn", "stream EOF, reconnect in ${gapSec}s")
            scope.launch {
                delay(logcatReconnectDelay)
                logcatReconnectDelay =
                    (logcatReconnectDelay * 2).coerceAtMost(LOGCAT_MAX_RECONNECT_MS)
                if (!stopped.get() && sessionDir != null && !NetLogState.logCapped) {
                    startLogcatStream()
                }
            }
        }

        try {
            logcatCancel = when (channel) {
                "Shizuku" -> ShizukuUtilCompat.executeStream(command, onLine, onFinish)
                "RootAIDL" -> CommandRunner.executeCommand(
                    command, true, onLine, onFinish
                )
                else -> {
                    // 普通应用无 READ_LOGS：logcat 大概率空输出/权限拒绝，
                    // 如实记录降级（时间线可追溯为什么流是空的）
                    timeline(
                        "logcat", "warn",
                        "local shell has no READ_LOGS, system log likely empty"
                    )
                    CommandRunner.executeCommand(command, false, onLine, onFinish)
                }
            }
            timeline("logcat", "info", "stream started · tags=$LOG_TAGS")
            // 流成功启动：重连退避回落（连续失败才指数放大）
            logcatReconnectDelay = LOGCAT_RECONNECT_MS
        } catch (e: Exception) {
            logcatRunning.set(false)
            timeline("logcat", "error", "stream failed to start: ${e.message}")
        }
    }

    /**
     * 故障进行中才启动捕获的场景：把 logcat 缓冲里的最近历史抢下来作为
     * 「现场存档」（-d 立即输出后退出，不进入长驻流）。判断依据：当前
     * WiFi 已断开或网络未验证。
     */
    private fun captureBootContextIfBroken() {
        scope.launch {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val wifiUp = cm.allNetworks.any {
                    it.let { n ->
                        try {
                            cm.getNetworkCapabilities(n)
                                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
                if (wifiUp) return@launch
                timeline("logcat", "warn", "wifi down at capture start, dumping boot context")

                val cmd = "$LOGCAT_BIN -d -t $BOOT_CONTEXT_LINES -s $LOG_TAGS"
                val out = execSync(cmd) ?: return@launch
                if (out.output.isNotBlank()) {
                    val f = NetLogStore.writeSnapshot(
                        sessionDir ?: return@launch, "boot-context",
                        buildString {
                            append("# 依据：捕获启动时 WiFi 已断开，回填最近 $BOOT_CONTEXT_LINES 行系统日志\n")
                            append("# 命令：").append(cmd).append("\n\n")
                            append(out.output)
                        }
                    )
                    timeline("snapshot", "info", "boot context saved (${NetLogStore.formatSize(f.length())})")
                }
            } catch (_: Exception) {
            }
        }
    }

    // ==================== T1：事件快照引擎 ====================

    /** 事件触发快照（5s 防抖合并；手动触发不走此入口） */
    private fun maybeSnapshot(reason: String) {
        if (!settings.snapshotOnEvent) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSnapshotAt < SNAPSHOT_DEBOUNCE_MS) return
        lastSnapshotAt = now
        scope.launch { captureSnapshot(reason) }
    }

    /**
     * 抓取一组系统状态快照。逐命令执行（协程级 8s 超时兜底——阻塞命令
     * 本身不响应取消，超时后放弃等待该命令结果，失败如实记录：哪条命令
     * 在哪台 ROM 上不可用本身就是有价值的诊断信息）。
     */
    private suspend fun captureSnapshot(reason: String) {
        val dir = sessionDir ?: return
        if (snapshotJob?.isActive == true) return  // 上一个快照还在跑，跳过（防堆积）
        snapshotJob = scope.launch {
            val sb = StringBuilder()
            sb.append("# 触发原因：").append(reason).append('\n')
            sb.append("# 时刻：").append(now()).append('\n')
            sb.append("# 通道：").append(NetLogState.channel).append("\n\n")
            for ((name, cmd) in SNAPSHOT_COMMANDS) {
                sb.append("===== ").append(name)
                    .append(" =====\n$ ").append(cmd).append('\n')
                val result = withTimeoutOrNull(SNAPSHOT_CMD_TIMEOUT_MS) {
                    execSync(cmd)
                }
                when {
                    result == null -> sb.append("(timeout after ")
                        .append(SNAPSHOT_CMD_TIMEOUT_MS / 1000).append("s)\n\n")
                    result.exitCode != 0 && result.output.isBlank() ->
                        sb.append("(exit=").append(result.exitCode).append(")\n\n")
                    else -> sb.append(result.output.trimEnd()).append("\n\n")
                }
            }
            try {
                val f = NetLogStore.writeSnapshot(dir, reason, sb.toString())
                timeline(
                    "snapshot", "info",
                    "'$reason' saved (${NetLogStore.formatSize(f.length())})"
                )
            } catch (_: Exception) {
            }
        }
    }

    // ==================== 可选：主动探测时间线 ====================

    /** 每 15s 一轮 HTTP 204 + DNS 双探测，定量记录「通/不通」曲线 */
    private fun startProbeLoop() {
        probeJob = scope.launch {
            while (isActive && !stopped.get()) {
                val http = probeHttp()
                val dns = probeDns()
                timeline(
                    "probe", when {
                        http == null || dns == null -> "warn"
                        else -> "info"
                    },
                    buildString {
                        append("HTTP ")
                        append(http ?: "✗")
                        append(" · DNS ")
                        append(dns ?: "✗")
                    }
                )
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    private suspend fun probeHttp(): String? = withTimeoutOrNull(4_000) {
        try {
            val conn = URL(PROBE_HTTP_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 3_500
            conn.readTimeout = 3_500
            conn.setRequestProperty("Cache-Control", "no-store")
            val start = SystemClock.elapsedRealtime()
            val code = conn.responseCode
            val cost = SystemClock.elapsedRealtime() - start
            conn.disconnect()
            if (code in 200..299) "✓ ${cost}ms" else "HTTP$code"
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun probeDns(): String? = withTimeoutOrNull(4_000) {
        try {
            val start = SystemClock.elapsedRealtime()
            val addrs = InetAddress.getAllByName(PROBE_DNS_HOST)
            val cost = SystemClock.elapsedRealtime() - start
            if (addrs.isNotEmpty()) "✓ ${cost}ms" else null
        } catch (_: Exception) {
            null
        }
    }

    // ==================== 通道与执行 ====================

    /** 按设置解析捕获通道名（自动时 Shizuku → Root AIDL → 本地 Shell） */
    private fun resolveChannelName(): String {
        return when (settings.channel) {
            1 -> if (shizukuReady()) "Shizuku" else "Shell(降级)"
            2 -> if (aidlReady()) "RootAIDL" else "Shell(降级)"
            3 -> "Shell"
            else -> when {
                shizukuReady() -> "Shizuku"
                aidlReady() -> "RootAIDL"
                else -> "Shell"
            }
        }
    }

    private fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    private fun aidlReady(): Boolean {
        val app = applicationContext as? ToolboxApp ?: return false
        return try {
            app.aidl.ipc != null
        } catch (_: Exception) {
            false
        }
    }

    /** 同步执行单条命令（快照/存档用，走会话通道） */
    private suspend fun execSync(command: String): CommandRunner.CommandResult? {
        val app = applicationContext as? ToolboxApp
        return when (NetLogState.channel.substringBefore("(")) {
            "Shizuku" -> try {
                com.wifi.toolbox.utils.ShizukuUtil.executeScriptSync(command)
            } catch (e: Exception) {
                CommandRunner.CommandResult("shizuku error: ${e.message}", -1)
            }
            "RootAIDL" -> app?.let {
                try {
                    AidlServiceHelper.executeCommandSync(it, command)
                } catch (e: Exception) {
                    CommandRunner.CommandResult("aidl error: ${e.message}", -1)
                }
            }
            else -> try {
                val p = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                p.waitFor()
                CommandRunner.CommandResult(out, p.exitValue())
            } catch (e: Exception) {
                CommandRunner.CommandResult("shell error: ${e.message}", -1)
            }
        }
    }

    // ==================== 时间线与通知 ====================

    /**
     * 统一时间线入口：双时钟（wall + elapsedRealtime，用户改系统时间
     * 也可对齐 logcat 行）、来源标记、级别，JSONL 逐行落盘 + UI 预览缓冲。
     * 落盘经单线程队列（主线程回调不碰磁盘，顺序有保证）。
     */
    private fun timeline(src: String, lvl: String, msg: String) {
        val dir = sessionDir ?: return
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime() - sessionStartElapsed
        val line = JSONObject().apply {
            put("t", SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(wall)))
            put("ts", wall)
            put("e", elapsed)
            put("src", src)
            put("lvl", lvl)
            put("msg", msg)
        }
        val json = line.toString()
        diskExecutor.execute {
            NetLogStore.appendTimeline(dir, json)
        }
        NetLogState.eventCount++
        NetLogState.addPreview(
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(wall)) +
                    " [$src] $msg"
        )
        // 通知数字每 5 个事件刷一次（避免高频事件拖垮通知服务）
        if (NetLogState.eventCount % 5 == 1) {
            mainHandler.post { updateNotification() }
        }
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun startAsForeground() {
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    private fun updateNotification() {
        if (sessionDir == null) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.netlog_notif_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.netlog_notif_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, com.wifi.toolbox.ui.MainActivity::class.java).apply {
            putExtra("target", "NetLog")
        }
        val contentIntent = PendingIntent.getActivity(
            this, CONTENT_PI_CODE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 「停止记录」：普通 getService（服务已在前台，无需再前台化）
        val stopIntent = PendingIntent.getService(
            this, NOTIF_REQUEST_CODE,
            Intent(this, NetworkLogService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.netlog_notif_title))
            .setContentText(
                getString(R.string.netlog_notif_text, NetLogState.eventCount)
            )
            .setContentIntent(contentIntent)
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.netlog_notif_stop),
                stopIntent
            )
            .build()
    }

    // ==================== 工具 ====================

    private fun isWifi(network: Network): Boolean = try {
        (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
            .getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) {
        false
    }

    /** 网络可读描述：id + 类型（如 100/WIFI） */
    private fun describe(network: Network): String =
        network.toString() + if (isWifi(network)) "/WIFI" else ""

    private fun extraInfo(network: Network): String = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(network)
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        when (validated) {
            true -> " (validated)"
            else -> ""
        }
    } catch (_: Exception) {
        ""
    }

    private fun cleanSsid(raw: String?): String =
        raw?.removeSurrounding("\"")?.ifBlank { "-" } ?: "-"

    companion object {
        const val CHANNEL_ID = "NetLogServiceChannel"
        const val NOTIF_ID = 4

        /** 通知 contentIntent 请求码（与 Pojie=0、Guard=1 区分，防 PendingIntent 同键顶替） */
        const val CONTENT_PI_CODE = 2
        const val NOTIF_REQUEST_CODE = 40

        const val ACTION_STOP = "com.wifi.toolbox.netlog.STOP"
        const val ACTION_SNAPSHOT = "com.wifi.toolbox.netlog.SNAPSHOT"
        const val ACTION_RELOAD = "com.wifi.toolbox.netlog.RELOAD"

        private const val LOGCAT_BIN = "/system/bin/logcat"

        /** logcat 网络标签集（冒号 V = 全级别） */
        const val LOG_TAGS =
            "WifiService:V WifiClientModeImpl:V ClientModeImpl:V " +
                    "ConnectivityService:V NetworkMonitor:V NetworkAgentInfo:V " +
                    "DhcpClient:V IpClient:V wpa_supplicant:V WifiHAL:V " +
                    "netd:V ResolverController:V"

        /** 快照命令组（只读命令，绝不改变网络状态） */
        private val SNAPSHOT_COMMANDS = listOf(
            "connectivity" to "dumpsys connectivity",
            "wifi" to "dumpsys wifi",
            "dnsresolver" to "dumpsys dnsresolver",
            "wlan0 addrs" to "ip -f inet addr show wlan0",
            "routes" to "ip route show table all",
            "wireless stats" to "cat /proc/net/wireless"
        )

        private const val SNAPSHOT_CMD_TIMEOUT_MS = 8_000L
        private const val SNAPSHOT_DEBOUNCE_MS = 5_000L
        private const val BOOT_CONTEXT_LINES = 800
        private const val LOGCAT_MAX_RECONNECT_MS = 60_000L
        private const val PROBE_INTERVAL_MS = 15_000L
        private const val PROBE_HTTP_URL = "http://connect.rom.miui.com/generate_204"
        private const val PROBE_DNS_HOST = "www.baidu.com"

        private const val RSSI_JUMP_DB = 6
        private const val RSSI_MAX_INTERVAL_MS = 60_000L
        private const val RSSI_CRITICAL = -80

        /** supplicant 握手关键步（防刷屏白名单） */
        private val SUPPLICANT_KEY_STATES = setOf(
            android.net.wifi.SupplicantState.ASSOCIATING,
            android.net.wifi.SupplicantState.ASSOCIATED,
            android.net.wifi.SupplicantState.FOUR_WAY_HANDSHAKE,
            android.net.wifi.SupplicantState.GROUP_HANDSHAKE,
            android.net.wifi.SupplicantState.COMPLETED
        )

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NetworkLogService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, NetworkLogService::class.java).apply { action = ACTION_STOP }
            )
        }

        /** 手动抓一轮快照（UI「立即快照」按钮） */
        fun snapshotNow(context: Context) {
            context.startService(
                Intent(context, NetworkLogService::class.java).apply { action = ACTION_SNAPSHOT }
            )
        }
    }
}

/**
 * 网络日志全局状态（Compose 快照流，UI 直接观察；与 GuardState 同模式）。
 */
object NetLogState {
    var running by androidx.compose.runtime.mutableStateOf(false)
    var sessionName by androidx.compose.runtime.mutableStateOf("")
    var startedAt by androidx.compose.runtime.mutableLongStateOf(0L)
    var eventCount by androidx.compose.runtime.mutableIntStateOf(0)
    var logLines by androidx.compose.runtime.mutableIntStateOf(0)
    var logCapped by androidx.compose.runtime.mutableStateOf(false)
    var channel by androidx.compose.runtime.mutableStateOf("")

    /** 最近一次错误（启动失败等，UI 提示用） */
    var lastError by androidx.compose.runtime.mutableStateOf("")

    /** UI 实时预览缓冲（最近 50 条，滚动窗口） */
    private val preview =
        androidx.compose.runtime.mutableStateListOf<String>()

    fun addPreview(line: String) {
        if (preview.size >= 50) preview.removeAt(0)
        preview.add(line)
    }

    fun clearPreview() = preview.clear()

    fun previewList(): List<String> = preview
}

/**
 * Shizuku 流式执行的薄封装（隔离 Shizuku API 依赖，便于服务层统一调用）。
 */
private object ShizukuUtilCompat {
    fun executeStream(
        command: String,
        onLine: Consumer<String>,
        onFinish: Consumer<CommandRunner.CommandResult>
    ): Runnable = com.wifi.toolbox.utils.ShizukuUtil.executeCommand(command, onLine, onFinish)
}
