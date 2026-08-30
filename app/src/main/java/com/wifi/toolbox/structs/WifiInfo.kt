@file:Suppress("DEPRECATION")

package com.wifi.toolbox.structs

import android.net.wifi.WifiConfiguration
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class WifiInfo(
    val ssid: String,
    val level: Int = 0,
    val bssid: String = "",
    val capabilities: String = "",
    val savedInfo: WifiConfiguration? = null,
    val pojieHistoryItem: PojieHistoryItem? = null
) : Parcelable {    companion object {
        fun parseCapabilities(input: WifiInfo): List<List<String>> {
            val regex = Regex("\\[(.*?)\\]")
            return regex.findAll(input.capabilities).map { match ->
                match.groupValues[1].split("-")
            }.toList()
        }

        fun checkIsFreeOpenNetwork(input: WifiInfo): Boolean {
            val protocols = parseCapabilities(input)
            val securityExcluders = listOf("WPA", "WPA2", "WPA3", "RSN", "PSK", "EAP")

            val hasESS = protocols.any { it.firstOrNull() == "ESS" }
            val noSecurity = protocols.none { it.firstOrNull() in securityExcluders }

            return hasESS && noSecurity
        }
    }
}