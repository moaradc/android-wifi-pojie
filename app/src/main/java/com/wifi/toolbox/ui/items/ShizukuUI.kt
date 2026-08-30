package com.wifi.toolbox.ui.items

import android.content.pm.PackageManager
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp
import rikka.shizuku.Shizuku

fun checkShizukuUI(
    app: ToolboxApp,
    onGranted: () -> Unit = {},
    onSuccess: () -> Unit = {}
): Boolean {
    try {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onSuccess()
            return true
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            app.alert(app.getString(R.string.shizuku), app.getString(R.string.permission_always_refuse))
        } else {
            app.shizuku.request{
                onGranted()
                onSuccess()
            }
        }
    } catch (_: IllegalStateException) {
        try {
            app.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            app.alert(app.getString(R.string.shizuku), app.getString(R.string.shizuku_not_running))
        } catch (_: PackageManager.NameNotFoundException) {
            app.alert(app.getString(R.string.shizuku), app.getString(R.string.shizuku_not_installed))
        }
    }
    return false
}