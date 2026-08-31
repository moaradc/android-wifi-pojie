package com.wifi.toolbox.utils

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 移动数据网络 QCI（5G SA 下为 5QI）读取器——Root 专用。
 *
 * 背景与考证（2026-08 联网核实）：
 * - Android 公开 Telephony API 不暴露 QCI；Android 12 新增的
 *   QosCallback / EpsBearerQosSessionAttributes 属 SystemApi（需
 *   READ_PRIVILEGED_PHONE_STATE，普通应用不可达），且各机型 HAL 支持参差；
 * - dumpsys telephony.registry / dumpsys phone 中不含任何 QoS 字段（MTK 实测
 *   grep qci/5qi/EpsQos/QosBearerSession 零命中），该路不通；
 * - Cellular-Z / Network Signal Guru 等通过高通 DIAG 接口读取（需 Root +
 *   高通平台，MTK 无此接口）。
 *
 * 因此采用与 BeaRerM（github.com/Luckyji6/BeaRerM，Xiaomi/MT6895/MIUI 实测）
 * 相同的方案：Root 下直接与基带 AT 通道对话，发 3GPP TS 27.007 标准查询：
 * - AT+CGDCONT?      已定义 PDP 上下文（cid 列表 + APN）
 * - AT+CGCONTRDP     动态上下文参数（判定上下文是否激活）
 * - AT+CGEQOSRDP=cid 网络实际下发的 QoS（QCI/5QI + GBR/MBR/APN-AMBR）
 * - AT+ESUO=n        MTK 专有：AT 通道绑定协议栈（4=SIM1, 5=SIM2），
 *                    会话内生效（须与查询同一 exec 3<> 会话）；结束后复位回 4
 *
 * 注意事项（BeaRerM 实测踩坑，照抄规避）：
 * - AT 节点无统一路径：按 MTK/高通/三星/展锐/其他候选表探测，发裸 AT、
 *   首个应答 OK 者胜出，成功节点持久化缓存（SharedPreferences）供下次直连；
 * - 被 rild 占用的节点 open 失败自动跳过（ttyC2 常被 mtkfusionrild 持有）；
 * - cid 编号不稳定，必须 CGDCONT 枚举，不硬编码；CGEQOSRDP 必须带 cid 参数；
 * - MagiskSU 会吞掉命令行里"像选项的参数"——脚本经 stdin 送入 su（非 su -c）
 *   天然规避；
 * - 现代高通（QRTR/QMI）可能不向 AP 暴露 AT 字符设备——所有候选均不应答
 *   时如实返回 noNode，不显示假数据。
 */
object QciReader {

    /**
     * 探测结果。
     * @param qci     网络实际下发的 QCI/5QI（如 "9"）；空=本次未读到
     * @param noRoot  true=无 su / Root 被拒绝（UI 提示「需 Root」）
     * @param noNode  true=Root 可用但无 AT 节点应答（如现代高通）或打开失败
     */
    data class QciResult(
        val qci: String,
        val noRoot: Boolean,
        val noNode: Boolean
    )

    /** 候选 AT 节点（MTK CCCI / 高通 smd / 三星 USB-CDC / 展锐 / 兜底） */
    private val AT_NODES = listOf(
        "/dev/ttyC0", "/dev/ttyC1", "/dev/ttyC3", "/dev/ttyC5", "/dev/ttyC6",
        "/dev/smd7", "/dev/smd8", "/dev/smd11", "/dev/at_usb0", "/dev/at_mdm0",
        "/dev/ttyACM0", "/dev/ttyACM1", "/dev/ttyACM2",
        "/dev/stty_lte0", "/dev/stty_lte1",
        "/dev/ttyUSB0", "/dev/ttyUSB2", "/dev/ttyGS0"
    )

    private const val PREFS = "qci_reader"
    private const val KEY_NODE = "at_node"

    /** su 二进制不存在（IOException）后缓存，避免每次刷新都白抛一次异常 */
    private val suSupported = AtomicBoolean(true)

