package com.wifi.toolbox.services

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.services.pojie.*
import com.wifi.toolbox.structs.PojieSettings
import kotlinx.coroutines.*

class PojieService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var sharedPreferences: SharedPreferences
    private var _pojieSettings: PojieSettings? = null

    private val pojieSettings: PojieSettings
        get() = _pojieSettings ?: PojieSettings.from(sharedPreferences).also { _pojieSettings = it }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        _pojieSettings = PojieSettings.from(prefs)
    }

    private lateinit var connectionWorker: ConnectWorker
    private lateinit var taskDispatcher: PojieTaskManager

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("settings_pojie", MODE_PRIVATE)
        sharedPreferences.registerOnSharedPreferenceChangeListener(prefsListener)
        connectionWorker = ConnectWorker(this)
        taskDispatcher = PojieTaskManager(this, serviceScope, connectionWorker)
    }

    fun log(log: String, allowEdit: Boolean = false) {
        (applicationContext as ToolboxApp).logState.addLog(log, allowEdit)
    }

    fun stopAllTasks() {
        val app = applicationContext as ToolboxApp
        app.runningPojieTasks.clear()
        taskDispatcher.cancelCurrentJob()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as ToolboxApp
        try {
            PojieNotification.createChannel(this)
            val notification = PojieNotification.buildForeground(this)
            startForeground(1, notification)

            if (!taskDispatcher.isWorking()) {
                app.logState.clear()
                log(getAsciiArt())

                connectionWorker.initLogServices(pojieSettings)
                taskDispatcher.startLoop(pojieSettings)
            }
            return START_STICKY
        } catch (e: Exception) {
            stopAllTasks()
            log(getString(R.string.service_start_failed))
            log(e.toString())
            stop()
            return START_NOT_STICKY
        }
    }

    private fun getAsciiArt(): String {
        return """      _      __                 _        
     | |___ / _|_ __ ___  _   _| |_ __ _ 
  _  | / __| |_| '_ ` _ \| | | | __/ _` |
 | |_| \__ \  _| | | | | | |_| | || (_| |
  \___/|___/_| |_| |_| |_|\__, |\__\__, |
                          |___/    |___/ 
==========================================
${getString(R.string.pojie_service_description)}
"""
    }

    fun stop() {
        log(getString(R.string.run_finished))
        taskDispatcher.cancelCurrentJob()
        connectionWorker.closeServices()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(prefsListener)
        stopForeground(STOP_FOREGROUND_REMOVE)
        serviceScope.cancel()
    }
}