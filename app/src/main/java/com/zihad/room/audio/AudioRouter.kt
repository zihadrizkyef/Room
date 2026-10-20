package com.zihad.room.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.zihad.room.Requirements

/**
 * Mengarahkan mic & suara ke intercom Bluetooth (mode telepon / SCO).
 *
 * Versi lama memakai startBluetoothSco() yang async dan bisa gagal diam-diam — akibatnya
 * Android memakai mic HP di saku (angin + mesin masuk). Di sini Android 12+ memakai
 * setCommunicationDevice(), dan rute yang *benar-benar* dipakai dilaporkan ke UI.
 *
 * Hanya intercom [Requirements.INTERCOM_NAME] yang dipakai; Bluetooth lain (headset, mobil)
 * diabaikan walau sedang tersambung.
 */
class AudioRouter(private val context: Context, private val onChanged: () -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var scoReceiver: BroadcastReceiver? = null
    private var commListener: Any? = null   // OnCommunicationDeviceChangedListener (API 31+)
    private var headset: BluetoothHeadset? = null
    /** X2PLUS tersambung sebagai headset (HFP) — dibaca dari profil Bluetooth, bukan dari nama AudioDeviceInfo. */
    @Volatile private var intercomConnected = false
    private var scoRequested = false          // startBluetoothSco dihitung per panggilan; jangan dobel

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
        openHeadsetProxy()
        connectBluetooth()
    }

    /**
     * Android 10/11 sering memberi AudioDeviceInfo bernama HP sendiri (bukan nama intercom), jadi
     * "apakah X2PLUS tersambung" ditanyakan langsung ke profil headset.
     */
    @SuppressLint("MissingPermission")
    private fun openHeadsetProxy() {
        runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.getProfileProxy(context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        headset = proxy as BluetoothHeadset
                        refreshHeadset(); onChanged()
                    }
                    override fun onServiceDisconnected(profile: Int) { headset = null; intercomConnected = false }
                }, BluetoothProfile.HEADSET)
        }
    }

    /**
     * Android ≤ 11 tidak selalu memberi tahu saat SCO putus (routedDevice tetap menunjuk Bluetooth),
     * jadi keberadaan intercom dicek ulang ke profil headset. Di Android 12+ rute sudah andal.
     */
    fun intercomPresent(): Boolean {
        if (Build.VERSION.SDK_INT > 30 || headset == null) return true
        refreshHeadset()
        return intercomConnected
    }

    @SuppressLint("MissingPermission")
    private fun refreshHeadset() {
        intercomConnected = try {
            headset?.connectedDevices.orEmpty().any { Requirements.isIntercomName(it.name) }
        } catch (_: SecurityException) { false }
    }

    /** Coba pindahkan ke intercom. Mengembalikan false kalau intercom yang diminta tidak tersambung. */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun connectBluetooth(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = am.availableCommunicationDevices
            val bt = devices.firstOrNull { isBluetooth(it) && isIntercom(it) }
            if (bt != null) return am.setCommunicationDevice(bt)
            if (am.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { am.setCommunicationDevice(it) }
            }
            return false
        }
        refreshHeadset()
        val hasBt = intercomConnected || am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isBluetooth(it) && isIntercom(it) }
        if (hasBt) {
            if (!scoRequested) {
                am.isSpeakerphoneOn = false
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                scoRequested = true
            }
        } else {
            if (scoRequested) { am.stopBluetoothSco(); am.isBluetoothScoOn = false; scoRequested = false }
            am.isSpeakerphoneOn = true
        }
        return hasBt
    }

    /**
     * Apakah [d] adalah intercom yang diwajibkan. Nama dari AudioDeviceInfo kadang berisi nama HP
     * sendiri (bukan nama perangkat BT), jadi alamat perangkat yang sudah dipasangkan ikut dicek.
     */
    @SuppressLint("MissingPermission")
    fun isIntercom(d: AudioDeviceInfo?): Boolean {
        if (d == null || !isBluetooth(d)) return false
        if (Requirements.isIntercomName(d.productName)) return true
        // Android ≤ 11 hanya memakai satu headset aktif, jadi kalau X2PLUS tersambung, SCO itu miliknya.
        if (Build.VERSION.SDK_INT <= 30 && intercomConnected) return true
        if (Build.VERSION.SDK_INT < 28) return false
        val addr = d.address.takeIf { it.isNotBlank() } ?: return false
        return try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .any { it.address.equals(addr, ignoreCase = true) && Requirements.isIntercomName(it.name) }
        } catch (_: SecurityException) { false }   // izin BLUETOOTH_CONNECT ditolak: cukup andalkan nama
    }

    /** Perangkat output komunikasi saat ini (Android 12+), untuk label UI. */
    fun currentOutput(): AudioDeviceInfo? =
        if (Build.VERSION.SDK_INT >= 31) am.communicationDevice else null

    @Suppress("DEPRECATION")
    fun stop() {
        headset?.let { h -> runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.closeProfileProxy(BluetoothProfile.HEADSET, h) } }
        headset = null; intercomConnected = false
        if (Build.VERSION.SDK_INT >= 31) {
            (commListener as? AudioManager.OnCommunicationDeviceChangedListener)
                ?.let { am.removeOnCommunicationDeviceChangedListener(it) }
            commListener = null
            am.clearCommunicationDevice()
        } else {
            scoReceiver?.let { context.unregisterReceiver(it) }
            scoReceiver = null
            am.stopBluetoothSco()
            scoRequested = false
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