    /**
     * 探测当前数据承载的 QCI/5QI。整个过程同步阻塞（数秒级），
     * 调用方须在 IO 线程执行。
     *
     * @param context 任意 Context（取 SharedPreferences 缓存 AT 节点）
     * @param dataPlmn 当前数据网络 PLMN 数字串（如 "46000"，免权限
     *                 TelephonyManager.networkOperator；双卡时用于挑选
     *                 与注册网络匹配的协议栈，空则取首个有效结果）
     */
    fun probe(context: Context, dataPlmn: String): QciResult {
        if (!suSupported.get()) return QciResult("", noRoot = true, noNode = false)

        val cachedNode = try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_NODE, null)
        } catch (_: Exception) {
            null
        }

        val cands = buildList {
            if (!cachedNode.isNullOrEmpty()) add(cachedNode)
            AT_NODES.forEach { if (it != cachedNode) add(it) }
        }

        val raw = runRootScript(probeScript(cands))

        // su 不可用/被拒绝：脚本无任何标记输出
        val rootWorked = raw.contains("@@NODE") || raw.contains("@@NONODE") ||
                raw.contains("@@STACK")
        if (!rootWorked) return QciResult("", noRoot = true, noNode = false)

        // Root 可用但无 AT 节点应答（现代高通等）——如实标记，不装作有值
        if (raw.contains("@@NONODE")) return QciResult("", noRoot = false, noNode = true)

        // 记住应答的节点，下次跳过扫描
        Regex("@@NODE\\s+(\\S+)").find(raw)?.groupValues?.get(1)?.let { node ->
            try {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_NODE, node).apply()
            } catch (_: Exception) {
            }
        }

        // @@OPENFAIL 且无任何栈数据：节点被 rild 突然占用等——按不可用处理
        if (raw.contains("@@OPENFAIL") && !raw.contains("@@STACK")) {
            return QciResult("", noRoot = false, noNode = true)
        }

        // 逐协议栈解析；命中数据 PLMN 的栈优先（双卡场景）
        val plmn = dataPlmn.trim()
        var fallback = ""
        val sections = raw.split(Regex("@@STACK\\s+"))
        for (i in 1 until sections.size) {
            val body = sections[i]
            val (stackPlmn, qci) = parseStack(body.substringAfter('\n'))
            if (qci.isEmpty()) continue
            if (plmn.isNotEmpty() && stackPlmn == plmn) return QciResult(
                qci, noRoot = false, noNode = false
            )
            if (fallback.isEmpty()) fallback = qci
        }
        // 无激活上下文/查询零命中：不算不可用，仅本次无值（稍后自动重试）
        return QciResult(fallback, noRoot = false, noNode = false)
    }

    /**
     * 单协议栈原始应答解析。
     * @return (PLMN, 选中承载的 QCI)
     * 选择规则：+CGCONTRDP 标记为激活、APN 非 IMS、QCI 非空的承载，
     * 多个时取第一个（数据默认承载）。
     */
    private fun parseStack(raw: String): Pair<String, String> {
        val plmn = Regex("\\+COPS:\\s*\\d+,\\d+,\"(\\d+)\",(\\d+)")
            .find(raw)?.groupValues?.get(1) ?: ""

        val apnByCid = mutableMapOf<String, String>()
        Regex("\\+CGDCONT:\\s*(\\d+),\"[^\"]*\",\"([^\"]*)\"").findAll(raw).forEach {
            apnByCid[it.groupValues[1]] = it.groupValues[2]
        }
        val activeCids = mutableSetOf<String>()
        val activeApn = mutableMapOf<String, String>()
        Regex("\\+CGCONTRDP:\\s*(\\d+),(\\d*),\"([^\"]*)\"").findAll(raw).forEach {
            val cid = it.groupValues[1]
            activeCids += cid
            var apn = it.groupValues[3]
            val cut = apn.indexOf(".MNC")
            if (cut > 0) apn = apn.substring(0, cut)
            if (apn.isNotEmpty()) activeApn[cid] = apn
        }

        // +CGEQOSRDP: cid,QCI,DL_GBR,UL_GBR,DL_MBR,UL_MBR,DL_AMBR,UL_AMBR
        var chosen = ""
        Regex("\\+CGEQOSRDP:\\s*([^\\r\\n]+)").findAll(raw).forEach { m ->
            if (chosen.isNotEmpty()) return@forEach
            val f = m.groupValues[1].trim().split(",", limit = 8)
            if (f.size < 2) return@forEach
            val cid = f[0].trim()
            val qci = f[1].trim()
            if (qci.isEmpty()) return@forEach
            val active = cid in activeCids
            val isIms = (activeApn[cid] ?: apnByCid[cid] ?: "")
                .lowercase().startsWith("ims")
            if (active && !isIms) chosen = qci
        }
        return plmn to chosen
    }

    /**
     * 生成探测脚本（POSIX sh，经 stdin 喂给 su）。
     * ask(): 打开节点→后台 cat 收应答→写命令→等待→关收→回显输出。
     * 注意：Kotlin 原始字符串同样插值 $，脚本内 shell 美元统一用 D 占位替换。
     */
    private fun probeScript(candidates: List<String>): String {
        val cands = candidates.joinToString(" ")
        val d = "\$"   //NOI18N shell 变量符号
        return """
ask(){
  D=${d}1; C=${d}2; W=${d}3
  O=/data/local/tmp/.wtq
  : > ${d}O
  exec 3<>"${d}D" 2>/dev/null || return 1
  cat <&3 >> ${d}O &
  R=${d}!
  printf '%s\r' "${d}C" >&3
  sleep ${d}W
  kill ${d}R 2>/dev/null
  exec 3>&-
  cat ${d}O
  rm -f ${d}O
}
NODE=""
for d in $cands; do
  [ -e "${d}d" ] || continue
  if ask "${d}d" AT 0.35 | grep -q OK; then NODE="${d}d"; break; fi
done
[ -n "${d}NODE" ] || { echo '@@NONODE'; exit 0; }
echo "@@NODE ${d}NODE"
if ask "${d}NODE" 'AT+ESUO=?' 0.4 | grep -q '+ESUO'; then STACKS="4 5"; else STACKS="0"; fi
probe(){
  S=${d}1
  if [ "${d}S" = 0 ]; then SET=""; else SET="AT+ESUO=${d}S"; fi
  O=/data/local/tmp/.wtq${d}S
  : > ${d}O
  exec 3<>"${d}NODE" 2>/dev/null || { echo '@@OPENFAIL'; return 1; }
  cat <&3 >> ${d}O &
  R=${d}!
  for c in ${d}SET "AT+COPS?" "AT+CGDCONT?" "AT+CGCONTRDP" "AT+CGEQOSRDP=0" "AT+CGEQOSRDP=1" "AT+CGEQOSRDP=2" "AT+CGEQOSRDP=3"; do
    printf '%s\r' "${d}c" >&3
    sleep 0.45
  done
  sleep 0.5
  kill ${d}R 2>/dev/null
  exec 3>&-
  echo "@@STACK ${d}S"
  cat ${d}O
  rm -f ${d}O
}
for s in ${d}STACKS; do probe ${d}s; done
if [ "${d}STACKS" != "0" ]; then ask "${d}NODE" 'AT+ESUO=4' 0.3 >/dev/null; fi
echo '@@DONE'
""".trimIndent() + "\n"
    }

    /**
     * 以 su 执行脚本（脚本经 stdin 送入，规避 MagiskSU 吞参数问题），
     * 带看门狗防挂死（首次触发 Magisk 授权弹窗时用户可能需要时间点击）。
     */
    private fun runRootScript(script: String, timeoutMs: Long = 25_000L): String {
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            p.outputStream.use { os ->
                os.write(script.toByteArray(Charsets.UTF_8))
                os.write("\nexit\n".toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val watchdog = Thread {
                try {
                    Thread.sleep(timeoutMs)
                    p.destroy()
                } catch (_: InterruptedException) {
                }
            }
            watchdog.isDaemon = true
            watchdog.start()
            val sb = StringBuilder()
            try {
                BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        sb.append(line).append('\n')
                    }
                }
                p.waitFor()
            } finally {
                watchdog.interrupt()
            }
            sb.toString()
        } catch (_: Exception) {
            // su 二进制不存在（IOException: Cannot run program "su"）
            suSupported.set(false)
            ""
        }
    }
}
