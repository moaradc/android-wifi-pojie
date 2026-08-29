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
    val actions: String,      // 执行的自愈动作链，如 "reassociate"
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

    private val prefs = context.getSharedPreferences("guard_stats", Context.MODE_PRIVATE)

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

    /** 自愈动作有效率（动作名 -> (执行次数, 成功次数)） */
    val actionStats = mutableStateMapOf<String, Pair<Int, Int>>()
    init {
        load()
    }

    fun recordCheck(ok: Boolean) {
        totalChecks++
        if (!ok) totalFailures++
        persist()
    }

    fun recordHeal(actions: List<String>, recovered: Boolean, costMs: Long, ssid: String, failedProbes: String) {
        totalHeals++
        if (recovered) totalRecovered++
        actions.forEach { action ->
            val pair = actionStats[action] ?: (0 to 0)
            actionStats[action] = pair.first + 1 to pair.second + if (recovered) 1 else 0
        }
        events = (listOf(
            GuardEvent(
                time = System.currentTimeMillis(),
                ssid = ssid,
                failedProbes = failedProbes,
                actions = actions.joinToString("+"),
                recovered = recovered,
                costMs = costMs
            )
        ) + events).take(MAX_EVENTS)
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
}
