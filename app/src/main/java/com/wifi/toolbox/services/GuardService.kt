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
import android.app.AlarmManager
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
import com.wifi.toolbox.utils.GuardLogStore
import com.wifi.toolbox.utils.GuardStats
import com.wifi.toolbox.utils.NetProber
import com.wifi.toolbox.utils.ShizukuUtil
import com.wifi.toolbox.utils.WifiHealer
import com.wifi.toolbox.utils.WifiIdentity
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
    private var backoffUntilMs = 0L          // 指数退避截止时间（时间戳门槛，不再持锁 delay）
    private var backoffSkipNotified = false  // 退避跳过提示已发（每个退避窗口仅记一次日志）
    private var lastEventLog = ""            // 最近一次事件触发的 action（去重）
    private var breakerNotified = false      // 熔断提示已发（防重复日志/通知）
    private var lastCheckDoneAt = 0L         // 上次检测完成时刻（最小检测间隔防重）
    private var wakeLock: PowerManager.WakeLock? = null  // 后台保活：息屏 CPU 唤醒锁
    private var autoSaveDay = ""                      // 自动保存：当天日期戳（跨天触发旧文件清理）

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
        // START_STICKY 重启（进程被系统/ROM 回收后拉起）携带 null intent：
        // 如实记日志，用户可据此判断后台存活状况（配合心跳闹钟自动恢复）
        if (intent == null) {
            log(getString(R.string.guard_log_restarted), GuardLog.LEVEL_INFO)
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RUN_CHECK -> {
                // 手动"立即检测"
                scope.launch { runOneCheck(manual = true) }
            }
            ACTION_HEARTBEAT -> {
                // 心跳兜底：Doze 忽略非白名单应用的唤醒锁会使协程 delay 定时器冻结，
                // 主循环失速超过 1.5 倍间隔时补一轮检测（闹钟触发自带临时白名单
                // 与短暂唤醒窗口，探测可在窗口内完成）；进程被杀场景 onStartCommand
                // 本身已重建前台服务与主循环
                val intervalMs = settings.checkIntervalSec * 1000L
                val last = GuardState.lastCheckTime
                if (last <= 0L || System.currentTimeMillis() - last > intervalMs * 3 / 2) {
                    scope.launch {
                        try {
                            runOneCheck()
                        } catch (_: Exception) {
                        }
                    }
                }
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

        // 心跳闹钟自续期：开关开启时所有存活路径（含 START_STICKY 重启/热加载）
        // 统一重排，同一 PendingIntent 自动替换旧闹钟；开关关闭则主动取消
        // 残留闹钟（热加载关开关即停，不留“幽灵闹钟”）；用户停止走 onDestroy 取消
        if (settings.keepAliveHeartbeat) scheduleHeartbeat() else cancelHeartbeat()
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

    // ==================== 心跳闹钟看门狗 ====================

    /**
     * 免特权后台兜底（默认配置即生效，无需 Shizuku/Root 一键保活）：
     *
     * 根因一（深度 Doze）：Android 对未进 Doze 白名单的应用忽略 PARTIAL_WAKE_LOCK，
     * 息屏静止约 30 分钟后 CPU 深睡，协程 delay 定时器冻结——「后台不检测」。
     * setAndAllowWhileIdle 闹钟在 Doze 中照常触发（每应用约 9 分钟限流一次，
     * 系统静默推迟不报错），触发时短暂唤醒 CPU 并给予临时白名单窗口。
     * 根因二（ROM 杀进程）：MIUI/HyperOS 等对未加白应用的进程查杀后，
     * START_STICKY 若被拦截，PendingIntent.getForegroundService 闹钟仍可拉起
     * 服务（targetSdk=28 不受 Android 12+ 后台启动前台服务限制）；
     * force-stop 语义会连闹钟一并取消，属系统级终止，仅能靠电池优化豁免/保活命令。
     *
     * 下限 60s 兼顾电池（正常态主循环自己跑，心跳仅作失速看门狗，
     * 触发时检测新鲜则只重排闹钟不检测）；非精确闹钟允许系统合并唤醒。
     * 开关与间隔可在设置页「后台保活」分组调整（默认关/自动档；
     * 遇到后台不检测时可手动开启）。
     */
    private fun scheduleHeartbeat() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 0 = 自动：跟随检测间隔（与历史默认行为一致）；>0 = 用户自定义固定值；
        // 均受 60 秒硬下限约束（更短在深度 Doze 中无意义且纯耗电）
        val baseSec = if (settings.heartbeatIntervalSec > 0) settings.heartbeatIntervalSec
                      else settings.checkIntervalSec
        val intervalSec = maxOf(baseSec, HEARTBEAT_MIN_SEC)
        val at = System.currentTimeMillis() + intervalSec * 1000L
        val pi = heartbeatPendingIntent()
        try {
            am.cancel(pi)
        } catch (_: Exception) {
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) {
            // 个别 ROM 禁用 while-idle：回退普通闹钟（Doze 中会被推迟到维护窗口，
            // 聊胜于无）
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: Exception) {
            }
        }
    }

    private fun heartbeatPendingIntent(): PendingIntent =
        PendingIntent.getForegroundService(
            this, HEARTBEAT_PI_CODE,
            Intent(this, GuardService::class.java).apply { action = ACTION_HEARTBEAT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun cancelHeartbeat() {
        try {
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .cancel(heartbeatPendingIntent())
        } catch (_: Exception) {
        }
    }

    /** 系统是否处于深度 Doze（此刻网络防火墙对非白名单应用生效） */
    private fun isDeviceIdle(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isDeviceIdleMode
    } catch (_: Exception) {
        false
    }

    // ==================== 主循环 ====================

    private suspend fun guardLoop() {
        log(getString(R.string.guard_log_started, settings.checkIntervalSec))
        // 开启守护立即执行首轮检测（原固定 delay(3s) 造成“开启要等一会才真正开始”；
        // WiFi 未就绪由 onlyWhenWifiConnected 跳过分支与防抖阈值兑底，不会误自愈）
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
            // 自动保存日志：将本轮新增日志追加到当天滚动文件（设置关闭时无动作）
            autoSaveFlush()
            // 疑似断网（防抖中）用独立快间隔：加速防抖确认与恢复发现；
            // 0/其他状态（在线/链路断/Portal等）用例行检测间隔
            val interval = (
                    if (GuardState.currentState == GuardState.STATE_SUSPECT &&
                        settings.suspectIntervalSec > 0
                    ) settings.suspectIntervalSec else settings.checkIntervalSec
                    ) * 1000L
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
     * 单轮检测 + 必要时自愈（带最小间隔防重）。
     *
     * 防重背景：NETWORK_STATE_CHANGED 是粘性广播，registerReceiver 注册当刻
     * 即回调一次，与 Alpha-16 移除首轮固定延迟后的"开启即检测"几乎同时执行，
     * 造成每次开启守护出现两条内容相同的"在线 ✓"日志（相差约 2 秒）；
     * 网络切换也常连发多条广播。距上次检测完成不足 [MIN_CHECK_GAP_MS] 的
     * 非手动检测直接跳过（手动"立即检测"不受限，用户意图优先）。
     */
    private suspend fun runOneCheck(manual: Boolean = false) = healMutex.withLock {
        val now = System.currentTimeMillis()
        if (!manual && lastCheckDoneAt > 0 && now - lastCheckDoneAt < MIN_CHECK_GAP_MS) {
            return@withLock
        }
        try {
            doCheck(manual)
        } finally {
            lastCheckDoneAt = System.currentTimeMillis()
        }
    }

    /** 检测主体：manual=true 时跳过防抖（手动检测立即给出结论） */
    private suspend fun doCheck(manual: Boolean) {
        if (manual) {
            // 手动"立即检测" = 半开探测：复位熔断计数与退避窗口
            // （用户明确希望立即再试一次，不应被退避门槛拦住）
            if (consecutiveHealFails > 0) {
                consecutiveHealFails = 0
                breakerNotified = false
                backoffUntilMs = 0L
                backoffSkipNotified = false
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
                return
            }
        }

        val verdict = NetProber.probe(applicationContext, settings) { cmd ->
            healer.shellExec(cmd)
        }

        // 深度 Doze 期间系统防火墙切断非白名单应用网络，探测失败不能归因于
        // WiFi 故障：计入失败会误触自愈（重连后验证必失败，污染统计并空转）。
        // 如实标注本轮不判定，退出 Doze 后下一轮正常检测（临时白名单窗口内
        // 探测成功则正常走在线分支，不受此影响）
        if (!verdict.online && !manual && isDeviceIdle()) {
            if (GuardState.currentState != GuardState.STATE_SUSPECT) {
                log(getString(R.string.guard_log_doze_skip), GuardLog.LEVEL_WARN)
            }
            GuardState.lastCheckTime = System.currentTimeMillis()
            GuardState.currentState = GuardState.STATE_SUSPECT
            return
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
            // 网络自行恢复（如路由器来电）：熔断计数、防抖计数与退避窗口一并复位
            // （旧版漏掉此处导致 consecutiveHealFails 永不复位、退避无限叠加）
            if (consecutiveHealFails > 0) {
                consecutiveHealFails = 0
                breakerNotified = false
                backoffUntilMs = 0L
                backoffSkipNotified = false
                log(getString(R.string.guard_log_breaker_reset), GuardLog.LEVEL_HEAL)
            }
            consecutiveFails = 0
            GuardState.currentState = GuardState.STATE_ONLINE
            return
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
            return
        }

        // WiFi 链路已断开：可能是用户主动断开，不重连
        if (settings.skipWhenWifiDisconnected && !verdict.wifiConnected) {
            log(getString(R.string.guard_log_wifi_link_down), GuardLog.LEVEL_WARN)
            GuardState.currentState = GuardState.STATE_LINK_DOWN
            return
        }

        // Captive Portal：重连无意义
        if (settings.skipOnCaptivePortal && verdict.portal) {
            log(getString(R.string.guard_log_portal), GuardLog.LEVEL_WARN)
            GuardState.currentState = GuardState.STATE_PORTAL
            return
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
    private suspend fun wifiIdentity(): Pair<String, Int> =
        WifiIdentity.resolve(applicationContext, applicationContext as? ToolboxApp)

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

        // 指数退避：连续失败后成倍等待（改为时间戳门槛，不占用 healMutex——
        // 原实现在锁内 delay 最长 15 分钟，期间手动"立即检测"会被互斥锁卡住）
        val now = System.currentTimeMillis()
        if (backoffUntilMs > now) {
            if (!backoffSkipNotified) {
                backoffSkipNotified = true
                log(
                    getString(R.string.guard_log_backoff, (backoffUntilMs - now) / 1000),
                    GuardLog.LEVEL_HEAL
                )
            }
            GuardState.currentState = GuardState.STATE_HEAL_FAILED
            return
        }
        backoffSkipNotified = false

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
            backoffUntilMs = 0L
            backoffSkipNotified = false
            consecutiveFails = 0
            GuardState.currentState = GuardState.STATE_ONLINE
            log(getString(R.string.guard_log_heal_ok, cost / 1000), GuardLog.LEVEL_HEAL)
            if (settings.notifyOnHeal) {
                notifyEvent(getString(R.string.guard_notif_healed_title, ssid), getString(R.string.guard_notif_healed_text, cost / 1000))
            }
        } else {
            consecutiveHealFails++
            // 登记下一个退避窗口（时间戳门槛，performHeal 入口按此跳过等待）。
            // 固定等待：用户需求移除指数翻倍——设置多少秒就等多少秒，
            // 行为可预期（不再 30→60→120→…→15分钟封顶翻倍）；
            // 熔断次数上限（healMaxAttempts）仍负责防路由器断电空转
            val capped = settings.healCooldownBaseSec * 1000L
            backoffUntilMs = System.currentTimeMillis() + capped
            backoffSkipNotified = false
            GuardState.currentState = GuardState.STATE_HEAL_FAILED
            log(getString(R.string.guard_log_heal_fail, consecutiveHealFails), GuardLog.LEVEL_ERROR)
            log(getString(R.string.guard_log_backoff, capped / 1000), GuardLog.LEVEL_HEAL)
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

    // ==================== 自动保存日志 ====================

    /**
     * 将实时日志缓冲中未保存的条目追加到自动保存文件（guard-auto-yyyyMMdd.log，
     * 每天一个）。游标 GuardState.autoSaveCursor 记录已落盘条数：
     * - 新增日志仅在游标之后，追加后游标前移；
     * - 中途开启开关会自动补写缓冲内全部历史（最多 200 条）；
     * - 追加失败（如目录失效且私有目录写入失败）时游标不动，下轮重试。
     * 触发点：每轮检测后 + onDestroy 最终落盘。跨天时先清理旧自动文件。
     */
    private fun autoSaveFlush() {
        if (!settings.autoSaveLog) return
        val logs = GuardState.logList()
        val cursor = GuardState.autoSaveCursor
        if (logs.size <= cursor) return
        val day = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        if (day != autoSaveDay) {
            autoSaveDay = day
            pruneAutoFiles()
        }
        val sb = StringBuilder()
        for (i in cursor until logs.size) {
            sb.append('[').append(GuardLog.formatTime(logs[i].time)).append("] ")
                .append(logs[i].msg).append('\n')
        }
        if (GuardLogStore.append(this, settings.logDirUri, "guard-auto-$day.log", sb.toString())) {
            GuardState.autoSaveCursor = logs.size
        }
    }

    /** 自动保存文件只保留最近 [AUTO_SAVE_KEEP] 个（跨天时触发，两位置一并统计） */
    private fun pruneAutoFiles() {
        try {
            val files = GuardLogStore.list(this, settings.logDirUri)
                .filter { it.name.startsWith("guard-auto-") }
            if (files.size > AUTO_SAVE_KEEP) {
                files.drop(AUTO_SAVE_KEEP).forEach { GuardLogStore.delete(this, it) }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        scope.cancel()
        // 停止守护：取消心跳闹钟（防在途闹钟把已停止的服务拉起复活）
        cancelHeartbeat()
        // 最终落盘：开启自动保存时把残留未保存日志写入文件（服务被停止的场景）
        try {
            autoSaveFlush()
        } catch (_: Exception) {
        }
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
        const val ACTION_HEARTBEAT = "com.wifi.toolbox.guard.HEARTBEAT"

        /** 心跳闹钟 PendingIntent 请求码（与通知按钮 10/11/20/21 不冲突） */
        const val HEARTBEAT_PI_CODE = 30

        /** 心跳闹钟下限间隔（秒）：正常态主循环自跑，心跳仅作看门狗 */
        const val HEARTBEAT_MIN_SEC = 60

        /** 自动保存日志滚动文件保留个数（guard-auto-*.log，跨天时清理） */
        const val AUTO_SAVE_KEEP = 30

        /** 非手动检测的最小间隔（毫秒）：防粘性广播/广播连发与首轮检测叠加重复 */
        const val MIN_CHECK_GAP_MS = 5_000L

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

    /**
     * 自动保存游标：实时日志缓冲中前 N 条已写入自动保存文件
     * （由 GuardService 维护；缓冲头部淘汰/清理/清空时同步前移或归零，
     *  保证追加写入不重复、不遗漏）
     */
    var autoSaveCursor by androidx.compose.runtime.mutableIntStateOf(0)

    fun addLog(msg: String, level: Int = GuardLog.LEVEL_INFO) {
        if (logs.size >= 200) {
            logs.removeAt(0)
            // 被淘汰的是已保存条目则游标前移；未保存条目被丢弃
            // （每轮检测新增远小于 200，正常不会发生）
            if (autoSaveCursor > 0) autoSaveCursor--
        }
        logs.add(GuardLogEntry(System.currentTimeMillis(), level, msg))
    }

    fun clearLogs() {
        logs.clear()
        autoSaveCursor = 0
    }

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
        val removed = before - logs.size
        // 已删除条目游标同步前移，避免自动保存重复追加幸存条目
        if (removed > 0) autoSaveCursor = (autoSaveCursor - removed).coerceAtLeast(0)
        return removed
    }
}
