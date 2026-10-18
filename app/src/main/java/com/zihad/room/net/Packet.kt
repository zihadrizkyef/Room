package com.zihad.room.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Format paket UDP Room. Header 24 byte, little-endian:
 *
 *  0  magic 'R','M'
 *  2  versi
 *  3  tipe (HELLO / AUDIO / PING / PONG / BYE)
 *  4  hash kode room   — paket dari room lain diabaikan
 *  8  id pengirim      — acak per sesi
 * 12  seq              — nomor frame audio, atau id ping
 * 16  waktu (nanodetik) — untuk ping/pong
 * 24  payload          — nama (HELLO) atau PCM16 mono (AUDIO)
 */
enum class PacketType(val code: Int) {
    HELLO(1), AUDIO(2), PING(3), PONG(4), BYE(5);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

class Packet(
    val type: PacketType,
    val room: Int,
    val sender: Int,
    val seq: Int = 0,
    val time: Long = 0,
    val name: String = "",
    val pcm: ShortArray? = null,
) {
    fun encode(): ByteArray {
        val payload = when (type) {
            PacketType.HELLO -> name.toByteArray(Charsets.UTF_8).take(MAX_NAME_BYTES).toByteArray()
            PacketType.AUDIO -> ByteArray(pcm!!.size * 2).also { out ->
                ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
            }
            else -> ByteArray(0)
        }
        val buf = ByteBuffer.allocate(HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('R'.code.toByte()).put('M'.code.toByte()).put(VERSION).put(type.code.toByte())
        buf.putInt(room).putInt(sender).putInt(seq).putLong(time).put(payload)
        return buf.array()
    }

    companion object {
        const val HEADER = 24
        const val VERSION: Byte = 1
        const val MAX_NAME_BYTES = 32

        /** Mengembalikan null untuk paket yang bukan milik Room atau rusak. */
        fun decode(data: ByteArray, length: Int = data.size): Packet? {
            if (length < HEADER) return null
            val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.LITTLE_ENDIAN)
            if (buf.get() != 'R'.code.toByte() || buf.get() != 'M'.code.toByte()) return null
            if (buf.get() != VERSION) return null
            val type = PacketType.of(buf.get().toInt()) ?: return null
            val room = buf.int
            val sender = buf.int
            val seq = buf.int
            val time = buf.long
            val payloadLen = length - HEADER
            return when (type) {
                PacketType.HELLO -> Packet(type, room, sender, seq, time,
                    name = String(data, HEADER, payloadLen, Charsets.UTF_8))
                PacketType.AUDIO -> {
                    if (payloadLen % 2 != 0) return null
                    val pcm = ShortArray(payloadLen / 2)
                    buf.asShortBuffer().get(pcm)
                    Packet(type, room, sender, seq, time, pcm = pcm)
                }
                else -> Packet(type, room, sender, seq, time)
            }
        }

        fun roomHash(code: String): Int = code.trim().lowercase().hashCode()
    }
}
