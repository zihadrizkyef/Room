package com.zihad.room

import com.zihad.room.audio.Dsp
import com.zihad.room.audio.HighPass
import com.zihad.room.audio.VoiceGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {
    private fun rms(b: ShortArray) = sqrt(b.sumOf { it.toDouble() * it } / b.size)

    private fun tone(hz: Double, n: Int = 16000) =
        ShortArray(n) { (8000 * sin(2 * PI * hz * it / 16000)).toInt().toShort() }

    @Test fun highPassCutsEngineRumbleKeepsVoice() {
        val rumble = tone(50.0); HighPass(16000).process(rumble)
        val voice = tone(1000.0); HighPass(16000).process(voice)
        val tail = { b: ShortArray -> b.copyOfRange(4000, b.size) }   // lewati transien awal
        assertTrue("50 Hz harus turun banyak", rms(tail(rumble)) < 8000 / sqrt(2.0) * 0.15)
        assertTrue("1 kHz harus tetap", rms(tail(voice)) > 8000 / sqrt(2.0) * 0.95)
    }

    @Test fun softClipIsContinuousAndBounded() {
        assertEquals(0.3f, Dsp.softClip(0.3f), 1e-6f)
        assertEquals(Dsp.softClip(0.5f), Dsp.softClip(0.50001f), 1e-3f)
        assertTrue(abs(Dsp.softClip(5f)) <= 1f)
        assertTrue(Dsp.softClip(0.8f) < 0.8f)
        assertEquals(-Dsp.softClip(0.9f), Dsp.softClip(-0.9f), 1e-6f)
    }

    @Test fun mixNeverWrapsAround() {
        val loud = ShortArray(160) { 30000 }
        val out = ShortArray(160)
        Dsp.mix(listOf(loud, loud, loud), 1f, out)
        assertTrue(out.all { it > 0 })
    }

    @Test fun voiceGateOpensOnSpeechHoldsThenCloses() {
        val gate = VoiceGate(hangoverFrames = 5)
        val quiet = ShortArray(320) { if (it % 2 == 0) 30 else -30 }          // ~ -60 dBFS
        val speech = ShortArray(320) { (6000 * sin(2 * PI * 300 * it / 16000)).toInt().toShort() }
        repeat(20) { assertFalse(gate.isOpen(quiet)) }
        assertTrue(gate.isOpen(speech))
        repeat(4) { assertTrue("hangover", gate.isOpen(quiet)) }
        assertFalse(gate.isOpen(quiet))
    }

    @Test fun voiceGateLearnsSteadyWindAsBackground() {
        val gate = VoiceGate(hangoverFrames = 5, floorRiseDbPerFrame = 0.5)
        val wind = ShortArray(320) { (2000 * sin(2 * PI * 200 * it / 16000)).toInt().toShort() }
        repeat(200) { gate.isOpen(wind) }
        assertFalse("angin stabil tidak boleh membuka gerbang terus", gate.isOpen(wind))
        val louder = ShortArray(320) { (12000 * sin(2 * PI * 300 * it / 16000)).toInt().toShort() }
        assertTrue(gate.isOpen(louder))
    }

    @Test fun levelIsZeroForSilence() {
        assertEquals(0f, Dsp.level(ShortArray(160)), 0f)
        assertTrue(Dsp.level(tone(440.0, 160)) > 0.5f)
    }
}
