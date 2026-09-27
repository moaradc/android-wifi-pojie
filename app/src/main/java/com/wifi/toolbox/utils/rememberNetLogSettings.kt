package com.wifi.toolbox.utils

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.edit
import com.wifi.toolbox.structs.NetLogSettings

/**
 * 网络日志设置状态：Compose 可观察 + SharedPreferences 自动持久化。
 * 写入后通知运行中的 NetworkLogService 热加载（不重启会话）；
 * 服务未运行时绝不因设置写入而拉起服务。
 */
@Composable
fun rememberNetLogSettings(context: Context): MutableState<NetLogSettings> {
    val prefs = remember(context) {
        context.getSharedPreferences(NetLogSettings.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val state = remember(prefs) { mutableStateOf(NetLogSettings.from(prefs)) }

    return remember(state) {
        object : MutableState<NetLogSettings> {
            override var value: NetLogSettings
                get() = state.value
                set(s) {
                    state.value = s
                    prefs.edit {
                        putBoolean(NetLogSettings.CAPTURE_SYSTEM_LOG_KEY, s.captureSystemLog)
                        putBoolean(NetLogSettings.SNAPSHOT_ON_EVENT_KEY, s.snapshotOnEvent)
                        putBoolean(NetLogSettings.PROBE_TIMELINE_KEY, s.probeTimeline)
                        putString(NetLogSettings.LOG_DIR_URI_KEY, s.logDirUri)
                        putBoolean(NetLogSettings.AUTO_SAVE_LOG_KEY, s.autoSaveLog)
                        putInt(NetLogSettings.KEEP_SESSIONS_KEY, s.keepSessions)
                        putInt(NetLogSettings.MAX_LOG_MB_KEY, s.maxLogMb)
                        putInt(NetLogSettings.CHANNEL_KEY, s.channel)
                    }
                    if (com.wifi.toolbox.services.NetLogState.running) {
                        try {
                            context.startService(
                                android.content.Intent(
                                    context,
                                    com.wifi.toolbox.services.NetworkLogService::class.java
                                ).apply {
                                    action = com.wifi.toolbox.services.NetworkLogService.ACTION_RELOAD
                                }
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
            override fun component1() = value
            override fun component2(): (NetLogSettings) -> Unit = { value = it }
        }
    }
}
