package com.playtranslate.ui

import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * Dims the app screen after a period of inactivity. Any interaction
 * (app touch or floating icon touch) resets the timer and undims.
 *
 * Create when the app should dim on inactivity (not during live mode).
 * Call [cancel] to clean up and undim.
 */
class DimController(private val overlay: View, private val timeoutMs: Long = 20000L) {

    companion object {
        var instance: DimController? = null

        /** Called from AccessibilityService when the floating icon is touched. */
        fun notifyInteraction() { instance?.onInteraction() }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable { dim() }
    private var isDimmed = false

    init {
        instance = this
        resetTimer()
    }

    fun onInteraction() {
        if (isDimmed) undim()
        resetTimer()
    }

    fun cancel() {
        handler.removeCallbacks(dimRunnable)
        undim()
        if (instance == this) instance = null
    }

    private fun resetTimer() {
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, timeoutMs)
    }

    private fun dim() {
        isDimmed = true
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(600L).start()
    }

    private fun undim() {
        isDimmed = false
        overlay.animate().cancel()
        overlay.visibility = View.GONE
    }
}
