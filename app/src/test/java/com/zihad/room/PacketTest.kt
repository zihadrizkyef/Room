package com.zihad.room

import com.zihad.room.net.Packet
import com.zihad.room.net.PacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketTest {
    @Test fun audioRoundTrip() {
        val pcm = ShortArray(160) { (it * 200 - 16000).toShort() }
        val bytes = Packet(PacketType.AUDIO, 42, 7, seq = 1234, pcm = pcm).encode()
        assertEquals(Packet.HEADER + 320, bytes.size)
        val p = Packet.decode(bytes)!!
        assertEquals(PacketType.AUDIO, p.type)
        assertEquals(42, p.room)
        assertEquals(7, p.sender)
        assertEquals(1234, p.seq)
        assertArrayEquals(pcm, p.pcm)
    }

    @Test fun helloCarriesName() {
        val p = Packet.decode(Packet(PacketType.HELLO, 1, 2, name = "Galaxy A15").encode())!!
        assertEquals("Galaxy A15", p.name)
    }

    @Test fun pingKeepsTime() {
        val p = Packet.decode(Packet(PacketType.PING, 1, 2, 9, time = 123_456_789_000L).encode())!!
        assertEquals(123_456_789_000L, p.time)
    }

    @Test fun rejectsForeignOrShort() {
        assertNull(Packet.decode(ByteArray(10)))
        assertNull(Packet.decode("hello there, this is not room".toByteArray()))
    }

    @Test fun roomHashIgnoresCaseAndSpace() {
        assertEquals(Packet.roomHash("Room"), Packet.roomHash(" room "))
    }
}
