package com.wifi.toolbox.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台保活三级方案（网络调研验证的通行做法：
 * tweaselORG/appstraction、kiosk-satellite 等开源项目同样使用
 * deviceidle whitelist + appops 组合）：
 *
 * 1. 系统级（应用自身能力，免特权）：
 *    - PARTIAL_WAKE_LOCK：息屏后 CPU 休眠会暂停协程定时器（delay 漂移），
 *      持有部分唤醒锁保证检测循环照常执行（由 GuardService 按 设置 开关管理）
 *    - 电池优化白名单：系统弹窗引导用户豁免（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）
 *
 * 2. Shizuku（uid 2000 shell）：
 *    - dumpsys deviceidle whitelist +<pkg>   Doze 白名单（写入 deviceidle.xml，重启持久）
 *    - cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow   后台运行权限（Android 9+，
 *      小米澎湃OS/MIUI 等国产 ROM 后台查杀的关键开关）
 *    - cmd appops set <pkg> RUN_IN_BACKGROUND allow       后台运行权限（Android 7+）
 *    - cmd appops set <pkg> START_FOREGROUND_SERVICE allow  前台服务启动权限（Android 9+）
 *
 * 3. Root（独立通道，不依赖应用内 RootAIDL 服务）：
 *    - 与 Shizuku 同一套命令，但直接 su -c 在同一 root shell 内一次跑完，
 *      有 Magisk/KernelSU 授权即可，无需先拉起 Root 服务；
 *      调研未发现 root 专属的额外保活命令（MIUI 自启动等为专有设置项，无公开命令）。
 *
 * 执行后回读校验并逐项报告结果；shell 与 root 均有权限执行上述命令。
 */
object KeepAliveHelper {

    /** 保活命令执行结果（逐项，用于 UI 反馈与日志） */
    data class KeepAliveResult(
        val doze: Boolean,      // Doze 白名单已包含本应用
        val runAnyBg: Boolean,  // RUN_ANY_IN_BACKGROUND 已 allow
        val runBg: Boolean,     // RUN_IN_BACKGROUND 已 allow
        val fgs: Boolean,       // START_FOREGROUND_SERVICE 已 allow
        val raw: String         // 原始输出摘要（排障用）
    ) {
        val all: Boolean get() = doze && runAnyBg && runBg && fgs
    }

    private fun cmds(pkg: String) = listOf(
        "dumpsys deviceidle whitelist +$pkg",
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        "cmd appops set $pkg RUN_IN_BACKGROUND allow",
        "cmd appops set $pkg START_FOREGROUND_SERVICE allow"
    )

    /** Shizuku 通道执行（uid 2000 shell 权限足够） */
    suspend fun applyViaShizuku(pkg: String): KeepAliveResult {
        val sb = StringBuilder()
        for (cmd in cmds(pkg)) {
            try {
                val r = ShizukuUtil.executeCommandSync(cmd)
                sb.append(cmd).append(" -> exit=").append(r.exitCode).append('\n')
            } catch (e: Exception) {
                sb.append(cmd).append(" -> ").append(e.javaClass.simpleName).append('\n')
            }
        }
        return try {
            val check = ShizukuUtil.executeCommandSync(checkCmd(pkg))
            sb.append(check.output.take(500))
            parseCheck(check.output, pkg).copy(raw = sb.toString())
        } catch (e: Exception) {
            sb.append("verify: ").append(e.javaClass.simpleName)
            KeepAliveResult(false, false, false, false, sb.toString())
        }
    }

    /**
     * Root 直连通道：单条 su -c 在同一 root shell 内跑完全部命令与回读校验
     * （Runtime.exec("su","-c",…) 经 Magisk/KernelSU 授权，无需 RootAIDL 服务）。
     * 授权失败/无 su 时输出不含 appops 校验行，调用方据此提示。
     */
    suspend fun applyViaSu(pkg: String): KeepAliveResult {
        // "设置命令; 回读校验" 全部拼进一条命令，单次 root shell 执行
        val script = (cmds(pkg) + checkCmd(pkg)).joinToString("; ")
        val sb = StringBuilder()
        return try {
            val r = CommandRunner.executeCommandSync(script, true)
            sb.append("exit=").append(r.exitCode).append('\n')
                .append(r.output.take(500))
            parseCheck(r.output, pkg).copy(raw = sb.toString())
        } catch (e: Exception) {
            // 无 su 二进制（IOException）或被拒绝授权
            sb.append(e.javaClass.simpleName).append(": ")
                .append(e.message?.take(200))
            KeepAliveResult(false, false, false, false, sb.toString())
        }
    }

    private fun checkCmd(pkg: String) =
        "dumpsys deviceidle whitelist; cmd appops get $pkg RUN_ANY_IN_BACKGROUND; " +
                "cmd appops get $pkg RUN_IN_BACKGROUND; " +
                "cmd appops get $pkg START_FOREGROUND_SERVICE"

    /**
     * 解析校验输出：appops 状态提取不到时不算通过（避免命令失败误报 ✓）。
     * allow/default/foreground 视为放行；ignore/deny 视为受限。
     */
    private fun parseCheck(out: String, pkg: String): KeepAliveResult {
        fun opOk(op: String): Boolean {
            val m = Regex("$op:\\s*(\\w+)", RegexOption.IGNORE_CASE).find(out)
            return when (m?.groupValues?.get(1)?.lowercase()) {
                "allow", "default", "foreground" -> true
                else -> false
            }
        }
        return KeepAliveResult(
            doze = out.contains(pkg),
            runAnyBg = opOk("RUN_ANY_IN_BACKGROUND"),
            runBg = opOk("RUN_IN_BACKGROUND"),
            fgs = opOk("START_FOREGROUND_SERVICE"),
            raw = ""
        )
    }

    /** 是否已在电池优化白名单（系统 API 免权限读取） */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 拉起系统"忽略电池优化"授权弹窗（需 Manifest 声明
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS；Google Play 对此权限有审核限制，
     * 本应用为侧载分发不受影响）
     */
    @SuppressLint("BatteryLife")
    fun requestBatteryExemption(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Exception) {
            // 个别 ROM 无此 Activity：回退到电池优化总列表页
            try {
                val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }
}
