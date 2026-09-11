package com.duoopen.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide view of the power state the effect cares about.
 *
 * [batterySaver] follows Android's Battery Saver (and, on Samsung, Power
 * Saving mode); [screenOn] follows the display. Both are observed through
 * dynamic receivers registered from [DuoApplication], so the wallpaper and the
 * accessibility service can dial the effect back the moment the system asks
 * them to — no polling.
 */
object PowerState {

    private val _batterySaver = MutableStateFlow(false)
    val batterySaver: StateFlow<Boolean> = _batterySaver.asStateFlow()

    private val _screenOn = MutableStateFlow(true)
    val screenOn: StateFlow<Boolean> = _screenOn.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val app = context.applicationContext
        val power = app.getSystemService(PowerManager::class.java)
        _batterySaver.value = power?.isPowerSaveMode == true
        _screenOn.value = power?.isInteractive ?: true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        _batterySaver.value = power?.isPowerSaveMode == true
                        Log.i(TAG, "battery saver=${_batterySaver.value}")
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        _screenOn.value = true
                        Log.i(TAG, "screen on")
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        _screenOn.value = false
                        Log.i(TAG, "screen off")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // System broadcasts; protected actions can't be spoofed by other apps.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    /** Direct query, in case the receiver hasn't fired yet (e.g. early start). */
    fun isBatterySaver(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    private const val TAG = "DuoPower"
}
