package com.zihad.room.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Hasil PackageInstaller. Kalau Android minta konfirmasi, tampilkan; kalau gagal, buang APK-nya. */
class UpdateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Updater.discard(context)   // ditolak/gagal (mis. kunci tanda tangan beda): jangan diulang terus
        }
    }
}
