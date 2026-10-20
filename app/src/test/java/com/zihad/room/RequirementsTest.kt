package com.zihad.room

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequirementsTest {
    @Test fun acceptsOnlyOrbitNebula() {
        assertTrue(Requirements.isRequiredSsid("\"OrbitNebula\""))
        assertTrue(Requirements.isRequiredSsid("OrbitNebula"))
        assertTrue(Requirements.isRequiredSsid("\"Orbit Nebula\""))
        assertFalse(Requirements.isRequiredSsid("\"OrbitNebula_5G\""))
        assertFalse(Requirements.isRequiredSsid("\"Rumah\""))
        assertFalse(Requirements.isRequiredSsid("<unknown ssid>"))
        assertFalse(Requirements.isRequiredSsid(null))
    }

    @Test fun unknownSsidIsDistinguishedFromWrongSsid() {
        assertTrue(Requirements.isUnknownSsid("<unknown ssid>"))
        assertTrue(Requirements.isUnknownSsid(null))
        assertFalse(Requirements.isUnknownSsid("\"Rumah\""))
    }

    @Test fun intercomNameIgnoresCaseAndSeparators() {
        assertTrue(Requirements.isIntercomName("X2PLUS"))
        assertTrue(Requirements.isIntercomName("x2 plus"))
        assertTrue(Requirements.isIntercomName("X2-PLUS_1A2B"))
        assertFalse(Requirements.isIntercomName("X1"))
        assertFalse(Requirements.isIntercomName("JBL Flip"))
        assertFalse(Requirements.isIntercomName(null))
    }
}
