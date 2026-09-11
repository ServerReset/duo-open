package com.duoopen.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the full-screen fold (accessibility overlay) is live in this
 * process. While it is, the live wallpaper and the app preview skip their own
 * effect — the overlay already folds everything, and a second pass would
 * frost the wallpaper twice.
 */
object OverlayState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal fun setRunning(running: Boolean) {
        _running.value = running
    }
}
