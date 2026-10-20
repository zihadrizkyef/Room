package com.zihad.room

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catatan perjalanan ("kotak hitam"): satu file teks per sesi, ditulis tiap detik di latar belakang.
 *
 * Tujuannya diagnosis setelah uji di jalan, saat tidak ada laptop/logcat yang tersambung. File ada di
 * `Android/data/com.zihad.room/files/logs/` dan bisa diambil lewat kabel (adb pull) atau pengelola file,
 * tanpa izin tambahan. Format `kunci=nilai` supaya mudah di-parse. Hanya angka teknis — tidak ada isi suara.
 * Gagal menulis tidak boleh mengganggu obrolan, jadi semua error ditelan.
 */
class FlightLog private constructor(private val file: File?) {
    private var out: BufferedWriter? = file?.let { runCatching { BufferedWriter(FileWriter(it, true)) }.getOrNull() }

    @Synchronized
    fun line(text: String) {
        val w = out ?: return
        runCatching {
            w.write("${System.currentTimeMillis()} $text\n")
            w.flush()   // tiap detik saja; tanpa flush, HP mati mendadak = catatan hilang
        }.onFailure { out = null }
    }

    fun event(text: String) = line("EVT $text")

    @Synchronized
    fun close() {
        runCatching { out?.close() }
        out = null
    }

    companion object {
        private const val KEEP_FILES = 12

        fun open(context: Context, deviceName: String): FlightLog {
            val dir = runCatching {
                File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
            }.getOrNull() ?: return FlightLog(null)
            prune(dir)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safe = deviceName.replace(Regex("[^A-Za-z0-9]+"), "_")
            return FlightLog(File(dir, "ride-$stamp-$safe.log"))
        }

        private fun prune(dir: File) {
            runCatching {
                dir.listFiles { f -> f.name.startsWith("ride-") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(KEEP_FILES - 1)
                    ?.forEach { it.delete() }
            }
        }
    }
}
