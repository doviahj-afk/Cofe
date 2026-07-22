package com.joshua.screenrecorder

import android.content.SharedPreferences

data class SrSettings(
    val fps: Int,
    val quality: String,
    val storageMode: String,
    val storagePath: String,
    val theme: String,
    val mic: Boolean
)

object Prefs {
    const val NAME = "sr_prefs"
    const val FPS = "fps"
    const val QUALITY = "quality"
    const val STORAGE_MODE = "storageMode"
    const val STORAGE_PATH = "storagePath"
    const val THEME = "theme"
    const val MIC = "mic"

    fun readAll(prefs: SharedPreferences): SrSettings {
        return SrSettings(
            fps = prefs.getInt(FPS, 30),
            quality = prefs.getString(QUALITY, "high") ?: "high",
            storageMode = prefs.getString(STORAGE_MODE, "default") ?: "default",
            storagePath = prefs.getString(STORAGE_PATH, "") ?: "",
            theme = prefs.getString(THEME, "system") ?: "system",
            mic = prefs.getBoolean(MIC, false)
        )
    }
}
