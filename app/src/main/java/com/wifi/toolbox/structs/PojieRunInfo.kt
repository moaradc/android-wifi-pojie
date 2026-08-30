package com.wifi.toolbox.structs

import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp

data class PojieRunInfo(
    val ssid: String,
    val tryList: List<String>,
    val tryIndex: Int = 0,
    val lastTryTime: Long = 0,
    val retryCount: Int = 0,
    val textTip: String = ToolboxApp.instance.getString(R.string.task_preparing),
    val costList: List<Long> = emptyList()
) {
    companion object{
        fun calculateAverageSpeed(task: PojieRunInfo): Long? {
            val costs = task.costList
            if (costs.size < 5) return null

            val avgMs = costs.average()
            if (avgMs <= 0) return null

            return avgMs.toLong()
        }
    }
}