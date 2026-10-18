package com.zihad.room.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Filter high-pass (Butterworth orde 2). Membuang dengung mesin dan gemuruh angin
 * di bawah ~150 Hz yang tidak membawa isi suara orang bicara.
 */
class HighPass(sampleRate: Int, cutoffHz: Double = 150.0) {
    private val b0: Double; private val b1: Double; private val b2: Double
    private val a1: Double; private val a2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    init {
        val w = 2 * PI * cutoffHz / sampleRate
        val alpha = sin(w) / (2 * Q)
        val c = cos(w)
        val a0 = 1 + alpha
        b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = (1 + c) / 2 / a0
        a1 = -2 * c / a0; a2 = (1 - alpha) / a0
    }

    fun process(buf: ShortArray) {
        for (i in buf.indices) {
            val x = buf[i].toDouble()
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            buf[i] = y.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    private companion object {
        const val Q = 0.7071   // Butterworth
    }
}

/**
 * Gerbang suara: true = ada orang bicara, kirim. false = diam, jangan kirim apa pun.
 *
 * Tujuannya mengurangi paket di WiFi (MiFi murah kewalahan kalau 3 HP mengirim terus).
 * Lantai bising diikuti otomatis — turun cepat, naik pelan — jadi angin/mesin yang stabil
 * lama-lama dianggap latar, dan suara orang yang lebih keras dari itu membuka gerbang.
 * Setelah bicara, gerbang tetap terbuka [hangoverFrames] supaya ujung kata tidak terpotong.
 */
class VoiceGate(
    private val hangoverFrames: Int,
    private val openAboveFloorDb: Double = 7.0,
    private val minOpenDb: Double = -52.0,
    private val floorRiseDbPerFrame: Double = 0.02,
) {
    private var floorDb = -60.0
    private var hang = 0

    fun isOpen(buf: ShortArray): Boolean {
        val db = Dsp.dbfs(buf)
        floorDb = if (db < floorDb) db else floorDb + floorRiseDbPerFrame
        if (db > floorDb + openAboveFloorDb && db > minOpenDb) hang = hangoverFrames
        else if (hang > 0) hang--
        return hang > 0
    }
}

object Dsp {
    /** Kekuatan sinyal dalam dBFS (0 = maksimum, -90 = hening). */
    fun dbfs(buf: ShortArray): Double {
        if (buf.isEmpty()) return -90.0
        var sum = 0.0
        for (s in buf) sum += s.toDouble() * s
        val rms = sqrt(sum / buf.size) / 32768.0
        return if (rms <= 3e-5) -90.0 else 20 * log10(rms)
    }

    /** Level 0..1 untuk meter UI (skala dB, -60 dB = 0). */
    fun level(buf: ShortArray): Float {
        if (buf.isEmpty()) return 0f
        var sum = 0.0
        for (s in buf) sum += s.toDouble() * s
        val rms = sqrt(sum / buf.size) / 32768.0
        if (rms <= 1e-6) return 0f
        return ((20 * log10(rms) + 60) / 60).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Campur beberapa suara jadi satu, dengan penguatan dan soft-clip (tanh) supaya
     * suara keras tidak pecah. [out] di-overwrite.
     */
    fun mix(frames: List<ShortArray>, gain: Float, out: ShortArray) {
        for (i in out.indices) {
            var acc = 0f
            for (f in frames) if (i < f.size) acc += f[i]
            val v = acc * gain / 32768f
            out[i] = (softClip(v) * 32767f).toInt().toShort()
        }
    }

    /** Linear sampai ±0.5, lalu melengkung halus menuju ±1 (tanpa lompatan). */
    fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= KNEE) return x
        val y = KNEE + (1 - KNEE) * tanh(((a - KNEE) / (1 - KNEE)).toDouble()).toFloat()
        return if (x < 0) -y else y
    }

    private const val KNEE = 0.5f
}
