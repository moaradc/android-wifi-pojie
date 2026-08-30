package com.wifi.toolbox.structs

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PojieHistoryItem(
    val ssid: String,
    val passwords: List<String>,
    val progress: Int,
    val password: String? = null,
    val lasttime: Long = 0L
) : Parcelable