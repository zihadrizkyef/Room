package com.zihad.room

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.zihad.room.ui.MainActivity

/**
 * Menjaga obrolan tetap jalan saat layar mati / aplikasi di belakang.
 * Juga mematikan power-save WiFi (penyebab umum suara tersendat).
 */
class RoomService : Service() {

    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification())
        }
        if (Room.engine == null) {
            acquireLocks()
            Room.engine = RoomEngine(applicationContext).also { it.start() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Room.engine?.stop()
        Room.engine = null
        wifiLocks.forEach { if (it.isHeld) it.release() }
        wifiLocks.clear()
        multicastLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        // Pegang keduanya: LOW_LATENCY tidak didukung semua driver (mis. Galaxy M11 → tidak pernah
        // aktif), sedangkan HIGH_PERF di banyak HP masih mematikan power-save WiFi.
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= 29) add(WifiManager.WIFI_MODE_FULL_LOW_LATENCY)
            add(WifiManager.WIFI_MODE_FULL_HIGH_PERF)
        }
        modes.mapTo(wifiLocks) { mode ->
            wifi.createWifiLock(mode, "room:wifi$mode").apply { setReferenceCounted(false); acquire() }
        }
        multicastLock = wifi.createMulticastLock("room:discovery").apply { setReferenceCounted(false); acquire() }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "room:audio").apply { setReferenceCounted(false); acquire() }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Room aktif", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RoomService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Room aktif")
            .setContentText("Ngobrol lewat WiFi lokal")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "room"
        private const val ACTION_STOP = "com.zihad.room.STOP"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, RoomService::class.java))

        fun stop(context: Context) {
            context.stopService(Intent(context, RoomService::class.java))
        }
    }
}
