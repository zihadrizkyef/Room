package com.zihad.room.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zihad.room.Room
import com.zihad.room.RoomService

/** Buka aplikasi = langsung masuk room. Tidak ada layar isian. */
class MainActivity : ComponentActivity() {

    private var micDenied by mutableStateOf(false)

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (mic == PackageManager.PERMISSION_GRANTED) startRoom() else micDenied = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Layar tetap nyala: WiFi low-latency di Android hanya aktif saat layar nyala.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val state by Room.state.collectAsStateWithLifecycle()
            RoomScreen(
                state = state,
                micDenied = micDenied,
                onToggleMute = Room::toggleMute,
                onConnectBluetooth = Room::connectBluetooth,
                onStart = ::ensurePermissionsAndStart,
                onStop = {
                    RoomService.stop(this)
                    finishAndRemoveTask()
                },
            )
        }
        ensurePermissionsAndStart()
    }

    private fun ensurePermissionsAndStart() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startRoom() else askPermissions.launch(needed.toTypedArray())
    }

    private fun startRoom() {
        micDenied = false
        RoomService.start(this)
    }
}
