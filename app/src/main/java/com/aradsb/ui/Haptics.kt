package com.aradsb.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short, deliberate haptic "clicks" for two moments:
 *  - [capture]  a crisp single tick when the reticle locks onto an aircraft;
 *  - [unlock]   a firmer two-pulse "ka-chunk" when the compass is unlocked for calibration.
 *
 * Uses the modern VibrationEffect API for calibrated, satisfying clicks where available and
 * degrades cleanly on older devices and on hardware without a vibrator (no-ops).
 */
class Haptics(context: Context) {

    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private val available = vibrator?.hasVibrator() == true
    private var lastCaptureMs = 0L

    /** Crisp single click — an aircraft was captured. */
    fun capture() {
        val v = vibrator ?: return
        if (!available) return
        // Cooldown: aircraft selection can flicker many times a second from hand jitter near a
        // marker. Without this guard each call would cancel the previous vibrate() before it
        // finished playing, so nothing is ever felt. Letting one pulse complete fixes that and
        // also stops a buzz-storm when panning across traffic.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastCaptureMs < CAPTURE_COOLDOWN_MS) return
        lastCaptureMs = now
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 30L), intArrayOf(0, 255), -1))
    }

    /** Firmer two-pulse click — the compass just unlocked for manual calibration. */
    fun unlock() {
        val v = vibrator ?: return
        if (!available) return
        // A light pulse then a heavier one, with a short gap: a tactile "ka-chunk".
        val timings = longArrayOf(0L, 16L, 38L, 30L)
        val amplitudes = intArrayOf(0, 150, 0, 255)
        v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    companion object {
        // Minimum gap between capture clicks, so flickering selection (or fast panning across
        // traffic) can't retrigger before one pulse finishes — or buzz continuously.
        private const val CAPTURE_COOLDOWN_MS = 500L
    }
}
