package com.wifi.toolbox.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台保活三级方案（网络调研 + AOSP 源码验证的通行做法：
 * tweaselORG/appstraction、kiosk-satellite 等开源项目同样使用
 * deviceidle whitelist + appops 组合）：
 *
 * 1. 系统级（应用自身能力，免特权）：
 *    - PARTIAL_WAKE_LOCK：息屏后 CPU 休眠会暂停协程定时器（delay 漂移），
 *      持有部分唤醒锁保证检测循环照常执行（由 GuardService 按 设置 开关管理）
 *    - 电池优化白名单：系统弹窗引导用户豁免（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS）
 *
 * 2. Shizuku（uid 2000 shell，Shell 应用持有 DUMP/DEVICE_POWER/MANAGE_APP_OPS_MODES）：
 *    - dumpsys deviceidle whitelist +<pkg>   Doze 白名单（写入 deviceidle.xml，重启持久）
 *    - cmd appops set <pkg> RUN_IN_BACKGROUND allow          后台运行（Android 7+）
 *    - cmd appops set <pkg> RUN_ANY_IN_BACKGROUND allow      后台运行（Android 9+，
 *      小米澎湃OS/MIUI 等国产 ROM 后台查杀的关键开关）
 *    - cmd appops set <pkg> START_FOREGROUND allow           前台服务启动权限（Android 9+）
 *
 * 3. Root（独立通道，不依赖应用内 RootAIDL 服务）：
 *    - 与 Shizuku 同一套命令，直接 su -c 在同一 root shell 内一次跑完，
 *      有 Magisk/KernelSU 授权即可
 *
 * ── 本版修正（Alpha-16，均经 AOSP 源码验证）──────────────────────────
 * a) 前台服务 op 的正确名称是 START_FOREGROUND（AppOpInfo.name，
 *    opStr=android:start_foreground，默认模式 ALLOW）；此前误写作
 *    START_FOREGROUND_SERVICE——AOSP 从不存在该 op，set/get 均报
 *    "Unknown operation string"，导致 Root 通道 FGS 项恒显示未生效。
 * b) appops 语义：set <op> default 会【删除】该 op 的显式条目
 *    （AppOpsCheckingServiceImpl.setPackageMode: mode==default 时 delete），
 *    之后 get 输出 "No operations." + "Default mode: allow"——
 *    恢复默认的校验必须把"无显式条目"判定为已恢复，而不是找 "OP: default"。
 * c) 回读脚本带 echo 分段标记，Doze/appops 各段独立解析；
 *    白名单判定兼容 Android 9+ 的 CSV 行格式（"user,com.foo,true"）。
 * d) Shizuku 的 newProcess 是 exec 语义（无 shell 解析），复合脚本必须
 *    经 sh -c 执行——此前含 ";" 的回读命令被按空格拆成 argv，Shizuku
 *    通道恒显示 0/4（见 ShizukuUtil.executeScriptSync）。
 */
object KeepAliveHelper {

    /** 保活命令执行结果（逐项，用于 UI 反馈与日志） */
    data class KeepAliveResult(
        val doze: Boolean,      // Doze 白名单已包含本应用
        val runAnyBg: Boolean,  // RUN_ANY_IN_BACKGROUND 已放行
        val runBg: Boolean,     // RUN_IN_BACKGROUND 已放行
        val fgs: Boolean,       // START_FOREGROUND 已放行
        val raw: String         // 原始输出摘要（排障用）
    ) {
        val all: Boolean get() = doze && runAnyBg && runBg && fgs
    }

    // ---- appops op 的 shell 名称（AppOpInfo.name，AOSP 原文）----
    private const val OP_RUN = "RUN_IN_BACKGROUND"          // API 24+
    private const val OP_RUN_ANY = "RUN_ANY_IN_BACKGROUND"  // API 28+
    private const val OP_FGS = "START_FOREGROUND"           // API 28+

    // 回读分段标记（su -c 与 sh -c 都经 shell 解释，echo 标记保证解析确定性）
    private const val MARK_DOZE = "---DOZE---"
    private const val MARK_RUN_ANY = "---RUN_ANY---"
    private const val MARK_RUN = "---RUN---"
    private const val MARK_FGS = "---FGS---"

