# AGENTS.md

Panduan untuk agen AI (dan manusia) yang mengerjakan repo ini.

## Apa ini

**Room** — intercom keluarga untuk satu motor. 2–3 HP Android, masing-masing tersambung ke intercom
Bluetooth (mode telepon/HFP), semua tersambung ke satu MiFi. Buka aplikasi = langsung ngobrol.

Prinsip produk yang **tidak boleh dilanggar tanpa diminta pemilik**:

- **Super simple.** Tidak ada layar isian, nama, kode room, login, atau pengaturan. Satu layar: status mic,
  daftar HP, tombol mic raksasa. Nama HP diambil otomatis dari nama perangkat.
- **Tanpa host.** Semua HP setara; satu HP mati tidak memutus yang lain.
- **Delay rendah di atas segalanya.** Setiap perubahan audio/jaringan harus dinilai dampaknya ke delay.

## Struktur

```
app/src/main/java/com/zihad/room/
  RoomEngine.kt        inti: socket UDP, thread rekam/putar/terima/tick, daftar peer, state UI
  RoomService.kt       foreground service (microphone), WiFi lock, wake lock, notifikasi
  audio/AudioRouter.kt arahkan mic & suara ke intercom Bluetooth (SCO)
  audio/JitterBuffer.kt antrean per peer, kedalaman dibatasi keras
  audio/Dsp.kt         high-pass 150 Hz, gerbang suara (VoiceGate), level meter, mixer soft-clip
  net/Packet.kt        format paket UDP (header 24 byte)
  ui/MainActivity.kt   minta izin → langsung start service
  ui/RoomScreen.kt     satu-satunya layar (Compose)
design/Room Prototype.html  mockup acuan visual; token warna di RoomScreen.kt berasal dari sini
```

## Arsitektur audio & jaringan

- **PCM 16 kHz mono, frame 20 ms, UDP unicast** langsung ke tiap peer, port `47800`. Tanpa codec:
  bandwidth WiFi lokal cukup, dan tidak ada delay/artefak encode-decode.
- **Discovery:** broadcast `HELLO` tiap 1 detik ke semua alamat broadcast interface + `255.255.255.255`.
  Peer baru dibalas `HELLO` unicast. `PING`/`PONG` tiap detik untuk angka delay (RTT/2 + antrean).
  Peer dianggap putus setelah 3 detik tanpa paket, dihapus setelah 30 detik. `BYE` saat Stop.
- **Id peer acak per sesi.** HP yang gabung lagi setelah putus mendadak dikenali lewat IP yang sama
  (atau nama sama + entri lama sudah putus) dan entri lamanya diganti — jangan sampai muncul dobel.
- **Gerbang suara:** saat tidak ada yang bicara, tidak ada paket AUDIO sama sekali. Hangover 400 ms.
  Frame pertama setelah diam diberi flag `FLAG_TALK_START` (di field `time`) supaya jeda yang disengaja
  tidak dianggap gangguan WiFi oleh jitter buffer.
- **Jitter buffer:** target 2–5 frame (40–100 ms). Kelebihan dibuang; kosong singkat menaikkan target,
  10 detik stabil menurunkannya. Loss disembunyikan dengan pengulangan frame yang dikecilkan.
- Paket diberi **DSCP EF** (`0xB8`) supaya masuk antrean voice WMM.
- Mic: `VOICE_COMMUNICATION`, NoiseSuppressor HP **nyala**, AutomaticGainControl **mati**.
- Android 12+: `setCommunicationDevice()`. Android ≤ 11: `startBluetoothSco()`. Mic yang *benar-benar*
  dipakai dibaca dari `AudioRecord.routedDevice` dan ditampilkan; mic HP = peringatan oranye.

## Pelajaran dari uji di HP sungguhan (jangan diulang)

- **Yang membebani MiFi murah adalah jumlah paket, bukan ukurannya.** Frame 10 ms + kirim terus di
  3 HP membuat 30–100% paket hilang. Jangan turunkan frame di bawah 20 ms atau menghapus gerbang suara
  tanpa mengukur ulang dengan 3 HP.
- **Bluetooth bisa merusak WiFi 2,4 GHz** di HP tertentu (Galaxy M11: ping 12 ms → ratusan–ribuan ms saat
  SCO aktif). Itu masalah chip, bukan aplikasi. Solusi di luar kode: MiFi 5 GHz.
- **`WIFI_MODE_FULL_LOW_LATENCY` tidak aktif di semua HP** (M11 tidak pernah). Selalu pegang juga
  `WIFI_MODE_FULL_HIGH_PERF`. Low-latency hanya aktif saat layar nyala, karena itu `FLAG_KEEP_SCREEN_ON`.
- Tipe foreground service `microphone` hanya dipakai di API 30+ (crash di API 29).
- Jangan pakai WiFi multicast/broadcast untuk audio: dikirim di basic rate dan tertahan DTIM.
- Delay yang **tidak bisa** dihilangkan aplikasi: Bluetooth HFP di dua ujung + buffer audio Android.

## Build & uji

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Windows; JDK 17+ di tempat lain
./gradlew testDebugUnitTest assembleDebug
```

- Unit test (JVM murni) ada untuk `Packet`, `JitterBuffer`, `Dsp`/`VoiceGate`. Logika audio/jaringan yang
  bisa dipisah dari Android API harus dipisah dan diberi test.
- Pasang ke banyak HP: `adb -s <serial> install -r -g app/build/outputs/apk/debug/app-debug.apk`.
- Di Git Bash, set `MSYS_NO_PATHCONV=1` sebelum `adb shell` dengan path `/sdcard/...`.
- Ukur jaringan dari HP: `adb shell ping -c 30 -i 0.2 <ip>` — bandingkan Room jalan vs mati.
- Jangan klaim kualitas suara membaik tanpa uji di jalan dengan intercom tersambung.

## Gaya kode

- Kotlin, Compose Material3, tanpa library jaringan/audio pihak ketiga.
- Komentar dan teks UI dalam **Bahasa Indonesia**; komentar menjelaskan *kenapa*, bukan *apa*.
- `minSdk 26`, `targetSdk 36`. Cek versi API dengan `Build.VERSION.SDK_INT` dan jangan buat objek
  dari kelas API baru di luar blok pengecekan (crash `NoClassDefFoundError` di HP lama).
