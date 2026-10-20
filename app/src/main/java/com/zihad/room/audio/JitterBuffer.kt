package com.zihad.room.audio

/**
 * Antrean kecil per orang yang meratakan paket WiFi yang datang tidak teratur.
 *
 * Bedanya dengan WebRTC bawaan: kedalaman antrean dibatasi keras (default 2–6 frame). Kalau antrean menumpuk melewati target, frame lama dibuang supaya delay
 * tidak pernah merayap naik. Kalau WiFi tersendat (antrean kosong lalu datang lagi
 * dalam waktu singkat), target dinaikkan satu frame; setelah lama stabil diturunkan lagi.
 *
 * Satu pemanggilan [pop] = satu frame waktu putar. Aman dipanggil dari dua thread.
 * Jeda kosong sesingkat [shortGapFrames] dianggap gangguan WiFi.
 */
class JitterBuffer(
    private val minDepth: Int = 2,
    private val maxDepth: Int = 6,
    private val calmFramesToShrink: Int = 1000,
    private val shortGapFrames: Int = 30,
) {
    private val slots = arrayOfNulls<ShortArray>(CAPACITY)
    private val slotSeq = IntArray(CAPACITY) { Int.MIN_VALUE }

    private var nextSeq = 0
    private var highestSeq = 0
    private var hasData = false
    private var playing = false

    private var lastFrame: ShortArray? = null
    private var concealed = 0
    private var emptyFor = 0
    private var calmFor = 0

    /** Penghitung kumulatif untuk diagnosis di jalan (lihat log "Room"). */
    @Volatile var played = 0L; private set
    @Volatile var missing = 0L; private set     // frame tidak datang tepat waktu → disembunyikan
    @Volatile var late = 0L; private set        // datang setelah waktunya diputar → dibuang
    @Volatile var overflow = 0L; private set    // dibuang karena antrean kelebihan

    /** Kedalaman target saat ini, dalam frame. */
    @Volatile var target = minDepth
        private set

    /**
     * [talkStart] = frame pertama setelah pengirim diam (gerbang suara). Jeda sebelumnya
     * memang disengaja, jadi tidak dihitung sebagai gangguan WiFi.
     */
    @Synchronized
    fun push(seq: Int, frame: ShortArray, talkStart: Boolean = false) {
        if (talkStart && !(playing && hasData)) emptyFor = 0
        if (playing && seq - nextSeq < 0) { late++; return }   // telat, sudah lewat waktunya
        if (hasData && !playing && seq - nextSeq < 0 && nextSeq - seq < CAPACITY) nextSeq = seq
        if (!hasData || seq - highestSeq > CAPACITY) {     // aliran baru / lompatan besar
            reset()
            nextSeq = seq
            highestSeq = seq
            hasData = true
        }
        val i = Math.floorMod(seq, CAPACITY)
        slots[i] = frame
        slotSeq[i] = seq
        if (seq - highestSeq > 0) highestSeq = seq
    }

    /** Frame berikutnya untuk diputar, atau null = diam. */
    @Synchronized
    fun pop(): ShortArray? {
        if (!hasData) { emptyFor++; return null }

        val depth = highestSeq - nextSeq + 1
        if (!playing) {
            if (depth < target) { emptyFor++; return null }
            if (emptyFor in 1..shortGapFrames) grow()   // kosong sebentar = WiFi tersendat
            emptyFor = 0
            playing = true
        }

        // Antrean kelebihan: buang frame lama supaya delay kembali ke target.
        if (depth > target + DRIFT_SLACK) {
            val skip = depth - target
            for (s in 0 until skip) take(nextSeq + s)
            nextSeq += skip
            overflow += skip
        }

        if (highestSeq - nextSeq < 0) {                     // habis: pengirim diam / putus
            playing = false
            hasData = false
            emptyFor = 1
            return null
        }

        val frame = take(nextSeq)
        nextSeq++
        if (frame != null) {
            concealed = 0
            played++
            lastFrame = frame
            if (++calmFor >= calmFramesToShrink) { calmFor = 0; if (target > minDepth) target-- }
            return frame
        }

        // Paket hilang: ulangi frame terakhir sambil dikecilkan supaya tidak "klik".
        calmFor = 0
        missing++
        val last = lastFrame ?: return ShortArray(0)
        concealed++
        if (concealed > MAX_CONCEAL) return ShortArray(last.size)
        val gain = 1f - concealed.toFloat() / (MAX_CONCEAL + 1)
        return ShortArray(last.size) { (last[it] * gain).toInt().toShort() }
    }

    @Synchronized
    fun depth(): Int = if (hasData) (highestSeq - nextSeq + 1).coerceAtLeast(0) else 0

    private fun take(seq: Int): ShortArray? {
        val i = Math.floorMod(seq, CAPACITY)
        if (slotSeq[i] != seq) return null
        val f = slots[i]
        slots[i] = null
        slotSeq[i] = Int.MIN_VALUE
        return f
    }

    private fun grow() {
        calmFor = 0
        if (target < maxDepth) target++
    }

    private fun reset() {
        slots.fill(null)
        slotSeq.fill(Int.MIN_VALUE)
        playing = false
        concealed = 0
        lastFrame = null
    }

    companion object {
        private const val CAPACITY = 64
        private const val DRIFT_SLACK = 2
        private const val MAX_CONCEAL = 3
    }
}
