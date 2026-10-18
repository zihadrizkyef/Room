package com.zihad.room

import com.zihad.room.audio.JitterBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {
    private fun frame(v: Int) = ShortArray(160) { v.toShort() }

    @Test fun waitsForTargetDepthThenPlaysInOrder() {
        val jb = JitterBuffer(minDepth = 2)
        jb.push(100, frame(1))
        assertNull(jb.pop())                     // baru 1 frame, target 2
        jb.push(101, frame(2))
        assertEquals(1, jb.pop()!![0].toInt())
        assertEquals(2, jb.pop()!![0].toInt())
    }

    @Test fun reordersOutOfOrderPackets() {
        val jb = JitterBuffer(minDepth = 3)
        jb.push(11, frame(11)); jb.push(10, frame(10)); jb.push(12, frame(12))
        assertEquals(10, jb.pop()!![0].toInt())
        assertEquals(11, jb.pop()!![0].toInt())
        assertEquals(12, jb.pop()!![0].toInt())
    }

    @Test fun concealsSingleLossWithFadedRepeat() {
        val jb = JitterBuffer(minDepth = 2)
        jb.push(0, frame(1000)); jb.push(1, frame(1000)); jb.push(3, frame(1000))
        jb.pop(); jb.pop()
        val concealed = jb.pop()!!               // seq 2 hilang
        assertTrue(concealed[0] in 1..999)
        assertEquals(1000, jb.pop()!![0].toInt())
    }

    @Test fun dropsBacklogSoDelayStaysBounded() {
        val jb = JitterBuffer(minDepth = 2, maxDepth = 6)
        jb.push(0, frame(0)); jb.push(1, frame(1))
        jb.pop()
        // WiFi macet lalu 20 paket datang sekaligus.
        for (s in 2..21) jb.push(s, frame(s))
        jb.pop()
        assertTrue("depth ${jb.depth()} harus dekat target", jb.depth() <= jb.target + 2)
    }

    @Test fun shortGapGrowsTargetAndCalmShrinksIt() {
        val jb = JitterBuffer(minDepth = 2, maxDepth = 6, calmFramesToShrink = 50)
        var seq = 0
        jb.push(seq++, frame(1)); jb.push(seq++, frame(1))
        jb.pop(); jb.pop()
        assertNull(jb.pop())                     // kosong = tersendat
        repeat(5) { jb.pop() }                   // 50 ms tanpa paket
        jb.push(seq++, frame(1)); jb.push(seq++, frame(1)); jb.push(seq++, frame(1))
        assertNotNull(jb.pop())
        assertEquals(3, jb.target)
        repeat(80) { jb.push(seq++, frame(1)); jb.pop() }
        assertEquals(2, jb.target)
    }

    @Test fun lateArrivalsAreIgnored() {
        val jb = JitterBuffer(minDepth = 2)
        jb.push(5, frame(5)); jb.push(6, frame(6))
        jb.pop(); jb.pop()
        jb.push(4, frame(4))                     // sudah lewat
        jb.push(7, frame(7)); jb.push(8, frame(8))
        assertEquals(7, jb.pop()!![0].toInt())
    }
}
