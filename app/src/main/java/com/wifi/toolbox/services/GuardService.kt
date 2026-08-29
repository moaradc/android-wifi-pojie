package com.wifi.toolbox.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.GuardSettings
import com.wifi.toolbox.utils.ApiUtil
import com.wifi.toolbox.utils.GuardStats
import com.wifi.toolbox.utils.NetProber
import com.wifi.toolbox.utils.ShizukuUtil
import com.wifi.toolbox.utils.WifiHealer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 网络守护前台服务：定时检测 WiFi 实际连通性，断网时自动重连并统计。
 *
 * 前后台常驻设计：
 * - 前台服务 + START_STICKY，系统不杀；被杀后自动重启
 * - 检测循环为纯协程，不依赖任何 UI 组件，熄屏/后台照常运行
 * - 全局单例状态（[GuardState]），UI 打开即读，服务重启状态不丢（统计持久化）
 *
 * 速度设计：
 * - 周期检测默认 30s，可预设 10s~5min 或自定义
 * - 网络切换事件即时触发检测（免等待）
 * - 检测期间各探测项并行，单轮耗时 ≈ 最慢单项超时
 *
 * 防误判设计（专业性核心）：
 * - 连续 N 次失败才判定断网（防抖，默认 2）
 * - WiFi 链路已断开时跳过（可能是用户主动断开）
 * - Captive Portal 场景跳过自愈（重连解决不了认证问题）
 * - 自愈失败后指数退避（防止路由器断电时空转轰炸）
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: SharedPreferences
    private lateinit var settings: GuardSettings
    private lateinit var stats: GuardStats
    private lateinit var healer: WifiHealer

    private var loopJob: Job? = null
    private val healMutex = Mutex()          // 自愈互斥（事件触发与定时触发并发保护）
    private var consecutiveFails = 0         // 连续失败计数（防抖）
    private var consecutiveHealFails = 0     // 连续自愈失败计数（指数退避）
    private var lastEventLog = ""            // 最近一次事件触发的 action（去重）

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(GuardSettings.PREFS_NAME, MODE_PRIVATE)
        reloadSettings()
        stats = (applicationContext as ToolboxApp).guardStats
        healer = WifiHealer(this, applicationContext as? ToolboxApp)
        registerEventReceiver()
        GuardState.running = true
        GuardState.stats = stats
    }

    private fun reloadSettings() {
        settings = GuardSettings.from(prefs)
        GuardState.settings = settings
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RUN_CHECK -> {
                // 手动"立即检测"
                scope.launch { runOneCheck(manual = true) }
            }
            ACTION_RELOAD -> reloadSettings()
        }

        startAsForeground()

        if (loopJob == null || loopJob?.isActive != true) {
            loopJob = scope.launch { guardLoop() }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        createChannel()
        val notification = buildNotification(getString(R.string.guard_notif_monitoring))
        if (settings.showPersistentNotification) {
            startForeground(NOTIF_ID, notification)
        } else {
            // 依然必须 startForeground（前台服务类型要求），但用最低优先级
            startForeground(NOTIF_ID, buildNotification(null, silent = true))
        }
    }

    // ==================== 主循环 ====================

    private suspend fun guardLoop() {
        log(getString(R.string.guard_log_started, settings.checkIntervalSec))
        // 启动先歇一小会儿，等系统网络就绪，避免开机瞬间的误判
        delay(3000)
        while (scope.isActive) {
            try {
                runOneCheck()
            } catch (e: Exception) {
                log("${getString(R.string.guard_log_check_error)}: ${e.message}")
            }
            // 每轮重新读设置，支持 UI 实时改间隔（不重启服务）
            reloadSettings()
            val interval = settings.checkIntervalSec * 1000L
            // 分片睡眠：设置改小后能尽快生效，同时熄屏下不额外唤醒
            var slept = 0L
            while (slept < interval && scope.isActive) {
                val chunk = minOf(5000L, interval - slept)
                delay(chunk)
                slept += chunk
            }
        }
    }

    /**
     * 单轮检测 + 必要时自愈。manual=true 时跳过防抖（手动检测立即给出结论）
     */
    private suspend fun runOneCheck(manual: Boolean = false) = healMutex.withLock {
        if (settings.onlyWhenWifiConnected) {
            // 关键：用 ConnectivityManager 的 allNetworks 判定 WiFi 连接（免定位权限），
            // 与 NetProber 绑网探测同源。原实现用 connectionInfo.ssid 判断，
            // 无 ACCESS_FINE_LOCATION 运行时授权时 SSID 恒为 <unknown ssid>，
            // 会导致 WiFi 明明已连接却被误判为"未连接"而永久跳过检测。
            val cm = applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val connected = NetProber.findWifiNetwork(cm) != null
            if (!connected) {
                // 防刷屏：仅手动检测或状态切换时打日志
                if (manual || GuardState.currentState != GuardState.STATE_LINK_DOWN) {
                    log(getString(R.string.guard_log_wifi_not_connected))
                }
                GuardState.lastCheckTime = System.currentTimeMillis()
                GuardState.currentState = GuardState.STATE_LINK_DOWN
                return@withLock
            }
        }

        val verdict = NetProber.probe(applicationContext, settings) { cmd ->
            healer.shellExec(cmd)
        }
        stats.recordCheck(verdict.online)

        val detail = verdict.results.joinToString(", ") { "${it.mode}=${if (it.ok) "OK" else it.detail}" }
        GuardState.lastCheckTime = System.currentTimeMillis()
        GuardState.lastVerdict = verdict

        if (verdict.online) {
            if (consecutiveFails > 0 || manual) {
                log(getString(R.string.guard_log_online, detail))
            }
            consecutiveFails = 0
            GuardState.currentState = GuardState.STATE_ONLINE
            return@withLock
        }

        consecutiveFails++
        log(
            getString(
                R.string.guard_log_offline_count,
                consecutiveFails, settings.failThreshold, detail
            )
        )

        // 防抖：未达阈值不动作
        if (!manual && consecutiveFails < settings.failThreshold) {
            GuardState.currentState = GuardState.STATE_SUSPECT
            return@withLock
        }

        // WiFi 链路已断开：可能是用户主动断开，不重连
        if (settings.skipWhenWifiDisconnected && !verdict.wifiConnected) {
            log(getString(R.string.guard_log_wifi_link_down))
            GuardState.currentState = GuardState.STATE_LINK_DOWN
            return@withLock
        }

        // Captive Portal：重连无意义
        if (settings.skipOnCaptivePortal && verdict.portal) {
            log(getString(R.string.guard_log_portal))
            GuardState.currentState = GuardState.STATE_PORTAL
            return@withLock
        }

        GuardState.currentState = GuardState.STATE_HEALING
        performHeal(verdict)
    }

    /**
     * 获取当前 WiFi 身份（SSID + networkId）。
     *
     * Android 9+ 应用层 WifiInfo 的 SSID 需要 ACCESS_FINE_LOCATION 运行时授权
     * 且定位服务开启，否则返回 <unknown ssid>；netId 在部分 ROM 上同样受限。
     * 特权通道（Shizuku/Root AIDL）不受此限制，作为兑底。
     */
    private suspend fun wifiIdentity(): Pair<String, Int> {
        var ssid = ""
        var netId = -1
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val raw = info?.ssid?.removeSurrounding("\"").orEmpty()
            if (raw.isNotEmpty() && raw != "<unknown ssid>") ssid = raw
            if (info != null) netId = info.networkId
        } catch (_: Exception) {
        }
        if (ssid.isEmpty() || netId == -1) {
            try {
                // Android 11+：cmd wifi status 输出形如 Current network: #0 "SSID"
                if (ssid.isEmpty()) {
                    val status = healer.shellExec("cmd wifi status").output
                    ssid = Regex("Current network:.*?\"([^\"]+)\"")
                        .find(status)?.groupValues?.get(1)
                        ?.removeSurrounding("\"")?.trim().orEmpty()
                }
                if (ssid.isEmpty() || netId == -1) {
                    // 通用兑底：dumpsys wifi 的 mWifiInfo 行（含 SSID 与 Net ID）
                    val dump = healer.shellExec("dumpsys wifi").output
                    if (ssid.isEmpty()) {
                        ssid = Regex("mWifiInfo SSID: ([^,\\r\\n]+)")
                            .find(dump)?.groupValues?.get(1)
                            ?.removeSurrounding("\"")?.trim().orEmpty()
                        if (ssid == "<unknown ssid>") ssid = ""
                    }
                    if (netId == -1) {
                        netId = Regex("mWifiInfo.*?Net ID: (\\d+)")
                            .find(dump)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                    }
                }
            } catch (_: Exception) {
            }
        }
        return ssid to netId
    }

    private suspend fun performHeal(verdict: com.wifi.toolbox.utils.ProbeVerdict) {
        // 指数退避：连续失败后成倍等待
        if (consecutiveHealFails > 0) {
            val backoff = settings.healCooldownBaseSec * 1000L *
                    (1L shl minOf(consecutiveHealFails, settings.maxBackoffPower).coerceAtMost(16))
            val capped = minOf(backoff, 15 * 60 * 1000L)
            log(getString(R.string.guard_log_backoff, capped / 1000))
            delay(capped)
        }

        val app = applicationContext as ToolboxApp
        // WiFi 身份获取：无定位权限时应用层 WifiInfo 拿不到真实 SSID，
        // 依次尝试 应用层 → cmd wifi status（特权）→ dumpsys wifi（特权）
        val (ssid, netId) = wifiIdentity()

        GuardState.lastHealSsid = ssid
        log(getString(R.string.guard_log_healing, ssid.ifEmpty { "?" }))

        val failedProbes = verdict.results.filter { !it.ok }.joinToString(",") { it.mode }
        val start = System.currentTimeMillis()

        val executed = healer.heal(
            settings = settings,
            ssid = ssid,
            netId = netId,
            verify = {
                NetProber.probe(applicationContext, settings) { cmd -> healer.shellExec(cmd) }.online
            },
            log = { log(it) }
        )

        // 最终验证（给最后一个动作一点生效时间）
        var recovered = false
        val deadline = System.currentTimeMillis() + settings.healVerifyTimeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(2000)
            if (NetProber.probe(applicationContext, settings) { cmd -> healer.shellExec(cmd) }.online) {
                recovered = true
                break
            }
        }

        val cost = System.currentTimeMillis() - start
        stats.recordHeal(executed, recovered, cost, ssid, failedProbes)

        if (recovered) {
            consecutiveHealFails = 0
            consecutiveFails = 0
            GuardState.currentState = GuardState.STATE_ONLINE
            log(getString(R.string.guard_log_heal_ok, cost / 1000))
            if (settings.notifyOnHeal) {
                notifyEvent(getString(R.string.guard_notif_healed_title, ssid), getString(R.string.guard_notif_healed_text, cost / 1000))
            }
        } else {
            consecutiveHealFails++
            GuardState.currentState = GuardState.STATE_HEAL_FAILED
            log(getString(R.string.guard_log_heal_fail, consecutiveHealFails))
            if (settings.notifyOnHealFail) {
                notifyEvent(getString(R.string.guard_notif_heal_fail_title), getString(R.string.guard_notif_heal_fail_text, ssid))
            }
        }
    }

    // ==================== 网络事件即时检测 ====================

    private fun registerEventReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                if (action == WifiManager.NETWORK_STATE_CHANGED_ACTION) {
                    val key = "net:${System.currentTimeMillis() / 8000}" // 8s 内去重
                    if (key != lastEventLog && settings.checkOnNetworkChange) {
                        lastEventLog = key
                        scope.launch {
                            delay(1500) // 等网络栈稳定再探测
                            try {
                                runOneCheck()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }
        }
        registerReceiver(receiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))
    }

    // ==================== 通知 ====================

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.guard_name),
                NotificationManager.IMPORTANCE_MIN
            )
            val eventChannel = NotificationChannel(
                EVENT_CHANNEL_ID,
                getString(R.string.guard_notif_event_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            manager?.createNotificationChannel(eventChannel)
        }
    }

    private fun buildNotification(text: String?, silent: Boolean = false): android.app.Notification {
        val intent = Intent(this, com.wifi.toolbox.ui.MainActivity::class.java).apply {
            putExtra("target", "Guard")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.guard_name))
            .setContentText(text ?: getString(R.string.guard_notif_monitoring))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(silent)
            .build()
    }

    private fun notifyEvent(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, EVENT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(EVENT_NOTIF_ID, notification)
    }

    private fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        GuardState.addLog("[$time] $msg")
    }

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        scope.cancel()
        GuardState.running = false
        GuardState.currentState = GuardState.STATE_IDLE
    }

    companion object {
        const val CHANNEL_ID = "GuardServiceChannel"
        const val EVENT_CHANNEL_ID = "GuardEventChannel"
        const val NOTIF_ID = 2
        const val EVENT_NOTIF_ID = 3
        const val ACTION_STOP = "com.wifi.toolbox.guard.STOP"
        const val ACTION_RUN_CHECK = "com.wifi.toolbox.guard.CHECK"
        const val ACTION_RELOAD = "com.wifi.toolbox.guard.RELOAD"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GuardService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, GuardService::class.java).apply { action = ACTION_STOP }
            )
        }
    }
}

/**
 * 守护服务全局状态（Compose 快照流，UI 直接观察，服务重启不丢 UI 数据）
 */
object GuardState {
    const val STATE_IDLE = "idle"
    const val STATE_ONLINE = "online"
    const val STATE_SUSPECT = "suspect"
    const val STATE_LINK_DOWN = "link_down"
    const val STATE_PORTAL = "portal"
    const val STATE_HEALING = "healing"
    const val STATE_HEAL_FAILED = "heal_failed"

    var running by androidx.compose.runtime.mutableStateOf(false)
    var currentState by androidx.compose.runtime.mutableStateOf(STATE_IDLE)
    var lastCheckTime by androidx.compose.runtime.mutableLongStateOf(0L)
    var lastHealSsid by androidx.compose.runtime.mutableStateOf("")
    var settings by androidx.compose.runtime.mutableStateOf(
        GuardSettings()
    )
    var stats: GuardStats? = null
    var lastVerdict: com.wifi.toolbox.utils.ProbeVerdict? = null

    private val logs = androidx.compose.runtime.mutableStateListOf<String>()

    fun addLog(msg: String) {
        if (logs.size >= 200) logs.removeAt(0)
        logs.add(msg)
    }

    fun logList(): List<String> = logs
}
