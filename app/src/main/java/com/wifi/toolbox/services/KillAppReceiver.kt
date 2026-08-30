package com.wifi.toolbox.services

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.utils.ActivityStack
import kotlin.system.exitProcess

/**
 * 通知「结束」按钮接收器：终止应用自身（含全部服务与 Activity）。
 *
 * 执行顺序很关键，不能直接杀进程：
 * 1. 先 stopService 各常驻服务 —— 抵消 START_STICKY 的自动重启标记，
 *    否则杀进程后系统会把守护/破解服务原样拉起，「结束」就变成了「重启」；
 * 2. 停 Root AIDL 服务（独立 root 进程，随停止指令退出）；
 * 3. 结束当前 Activity、取消守护通知（进程死后系统亦会回收，主动取消更稳）；
 * 4. 短暂延迟后杀掉自身进程 —— 留给 AMS 处理 stopService 的时间窗口。
 */
class KillAppReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext

        // 1. 停止常驻服务（取消 START_STICKY 复活标记）
        listOf(
            GuardService::class.java,
            PojieService::class.java,
            WifiLogcatService::class.java
        ).forEach { cls ->
            try {
                app.stopService(Intent(app, cls))
            } catch (_: Exception) {
            }
        }

        // 2. 停 root AIDL 服务（独立进程）
        try {
            (app as? ToolboxApp)?.aidl?.stopAIDLService()
        } catch (_: Exception) {
        }

        // 3. 结束 Activity + 取消通知（含破解前台通知 id=1）
        try {
            ActivityStack.get()?.finish()
        } catch (_: Exception) {
        }
        try {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(1)
            nm.cancel(GuardService.NOTIF_ID)
            nm.cancel(GuardService.EVENT_NOTIF_ID)
        } catch (_: Exception) {
        }

        // 4. 延迟杀进程：等 AMS 落地 stopService（约数百毫秒），
        //    期间进程已无前台组件，即便被 LMK 提前回收结果也一致
        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
            exitProcess(0)
        }, 400)
    }
}
