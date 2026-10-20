package com.zihad.room.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.zihad.room.Requirements

enum class WifiStatus { OK, NO_WIFI, WRONG_NETWORK }

class WifiCheck(val status: WifiStatus, val network: Network? = null, val ssid: String = "")

/**
 * Memastikan HP tidak tersambung ke WiFi *selain* [Requirements.WIFI_SSID] dan mengembalikan [Network]-nya,
 * supaya socket bisa dikunci ke WiFi itu. Tanpa kunci, Android bisa mengirim paket lewat data
 * seluler saat WiFi MiFi dianggap "tanpa internet" — paket ke IP lokal pun lenyap.
 *
 * Nama WiFi hanya terbaca kalau izin Lokasi diberikan dan Lokasi menyala. Kalau tidak terbaca,
 * pemeriksaan dilewati (bukan diblokir) supaya buka aplikasi tetap langsung ngobrol.
 */
object WifiGuard {

    @Suppress("DEPRECATION")
    fun check(context: Context): WifiCheck {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return WifiCheck(WifiStatus.NO_WIFI)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return WifiCheck(WifiStatus.OK, net)

        val raw = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo?.ssid
        return when {
            Requirements.isUnknownSsid(raw) -> WifiCheck(WifiStatus.OK, net)
            Requirements.isRequiredSsid(raw) -> WifiCheck(WifiStatus.OK, net, Requirements.cleanSsid(raw))
            else -> WifiCheck(WifiStatus.WRONG_NETWORK, ssid = Requirements.cleanSsid(raw))
        }
    }
}
