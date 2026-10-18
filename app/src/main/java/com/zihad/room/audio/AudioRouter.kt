package com.zihad.room.audio

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Mengarahkan mic & suara ke intercom Bluetooth (mode telepon / SCO).
 *
 * Versi lama memakai startBluetoothSco() yang async dan bisa gagal diam-diam — akibatnya
 * Android memakai mic HP di saku (angin + mesin masuk). Di sini Android 12+ memakai
 * setCommunicationDevice(), dan rute yang *benar-benar* dipakai dilaporkan ke UI.
 */
class AudioRouter(private val context: Context, private val onChanged: () -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var scoReceiver: BroadcastReceiver? = null
    private var commListener: Any? = null   // OnCommunicationDeviceChangedListener (API 31+)

    fun start() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnCommunicationDeviceChangedListener { onChanged() }
            am.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(context), l)
            commListener = l
        } else {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = onChanged()
            }
            ContextCompat.registerReceiver(context, r,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_EXPORTED)
            scoReceiver = r
        }
        connectBluetooth()
    }

    /** Coba pindahkan ke intercom. Mengembalikan false kalau tidak ada perangkat BT. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun connectBluetooth(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = am.availableCommunicationDevices
            val bt = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            if (bt != null) return am.setCommunicationDevice(bt)
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            return false
        }
        val hasBt = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (hasBt) {
            am.isSpeakerphoneOn = false
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
        } else {
            am.isSpeakerphoneOn = true
        }
        return hasBt
    }

    /** Perangkat output komunikasi saat ini (Android 12+), untuk label UI. */
    fun currentOutput(): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= 31) am.communicationDevice else null

    @Suppress("DEPRECATION")
    fun stop() {
        if (Build.VERSION.SDK_INT >= 31) {
            (commListener as? AudioManager.OnCommunicationDeviceChangedListener)
                ?.let { am.removeOnCommunicationDeviceChangedListener(it) }
            commListener = null
            am.clearCommunicationDevice()
        } else {
            scoReceiver?.let { context.unregisterReceiver(it) }
            scoReceiver = null
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
            am.isSpeakerphoneOn = false
        }
        am.mode = AudioManager.MODE_NORMAL
    }

    companion object {
        fun isBluetooth(d: AudioDeviceInfo?) = d != null && (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET))
    }
}
