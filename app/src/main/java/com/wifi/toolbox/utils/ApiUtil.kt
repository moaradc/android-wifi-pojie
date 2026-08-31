package com.wifi.toolbox.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.*
import android.net.wifi.*
import android.os.Build
import android.provider.Settings
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.wifi.toolbox.ui.MainActivity

@Suppress("DEPRECATION") //targetSdk = 28 不用理会警告
object ApiUtil {

    /**
     * 定位权限是否就绪：FINE 或 COARSE 任一即可。
     *
     * 官方规则（Android 10 隐私变更）：targetSdk≤28 的应用声明 COARSE 或
     * FINE 任一即可使用 startScan/getScanResults/getConnectionInfo 等
     * Wi-Fi API——Android 12+ 用户选「仅近似定位」（只授 COARSE）时本应用
     * 的系统 API 通道同样可用，不应误判为缺权限。
     */
    fun hasLocationPermission(context: Context): Boolean {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setWifiEnabled(context: Context, enabled: Boolean): Boolean {
        if (isWifiEnabled(context) == enabled) return true
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return try {
            wifiManager.setWifiEnabled(enabled)
        } catch (_: SecurityException) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun connectToWifiApi29(
        context: Context,
        ssid: String,
        password: String,
        onStatus: (Boolean) -> Unit = {}
    ): ConnectivityManager.NetworkCallback? {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        try {
            val builder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (password.isNotEmpty()) {
                builder.setWpa2Passphrase(password)
            }
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(builder.build())
                .build()

            // 【生命周期关键】WifiNetworkSpecifier 的连接随请求存活：
            // onAvailable 时绝不能注销回调——注销即释放请求来源的网络（官方文档：
            // unregisterNetworkCallback "possibly releases networks originating
            // from a request"），会导致连接成功后立即断开。
            // 连接保持：成功后由调用方持有回调；失败(onUnavailable)时请求已死，
            // 注销仅为清理，无网络可释放，安全。
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onStatus(true)
                }

                override fun onUnavailable() {
                    onStatus(false)
                    clearCallback()
                }

                private fun clearCallback() {
                    try {
                        connectivityManager.unregisterNetworkCallback(this)
                    } catch (_: Exception) {
                    }
                }
            }
            connectivityManager.requestNetwork(networkRequest, callback)

            return callback
        } catch (_: Exception) {
            onStatus(false)
            return null
        }
    }

    fun cancelWifiRequest(context: Context, callback: ConnectivityManager.NetworkCallback) {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // 幂等：onUnavailable 已自注销的回调再次注销会抛 IllegalArgumentException，
        // 此处必须吞掉——否则异常会沿 cleanConnection → handleAttemptResult 向上中断
        // 成功结果处理链（历史上导致破解成功却不写历史/不停任务）。
        try {
            connectivityManager.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
    }

    fun disconnectWifi(context: Context) {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.disconnect()
    }

    fun connectToWifiApi28(context: Context, ssid: String, password: String): Int {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiConfig = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
        }
        val netId = wifiManager.addNetwork(wifiConfig)
        return if (netId != -1) {
            wifiManager.enableNetwork(netId, true)
            netId
        } else -1
    }

    fun enableNetwork(context: Context, netId: Int) {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiManager.enableNetwork(netId, true)
    }

    fun forgetNetwork(context: Context, netId: Int): Boolean {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.removeNetwork(netId)
    }

    fun enableLocation(context: Context, onEnabled: (() -> Unit)? = null): Boolean {
        val activity = context as? MainActivity ?: return false
        if (!isLocationEnabled(context)) {
            activity.pendingLocationCallback = onEnabled
            val locationRequest =
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10000).build()
            val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest)
                .setAlwaysShow(true)
            val client = LocationServices.getSettingsClient(activity)

            client.checkLocationSettings(builder.build()).addOnFailureListener { exception ->
                if (exception is ResolvableApiException) {
                    try {
                        val intentSenderRequest =
                            IntentSenderRequest.Builder(exception.resolution.intentSender).build()
                        activity.locationLauncher.launch(intentSenderRequest)
                    } catch (_: Exception) {
                        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                        activity.startActivity(intent)
                    }
                }
            }
            return false
        }
        return true
    }


    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    fun getSavedWifiList(context: Context): List<WifiConfiguration> {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val configs = wifiManager.configuredNetworks ?: return emptyList()
        return configs.distinctBy { it.networkId }
    }

    fun getNetIdBySsid(context: Context, ssid: String): Int {
        val savedNetworks: List<WifiConfiguration> = getSavedWifiList(context)
        return savedNetworks.find { it.SSID.removeSurrounding("\"") == ssid }?.networkId ?: -1
    }

    fun startScan(context: Context): Boolean {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.startScan()
    }

    @SuppressLint("MissingPermission")
    fun getScanResults(context: Context): List<com.wifi.toolbox.structs.WifiInfo> {
        if (hasLocationPermission(context)) {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            return wifiManager.scanResults.map {
                com.wifi.toolbox.structs.WifiInfo(
                    ssid = it.SSID,
                    level = it.level,
                    bssid = it.BSSID,
                    capabilities = it.capabilities,
                    frequency = it.frequency
                )
            }.sortedByDescending { it.level }
        }
        return emptyList()
    }

    fun isLocationEnabled(context: Context): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun isWifiEnabled(context: Context): Boolean {
        val wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wifiManager.isWifiEnabled
    }

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
        val result = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        return result
    }

    fun requestLocationPermission(activity: Activity, onGranted: (() -> Unit)? = null): Boolean {
        val activity = activity as? MainActivity ?: return false
        return if (!hasLocationPermission(activity)) {
            activity.pendingPermissionCallback = onGranted
            // FINE+COARSE 双权限同请求：官方文档明确 Android 12+ 单独请求
            // FINE 会被部分版本系统直接忽略（不弹任何对话框）
            activity.permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            false
        } else {
            true
        }
    }
}