package com.wifi.toolbox.app

import android.content.pm.PackageManager
import com.wifi.toolbox.utils.ShizukuUtil.REQUEST_PERMISSION_CODE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class AppShizuku(private val scope: CoroutineScope) {

    private val msgFlow = MutableSharedFlow<Boolean>()

    val listener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == REQUEST_PERMISSION_CODE) {
            val ok = grantResult == PackageManager.PERMISSION_GRANTED
            scope.launch {
                msgFlow.emit(ok)
            }
        }
    }

    /**
     * 初始化Shizuku配置
     * @param none
     * @return none
     */
    fun init() {
        try {
            ShizukuProvider.enableMultiProcessSupport(true)
        } catch (_: Throwable) {
        }
        try {
            Shizuku.addRequestPermissionResultListener(listener)
        } catch (_: Throwable) {
        }
    }

    /**
     * 移除监听
     * @param none
     * @return none
     */
    fun removeListener() {
        try {
            Shizuku.removeRequestPermissionResultListener(listener)
        } catch (_: Throwable) {
        }
    }

    /**
     * 申请权限
     * @param call 成功后的回调
     * @param onFail 用户拒绝授权时的回调（可空：历史调用点无失败提示需求）
     * @return none
     */
    fun request(call: () -> Unit, onFail: (() -> Unit)? = null) {
        Shizuku.requestPermission(REQUEST_PERMISSION_CODE)
        scope.launch {
            try {
                val ok = msgFlow.first()
                if (ok) {
                    call()
                } else {
                    onFail?.invoke()
                }
            } catch (_: Throwable) {
                onFail?.invoke()
            }
        }
    }
}