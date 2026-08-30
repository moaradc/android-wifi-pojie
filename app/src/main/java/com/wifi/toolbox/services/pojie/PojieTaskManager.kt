package com.wifi.toolbox.services.pojie

import android.content.Context
import androidx.compose.runtime.snapshotFlow
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.services.PojieService
import com.wifi.toolbox.structs.*
import com.wifi.toolbox.utils.AidlServiceHelper
import com.wifi.toolbox.utils.ShizukuUtil
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.scan
import kotlin.math.pow

/**
 * 负责管理全部任务排队运行
 */
class PojieTaskManager(
    private val service: PojieService,
    private val scope: CoroutineScope,
    private val worker: ConnectWorker
) {

    private var mainLoopJob: Job? = null
    private var currentWorkerJob: Job? = null
    private var settingsMonitorJob: Job? = null

    @Volatile
    private var currentWorkingSsid: String? = null
    private val handledSsids = mutableSetOf<String>()

    fun isWorking(): Boolean = mainLoopJob != null && mainLoopJob!!.isActive

    fun cancelCurrentJob() {
        settingsMonitorJob?.cancel()
        currentWorkerJob?.cancel()
        mainLoopJob?.cancel()
    }

    fun startLoop(settings: PojieSettings) {
        val app = service.applicationContext as ToolboxApp
        val safeScope = scope + SupervisorJob()

        settingsMonitorJob = safeScope.launch {
            try {
                while (isActive) {
                    if (settings.connectMode != 4) {
                        if (AidlServiceHelper.executeCommandSync(
                                app,
                                "am force-stop com.android.settings"
                            ).exitCode != 0
                        ) {
                            ShizukuUtil.executeCommandSync("am force-stop com.android.settings")
                        }
                    }
                    delay(250)
                }
            } finally {
            }
        }

        mainLoopJob = scope.launch {
            launch {
                snapshotFlow { app.runningPojieTasks.toList() }
                    .scan(listOf<PojieRunInfo>()) { old, new ->
                        val removed = old.map { it.ssid }.toSet() - new.map { it.ssid }.toSet()
                        removed.forEach {
                            if (!handledSsids.remove(it)) {
                                if (worker.forgetNetwork(settings, it)) {
                                    service.log(service.getString(R.string.forget_network_log, it))
                                } else {
                                    service.log(
                                        service.getString(
                                            R.string.forget_network_failed,
                                            it
                                        )
                                    )
                                }
                            }
                        }

                        val targetSsid = currentWorkingSsid
                        val isCurrentTaskRemoved =
                            targetSsid != null && new.none { it.ssid == targetSsid }
                        if (isCurrentTaskRemoved || new.isEmpty() || new.size > old.size) {
                            currentWorkerJob?.cancel()
                        }
                        new
                    }
                    .collect()
            }

            while (isActive) {
                try {
                    if (app.runningPojieTasks.isEmpty()) {
                        service.stop()
                        break
                    }
                    val task = findNextReadyTask(app)
                    if (task == null) {
                        handleCooldown(app)
                        continue
                    }
                    executeTaskAttempt(app, task, settings)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    service.log(service.getString(R.string.error_string, e.message ?: ""))
                    delay(100)
                }
            }
        }
    }

    private suspend fun handleCooldown(app: ToolboxApp): Boolean {
        val now = System.currentTimeMillis()
        val tasks = app.runningPojieTasks
        if (tasks.isEmpty()) return false

        val nextAvailableTime = tasks.minOf {
            val waitTime = if (it.retryCount > 0) {
                (2.0.pow(it.retryCount.toDouble() - 1).toLong()) * app.pojieConfig.doublingBase
            } else 0L
            it.lastTryTime + waitTime
        }

        val waitMs = maxOf(0L, nextAvailableTime - now)
        if (waitMs > 0) {
            val cooldownJob = scope.launch {
                service.log(service.getString(R.string.cooldown_log, waitMs))
                PojieNotification.update(
                    context = service,
                    contentText = service.getString(R.string.cooldown_status),
                    subText = ""
                )
                delay(waitMs)
            }
            currentWorkerJob = cooldownJob
            try {
                cooldownJob.join()
            } catch (_: CancellationException) {
                return false
            } finally {
                currentWorkerJob = null
            }
        }
        return true
    }

    private fun findNextReadyTask(app: ToolboxApp): PojieRunInfo? {
        val now = System.currentTimeMillis()
        val config = app.pojieConfig
        return app.runningPojieTasks.filter {
            val waitTime = if (it.retryCount > 0) {
                (2.0.pow(it.retryCount.toDouble() - 1).toLong()) * config.doublingBase
            } else 0L
            now - it.lastTryTime >= waitTime
        }.minByOrNull { -(System.currentTimeMillis() - it.lastTryTime) }
    }

    private suspend fun executeTaskAttempt(
        app: ToolboxApp,
        task: PojieRunInfo,
        settings: PojieSettings
    ) {
        app.runningPojieTasks.forEach {
            app.pojieTask.edit(it.ssid) { state ->
                state.copy(textTip = service.getString(if (it.ssid == task.ssid) R.string.task_attempting else R.string.task_queuing))
            }
        }

        currentWorkingSsid = task.ssid
        val currentPass =
            task.tryList.getOrNull(task.tryIndex) ?: service.getString(R.string.unknown)

        app.pojieTask.edit(task.ssid) {
            it.copy(
                textTip = service.getString(R.string.task_attempting_with_pass, currentPass),
                lastTryTime = System.currentTimeMillis()
            )
        }

        service.log(
            service.getString(
                R.string.task_log_format,
                worker.getLogTime(),
                task.ssid,
                currentPass
            ), true
        )

        val totalRemainingTime = app.runningPojieTasks
            .map {
                it.let { info ->
                    PojieRunInfo.calculateAverageSpeed(info)
                        ?.times(info.tryList.size - info.tryIndex)
                }
            }
            .let { list -> if (list.any { it == null }) null else list.filterNotNull().sum() }

        PojieNotification.update(
            context = service,
            contentText = service.getString(R.string.notif_attempting, task.ssid, currentPass),
            subText = formatDuration(app, totalRemainingTime)
        )

        val startTime = System.currentTimeMillis()
        val deferredResult = scope.async(Dispatchers.Default) {
            try {
                worker.performTaskLogic(app, SinglePojieTask(task.ssid, currentPass), settings)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                service.log(service.getString(R.string.error_string, e.message ?: ""))
                SinglePojieTask.RESULT_ERROR
            }
        }

        currentWorkerJob = deferredResult
        var taskResult: Int
        try {
            taskResult = deferredResult.await()
        } catch (_: CancellationException) {
            taskResult = SinglePojieTask.RESULT_CANCEL
            deferredResult.cancel()
        } catch (_: Exception) {
            taskResult = SinglePojieTask.RESULT_ERROR
        }

        val duration = System.currentTimeMillis() - startTime
        handleAttemptResult(app, task, currentPass, taskResult, settings, duration)
        currentWorkingSsid = null
        currentWorkerJob = null
    }

    fun formatDuration(context: Context, ms: Long?): String {
        if (ms == null) {
            return context.getString(R.string.calculating_time)
        }
        val totalMinutes = ms / 60000
        if (totalMinutes < 1) return context.getString(R.string.time_unit_less_than_minute)

        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60

        val result = mutableListOf<String>()
        if (days > 0) result.add(context.getString(R.string.time_unit_day, days))
        if (hours > 0 || days > 0) result.add(context.getString(R.string.time_unit_hour, hours))
        result.add(context.getString(R.string.time_unit_minute, minutes))

        return context.getString(R.string.remaining_time_format, result.joinToString(" "))
    }

    private fun handleAttemptResult(
        app: ToolboxApp,
        task: PojieRunInfo,
        pass: String,
        result: Int,
        settings: PojieSettings,
        duration: Long
    ) {
        val timeTag = worker.getLogTime()
        var resultStr = service.getString(
            when (result) {
                SinglePojieTask.RESULT_CANCEL -> R.string.result_interrupted
                SinglePojieTask.RESULT_SUCCESS -> R.string.result_success
                SinglePojieTask.RESULT_FAILED -> R.string.result_failed
                SinglePojieTask.RESULT_TIMEOUT -> R.string.result_timeout
                SinglePojieTask.RESULT_ERROR_TRANSIENT -> R.string.result_rejected
                else -> R.string.result_error
            }
        )
        if (result == SinglePojieTask.RESULT_FAILED) resultStr += app.getString(
            R.string.fail_time,
            duration
        )

        app.logState.setLine(
            service.getString(
                R.string.result_log_format,
                timeTag,
                task.ssid,
                pass,
                resultStr
            )
        )

        when (result) {
            SinglePojieTask.RESULT_CANCEL -> app.finishedPojieTasksTip[task.ssid] =
                service.getString(R.string.tip_interrupted, task.tryIndex)

            SinglePojieTask.RESULT_SUCCESS -> app.finishedPojieTasksTip[task.ssid] =
                service.getString(R.string.tip_success, pass)

            else -> {
                if (result != SinglePojieTask.RESULT_FAILED && result != SinglePojieTask.RESULT_TIMEOUT && result != SinglePojieTask.RESULT_ERROR_TRANSIENT) {
                    app.finishedPojieTasksTip[task.ssid] =
                        service.getString(R.string.tip_error_view_output)
                }
            }
        }

        worker.cleanConnection(settings)

        if (result == SinglePojieTask.RESULT_CANCEL) return

        when (result) {
            SinglePojieTask.RESULT_SUCCESS -> {
                service.log("${service.getString(R.string.result_success)}: (${task.ssid}, $pass)")
                app.pojieHistory.addOrUpdateHistory(
                    PojieHistoryItem(
                        task.ssid,
                        task.tryList,
                        task.tryIndex,
                        pass
                    )
                )
                handledSsids.add(task.ssid)
                app.pojieTask.stop(task.ssid)
            }

            SinglePojieTask.RESULT_FAILED -> {
                processTaskCompletion(app, task.ssid)
                app.pojieTask.edit(task.ssid) {
                    val newList = it.costList.toMutableList().apply {
                        add(duration)
                        if (size > 20) removeAt(0)
                    }
                    it.copy(
                        costList = newList,
                        retryCount = 0
                    )
                }
            }

            SinglePojieTask.RESULT_TIMEOUT, SinglePojieTask.RESULT_ERROR_TRANSIENT -> {
                app.pojieTask.edit(task.ssid) {
                    val newList = it.costList.toMutableList()
                    if (newList.isNotEmpty()) {
                        newList[newList.size - 1] += duration
                    }
                    it.copy(
                        costList = newList,
                        retryCount = it.retryCount + 1
                    )
                }
                getTask(app, task.ssid)?.let { updated ->
                    if (app.pojieConfig.retryCountType <= 5 && updated.retryCount > app.pojieConfig.retryCountType) {
                        app.pojieTask.edit(task.ssid) { it.copy(retryCount = 0) }
                        processTaskCompletion(app, task.ssid)
                    }
                }
            }

            else -> app.pojieTask.stop(task.ssid)
        }

        scope.launch(Dispatchers.IO) { updateHistory(app, task.ssid) }
    }

    private fun processTaskCompletion(app: ToolboxApp, ssid: String) {
        val task = getTask(app, ssid) ?: return
        val nextIndex = task.tryIndex + 1
        app.pojieTask.edit(ssid) { it.copy(tryIndex = nextIndex) }
        if (nextIndex >= task.tryList.size) {
            app.finishedPojieTasksTip[ssid] =
                service.getString(R.string.tip_all_failed, task.tryList.size)
            app.pojieTask.stop(ssid)
        }
    }

    private fun getTask(app: ToolboxApp, ssid: String): PojieRunInfo? =
        app.runningPojieTasks.find { it.ssid == ssid }

    private fun updateHistory(app: ToolboxApp, ssid: String) {
        getTask(app, ssid)?.let { task ->
            app.pojieHistory.addOrUpdateHistory(
                PojieHistoryItem(
                    task.ssid,
                    task.tryList,
                    task.tryIndex
                )
            )
        }
    }
}