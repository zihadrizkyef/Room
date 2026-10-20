package com.zihad.room

import android.view.KeyEvent
import com.zihad.room.audio.MediaKeyFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaKeyFilterTest {

    @Test fun satuTekanSatuToggle() {
        val f = MediaKeyFilter()
        assertTrue(f.accept(down = true, repeatCount = 0, nowMs = 1000))
        assertFalse(f.accept(down = false, repeatCount = 0, nowMs = 1080))   // UP diabaikan
    }

    @Test fun ditahanTidakToggleBerulang() {
        val f = MediaKeyFilter()
        assertTrue(f.accept(true, 0, 1000))
        assertFalse(f.accept(true, 1, 1500))
        assertFalse(f.accept(true, 2, 1600))
    }

    @Test fun pauseLaluPlayBeruntunDihitungSekali() {
        val f = MediaKeyFilter(debounceMs = 400)
        assertTrue(f.accept(true, 0, 1000))
        assertFalse(f.accept(true, 0, 1150))
        assertTrue(f.accept(true, 0, 1500))
    }

    @Test fun tombolYangDihitung() {
        assertTrue(MediaKeyFilter.isToggleKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertTrue(MediaKeyFilter.isToggleKey(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertTrue(MediaKeyFilter.isToggleKey(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertTrue(MediaKeyFilter.isToggleKey(KeyEvent.KEYCODE_HEADSETHOOK))
        assertFalse(MediaKeyFilter.isToggleKey(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertFalse(MediaKeyFilter.isToggleKey(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
