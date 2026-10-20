package com.zihad.room.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Aturan versi, dipisah dari Android supaya bisa di-test. */
object UpdateRule {
    /** Tag rilis "v12" → 12. Selain itu (mis. "beta") diabaikan. */
    fun versionOf(tag: String?): Long? = tag?.trim()?.removePrefix("v")?.toLongOrNull()

    fun isNewer(tag: String?, current: Long): Boolean = (versionOf(tag) ?: return false) > current
}

/**
 * Update otomatis dari GitHub Releases, tanpa layar apa pun:
 * - Saat aplikasi dibuka: cek rilis terbaru, kalau lebih baru unduh APK-nya ke cache (latar belakang).
 * - APK yang sudah siap dipasang saat aplikasi dibuka lagi (sebelum obrolan mulai) atau saat Stop —
 *   jadi update tidak pernah memutus obrolan yang sedang jalan.
 * Android 12+ memasang tanpa dialog konfirmasi untuk update berikutnya; Android 11 ke bawah tetap
 * meminta satu ketukan "Pasang".
 */
object Updater {
    private const val TAG = "RoomUpdate"
    private const val API = "https://api.github.com/repos/zihadrizkyef/Room/releases/latest"
    private const val ASSET = "room.apk"
    private const val PREFS = "update"

    private fun staged(c: Context) = File(c.cacheDir, "update/room.apk")

    private fun currentVersion(c: Context): Long =
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    /** Cek + unduh di thread latar. Aman dipanggil tiap aplikasi dibuka; gagal = diam saja. */
    fun checkInBackground(context: Context) {
        val c = context.applicationContext
        thread(name = "room-update") {
            runCatching {
                val json = JSONObject(get(API).use { it.readBytes().decodeToString() })
                val tag = json.optString("tag_name")
                if (!UpdateRule.isNewer(tag, currentVersion(c))) return@runCatching
                val file = staged(c)
                if (file.exists() && stagedVersion(c) == UpdateRule.versionOf(tag)) return@runCatching
                val assets = json.getJSONArray("assets")
                val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .first { it.getString("name") == ASSET }.getString("browser_download_url")
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "room.apk.part")
                get(url).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                file.delete()
                tmp.renameTo(file)
                Log.i(TAG, "update $tag siap dipasang")
            }.onFailure { Log.w(TAG, "cek update gagal: ${it.message}") }
        }
    }

    /** True kalau APK baru sudah diunduh dan layak dipasang. */
    fun hasStaged(context: Context): Boolean {
        val v = stagedVersion(context) ?: return false
        if (v <= currentVersion(context)) { staged(context).delete(); return false }
        return true
    }

    /**
     * Pasang APK yang sudah siap. Mengembalikan true kalau pemasangan dimulai (proses ini akan
     * dimatikan Android kalau berhasil). Pertama kali, Android minta izin "pasang aplikasi" dulu.
     */
    fun install(context: Context): Boolean {
        val c = context.applicationContext
        val file = staged(c)
        if (!hasStaged(c)) return false
        if (isInstalling(c)) return true
        if (!c.packageManager.canRequestPackageInstalls()) {
            val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean("asked", false)) return false      // jangan menagih terus
            prefs.edit().putBoolean("asked", true).apply()
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        return runCatching {
            val installer = c.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite("room.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val pi = PendingIntent.getBroadcast(c, id, Intent(c, UpdateReceiver::class.java),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("installing", System.currentTimeMillis()).apply()
                s.commit(pi.intentSender)
            }
            true
        }.getOrElse { Log.w(TAG, "install gagal: ${it.message}"); false }
    }

    fun discard(context: Context) { staged(context).delete() }

    /**
     * True selama pemasangan masih berjalan di latar belakang (bisa puluhan detik). Dipakai UI untuk
     * menampilkan "Memperbarui…" dan mencegah sesi pemasangan ganda kalau aplikasi dibuka lagi.
     * Kedaluwarsa 3 menit supaya tidak macet selamanya kalau hasilnya tidak pernah datang.
     */
    fun isInstalling(context: Context): Boolean {
        val since = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("installing", 0)
        return System.currentTimeMillis() - since < 3 * 60_000
    }

    fun clearInstalling(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("installing").apply()
    }

    private fun stagedVersion(c: Context): Long? {
        val f = staged(c).takeIf { it.exists() } ?: return null
        return c.packageManager.getPackageArchiveInfo(f.path, 0)
            ?.takeIf { it.packageName == c.packageName }?.longVersionCode
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000; readTimeout = 30_000
        setRequestProperty("User-Agent", "room-app")
        setRequestProperty("Accept", "application/vnd.github+json")
        check(responseCode == 200) { "HTTP $responseCode" }
        inputStream
    }
}
