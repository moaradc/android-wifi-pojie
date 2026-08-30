package com.wifi.toolbox.app

import android.content.Context
import com.wifi.toolbox.R
import com.wifi.toolbox.ToolboxApp.AlertDialogData
import com.wifi.toolbox.ToolboxApp.SnackbarData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class AppUI(private val context: Context,
            private val scope: CoroutineScope) {

    private val _alertFlow = MutableSharedFlow<AlertDialogData?>(replay = 1)
    val alertFlow = _alertFlow.asSharedFlow()

    private val _snackFlow = MutableSharedFlow<SnackbarData>()
    val snackFlow = _snackFlow.asSharedFlow()

    /**
     * 显示弹窗
     * @param t 标题
     * @param s 内容
     * @return none
     */
    fun alert(t: String, s: String) {
        scope.launch {
            _alertFlow.emit(AlertDialogData(t, s))
        }
    }

    /**
     * 关闭弹窗
     * @param none
     * @return none
     */
    fun dismissAlert() {
        scope.launch {
            _alertFlow.emit(null)
        }
    }

    /**
     * 显示提示条
     * @param m 消息
     * @param l 按钮文字
     * @param call 点击回调
     * @return none
     */
    fun snackbar(m: String, l: String? = context.getString(R.string.btn_disappear), call: (() -> Unit)? = null) {
        scope.launch {
            _snackFlow.emit(SnackbarData(m, l, call))
        }
    }
}