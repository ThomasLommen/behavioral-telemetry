package com.behavioral.telemetry.sensors

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

object NetworkStateHelper {

    data class NetworkSnapshot(
        val type: String,
        val isMetered: Boolean,
        val isVpn: Boolean
    )

    fun getNetworkSnapshot(context: Context): NetworkSnapshot {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return NetworkSnapshot("UNKNOWN", isMetered = false, isVpn = false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = cm.activeNetwork ?: return NetworkSnapshot("OFFLINE", isMetered = false, isVpn = false)
                val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkSnapshot("OFFLINE", isMetered = false, isVpn = false)

                val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    isVpn -> "VPN"
                    else -> "OTHER"
                }

                return NetworkSnapshot(type, isMetered, isVpn)
            } else {
                @Suppress("DEPRECATION")
                val netInfo = cm.activeNetworkInfo
                if (netInfo == null || !netInfo.isConnected) {
                    return NetworkSnapshot("OFFLINE", isMetered = false, isVpn = false)
                }
                @Suppress("DEPRECATION")
                val type = when (netInfo.type) {
                    ConnectivityManager.TYPE_WIFI -> "WIFI"
                    ConnectivityManager.TYPE_MOBILE -> "CELLULAR"
                    ConnectivityManager.TYPE_VPN -> "VPN"
                    else -> "OTHER"
                }
                return NetworkSnapshot(type, isMetered = false, isVpn = false)
            }
        } catch (e: Exception) {
            return NetworkSnapshot("UNKNOWN", isMetered = false, isVpn = false)
        }
    }
}
