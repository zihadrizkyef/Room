package com.zihad.room

/**
 * Perangkat yang wajib dipakai. Room sengaja tidak bisa dipakai di WiFi atau Bluetooth lain:
 * satu motor, satu MiFi, satu intercom — tidak ada pilihan yang bisa salah.
 */
object Requirements {
    const val WIFI_SSID = "OrbitNebula"
    const val INTERCOM_NAME = "X2PLUS"

    private const val UNKNOWN_SSID = "<unknown ssid>"

    /** Android mengembalikan SSID dengan tanda kutip ("OrbitNebula"), atau "<unknown ssid>" tanpa izin lokasi. */
    fun isUnknownSsid(raw: String?) = raw == null || raw == UNKNOWN_SSID

    fun cleanSsid(raw: String?) = raw?.trim()?.removeSurrounding("\"").orEmpty()

    fun isRequiredSsid(raw: String?) = !isUnknownSsid(raw) && cleanSsid(raw) == WIFI_SSID

    /** Nama Bluetooth boleh ditulis "X2PLUS", "X2 Plus", "x2-plus", dst. */
    fun isIntercomName(name: CharSequence?): Boolean {
        val n = name?.filter { it.isLetterOrDigit() }?.toString()?.lowercase() ?: return false
        return n.contains(INTERCOM_NAME.lowercase())
    }
}
