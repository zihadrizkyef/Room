# Room

Intercom keluarga untuk satu motor: beberapa HP, satu MiFi, masing-masing tersambung ke intercom Bluetooth.

**Cara pakai:** sambungkan semua HP ke WiFi MiFi yang sama → buka Room. Selesai. Tidak ada host, nama, atau kode room.

## Kenapa lebih cepat dari versi lama / Discord

| | Versi lama (WebRTC) | Room 2 |
|---|---|---|
| Codec | Opus 16 kbps + DTX (putus-putus saat angin) | Tanpa codec: PCM 16 kHz mentah, dikirim per 10 ms |
| Antrean penerima | Bawaan WebRTC, bisa sampai detik | 20–60 ms, dibatasi keras; kelebihan dibuang |
| Prioritas WiFi | Normal | DSCP EF → antrean "voice" WiFi |
| Power-save WiFi | `HIGH_PERF` (tidak efektif di Android 10+) | `LOW_LATENCY` + layar tetap nyala |
| Mic | `startBluetoothSco()` bisa gagal diam-diam → mic HP | `setCommunicationDevice()` + UI menampilkan mic yang benar-benar dipakai |
| Olahan suara | AEC + AGC + NS WebRTC | High-pass 150 Hz + noise suppressor HP; AGC mati |
| Topologi | Butuh 1 HP host | Semua setara, ditemukan otomatis lewat broadcast |

Yang **tidak** bisa dihilangkan aplikasi: delay Bluetooth mode telepon (HFP) di dua ujung dan buffer audio Android.
Angka "jaringan" di layar = delay WiFi + antrean saja, belum termasuk Bluetooth.

## Build

```
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`. Desain: `design/Room Prototype.html`.
