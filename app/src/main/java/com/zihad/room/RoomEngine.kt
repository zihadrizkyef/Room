package com.zihad.room

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Network
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.zihad.room.audio.AudioRouter
import com.zihad.room.audio.Dsp
import com.zihad.room.audio.HighPass
import com.zihad.room.audio.JitterBuffer
import com.zihad.room.audio.VoiceGate
import com.zihad.room.net.Packet
import com.zihad.room.net.PacketType
import com.zihad.room.net.WifiCheck
import com.zihad.room.net.WifiGuard
import com.zihad.room.net.WifiStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

enum class MicRoute { BLUETOOTH, PHONE, UNKNOWN }

data class PeerUi(val id: Int, val name: String, val level: Float, val delayMs: Int?, val lost: Boolean)

data class RoomState(
    val running: Boolean = false,
    val muted: Boolean = false,
    val talking: Boolean = false,
    val myName: String = "",
    val myLevel: Float = 0f,
    val micRoute: MicRoute = MicRoute.UNKNOWN,
    val micLabel: String = "",
    val peers: List<PeerUi> = emptyList(),
    val wifi: WifiStatus = WifiStatus.OK,
    val wifiName: String = "",
    val error: String? = null,
)

/** Titik temu UI ↔ service. Engine hidup selama RoomService jalan. */
object Room {
    internal val mutableState = MutableStateFlow(RoomState())
    val state: StateFlow<RoomState> = mutableState
    @Volatile internal var engine: RoomEngine? = null

    fun toggleMute() { engine?.toggleMute() }
    fun connectBluetooth() { engine?.connectBluetooth() }
}

/**
 * Intercom lokal tanpa server dan tanpa codec:
 * - Suara PCM 16 kHz mono, dikirim per 20 ms langsung (UDP unicast) ke tiap HP lain.
 *   Di WiFi lokal bandwidth-nya kecil (~256 kbps per arah), jadi tidak perlu dikompres —
 *   tidak ada delay encode/decode dan tidak ada artefak codec.
 * - Yang membebani MiFi murah adalah jumlah paket, bukan ukurannya: karena itu frame 20 ms
 *   (bukan 10) dan gerbang suara — saat tidak ada yang bicara, tidak ada paket audio sama sekali.
 * - Paket ditandai DSCP EF supaya MiFi/WiFi memasukkannya ke antrean "voice" (WMM AC_VO).
 * - HP lain ditemukan otomatis lewat broadcast HELLO tiap detik. Semua HP di WiFi yang
 *   sama = satu obrolan; tidak ada host, jadi tidak ada satu HP yang jadi titik gagal.
 */
class RoomEngine(private val context: Context) {

    private class Peer(val id: Int, @Volatile var addr: InetAddress) {
        @Volatile var name = "HP"
        @Volatile var lastSeen = SystemClock.elapsedRealtime()
        @Volatile var rttMs: Int? = null
        @Volatile var level = 0f
        val buffer = JitterBuffer(minDepth = 2, maxDepth = 8, calmFramesToShrink = 500, shortGapFrames = 15)
        val lost get() = SystemClock.elapsedRealtime() - lastSeen > LOST_AFTER_MS
    }

    private val myId = Random.nextInt(1, Int.MAX_VALUE)
    private val myName = deviceName()
    private val peers = ConcurrentHashMap<Int, Peer>()
    private val running = AtomicBoolean(false)
    private val router = AudioRouter(context) { publish() }
    private val threads = mutableListOf<Thread>()

    private var socket: DatagramSocket? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    @Volatile private var muted = false
    @Volatile private var myLevel = 0f
    @Volatile private var talking = false
    @Volatile private var micDevice: AudioDeviceInfo? = null
    @Volatile private var wifi = WifiCheck(WifiStatus.NO_WIFI)
    @Volatile private var intercomOk = false
    private var boundNetwork: Network? = null

