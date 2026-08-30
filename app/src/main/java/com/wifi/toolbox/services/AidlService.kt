@file:Suppress("DEPRECATION")
package com.wifi.toolbox.services

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import com.wifi.toolbox.IToolboxService
import com.wifi.toolbox.IToolboxCallback
import com.wifi.toolbox.utils.CommandRunner
import com.wifi.toolbox.utils.ShizukuUtil.getWifiMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class AidlService : RootService() {
    private val TAG = "AidlService"
    private var expectedUid = -1

    private val taskMap = ConcurrentHashMap<Int, Runnable>()
    private val taskIdGenerator = AtomicInteger(1)

    inner class ToolboxIPC : IToolboxService.Stub() {

        private fun checkCaller() {
            val callingUid = getCallingUid()
            if (expectedUid != -1 && callingUid != expectedUid) {
                throw Exception("Permission Denied")
            }
        }

        private fun asInterface(className: String, name: String): Any {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, name) as IBinder
            return Class.forName("$className\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        }

        override fun getUid(): Int {
            checkCaller()
            return Process.myUid()
        }

        override fun pressPowerKey() {
            checkCaller()
            try {
                val input = asInterface("android.hardware.input.IInputManager", "input")
                val inject = input.javaClass.getMethod(
                    "injectInputEvent",
                    android.view.InputEvent::class.java,
                    Int::class.javaPrimitiveType
                )
                val now = android.os.SystemClock.uptimeMillis()
                val eventDown = android.view.KeyEvent(
                    now,
                    now,
                    android.view.KeyEvent.ACTION_DOWN,
                    android.view.KeyEvent.KEYCODE_POWER,
                    0
                )
                val eventUp = android.view.KeyEvent(
                    now,
                    now,
                    android.view.KeyEvent.ACTION_UP,
                    android.view.KeyEvent.KEYCODE_POWER,
                    0
                )
                inject.invoke(input, eventDown, 2)
                inject.invoke(input, eventUp, 2)
            } catch (e: Exception) {
                Log.e(TAG, "PowerKey Error", e)
            }
        }

        val PACKAGE_NAME = "android"

        override fun setMediaVolumeMax() {
            checkCaller()
            val audioService = asInterface("android.media.IAudioService", "audio")
            val maxVolume =
                audioService.javaClass.getMethod("getStreamMaxVolume", Int::class.javaPrimitiveType)
                    .invoke(audioService, 3) as Int
            audioService.javaClass.getMethod(
                "setStreamVolume",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
                .invoke(audioService, 3, maxVolume, 0, PACKAGE_NAME)
        }

        override fun setWifiEnabled(enabled: Boolean) {
            checkCaller()
            val wifiService = getWifiService()
            val method = getWifiMethod(wifiService, "setWifiEnabled")
            when (method.parameterTypes.size) {
                3 -> method.invoke(wifiService, PACKAGE_NAME, enabled, Bundle())
                2 -> method.invoke(wifiService, PACKAGE_NAME, enabled)
                1 -> method.invoke(wifiService, enabled)
            }
        }

        override fun connectToWifi(ssid: String, password: String): Int {
            checkCaller()
            val wifiService = getWifiService()
            val wifiConfig =
                Class.forName("android.net.wifi.WifiConfiguration").getDeclaredConstructor()
                    .newInstance()
            wifiConfig.javaClass.getField("SSID").set(wifiConfig, "\"$ssid\"")
            val keyMgmt = java.util.BitSet()
            if (password.isEmpty()) {
                keyMgmt.set(0)
            } else {
                wifiConfig.javaClass.getField("preSharedKey").set(wifiConfig, "\"$password\"")
                keyMgmt.set(1)
            }
            wifiConfig.javaClass.getField("allowedKeyManagement").set(wifiConfig, keyMgmt)

            val addMethod = getWifiMethod(wifiService, "addOrUpdateNetwork")
            val netId = when (addMethod.parameterTypes.size) {
                3 -> addMethod.invoke(wifiService, wifiConfig, PACKAGE_NAME, Bundle())
                2 -> addMethod.invoke(wifiService, wifiConfig, PACKAGE_NAME)
                1 -> addMethod.invoke(wifiService, wifiConfig)
                else -> -1
            } as Int

            if (netId != -1) {
                val enableMethod = getWifiMethod(wifiService, "enableNetwork")
                if (enableMethod.parameterTypes.size == 3) enableMethod.invoke(
                    wifiService,
                    netId,
                    true,
                    PACKAGE_NAME
                )
                else enableMethod.invoke(wifiService, netId, true)
            }
            return netId
        }

        override fun enableNetwork(netId: Int) {
            checkCaller()
            val wifiService = getWifiService()
            val enableMethod = getWifiMethod(wifiService, "enableNetwork")
            if (enableMethod.parameterTypes.size == 3) enableMethod.invoke(
                wifiService,
                netId,
                true,
                PACKAGE_NAME
            )
            else enableMethod.invoke(wifiService, netId, true)
        }

        override fun disconnectWifi() {
            checkCaller()
            val wifiService = getWifiService()
            val infoMethod = getWifiMethod(wifiService, "getConnectionInfo")
            val wifiInfo = when (infoMethod.parameterTypes.size) {
                2 -> infoMethod.invoke(wifiService, PACKAGE_NAME, null)
                1 -> infoMethod.invoke(wifiService, PACKAGE_NAME)
                else -> infoMethod.invoke(wifiService)
            } as? android.net.wifi.WifiInfo

            wifiInfo?.networkId?.takeIf { it != -1 }?.let { id ->
                try {
                    getWifiMethod(wifiService, "allowAutojoin").invoke(wifiService, id, false)
                } catch (_: Exception) {
                    val dm = getWifiMethod(wifiService, "disableNetwork")
                    if (dm.parameterTypes.size == 2) dm.invoke(
                        wifiService,
                        id,
                        PACKAGE_NAME
                    ) else dm.invoke(wifiService, id)
                }
            }
            val disMethod = getWifiMethod(wifiService, "disconnect")
            if (disMethod.parameterTypes.size == 1) disMethod.invoke(
                wifiService,
                PACKAGE_NAME
            ) else disMethod.invoke(wifiService)
        }

        override fun forgetNetwork(netId: Int) {
            checkCaller()
            val wifiService = getWifiService()
            if (Build.VERSION.SDK_INT <= 29) {
                val m = getWifiMethod(wifiService, "removeNetwork")
                if (m.parameterTypes.size == 2) m.invoke(
                    wifiService,
                    netId,
                    PACKAGE_NAME
                ) else m.invoke(wifiService, netId)
            } else {
                val m = getWifiMethod(wifiService, "forget")
                if (m.parameterTypes.size == 4) m.invoke(
                    wifiService,
                    netId,
                    null,
                    null,
                    0
                ) else m.invoke(wifiService, netId, null)
            }
        }

        override fun startWifiScan(allowUseCommand: Boolean): Boolean {
            checkCaller()
            val wifiService = getWifiService()
            val m = getWifiMethod(wifiService, "startScan")
            val result = try {
                when {
                    Build.VERSION.SDK_INT >= 30 -> m.invoke(
                        wifiService,
                        PACKAGE_NAME,
                        null
                    ) as Boolean

                    Build.VERSION.SDK_INT >= 28 -> m.invoke(wifiService, PACKAGE_NAME) as Boolean
                    else -> {
                        m.invoke(wifiService, null, null); true
                    }
                }
            } catch (_: Exception) {
                false
            }

            if (!result && allowUseCommand) {
                return com.topjohnwu.superuser.Shell.cmd("cmd wifi start-scan").exec().isSuccess
            }
            return result
        }

        override fun getSavedWifiList(): List<Bundle> {
            checkCaller()
            return getSavedWifiList_().map { config ->
                Bundle().apply {
                    putString("SSID", config.SSID)
                    putInt("networkId", config.networkId)
                    putString("preSharedKey", config.preSharedKey)
                }
            }
        }

        fun getSavedWifiListOld(): List<WifiConfiguration> {
            val wifiService = getWifiService()

            val results = mutableListOf<WifiConfiguration>()
            val wifiConfigurationClass = Class.forName("android.net.wifi.WifiConfiguration")
            val getMethod = getWifiMethod(wifiService, "getConfiguredNetworks")

            val rawResult = when (getMethod.parameterTypes.size) {
                3 -> getMethod.invoke(wifiService, PACKAGE_NAME, null, false)
                2 -> getMethod.invoke(wifiService, PACKAGE_NAME, Bundle())
                1 -> getMethod.invoke(wifiService, PACKAGE_NAME)
                0 -> getMethod.invoke(wifiService)
                else -> null
            } ?: return emptyList()

            @Suppress("UNCHECKED_CAST")
            val configuredNetworksList = if (rawResult is List<*>) {
                rawResult as List<Any>
            } else {
                val getListMethod = rawResult.javaClass.getMethod("getList")
                getListMethod.invoke(rawResult) as List<Any>
            }

            if (configuredNetworksList.isEmpty()) return results
            val networkIdField = wifiConfigurationClass.getField("networkId")
            val ssidFieldConfig = try {
                wifiConfigurationClass.getField("SSID")
            } catch (_: Exception) {
                null
            }

            val seenIds = HashSet<Int>()
            configuredNetworksList.forEach { config ->
                val networkId = networkIdField.get(config) as Int
                if (seenIds.contains(networkId)) return@forEach
                seenIds.add(networkId)
                var ssidValue = ""
                if (ssidFieldConfig != null) {
                    ssidValue = try {
                        ssidFieldConfig.get(config)?.toString() ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                }
                if (ssidValue.length >= 2 && ssidValue.startsWith("\"") && ssidValue.endsWith("\"")) {
                    ssidValue = ssidValue.substring(1, ssidValue.length - 1)
                }
                results.add(config as WifiConfiguration)
            }
            return results
        }

        /**
         * 获取已保存的Wifi列表
         * 此代码借鉴（ctrlCV）自zacharee/WiFiList项目
         * @return 获取到的wifi列表List<WifiConfiguration>
         * */
        @SuppressLint("PrivateApi")
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        fun getSavedWifiList_(): List<WifiConfiguration> {
            try {
                val base = Class.forName("android.net.wifi.IWifiManager")
                val iwm = getWifiService()

                val user = when (uid) {
                    0 -> "root"
                    1000 -> "system"
                    else -> "shell"
                }

                val privilegedConfigs = when {
                    Build.VERSION.SDK_INT >= 33 -> {
                        val method = base.getMethod(
                            "getPrivilegedConfiguredNetworks",
                            String::class.java,
                            String::class.java,
                            Bundle::class.java
                        )
                        method.invoke(iwm, user, PACKAGE_NAME, Bundle().apply {
                            putParcelable(
                                "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE",
                                AttributionSource::class.java.getConstructor(
                                    Int::class.java,
                                    String::class.java,
                                    String::class.java,
                                    Set::class.java,
                                    AttributionSource::class.java
                                ).newInstance(
                                    uid,
                                    PACKAGE_NAME,
                                    PACKAGE_NAME,
                                    null as Set<String>?,
                                    null
                                )
                            )
                        })
                    }

                    Build.VERSION.SDK_INT >= 29 -> {
                        //注：这里测试android10模拟器获取不到（也许这就是为什么WifiList的minsdk是30吧…）
                        try {
                            try {
                                base.getMethod(
                                    "getPrivilegedConfiguredNetworks",
                                    String::class.java
                                )
                                    .invoke(iwm, PACKAGE_NAME)
                            } catch (_: NoSuchMethodException) {
                                base.getMethod(
                                    "getPrivilegedConfiguredNetworks",
                                    String::class.java,
                                    String::class.java
                                )
                                    .invoke(iwm, user, PACKAGE_NAME)
                            }
                        } catch (_: NoSuchMethodException) {
                            base.getMethod(
                                "getPrivilegedConfiguredNetworks",
                                String::class.java,
                                String::class.java,
                                Bundle::class.java
                            )
                                .invoke(iwm, user, PACKAGE_NAME, null)
                        }
                    }

                    else -> {
                        try {
                            base.getMethod("getPrivilegedConfiguredNetworks", String::class.java)
                                .invoke(iwm, PACKAGE_NAME)
                        } catch (_: NoSuchMethodException) {
                            try {
                                base.getMethod("getPrivilegedConfiguredNetworks").invoke(iwm)
                            } catch (_: NoSuchMethodException) {
                                base.getMethod("getConfiguredNetworks").invoke(iwm)
                            }
                        }
                    }
                }

                val resultList = when (privilegedConfigs) {
                    is List<*> -> privilegedConfigs as List<WifiConfiguration>
                    null -> listOf()
                    else -> {
                        try {
                            privilegedConfigs::class.java.getMethod("getList")
                                .invoke(privilegedConfigs) as List<WifiConfiguration>
                        } catch (_: Exception) {
                            listOf()
                        }
                    }
                }
                if (resultList.isEmpty()) throw Exception("empty list")
                return resultList.distinctBy { it.networkId }
            } catch (e: Exception) {
                //这里兜个底，密码不要了其实也可以的
                Log.w("ShizukuUtil", e.stackTraceToString())
                return getSavedWifiListOld()
            }
        }

        override fun getNetIdBySsid(ssid: String): Int {
            checkCaller()
            val list = getSavedWifiList()
            return list.find { it.getString("ssid")?.removeSurrounding("\"") == ssid }
                ?.getInt("netId") ?: -1
        }

        override fun getWifiScanResults(): List<Bundle> {
            checkCaller()
            val wifiService = getWifiService()
            val getResultsMethod = getWifiMethod(wifiService, "getScanResults")
            val raw = when (getResultsMethod.parameterTypes.size) {
                2 -> getResultsMethod.invoke(wifiService, PACKAGE_NAME, null)
                1 -> getResultsMethod.invoke(wifiService, PACKAGE_NAME)
                else -> null
            } ?: return emptyList()
            val scanResults = if (raw is List<*>) raw as List<Any>
            else raw.javaClass.getMethod("getList").invoke(raw) as List<Any>
            return scanResults.map { result ->
                Bundle().apply {
                    putString(
                        "ssid",
                        result.javaClass.getField("SSID").get(result)?.toString() ?: ""
                    )
                    putString(
                        "bssid",
                        result.javaClass.getField("BSSID").get(result)?.toString() ?: ""
                    )
                    putInt("level", result.javaClass.getField("level").get(result) as Int)
                    putString(
                        "capabilities",
                        result.javaClass.getField("capabilities").get(result)?.toString() ?: ""
                    )
                }
            }
        }

        override fun stopCommand(taskId: Int) {
            checkCaller()
            taskMap.remove(taskId)?.run()
        }

        override fun executeCommand(command: String, callback: IToolboxCallback): Int {
            checkCaller()
            val taskId = taskIdGenerator.getAndIncrement()

            val stopFunc = CommandRunner.executeCommand(
                command, false,
                onOutputReceived = {
                    callback.onOutput(it)
                }, onCommandFinished = {
                    taskMap.remove(taskId)
                    callback.onFinished(it.output, it.exitCode)
                })

            taskMap[taskId] = stopFunc
            return taskId
        }

        private fun getWifiService(): Any = asInterface("android.net.wifi.IWifiManager", "wifi")
    }

    override fun onCreate() {
        Log.d(TAG, "ToolboxIPC: onCreate")
    }

    override fun onRebind(intent: Intent) {
        Log.d(TAG, "ToolboxIPC: onRebind")
    }

    override fun onBind(intent: Intent): IBinder {
        expectedUid = intent.getIntExtra("caller_uid", -1)
        Log.d(TAG, "ToolboxIPC: onBind, UID: $expectedUid")
        return ToolboxIPC()
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.d(TAG, "ToolboxIPC: onUnbind")
        return true
    }

    override fun onDestroy() {
        Log.d(TAG, "ToolboxIPC: onDestroy")
    }
}