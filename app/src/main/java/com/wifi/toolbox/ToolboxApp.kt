package com.wifi.toolbox

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.content.Intent
import com.wifi.toolbox.app.AppAidl
import com.wifi.toolbox.app.AppCrash
import com.wifi.toolbox.app.AppPojieTask
import com.wifi.toolbox.app.AppShizuku
import com.wifi.toolbox.app.AppUI
import com.wifi.toolbox.utils.ActivityStack
import com.wifi.toolbox.utils.GuardStats
import com.wifi.toolbox.utils.PojieHistoryManager
import com.wifi.toolbox.utils.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class ToolboxApp : Application() {

    companion object {
        lateinit var instance: ToolboxApp
            private set
    }


    data class AlertDialogData(val title: String, val text: String)
    data class SnackbarData(
        val message: String,
        val actionLabel: String? = null,
        val onActionClick: (() -> Unit)? = null
    )

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var appCrash: AppCrash
    lateinit var shizuku: AppShizuku
    lateinit var ui: AppUI
    lateinit var pojieTask: AppPojieTask
    lateinit var aidl: AppAidl
    lateinit var settings: SettingsManager
    lateinit var pojieHistory: PojieHistoryManager
    lateinit var guardStats: GuardStats

    val pojieConfig get() = pojieTask.pojieConfig
    val logState get() = pojieTask.logState
    val alertDialogState get() = ui.alertFlow
    val snackbarState get() = ui.snackFlow
    val runningPojieTasks get() = pojieTask.TaskRunList
    val finishedPojieTasksTip get() = pojieTask.TaskEndTip

    override fun onCreate() {
        super.onCreate()
        instance = this

        appCrash = AppCrash(this)
        shizuku = AppShizuku(appScope)
        ui = AppUI(this, appScope)
        aidl = AppAidl(this)
        pojieTask = AppPojieTask(this)
        settings = SettingsManager(this)
        pojieHistory = PojieHistoryManager(this)
        guardStats = GuardStats(this)

        appCrash.startCatch()
        shizuku.init()
        if (settings.global.startAidlServiceOnBoot) aidl.startAIDLServiceRoot()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = ActivityStack.register(activity)
            override fun onActivityPaused(activity: Activity) = ActivityStack.unregister()
            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: android.os.Bundle?
            ) {
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: android.os.Bundle
            ) {
            }

            override fun onActivityDestroyed(activity: Activity) {}
        })
    }


    override fun onTerminate() {
        super.onTerminate()
        shizuku.removeListener()
        appScope.cancel()
    }

    fun restart() {
        val restartIntent =
            this.packageManager.getLaunchIntentForPackage(this.packageName)
        this.startActivity(
            Intent.makeRestartActivityTask(restartIntent?.component)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) },
            ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
        )
        Runtime.getRuntime().exit(0)
    }


    fun alert(title: String, text: String) = ui.alert(title, text)
}