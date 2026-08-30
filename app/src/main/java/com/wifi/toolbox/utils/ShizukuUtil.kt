@file:Suppress("DEPRECATION")

package com.wifi.toolbox.utils

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.*
import com.wifi.toolbox.structs.*
import rikka.shizuku.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer
import org.lsposed.hiddenapibypass.*
import java.util.BitSet
import android.os.WorkSource
import android.util.Log
import java.lang.reflect.Method

object ShizukuUtil {

    const val REQUEST_PERMISSION_CODE = 1001

    fun initialize(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sharedPreferences =
                context.getSharedPreferences("settings_global", Context.MODE_PRIVATE)
            var hiddenApiBypassOption: Int
            try {
                hiddenApiBypassOption = sharedPreferences.getInt("hidden_api_bypass", 1)
            } catch (_: ClassCastException) {
                val stringValue = sharedPreferences.getString("hidden_api_bypass", null)
                hiddenApiBypassOption = stringValue?.toIntOrNull() ?: 1
            }

            when (hiddenApiBypassOption) {
                1 -> LSPass.addHiddenApiExemptions("")
                2 -> HiddenApiBypass.addHiddenApiExemptions("")
            }
        }
    }

    const val PACKAGE_NAME = "com.android.shell"

    private fun asInterface(className: String, original: IBinder): Any {
        return Class.forName("$className\$Stub").run {
            getMethod("asInterface", IBinder::class.java).invoke(
                null,
                ShizukuBinderWrapper(original)
            )!!
        }
    }

    /**
     * 下面是一些奇奇怪怪的测试，应用用不到，用来检查功能正不正常
     */

    fun lookScreen() {
        val inputManagerBinder = SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)
        val input = asInterface("android.hardware.input.IInputManager", inputManagerBinder)
        val inject = input::class.java.getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.java
        )
        val now = SystemClock.uptimeMillis()
        val injectMode = 2
        inject.invoke(
            input, KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_POWER, 0), injectMode
        )
        inject.invoke(
            input, KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_POWER, 0), injectMode
        )
    }

    fun setMediaVolumeMax() {
        val audioManagerBinder = SystemServiceHelper.getSystemService(Context.AUDIO_SERVICE)
        val audioService = asInterface("android.media.IAudioService", audioManagerBinder)

        val getStreamMaxVolumeMethod = audioService::class.java.getMethod(
            "getStreamMaxVolume", Int::class.java
        )
        val maxVolume =
            getStreamMaxVolumeMethod.invoke(audioService, AudioManager.STREAM_MUSIC) as Int

        val setStreamVolumeMethod = audioService::class.java.getMethod(
            "setStreamVolume", Int::class.java, Int::class.java, Int::class.java, String::class.java
        )
        setStreamVolumeMethod.invoke(
            audioService,
            AudioManager.STREAM_MUSIC,
            maxVolume,
            0,
            PACKAGE_NAME
        )
    }

    /**
     * 下面是应用操作wifi的核心功能
     */

    /**
     * ### setWifiEnabled
     *
     * * **Android 7 - 16**: 传入 `(String packageName, boolean enable)`，返回 `boolean`。
     *
     * ### removeNetwork
     *
     * * **Android 7 - 8**: 传入 `(int netId)`，返回 `boolean`。
     * * **Android 9 - 16**: 传入 `(int netId, String packageName)`，返回 `boolean`。
     *
     * ### addOrUpdateNetwork
     *
     * * **Android 7 - 8**: 传入 `(WifiConfiguration config)`，返回 `int`。
     * * **Android 9 - 12**: 传入 `(WifiConfiguration config, String packageName)`，返回 `int`。
     * * **Android 13 - 16**: 传入 `(WifiConfiguration config, String packageName, Bundle extras)`，返回 `int`。
     *
     * ### enableNetwork
     *
     * * **Android 7 - 8**: 传入 `(int netId, boolean disableOthers)`，返回 `boolean`。
     * * **Android 9 - 16**: 传入 `(int netId, boolean disableOthers, String packageName)`，返回 `boolean`。
     *
     * ### disableNetwork
     *
     * * **Android 7 - 8**: 传入 `(int netId)`，返回 `boolean`。
     * * **Android 9 - 16**: 传入 `(int netId, String packageName)`，返回 `boolean`。
     *
     * ### getConnectionInfo
     *
     * * **Android 7**: 传入 `()`，返回 `WifiInfo`。
     * * **Android 8 - 10**: 传入 `(String callingPackage)`，返回 `WifiInfo`。
     * * **Android 11 - 16**: 传入 `(String callingPackage, String callingFeatureId)`，返回 `WifiInfo`。
     *
     * ### allowAutojoin
     *
     * * **Android 7 - 10**: 未定义。
     * * **Android 11 - 16**: 传入 `(int netId, boolean choice)`，返回 `void`。
     *
     * ### disconnect
     *
     * * **Android 7 - 8**: 传入 `()`，返回 `void`。
     * * **Android 9**: 传入 `(String packageName)`，返回 `void`。
     * * **Android 10 - 16**: 传入 `(String packageName)`，返回 `boolean`。
     *
     * ### startScan
     *
     * * **Android 7**: 传入 `(ScanSettings requested, WorkSource ws)`，返回 `boolean`。
     * * **Android 8**: 传入 `(ScanSettings requested, WorkSource ws, String packageName)`，返回 `boolean`。
     * * **Android 9 - 10**: 传入 `(String packageName)`，返回 `boolean`。
     * * **Android 11 - 16**: 传入 `(String packageName, String featureId)`，返回 `boolean`。
     *
     * ### getScanResults
     *
     * * **Android 7 - 10**: 传入 `(String callingPackage)`，返回 `List<ScanResult>`。
     * * **Android 11 - 14**: 传入 `(String callingPackage, String callingFeatureId)`，返回 `List<ScanResult>`。
     * * **Android 15 - 16**: 传入 `(String callingPackage, String callingFeatureId)`，返回 `ParceledListSlice<ScanResult>`。
     *
     * ### getConfiguredNetworks
     *
     * * **Android 7**: 传入 `()`，返回 `List<WifiConfiguration>`。
     * * **Android 8 - 9**: 传入 `()`，返回 `ParceledListSlice<WifiConfiguration>`。
     * * **Android 10**: 传入 `(String packageName)`，返回 `ParceledListSlice<WifiConfiguration>`。
     * * **Android 11**: 传入 `(String packageName, String featureId)`，返回 `ParceledListSlice<WifiConfiguration>`。
     * * **Android 12 - 16**: 传入 `(String packageName, String featureId, boolean callerNetworksOnly)`，返回 `ParceledListSlice<WifiConfiguration>`。
     *
     *
     * ### forget
     *
     * * **Android 7 - 10**: 未定义。
     * * **Android 11**: 传入 `(int netId, IBinder binder, IActionListener listener, int callbackIdentifier)`，返回 `void` 。
     * * **Android 12 - 16**: 传入 `(int netId, IActionListener listener)`，返回 `void` 。
     */
    @SuppressLint("PrivateApi")
    fun getWifiMethod(wifiService: Any, methodName: String): Method {
        val clazz = wifiService::class.java
        val sdk = Build.VERSION.SDK_INT
        val stringClass = String::class.java
        val intType = Integer.TYPE
        val booleanType = java.lang.Boolean.TYPE
        val wifiConfigClass = Class.forName("android.net.wifi.WifiConfiguration")

        val method = when (methodName) {
            "setWifiEnabled" -> {
                try {
                    clazz.getMethod(methodName, stringClass, booleanType)
                } catch (_: Exception) {
                    clazz.getMethod(methodName, booleanType)//？？？源码的aidl情报有误
                }
            }

            "removeNetwork" -> {
                if (sdk >= 28) clazz.getMethod(methodName, intType, stringClass)
                else clazz.getMethod(methodName, intType)
            }

            "addOrUpdateNetwork" -> {
                when {
                    sdk >= 33 -> clazz.getMethod(
                        methodName,
                        wifiConfigClass,
                        stringClass,
                        Bundle::class.java
                    )

                    sdk >= 28 -> clazz.getMethod(methodName, wifiConfigClass, stringClass)
                    else -> clazz.getMethod(methodName, wifiConfigClass)
                }
            }

            "enableNetwork" -> {
                if (sdk >= 28) clazz.getMethod(methodName, intType, booleanType, stringClass)
                else clazz.getMethod(methodName, intType, booleanType)
            }

            "getConnectionInfo" -> {
                when {
                    sdk >= 30 -> clazz.getMethod(methodName, stringClass, stringClass)
                    sdk >= 26 -> clazz.getMethod(methodName, stringClass)
                    else -> clazz.getMethod(methodName)
                }
            }

            "allowAutojoin" -> {
                if (sdk >= 30) clazz.getMethod(methodName, intType, booleanType)
                else null
            }

            "disconnect" -> {
                if (sdk >= 28) clazz.getMethod(methodName, stringClass)
                else clazz.getMethod(methodName)
            }

            "startScan" -> {
                when {
                    sdk >= 30 -> clazz.getMethod(methodName, stringClass, stringClass)
                    sdk >= 28 -> clazz.getMethod(methodName, stringClass)
                    else -> {
                        val scanSettingsClass = Class.forName("android.net.wifi.ScanSettings")
                        when {
                            sdk >= 26 -> clazz.getMethod(
                                methodName,
                                scanSettingsClass,
                                WorkSource::class.java,
                                stringClass
                            )

                            else -> clazz.getMethod(
                                methodName,
                                scanSettingsClass,
                                WorkSource::class.java
                            )
                        }
                    }
                }
            }

            "getScanResults" -> {
                if (sdk >= 30) clazz.getMethod(methodName, stringClass, stringClass)
                else clazz.getMethod(methodName, stringClass)
            }

            "getConfiguredNetworks" -> {
                when {
                    sdk >= 31 -> clazz.getMethod(methodName, stringClass, stringClass, booleanType)
                    sdk == 30 -> clazz.getMethod(methodName, stringClass)
                    sdk == 29 -> clazz.getMethod(methodName, stringClass)
                    else -> clazz.getMethod(methodName)
                }
            }

            "disableNetwork" -> {
                if (sdk >= 28) clazz.getMethod(methodName, intType, stringClass)
                else clazz.getMethod(methodName, intType)
            }

            "forget" -> {
                when {
                    sdk >= 31 -> clazz.getMethod(
                        methodName,
                        intType,
                        Class.forName("android.net.wifi.IActionListener")
                    )

                    sdk == 30 -> clazz.getMethod(
                        methodName,
                        intType,
                        IBinder::class.java,
                        Class.forName("android.net.wifi.IActionListener"),
                        intType
                    )

                    else -> null
                }
            }

            else -> null
        }
        return method ?: throw NoSuchMethodException(methodName)
    }

    fun setWifiEnabled(enabled: Boolean) {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val method = getWifiMethod(wifiService, "setWifiEnabled")

        when (method.parameterTypes.size) {
            3 -> method.invoke(wifiService, PACKAGE_NAME, enabled, Bundle())
            2 -> method.invoke(wifiService, PACKAGE_NAME, enabled)
            1 -> method.invoke(wifiService, enabled)
        }
    }

    fun forgetNetwork(netId: Int) {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val sdk = Build.VERSION.SDK_INT
        if (sdk <= 29) {
            val removeMethod = getWifiMethod(wifiService, "removeNetwork")
            when (removeMethod.parameterTypes.size) {
                2 -> removeMethod.invoke(wifiService, netId, PACKAGE_NAME)
                1 -> removeMethod.invoke(wifiService, netId)
            }
        } else {
            val forgetMethod = getWifiMethod(wifiService, "forget")
            when (forgetMethod.parameterTypes.size) {
                4 -> forgetMethod.invoke(wifiService, netId, null, null, 0)
                2 -> forgetMethod.invoke(wifiService, netId, null)
            }
        }
    }

    fun getNetIdBySsid(ssid: String): Int {
        val savedNetworks: List<WifiConfiguration> = getSavedWifiList()
        return savedNetworks.find { it.SSID.removeSurrounding("\"") == ssid }?.networkId ?: -1
    }

    fun connectToWifi(ssid: String, password: String): Int {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val addMethod = getWifiMethod(wifiService, "addOrUpdateNetwork")

        val wifiConfigurationClass = Class.forName("android.net.wifi.WifiConfiguration")
        val wifiConfig = wifiConfigurationClass.getDeclaredConstructor().newInstance()
        wifiConfigurationClass.getField("SSID").set(wifiConfig, "\"$ssid\"")

        val keyMgmtBitSet = BitSet()
        if (password.isEmpty()) {
            keyMgmtBitSet.set(0)
        } else {
            wifiConfigurationClass.getField("preSharedKey").set(wifiConfig, "\"$password\"")
            keyMgmtBitSet.set(1)
        }
        wifiConfigurationClass.getField("allowedKeyManagement").set(wifiConfig, keyMgmtBitSet)

        val netId = when (addMethod.parameterTypes.size) {
            3 -> addMethod.invoke(wifiService, wifiConfig, PACKAGE_NAME, Bundle())
            2 -> addMethod.invoke(wifiService, wifiConfig, PACKAGE_NAME)
            1 -> addMethod.invoke(wifiService, wifiConfig)
            else -> -1
        } as Int

        if (netId == -1) throw RuntimeException("添加网络失败")

        enableNetwork(netId)
        return netId
    }

    fun enableNetwork(netId: Int) {
        val wifiManagerBinder = SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        val wifiService = asInterface("android.net.wifi.IWifiManager", wifiManagerBinder)

        val enableMethod = getWifiMethod(wifiService, "enableNetwork")
        when (enableMethod.parameterTypes.size) {
            3 -> enableMethod.invoke(wifiService, netId, true, PACKAGE_NAME)
            2 -> enableMethod.invoke(wifiService, netId, true)
        }

    }

    /**
     * 优雅地断开网络
     * */
    fun disconnectWifi() {
        val wifiService = asInterface(
            "android.net.wifi.IWifiManager",
            SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        )

        val infoMethod = getWifiMethod(wifiService, "getConnectionInfo")
        val wifiInfo = when (infoMethod.parameterTypes.size) {
            2 -> infoMethod.invoke(wifiService, PACKAGE_NAME, null)
            1 -> infoMethod.invoke(wifiService, PACKAGE_NAME)
            else -> infoMethod.invoke(wifiService)
        } as? android.net.wifi.WifiInfo

        val currentNetId = wifiInfo?.networkId ?: -1
        if (currentNetId != -1) {
            try {
                val allowAutojoinMethod = getWifiMethod(wifiService, "allowAutojoin")
                allowAutojoinMethod.invoke(wifiService, currentNetId, false)
            } catch (_: NoSuchMethodException) {
                val disableMethod = getWifiMethod(wifiService, "disableNetwork")
                if (disableMethod.parameterTypes.size == 2) {
                    disableMethod.invoke(wifiService, currentNetId, PACKAGE_NAME)
                } else {
                    disableMethod.invoke(wifiService, currentNetId)
                }
            } catch (_: Exception) {
            }
        }

        val disconnectMethod = getWifiMethod(wifiService, "disconnect")
        when (disconnectMethod.parameterTypes.size) {
            1 -> disconnectMethod.invoke(wifiService, PACKAGE_NAME)
            else -> disconnectMethod.invoke(wifiService)
        }
    }

    fun startWifiScan(allowUseCommand: Boolean = false): Boolean {
        val wifiService = asInterface(
            "android.net.wifi.IWifiManager",
            SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        )

        val scanMethod = getWifiMethod(wifiService, "startScan")
        val sdk = Build.VERSION.SDK_INT

        val scanInitiated = try {
            when {
                sdk >= 30 -> scanMethod.invoke(wifiService, PACKAGE_NAME, null) as Boolean
                sdk >= 28 -> scanMethod.invoke(wifiService, PACKAGE_NAME) as Boolean
                sdk >= 26 -> scanMethod.invoke(wifiService, null, null, PACKAGE_NAME) as Boolean
                else -> {
                    scanMethod.invoke(wifiService, null, null)
                    true
                }
            }
        } catch (_: Exception) {
            false
        }

        if (!scanInitiated && allowUseCommand) {
            return executeCommandSync("cmd wifi start-scan").exitCode == 0
        }
        return scanInitiated
    }

    fun getWifiScanResults(): List<WifiInfo> {
        val wifiService = asInterface(
            "android.net.wifi.IWifiManager",
            SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        )

        val results = mutableListOf<WifiInfo>()
        val getScanResultsMethod = getWifiMethod(wifiService, "getScanResults")

        val resultObject = when (getScanResultsMethod.parameterTypes.size) {
            2 -> getScanResultsMethod.invoke(wifiService, PACKAGE_NAME, null)
            1 -> getScanResultsMethod.invoke(wifiService, PACKAGE_NAME)
            else -> null
        } ?: return results

        @Suppress("UNCHECKED_CAST")
        val scanResultsList = if (resultObject is List<*>) {
            resultObject as List<Any>
        } else {
            val getListMethod = resultObject.javaClass.getMethod("getList")
            getListMethod.invoke(resultObject) as List<Any>
        }

        if (scanResultsList.isEmpty()) {
            return results
        }

        val scanResultClass = Class.forName("android.net.wifi.ScanResult")
        val ssidField = scanResultClass.getField("SSID")
        val bssidField = scanResultClass.getField("BSSID")
        val levelField = scanResultClass.getField("level")
        val capabilitiesField = scanResultClass.getField("capabilities")

        scanResultsList.forEach { result ->
            val ssid = ssidField.get(result)?.toString() ?: ""
            val bssid = bssidField.get(result)?.toString() ?: ""
            val level = levelField.get(result) as Int
            val capabilities = capabilitiesField.get(result)?.toString() ?: ""

            results.add(WifiInfo(ssid, level, bssid, capabilities))
        }
        results.sortByDescending { it.level }
        return results
    }


    fun getSavedWifiListOld(): List<WifiConfiguration> {
        val wifiService = asInterface(
            "android.net.wifi.IWifiManager",
            SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
        )

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
    fun getSavedWifiList(): List<WifiConfiguration> {
        try {
            val base = Class.forName("android.net.wifi.IWifiManager")
            val iwm = asInterface(
                "android.net.wifi.IWifiManager",
                SystemServiceHelper.getSystemService(Context.WIFI_SERVICE)
            )

            val user = when (Shizuku.getUid()) {
                0 -> "root"
                1000 -> "system"
                else -> "shell"
            }

            //这个API咋这么乱啊！！
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
                                Shizuku.getUid(),
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
                            base.getMethod("getPrivilegedConfiguredNetworks", String::class.java)
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


    /**
     * 执行命令
     * @param command 命令文本
     * @param onOutputReceived 当接收到输出时的回调函数
     * @param onCommandFinished 当命令全部结束时的回调函数
     * @return 停止执行的函数
     */
    fun executeCommand(
        command: String,
        onOutputReceived: Consumer<String>?,
        onCommandFinished: Consumer<CommandRunner.CommandResult>?
    ): Runnable {
        val isCancelled = AtomicBoolean(false)
        val isRunning = AtomicBoolean(true)

        val allOutput = StringBuilder()
        val processHolder = arrayOfNulls<Process>(1)

        val outputThread = Thread {
            var exitCode = -1
            try {
                val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                newProcessMethod.isAccessible = true

                val cmd = CommandRunner.parseCommand(command)

                val process = newProcessMethod.invoke(null, cmd, null, "/") as Process
                processHolder[0] = process

                val inputStream = process.inputStream
                val reader = BufferedReader(InputStreamReader(inputStream))
                var line: String? = null

                while (isRunning.get() && (reader.readLine().also { line = it }) != null) {
                    if (isCancelled.get()) break

                    allOutput.append(line).append("\n")
                    onOutputReceived?.accept(line ?: "")
                }

                try {
                    exitCode = process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                val errorStream = process.errorStream
                val errorReader = BufferedReader(InputStreamReader(errorStream))
                while (isRunning.get() && (errorReader.readLine().also { line = it }) != null) {
                    if (isCancelled.get()) {
                        break
                    }
                    allOutput.append(line).append("\n")
                    onOutputReceived?.accept(line ?: "")
                }

                try {
                    process.waitFor()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }

                if (!isCancelled.get()) {
                    onCommandFinished?.accept(
                        CommandRunner.CommandResult(
                            allOutput.toString(),
                            exitCode
                        )
                    )
                }
            } catch (e: Exception) {
                if (!isCancelled.get()) {
                    onCommandFinished?.accept(
                        CommandRunner.CommandResult(
                            e.stackTraceToString(),
                            exitCode
                        )
                    )
                }
            } finally {
                isRunning.set(false)
            }
        }

        outputThread.start()

        return Runnable {
            isCancelled.set(true)
            isRunning.set(false)

            processHolder[0]?.destroy()
            outputThread.interrupt()
        }
    }

    /**
     * 同步执行命令，等待全部执行完毕后返回输出结果
     * @param command 命令文本
     * @return CompletableFuture 异步返回命令执行的完整输出
     */
    fun executeCommandSync(command: String): CommandRunner.CommandResult {
        val future = CompletableFuture<CommandRunner.CommandResult?>()

        executeCommand(command, null) { future.complete(it) }

        try {
            return future.get() ?: CommandRunner.CommandResult("", -1)
        } catch (e: ExecutionException) {
            throw RuntimeException(e)
        } catch (e: InterruptedException) {
            throw RuntimeException(e)
        }
    }
}