    /** RUN_ANY/START_FOREGROUND 两项 op 仅 Android 9+ 存在；
     *  更早版本没有对应的后台限制，按"已放行/已恢复"处理 */
    private val ops28 get() = Build.VERSION.SDK_INT >= 28

    private fun applyCmds(pkg: String): List<String> = buildList {
        add("dumpsys deviceidle whitelist +$pkg")
        add("cmd appops set $pkg $OP_RUN allow")
        if (ops28) {
            add("cmd appops set $pkg $OP_RUN_ANY allow")
            add("cmd appops set $pkg $OP_FGS allow")
        }
    }

    /** 恢复默认（撤销保活）：白名单移除 + appops 重置 default */
    private fun revertCmds(pkg: String): List<String> = buildList {
        add("dumpsys deviceidle whitelist -$pkg")
        add("cmd appops set $pkg $OP_RUN default")
        if (ops28) {
            add("cmd appops set $pkg $OP_RUN_ANY default")
            add("cmd appops set $pkg $OP_FGS default")
        }
    }

    /** 回读校验脚本：分段标记 + 白名单 dump + 三个 op 的 get */
    private fun checkScript(pkg: String): String = listOf(
        "echo $MARK_DOZE",
        "dumpsys deviceidle whitelist",
        "echo $MARK_RUN_ANY",
        "cmd appops get $pkg $OP_RUN_ANY",
        "echo $MARK_RUN",
        "cmd appops get $pkg $OP_RUN",
        "echo $MARK_FGS",
        "cmd appops get $pkg $OP_FGS"
    ).joinToString("; ")

    /** 命令 + 回读拼成单个 shell 脚本 */
    private fun fullScript(cmds: List<String>, pkg: String): String =
        (cmds + checkScript(pkg)).joinToString("; ")

    // ==================== Shizuku 通道 ====================

