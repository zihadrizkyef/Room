package com.zihad.room

import com.zihad.room.update.UpdateRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRuleTest {
    @Test fun parsesTag() {
        assertEquals(12L, UpdateRule.versionOf("v12"))
        assertEquals(3L, UpdateRule.versionOf(" 3 "))
        assertNull(UpdateRule.versionOf("beta"))
        assertNull(UpdateRule.versionOf(null))
    }

    @Test fun onlyNewerVersionsUpdate() {
        assertTrue(UpdateRule.isNewer("v3", 2))
        assertFalse(UpdateRule.isNewer("v2", 2))
        assertFalse(UpdateRule.isNewer("v1", 2))
        assertFalse(UpdateRule.isNewer("nightly", 2))
    }
}
