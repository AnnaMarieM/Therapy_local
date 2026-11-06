package com.therapylocal.app.config

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen awake and display preferences.
 * By default, the app keeps the screen awake so therapists can see live cues, recording progress,
 * and session details without the display timing out or going dark when untouched on a desk.
 */
object DisplayConfig {
    const val PREF_KEEP_SCREEN_AWAKE = "keep_screen_awake"

    private val _keepScreenAwake = MutableStateFlow(true)
    val keepScreenAwake: StateFlow<Boolean> = _keepScreenAwake.asStateFlow()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _keepScreenAwake.value = prefs.getBoolean(PREF_KEEP_SCREEN_AWAKE, true)
    }

    fun setKeepScreenAwake(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_KEEP_SCREEN_AWAKE, enabled).apply()
        _keepScreenAwake.value = enabled
    }
}

