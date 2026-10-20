package com.zihad.room.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Tombol play/pause di intercom (AVRCP) = tombol mic di layar. Pengendara tidak bisa melihat HP,
 * jadi setiap perubahan dibalas bunyi pendek lewat intercom.
 */
class MediaButton(context: Context, private val toggle: () -> Boolean) {

    private val filter = MediaKeyFilter()
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70) }.getOrNull()

    private val session = MediaSession(context, "room").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val e = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                if (!MediaKeyFilter.isToggleKey(e.keyCode)) return false
                if (filter.accept(e.action == KeyEvent.ACTION_DOWN, e.repeatCount, e.eventTime)) {
                    val muted = toggle()
                    tone?.startTone(if (muted) ToneGenerator.TONE_PROP_NACK else ToneGenerator.TONE_PROP_ACK, 200)
                }
                return true
            }
        })
        // Android mengirim tombol media ke sesi yang aktif & "sedang main"; tanpa ini tombol
        // jatuh ke YouTube/Spotify yang terakhir dipakai.
        setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
        isActive = true
    }

    fun release() {
        session.isActive = false
        session.release()
        tone?.release()
    }
}

/** Logika murni (bisa dites di JVM): tombol mana yang dihitung, dan satu tekan = satu toggle. */
class MediaKeyFilter(private val debounceMs: Long = 400) {
    private var lastMs = Long.MIN_VALUE / 2

    /**
     * Hanya ACTION_DOWN pertama yang dihitung (bukan UP, bukan auto-repeat saat ditahan).
     * Debounce karena sebagian intercom mengirim PAUSE lalu PLAY beruntun untuk satu tekan.
     */
    fun accept(down: Boolean, repeatCount: Int, nowMs: Long): Boolean {
        if (!down || repeatCount > 0 || nowMs - lastMs < debounceMs) return false
        lastMs = nowMs
        return true
    }

    companion object {
        // Intercom berbeda mengirim kode berbeda untuk tombol yang sama; semuanya berarti "toggle".
        fun isToggleKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            keyCode == KeyEvent.KEYCODE_HEADSETHOOK
    }
}