    /** Suara hanya jalan kalau WiFi dan intercom yang diwajibkan sama-sama benar. */
    private val canTalk get() = wifi.status == WifiStatus.OK && intercomOk

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            router.start()
            socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                trafficClass = DSCP_EF
                bind(InetSocketAddress(PORT))
            }
            refreshWifi()
            openAudio()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mulai", e)
            stop()
            Room.mutableState.value = RoomState(error = "Gagal mulai: ${e.message}")
            return
        }
        threads += thread(name = "room-rx") { receiveLoop() }
        threads += thread(name = "room-mic") { captureLoop() }
        threads += thread(name = "room-play") { playLoop() }
        threads += thread(name = "room-tick") { tickLoop() }
        publish()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { sendToPeers(Packet(PacketType.BYE, ROOM, myId).encode()) }
        socket?.close()
        threads.forEach { it.join(500) }
        threads.clear()
        record?.run { runCatching { stop() }; release() }
        track?.run { runCatching { stop() }; release() }
        noiseSuppressor?.release()
        gainControl?.release()
        record = null; track = null; noiseSuppressor = null; gainControl = null; socket = null
        router.stop()
        peers.clear()
        Room.mutableState.value = RoomState()
    }

    fun toggleMute() { muted = !muted; publish() }

    fun connectBluetooth() { router.connectBluetooth(); publish() }

    // ---------------------------------------------------------------- audio

    @SuppressLint("MissingPermission")   // izin mic diminta di MainActivity sebelum service jalan
    private fun openAudio() {
        val minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, FRAME_BYTES * 4))
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "mic tidak bisa dibuka" }
        // Peredam bising bawaan HP: nyala. Penguat otomatis: mati (ia ikut membesarkan angin).
        if (NoiseSuppressor.isAvailable()) noiseSuppressor = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true }
        if (AutomaticGainControl.isAvailable()) gainControl = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = false }
        rec.addOnRoutingChangedListener({ r -> micDevice = r.routedDevice; publish() }, Handler(Looper.getMainLooper()))
        rec.startRecording()
        micDevice = rec.routedDevice
        record = rec

        val minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val trk = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(maxOf(minOut, FRAME_BYTES * 3))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        trk.play()
        track = trk
    }

    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rec = record ?: return
        val hp = HighPass(SAMPLE_RATE)
        val gate = VoiceGate(hangoverFrames = 400 / FRAME_MS)
        val frame = ShortArray(FRAME)
        var seq = 0
        var wasOpen = false
        while (running.get()) {
            val n = rec.read(frame, 0, FRAME)
            if (n < FRAME) { if (n < 0) SystemClock.sleep(10); continue }
            hp.process(frame)
            val open = gate.isOpen(frame)
            if (muted || !open || !canTalk) { myLevel = 0f; talking = false; wasOpen = false; continue }
            myLevel = smooth(myLevel, Dsp.level(frame))
            talking = true
            val flags = if (wasOpen) 0L else FLAG_TALK_START
            wasOpen = true
            sendToPeers(Packet(PacketType.AUDIO, ROOM, myId, seq++, time = flags, pcm = frame).encode())
        }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val trk = track ?: return
        val out = ShortArray(FRAME)
        val frames = ArrayList<ShortArray>(4)
        while (running.get()) {
            frames.clear()
            val audible = canTalk
            for (p in peers.values) {
                val f = p.buffer.pop()
                if (!audible) {
                    p.level = 0f
                } else if (f != null && f.isNotEmpty()) {
                    frames += f
                    p.level = smooth(p.level, Dsp.level(f))
                } else {
                    p.level *= 0.8f
                }
            }
            if (frames.isEmpty()) out.fill(0) else Dsp.mix(frames, 1f, out)
            if (trk.write(out, 0, FRAME) < 0) SystemClock.sleep(10)   // write blocking = jam putar
        }
    }

    // ------------------------------------------------------------- jaringan

    private fun receiveLoop() {
        val s = socket ?: return
        val buf = ByteArray(2048)
        val dp = DatagramPacket(buf, buf.size)
        while (running.get()) {
            try { s.receive(dp) } catch (_: Exception) { break }
            val p = Packet.decode(buf, dp.length) ?: continue
            if (p.room != ROOM || p.sender == myId || wifi.status != WifiStatus.OK) continue
            if (p.type == PacketType.BYE) { peers.remove(p.sender); continue }

            var isNew = false
            val peer = peers.computeIfAbsent(p.sender) { isNew = true; Peer(it, dp.address) }
            peer.addr = dp.address
            peer.lastSeen = SystemClock.elapsedRealtime()
            // HP yang sama gabung lagi setelah putus mendadak (tanpa BYE) membawa id baru.
            // Satu IP = satu HP, jadi entri lama dengan IP yang sama langsung diganti.
            if (isNew) peers.values.removeIf { it.id != p.sender && it.addr == dp.address }

            when (p.type) {
                PacketType.AUDIO -> peer.buffer.push(p.seq, p.pcm!!, talkStart = p.time == FLAG_TALK_START)
                PacketType.HELLO -> {
                    peer.name = p.name.ifBlank { "HP" }
                    // IP-nya bisa berubah (DHCP); entri lama bernama sama yang sudah putus = HP ini juga.
                    peers.values.removeIf { it.id != p.sender && it.lost && it.name == peer.name }
                    // Balas langsung supaya HP baru tahu kita, walau broadcast-nya cuma lewat satu arah.
                    if (isNew) send(hello(), peer.addr)
                }
                PacketType.PING -> send(Packet(PacketType.PONG, ROOM, myId, p.seq, p.time).encode(), peer.addr)
                PacketType.PONG -> {
                    val rtt = ((System.nanoTime() - p.time) / 1_000_000).toInt()
                    peer.rttMs = peer.rttMs?.let { (it * 7 + rtt * 3) / 10 } ?: rtt   // rata-rata bergerak
                }
                PacketType.BYE -> Unit
            }
        }
    }

    private fun tickLoop() {
        var tick = 0
        while (running.get()) {
            if (tick % 10 == 0) {
                refreshWifi()
                if (!intercomOk && tick % 30 == 0) router.connectBluetooth()   // intercom dinyalakan belakangan
                val hello = hello()
                broadcastAddresses().forEach { send(hello, it) }
                val now = System.nanoTime()
                sendToPeers(Packet(PacketType.PING, ROOM, myId, tick, now).encode())
                val cutoff = SystemClock.elapsedRealtime() - FORGET_AFTER_MS
                peers.values.removeIf { it.lastSeen < cutoff }
            }
            publish()
            if (tick % 50 == 0) logStats()
            tick++
            SystemClock.sleep(100)
        }
    }

    private fun hello() = Packet(PacketType.HELLO, ROOM, myId, name = myName).encode()

    // Tetap kirim ke HP yang ditandai "terputus": PING/HELLO yang hilang sebentar di WiFi jelek
    // tidak boleh membuat suara kita berhenti dikirim sampai ada paket masuk lagi.
    private fun sendToPeers(data: ByteArray) {
        for (p in peers.values) send(data, p.addr)
    }

    private fun send(data: ByteArray, to: InetAddress) {
        if (wifi.status != WifiStatus.OK) return   // jangan pernah bocor ke WiFi lain
        try { socket?.send(DatagramPacket(data, data.size, to, PORT)) } catch (_: Exception) { }
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val out = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                ni.interfaceAddresses.mapNotNullTo(out) { it.broadcast }
            }
        }
        return out
    }

    // ------------------------------------------------------------------- UI

    private fun publish() {
        if (!running.get()) return
        val mic = micDevice
        val out = router.currentOutput()
        val route = when {
            router.isIntercom(mic) || (mic == null && router.isIntercom(out)) -> MicRoute.BLUETOOTH
            mic != null -> MicRoute.PHONE
            else -> MicRoute.UNKNOWN
        }
        intercomOk = route == MicRoute.BLUETOOTH
        val label = when {
            route == MicRoute.BLUETOOTH -> "Intercom ${Requirements.INTERCOM_NAME}"
            route == MicRoute.PHONE && AudioRouter.isBluetooth(mic) ->
                "Bluetooth lain (${mic?.productName}), bukan ${Requirements.INTERCOM_NAME}"
            route == MicRoute.PHONE -> "Mic HP, ${Requirements.INTERCOM_NAME} belum tersambung"
            else -> "Menyiapkan mic…"
        }
        Room.mutableState.value = RoomState(
            running = true,
            muted = muted,
            talking = talking && !muted,
            myName = myName,
            myLevel = if (muted) 0f else myLevel,
            micRoute = route,
            micLabel = label,
            peers = peers.values.sortedBy { it.name }.map {
                PeerUi(it.id, it.name, if (it.lost) 0f else it.level,
                    it.rttMs?.let { rtt -> rtt / 2 + it.buffer.target * FRAME_MS }, it.lost)
            },
            wifi = wifi.status,
            wifiName = wifi.ssid,
        )
    }

    /** Cek WiFi dan kunci socket ke jaringan OrbitNebula supaya paket tidak lari ke jalur lain. */
    private fun refreshWifi() {
        val w = WifiGuard.check(context)
        val net = w.network.takeIf { w.status == WifiStatus.OK }
        if (net == null) {
            boundNetwork = null
        } else if (net != boundNetwork) {
            val s = socket
            if (s != null && runCatching { net.bindSocket(s) }.onFailure { Log.w(TAG, "bindSocket gagal", it) }.isSuccess) {
                boundNetwork = net
            }
        }
        wifi = w
    }

    /** Statistik tiap 5 detik: `adb logcat -s Room` setelah uji di jalan untuk melihat sumber putus-putus. */
    private fun logStats() {
        Log.i(TAG, "wifi=${wifi.status} ${wifi.ssid} intercom=$intercomOk peers=${peers.size}")
        for (p in peers.values) {
            val b = p.buffer
            Log.i(TAG, "peer=${p.name} lost=${p.lost} rtt=${p.rttMs} target=${b.target} " +
                "played=${b.played} missing=${b.missing} late=${b.late} overflow=${b.overflow}")
        }
    }

    private fun deviceName(): String =
        (Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL)
            .take(24)

    private fun smooth(prev: Float, now: Float) = if (now > prev) now else prev * 0.85f + now * 0.15f

    companion object {
        private const val TAG = "Room"
        const val SAMPLE_RATE = 16_000
        const val FRAME_MS = 20
        const val FRAME = SAMPLE_RATE / 1000 * FRAME_MS
        const val FRAME_BYTES = FRAME * 2
        const val PORT = 47_800
        private const val DSCP_EF = 0xB8
        /** Di paket AUDIO, field waktu dipakai sebagai flag. */
        private const val FLAG_TALK_START = 1L
        private const val LOST_AFTER_MS = 3_000L
        private const val FORGET_AFTER_MS = 30_000L
        /** Satu room tetap: semua HP di WiFi yang sama otomatis bergabung. */
        val ROOM = Packet.roomHash("room")
    }
}
