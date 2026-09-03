package com.wifi.toolbox.utils

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.wifi.toolbox.structs.GuardSettings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单次自愈事件记录（环形缓冲，仅保留最近 [MAX_EVENTS] 条）
 */
data class GuardEvent(
    val time: Long,
    val ssid: String,
    val failedProbes: String, // 失败的检测项，如 "HTTP,DNS"
    val actions: String,      // 尝试的自愈动作链（含执行失败动作，如实反映升压过程）
    val recovered: Boolean,   // 自愈后是否恢复
    val costMs: Long          // 自愈总耗时
)

/**
 * 网络守护统计模型：重连次数、各动作有效率、每日分布。
 * 持久化：SharedPreferences + JSON（轻量数据无需 Room）。
 *
 * 设计目标：让用户能从统计里反推自己设备的根因——
 * 例如"轻量档一直无效但 disable/enable 一直有效"说明是省电休眠问题。
 */
class GuardStats(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("guard_stats", Context.MODE_PRIVATE)
    private val settingsPrefs = appContext.getSharedPreferences(
        GuardSettings.PREFS_NAME, Context.MODE_PRIVATE
    )

    var totalChecks by mutableStateOf(0L)      // 总检测次数
        private set
    var totalFailures by mutableStateOf(0L)    // 判定断网次数（防抖后）
        private set
    var totalHeals by mutableStateOf(0L)       // 自愈执行次数
        private set
    var totalRecovered by mutableStateOf(0L)   // 自愈成功恢复次数
        private set
    var events by mutableStateOf(emptyList<GuardEvent>())
        private set

    /** 自愈动作有效率（动作名 -> (执行成功次数, 其中自愈恢复次数)）。
     *  只统计执行成功的动作——执行失败（通道返回失败/该版本无此命令）的动作
     *  未对网络产生任何效果，计入会虚增成功率并误导高成功率档选优 */
    val actionStats = mutableStateMapOf<String, Pair<Int, Int>>()
    init {
        load()
        autoPrune()
    }

    fun recordCheck(ok: Boolean) {
        totalChecks++
        if (!ok) totalFailures++
        persist()
    }

    /**
     * 记录一次自愈：动作有效率只累计「执行成功」的动作（事件历史的动作链
     * 仍保留完整尝试记录，与实时日志一致）；旧版本已计入的失败动作计数
     * 不做追溯清洗，需要精确观察可手动清零后重新累积。
     */
    fun recordHeal(
        actionRuns: List<HealActionRun>,
        recovered: Boolean,
        costMs: Long,
        ssid: String,
        failedProbes: String
    ) {
        totalHeals++
        if (recovered) totalRecovered++
        actionRuns.filter { it.ok }.forEach {
            val pair = actionStats[it.action] ?: (0 to 0)
            actionStats[it.action] = pair.first + 1 to pair.second + if (recovered) 1 else 0
        }
        events = (listOf(
            GuardEvent(
                time = System.currentTimeMillis(),
                ssid = ssid,
                failedProbes = failedProbes,
                actions = actionRuns.joinToString("+") { it.action },
                recovered = recovered,
                costMs = costMs
            )
        ) + events).take(MAX_EVENTS)
        autoPrune()
        persist()
    }

    fun reset() {
        totalChecks = 0
        totalFailures = 0
        totalHeals = 0
        totalRecovered = 0
        events = emptyList()
        actionStats.clear()
        persist()
    }

    /** 仅清空事件历史（保留累计统计与动作有效率） */
    fun clearEvents() {
        events = emptyList()
        persist()
    }

    /**
     * 自动清理：按设置的保留天数删除过期事件历史。
     * 保留天数读自守护设置（settings_guard），0 = 关闭不动作。
     * 触发点：初始化、每次记录新事件、统计页打开——无需服务运行也能清理。
     * @return 删除条数（未开启返回 0）
     */
    fun autoPrune(): Int {
        val keepDays = try {
            settingsPrefs.getInt(
                GuardSettings.AUTO_CLEAN_DAYS_KEY, GuardSettings.AUTO_CLEAN_DAYS_DEFAULT
            )
        } catch (_: Exception) {
            0
        }
        if (keepDays <= 0 || events.isEmpty()) return 0
        val cutoff = System.currentTimeMillis() - keepDays * 86_400_000L
        val before = events.size
        events = events.filter { it.time >= cutoff }
        val removed = before - events.size
        if (removed > 0) persist()
        return removed
    }

    private fun persist() {
        val ev = JSONArray()
        events.take(MAX_EVENTS).forEach { e ->
            ev.put(
                JSONObject()
                    .put("t", e.time)
                    .put("s", e.ssid)
                    .put("p", e.failedProbes)
                    .put("a", e.actions)
                    .put("r", e.recovered)
                    .put("c", e.costMs)
            )
        }
        val acts = JSONObject()
        actionStats.forEach { (k, v) -> acts.put(k, JSONArray(listOf(v.first, v.second))) }
        prefs.edit {
            putLong("totalChecks", totalChecks)
            putLong("totalFailures", totalFailures)
            putLong("totalHeals", totalHeals)
            putLong("totalRecovered", totalRecovered)
            putString("events", ev.toString())
            putString("actions", acts.toString())
        }
    }

    private fun load() {
        totalChecks = prefs.getLong("totalChecks", 0)
        totalFailures = prefs.getLong("totalFailures", 0)
        totalHeals = prefs.getLong("totalHeals", 0)
        totalRecovered = prefs.getLong("totalRecovered", 0)
        events = try {
            val arr = JSONArray(prefs.getString("events", "[]"))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                GuardEvent(
                    time = o.getLong("t"),
                    ssid = o.optString("s"),
                    failedProbes = o.optString("p"),
                    actions = o.optString("a"),
                    recovered = o.optBoolean("r"),
                    costMs = o.optLong("c")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
        actionStats.clear()
        try {
            val acts = JSONObject(prefs.getString("actions", "{}"))
            acts.keys().forEach { k ->
                val v = acts.getJSONArray(k)
                actionStats[k] = v.getInt(0) to v.getInt(1)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val MAX_EVENTS = 100

        fun formatTime(time: Long): String =
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    }

    /**
     * 高成功率档依据：累计自愈成功次数最高的动作。
     *
     * 排序规则（主键：累计成功次数，次键：成功率）——
     * "成功 50/100" 排在 "成功 2/2" 之前：样本量大的动作更可信，
     * 避免只试过一次的动作因 100% 成功率而垄断选择。
     * 无任何统计数据时返回 null（下拉菜单中不展示该档位）。
     * 样本口径与 [recordHeal] 一致：仅执行成功的动作参与选优。
     */
    fun bestAction(): String? {
        return actionStats.entries
            .filter { it.value.second > 0 }
            .maxWithOrNull(
                compareByDescending<Map.Entry<String, Pair<Int, Int>>> { it.value.second }
                    .thenByDescending {
                        if (it.value.first > 0) it.value.second.toFloat() / it.value.first else 0f
                    }
            )?.key
    }

    /** 高成功率档的动作成功率（百分比，供 UI 展示）；无数据返回 -1 */
    fun bestActionRate(action: String?): Int {
        if (action == null) return -1
        val v = actionStats[action] ?: return -1
        return if (v.first > 0) v.second * 100 / v.first else -1
    }
}
