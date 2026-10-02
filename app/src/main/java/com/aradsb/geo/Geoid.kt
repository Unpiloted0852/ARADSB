package com.aradsb.geo

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EGM2008 geoid undulation N (metres above the WGS-84 ellipsoid), from a bundled
 * 15-arc-minute grid subsampled from the GeographicLib/NGA EGM2008 5' dataset
 * (assets/egm2008_15min.bin: 1440x721 little-endian int16 centimetres; row 0 = lat +90,
 * col 0 = lon 0E, 0.25 deg steps, longitude wrapping).
 *
 * Accuracy of this grid vs the source 5' dataset (measured during generation): ~6 cm RMS,
 * worst ~1.7 m at the steepest geoid gradients. Orthometric (≈ MSL) height = HAE − N.
 */
class Geoid(context: Context) {

    private val grid: ShortArray

    init {
        val bytes = context.assets.open("egm2008_15min.bin").use { it.readBytes() }
        require(bytes.size == W * H * 2) { "geoid asset size ${bytes.size}" }
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        grid = ShortArray(W * H).also { sb.get(it) }
    }

    /** Geoid undulation N in metres at (latDeg, lonDeg), bilinear-interpolated. */
    fun undulation(latDeg: Double, lonDeg: Double): Double {
        val lat = latDeg.coerceIn(-90.0, 90.0)
        var lon = lonDeg % 360.0
        if (lon < 0) lon += 360.0
        val r = (90.0 - lat) / STEP
        val c = lon / STEP
        val r0 = kotlin.math.floor(r).toInt().coerceIn(0, H - 1)
        val c0 = kotlin.math.floor(c).toInt().coerceIn(0, W - 1)
        val fr = (r - r0).coerceIn(0.0, 1.0)
        val fc = (c - c0).coerceIn(0.0, 1.0)
        val r1 = (r0 + 1).coerceAtMost(H - 1)
        val c1 = (c0 + 1) % W
        fun g(ri: Int, ci: Int) = grid[ri * W + ci] / 100.0
        return (1 - fr) * (1 - fc) * g(r0, c0) + (1 - fr) * fc * g(r0, c1) +
            fr * (1 - fc) * g(r1, c0) + fr * fc * g(r1, c1)
    }

    companion object {
        private const val W = 1440
        private const val H = 721
        private const val STEP = 0.25
    }
}