    suspend fun applyViaShizuku(pkg: String): KeepAliveResult =
        try {
            runScript(
                ShizukuUtil.executeScriptSync(fullScript(applyCmds(pkg), pkg)),
                pkg, isRevert = false
            )
        } catch (e: Exception) {
            failResult("shizuku: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
        }

    suspend fun revertViaShizuku(pkg: String): KeepAliveResult =
        try {
            runScript(
                ShizukuUtil.executeScriptSync(fullScript(revertCmds(pkg), pkg)),
                pkg, isRevert = true
            )
        } catch (e: Exception) {
            failResult("shizuku: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
        }

    // ==================== Root 直连通道 ====================

    /** su -c 单次 shell 跑完全部命令与回读校验（Magisk/KernelSU 授权即可） */
    suspend fun applyViaSu(pkg: String): KeepAliveResult =
        try {
            runScript(
                CommandRunner.executeCommandSync(fullScript(applyCmds(pkg), pkg), true),
                pkg, isRevert = false
            )
        } catch (e: Exception) {
            failResult("${e.javaClass.simpleName}: ${e.message?.take(200)}")
        }

    suspend fun revertViaSu(pkg: String): KeepAliveResult =
        try {
            runScript(
                CommandRunner.executeCommandSync(fullScript(revertCmds(pkg), pkg), true),
                pkg, isRevert = true
            )
        } catch (e: Exception) {
            failResult("${e.javaClass.simpleName}: ${e.message?.take(200)}")
        }

    private fun failResult(reason: String) =
        KeepAliveResult(false, false, false, false, reason)

    private fun runScript(
        r: CommandRunner.CommandResult,
        pkg: String,
        isRevert: Boolean
    ): KeepAliveResult {
        val raw = "exit=${r.exitCode}\n${r.output.take(600)}"
        // 首个标记未出现 = 脚本根本没跑起来（su/sh 授权失败、通道异常），
        // 逐项全判未生效，调用方也可用该特征提示授权失败
        if (!r.output.contains(MARK_DOZE)) {
            return KeepAliveResult(false, false, false, false, raw)
        }
        val parsed = if (isRevert) parseRevertCheck(r.output, pkg)
        else parseApplyCheck(r.output, pkg)
        return parsed.copy(raw = raw)
    }

    // ==================== 回读解析 ====================

    /** 取标记段：startMarker 之后、endMarker 之前（endMarker 为 null 表示取到结尾） */
    private fun section(out: String, startMarker: String, endMarker: String?): String {
        val s = out.substringAfter(startMarker, "")
        return if (endMarker == null) s else s.substringBefore(endMarker, s)
    }

    /**
     * 白名单 dump 段中是否存在本应用。兼容多种行格式：
     * - Android 9+ CSV："user,com.wifi.toolbox,true"（system-excidle,/system,/user, 前缀）
     * - 裸包名行（部分 ROM）、"*pkg"、"pkg=true"
     */
    private fun whitelistHasPkg(sec: String, pkg: String): Boolean {
        return sec.lineSequence().any { line ->
            val t = line.trim()
            t == pkg || t == "*$pkg" || t == "$pkg=true" ||
                    t.split(',').any { it.trim() == pkg }
        }
    }

    /**
     * 解析单个 op 的 appops 状态（AOSP 行为）：
     * - 显式设置过："<OP>: <mode>"（可能带 "; time=…" 后缀）
     * - 未显式设置（含 set default 删除条目后）："No operations." + "Default mode: <mode>"
     * 返回 (显式模式或 null, 默认模式或 null)
     */
    private fun opModes(sec: String, op: String): Pair<String?, String?> {
        val explicit = Regex("$op:\\s*(\\w+)", RegexOption.IGNORE_CASE)
            .find(sec)?.groupValues?.get(1)?.lowercase()
        val defaultMode = Regex("Default mode:\\s*(\\w+)", RegexOption.IGNORE_CASE)
            .find(sec)?.groupValues?.get(1)?.lowercase()
        return explicit to defaultMode
    }

    /** 保活判定：显式 allow/foreground 视为放行；显式 default 也视为放行
     *  （三个 op 的 AOSP 默认模式均为 ALLOW，AppOpsManager.setDefaultMode）；
     *  无显式条目时看回读到的默认模式；ignore/deny 为受限 */
    private fun opAllowed(sec: String, op: String): Boolean {
        val (explicit, defaultMode) = opModes(sec, op)
        return when (explicit) {
            "allow", "foreground", "default" -> true
            null -> defaultMode == "allow" || defaultMode == "foreground"
            else -> false
        }
    }

    /** 恢复默认判定：无显式条目（"No operations."）或条目本身为 default 视为已恢复；
     *  段内容无法解析（既无条目也无 No operations.）按未恢复如实报告 */
    private fun opIsDefault(sec: String, op: String): Boolean {
        val (explicit, _) = opModes(sec, op)
        if (explicit == "default") return true
        return explicit == null && sec.contains("No operations", ignoreCase = true)
    }

    private fun parseApplyCheck(out: String, pkg: String): KeepAliveResult {
        val dozeSec = section(out, MARK_DOZE, MARK_RUN_ANY)
        val runAnySec = section(out, MARK_RUN_ANY, MARK_RUN)
        val runSec = section(out, MARK_RUN, MARK_FGS)
        val fgsSec = section(out, MARK_FGS, null)
        return KeepAliveResult(
            doze = whitelistHasPkg(dozeSec, pkg),
            runAnyBg = if (ops28) opAllowed(runAnySec, OP_RUN_ANY) else true,
            runBg = opAllowed(runSec, OP_RUN),
            fgs = if (ops28) opAllowed(fgsSec, OP_FGS) else true,
            raw = ""
        )
    }

    private fun parseRevertCheck(out: String, pkg: String): KeepAliveResult {
        val dozeSec = section(out, MARK_DOZE, MARK_RUN_ANY)
        val runAnySec = section(out, MARK_RUN_ANY, MARK_RUN)
        val runSec = section(out, MARK_RUN, MARK_FGS)
        val fgsSec = section(out, MARK_FGS, null)
        return KeepAliveResult(
            doze = !whitelistHasPkg(dozeSec, pkg),
            runAnyBg = if (ops28) opIsDefault(runAnySec, OP_RUN_ANY) else true,
            runBg = opIsDefault(runSec, OP_RUN),
            fgs = if (ops28) opIsDefault(fgsSec, OP_FGS) else true,
            raw = ""
        )
    }

    // ==================== 系统级（免特权） ====================

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
