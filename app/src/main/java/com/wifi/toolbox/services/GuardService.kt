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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
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
    private var consecutiveHealFails = 0     // 连续自愈失败计数（指数退避+熔断）
    private var lastEventLog = ""            // 最近一次事件触发的 action（去重）
    private var breakerNotified = false      // 熔断提示已发（防重复日志/通知）
    private var wakeLock: PowerManager.WakeLock? = null  // 后台保活：息屏 CPU 唤醒锁

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(GuardSettings.PREFS_NAME, MODE_PRIVATE)
        reloadSettings()
        stats = (applicationContext as ToolboxApp).guardStats
        healer = WifiHealer(this, applicationContext as? ToolboxApp)
        registerEventReceiver()
        // reloadSettings() 内已同步唤醒锁
        GuardState.running = true
        GuardState.stats = stats
    }

    private fun reloadSettings() {
        settings = GuardSettings.from(prefs)
        GuardState.settings = settings
        syncWakeLock()   // 设置热加载时唤醒锁即时开/关
    }

    /**
     * 后台保活（系统级）：按设置持有/释放 PARTIAL_WAKE_LOCK。
     * 息屏后 CPU 深睡会暂停协程 delay 定时器（表现为“后台不检测”），
     * 持有部分唤醒锁保证检测/自愈循环照常运行。非引用计数，重复 acquire 前先释放。
     */
    private fun syncWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (settings.keepAliveWakeLock) {
            if (wakeLock?.isHeld != true) {
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "wifi.toolbox:guard"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } else {
            try {
                wakeLock?.takeIf { it.isHeld }?.release()
            } catch (_: Exception) {
            }
            wakeLock = null
        }
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
            ACTION_RELOAD -> {
                reloadSettings()
                // 通知样式随设置即时重建：如"常驻守护通知"开关切换后，
                // 前台通知在 标准显示/静默最低优先级 之间立即切换
                startAsForeground()
            }
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
                log("${getString(R.string.guard_log_check_error)}: ${e.message}", GuardLog.LEVEL_ERROR)
            }
            // 每轮重新读设置，支持 UI 实时改间隔（不重启服务）
            reloadSettings()
            // 自动清理实时日志（保留天数 > 0 时按时间过期；开销极小）
            if (settings.autoCleanDays > 0) {
                GuardState.pruneLogs(settings.autoCleanDays)
            }
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
        if (manual) {
            // 手动"立即检测" = 半开探测：复位熔断计数（用户明确希望立即再试一次）
            if (consecutiveHealFails > 0) {
                consecutiveHealFails = 0
                breakerNotified = false
            }
        }
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
                    log(getString(R.string.guard_log_wifi_not_connected), GuardLog.LEVEL_WARN)
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
            // verboseLog 开启时每轮在线结果都记录（用户可确认守护在跑）；
            // 关闭时仅记录"从故障恢复"与手动检测，保持日志清爽
            if (settings.verboseLogEnabled(GuardLog.LEVEL_INFO) || consecutiveFails > 0 || manual) {
                log(getString(R.string.guard_log_online, detail), GuardLog.LEVEL_INFO)
            }
            // 网络自行恢复（如路由器来电）：熔断计数与防抖计数一并复位
            // （旧版漏掉此处导致 consecutiveHealFails 永不复位、退避无限叠加）
            if (consecutiveHealFails > 0) {
                consecutiveHealFails = 0
                breakerNotified = false
                log(getString(R.string.guard_log_breaker_reset), GuardLog.LEVEL_HEAL)
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
            ),
            GuardLog.LEVEL_ERROR
        )

        // 防抖：未达阈值不动作
        if (!manual && consecutiveFails < settings.failThreshold) {
            GuardState.currentState = GuardState.STATE_SUSPECT
            return@withLock
        }

        // WiFi 链路已断开：可能是用户主动断开，不重连
        if (settings.skipWhenWifiDisconnected && !verdict.wifiConnected) {
            log(getString(R.string.guard_log_wifi_link_down), GuardLog.LEVEL_WARN)
            GuardState.currentState = GuardState.STATE_LINK_DOWN
            return@withLock
        }

        // Captive Portal：重连无意义
        if (settings.skipOnCaptivePortal && verdict.portal) {
            log(getString(R.string.guard_log_portal), GuardLog.LEVEL_WARN)
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
        // ---- 熔断检查（Circuit Breaker，网络调研 AWS/ByteByteGo 通行做法）----
        // 连续自愈失败达到次数上限后停止自愈动作（检测继续），
        // 等待网络恢复在线或手动"立即检测"时复位（半开探测思想）
        val maxAttempts = settings.healMaxAttempts
        if (maxAttempts > 0 && consecutiveHealFails >= maxAttempts) {
            if (!breakerNotified) {
                breakerNotified = true
                log(
                    getString(R.string.guard_log_breaker_open, maxAttempts),
                    GuardLog.LEVEL_ERROR
                )
                if (settings.notifyOnHealFail) {
                    notifyEvent(
                        getString(R.string.guard_notif_breaker_title),
                        getString(R.string.guard_notif_breaker_text, maxAttempts)
                    )
                }
            }
            GuardState.currentState = GuardState.STATE_HEAL_FAILED
            return
        }

        // 指数退避：连续失败后成倍等待
        if (consecutiveHealFails > 0) {
            val backoff = settings.healCooldownBaseSec * 1000L *
                    (1L shl minOf(consecutiveHealFails, settings.maxBackoffPower).coerceAtMost(16))
            val capped = minOf(backoff, 15 * 60 * 1000L)
            log(getString(R.string.guard_log_backoff, capped / 1000), GuardLog.LEVEL_HEAL)
            delay(capped)
        }

        val app = applicationContext as ToolboxApp
        // WiFi 身份获取：无定位权限时应用层 WifiInfo 拿不到真实 SSID，
        // 依次尝试 应用层 → cmd wifi status（特权）→ dumpsys wifi（特权）
        val (ssid, netId) = wifiIdentity()

        GuardState.lastHealSsid = ssid
        log(getString(R.string.guard_log_healing, ssid.ifEmpty { "?" }), GuardLog.LEVEL_HEAL)

        val failedProbes = verdict.results.filter { !it.ok }.joinToString(",") { it.mode }
        val start = System.currentTimeMillis()

        val executed = healer.heal(
            settings = settings,
            ssid = ssid,
            netId = netId,
            verify = {
                NetProber.probe(applicationContext, settings) { cmd -> healer.shellExec(cmd) }.online
            },
            log = { log(it, GuardLog.LEVEL_HEAL) },
            actionStats = stats.actionStats.toMap()
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
            breakerNotified = false
            consecutiveFails = 0
            GuardState.currentState = GuardState.STATE_ONLINE
            log(getString(R.string.guard_log_heal_ok, cost / 1000), GuardLog.LEVEL_HEAL)
            if (settings.notifyOnHeal) {
                notifyEvent(getString(R.string.guard_notif_healed_title, ssid), getString(R.string.guard_notif_healed_text, cost / 1000))
            }
        } else {
            consecutiveHealFails++
            GuardState.currentState = GuardState.STATE_HEAL_FAILED
            log(getString(R.string.guard_log_heal_fail, consecutiveHealFails), GuardLog.LEVEL_ERROR)
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
        createChannelStatic(this)
    }

    private fun buildNotification(text: String?, silent: Boolean = false): android.app.Notification {
        val intent = Intent(this, com.wifi.toolbox.ui.MainActivity::class.java).apply {
            putExtra("target", "Guard")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )
        // 「关闭守护」：普通 getService 即可（服务已在运行，无需前台化义务；
        // 若用 getForegroundService 会因 ACTION_STOP 提前 return 不调 startForeground 而超时崩溃）
        val stopIntent = PendingIntent.getService(
            this, 10,
            Intent(this, GuardService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        // 「结束」：广播接收器直接杀进程（通知操作按钮 PendingIntent 在
        // 临时白名单内，允许后台启动组件；杀进程本身无需前台服务）
        val killIntent = PendingIntent.getBroadcast(
            this, 11,
            Intent(this, KillAppReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.guard_name))
            .setContentText(text ?: getString(R.string.guard_notif_monitoring))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(silent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.guard_notif_action_stop),
                stopIntent
            )
            .addAction(
                android.R.drawable.ic_lock_power_off,
                getString(R.string.guard_notif_action_exit),
                killIntent
            )
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

    private fun log(msg: String, level: Int = GuardLog.LEVEL_INFO) {
        // 按设置的"记录日志类型"位掩码过滤（默认全部记录）
        if (!settings.logLevelsEnabled(level)) return
        GuardState.addLog(msg, level)
    }

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        scope.cancel()
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        GuardState.running = false
        GuardState.currentState = GuardState.STATE_IDLE
        // "常驻守护通知"开启：服务停止后仍保留"未运行"常驻通知（开关关闭则由系统
        // 随前台服务移除通知，无需处理）。延迟发出：避开系统移除前台通知的时序，
        // 确保新通知不被连带移除
        if (settings.showPersistentNotification) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, buildIdleNotification(this))
                } catch (_: Exception) {
                }
            }, 600)
        }
    }

    companion object {
        const val CHANNEL_ID = "GuardServiceChannel"
        const val EVENT_CHANNEL_ID = "GuardEventChannel"
        const val NOTIF_ID = 2
        const val EVENT_NOTIF_ID = 3
        const val ACTION_STOP = "com.wifi.toolbox.guard.STOP"
        const val ACTION_RUN_CHECK = "com.wifi.toolbox.guard.CHECK"
        const val ACTION_RELOAD = "com.wifi.toolbox.guard.RELOAD"

        private fun createChannelStatic(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.guard_name),
                    NotificationManager.IMPORTANCE_MIN
                )
                val eventChannel = NotificationChannel(
                    EVENT_CHANNEL_ID,
                    context.getString(R.string.guard_notif_event_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                val manager =
                    context.getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
                manager?.createNotificationChannel(eventChannel)
            }
        }

        /**
         * 服务未运行时的常驻通知（仅"常驻守护通知"开关开启时显示）：
         * 点击进入守护页，可快速重新开启守护。
         */
        fun buildIdleNotification(context: Context): android.app.Notification {
            createChannelStatic(context)
            val intent = Intent(context, com.wifi.toolbox.ui.MainActivity::class.java).apply {
                putExtra("target", "Guard")
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE
            )
            // 「开启守护」：服务未运行，须用 getForegroundService（守护服务
            // 启动后 5s 内会 startForeground，满足前台服务义务；通知操作按钮
            // 属用户交互，豁免后台启动前台服务限制）
            val startIntent = PendingIntent.getForegroundService(
                context, 20,
                Intent(context, GuardService::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            val killIntent = PendingIntent.getBroadcast(
                context, 21,
                Intent(context, KillAppReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.guard_name))
                .setContentText(context.getString(R.string.guard_notif_stopped))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .addAction(
                    android.R.drawable.ic_media_play,
                    context.getString(R.string.guard_notif_action_start),
                    startIntent
                )
                .addAction(
                    android.R.drawable.ic_lock_power_off,
                    context.getString(R.string.guard_notif_action_exit),
                    killIntent
                )
                .build()
        }

        /**
         * 服务未运行时同步常驻通知状态：
         * 开关开启 → 显示"未运行"常驻通知（无论守护开关是否开）
         * 开关关闭 → 移除通知。
         * 服务运行中不介入（前台通知由服务自身管理）。
         */
        fun syncIdleNotification(context: Context) {
            if (GuardState.running) return
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val prefs = context.getSharedPreferences(
                GuardSettings.PREFS_NAME, Context.MODE_PRIVATE
            )
            val enabled = try {
                GuardSettings.from(prefs).showPersistentNotification
            } catch (_: Exception) {
                true
            }
            if (enabled) {
                try {
                    nm.notify(NOTIF_ID, buildIdleNotification(context))
                } catch (_: Exception) {
                }
            } else {
                nm.cancel(NOTIF_ID)
            }
        }

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
 * 结构化日志条目：时间戳 + 级别 + 正文。
 * 级别用于 UI 筛选（全部/仅异常/仅自愈）与着色。
 */
data class GuardLogEntry(
    val time: Long,
    val level: Int,
    val msg: String
)

/** 日志级别 */
object GuardLog {
    const val LEVEL_INFO = 0     // 常规信息（含在线检测结果）
    const val LEVEL_WARN = 1     // 跳过/豁免类
    const val LEVEL_ERROR = 2    // 断网判定/自愈失败
    const val LEVEL_HEAL = 3     // 自愈过程

    /** 用于 UI 展示的时间格式（无年份，日志为滚动缓冲） */
    fun formatTime(time: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(time))
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
    /** 最近一次特权 shell（ICMP 探测/诊断命令）实际使用的通道 */
    var lastShellChannel by androidx.compose.runtime.mutableStateOf("")
    /** 最近一次自愈动作实际使用的通道 */
    var lastHealChannel by androidx.compose.runtime.mutableStateOf("")
    var settings by androidx.compose.runtime.mutableStateOf(
        GuardSettings()
    )
    var stats: GuardStats? = null
    var lastVerdict: com.wifi.toolbox.utils.ProbeVerdict? = null

    private val logs = androidx.compose.runtime.mutableStateListOf<GuardLogEntry>()

    fun addLog(msg: String, level: Int = GuardLog.LEVEL_INFO) {
        if (logs.size >= 200) logs.removeAt(0)
        logs.add(GuardLogEntry(System.currentTimeMillis(), level, msg))
    }

    fun clearLogs() = logs.clear()

    fun logList(): List<GuardLogEntry> = logs

    /**
     * 自动清理实时日志：移除超过保留天数的日志条目。
     * keepDays <= 0 时不动作；条目本身有 200 条上限，此处仅按时间过期。
     * 触发点：检测轮次与状态页打开（服务未运行时 UI 也能清理）。
     * @return 删除条数
     */
    fun pruneLogs(keepDays: Int): Int {
        if (keepDays <= 0 || logs.isEmpty()) return 0
        val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
        val before = logs.size
        logs.removeAll { it.time < cutoff }
        return before - logs.size
    }
}
