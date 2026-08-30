package com.wifi.toolbox.app

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import com.wifi.toolbox.services.PojieService
import com.wifi.toolbox.structs.PojieConfig
import com.wifi.toolbox.structs.PojieRunInfo
import com.wifi.toolbox.utils.LogState

class AppPojieTask(private val context: Context) {

    var pojieConfig by mutableStateOf(PojieConfig())
        private set

    var logState by mutableStateOf(LogState())
        private set

    val TaskRunList = mutableStateListOf<PojieRunInfo>()
    val TaskEndTip = mutableStateMapOf<String, String>()

    /**
     * 更新配置
     * @param info 新配置
     * @return none
     */
    fun update(info: PojieConfig) {
        pojieConfig = info
    }

    /**
     * 开始任务
     * @param info 任务信息
     * @return none
     */
    fun start(info: PojieRunInfo) {
        if (TaskRunList.none { it.ssid == info.ssid }) {
            if (TaskRunList.isEmpty()) {
                val intent = Intent(context, PojieService::class.java)
                context.startService(intent)
            }
            TaskRunList.add(info)
            TaskEndTip.remove(info.ssid)
        }
    }

    /**
     * 停止任务
     * @param name 无线名称
     * @return none
     */
    fun stop(name: String) {
        TaskRunList.removeIf { it.ssid == name }
    }

    /**
     * 改变任务状态
     * @param name 无线名称
     * @param change 转换函数
     * @return none
     */
    fun edit(name: String, change: (PojieRunInfo) -> PojieRunInfo) {
        val index = TaskRunList.indexOfFirst { it.ssid == name }
        if (index != -1) {
            TaskRunList[index] = change(TaskRunList[index])
        }
    }
}