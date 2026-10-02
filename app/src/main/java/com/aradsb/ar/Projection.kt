package com.aradsb.ar

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An immutable snapshot of the overlay's view geometry for one instant: the remapped
 * device->world rotation, the focal length in pixels, the view centre, the magnetic
 * declination and the current alignment offsets.
 *
 * It is produced on the UI thread by [ArOverlayView.projectionSnapshot] and consumed by the
 * computer-vision aligner on a background thread, so it carries copies of everything (no shared
 * mutable state). All angle inputs/outputs are degrees; pixel coordinates are in the overlay's
 * view space, which is identical to the PreviewView's space (both fill the same parent).
 */
class Projection(
    private val remapped: FloatArray,   // 9, device->world (already remapped for display rotation)
    private val fpx: Float,
    private val cx: Float,
    private val cy: Float,
    val declinationDeg: Float,
    val azOffsetDeg: Float,
    val elOffsetDeg: Float,
    val viewW: Int,
    val viewH: Int,
) {
    /** World ENU unit direction -> view pixel, or null if it is behind the camera. */
    fun worldDirToView(e: Float, n: Float, u: Float): FloatArray? {
        val sx = remapped[0] * e + remapped[3] * n + remapped[6] * u
        val sy = remapped[1] * e + remapped[4] * n + remapped[7] * u
        val sz = remapped[2] * e + remapped[5] * n + remapped[8] * u
        val depth = -sz
        if (depth <= 1e-3f) return null
        return floatArrayOf(cx + (sx / depth) * fpx, cy - (sy / depth) * fpx)
    }

    /** View pixel -> world ENU unit direction (always defined). Returns [e, n, u]. */
    fun viewToWorldDir(px: Float, py: Float): FloatArray {
        val dx = (px - cx) / fpx
        val dy = -(py - cy) / fpx
        val dz = -1f
        var e = remapped[0] * dx + remapped[1] * dy + remapped[2] * dz
        var n = remapped[3] * dx + remapped[4] * dy + remapped[5] * dz
        var u = remapped[6] * dx + remapped[7] * dy + remapped[8] * dz
        val len = sqrt(e * e + n * n + u * u)
        if (len > 0f) { e /= len; n /= len; u /= len }
        return floatArrayOf(e, n, u)
    }

    /**
     * Target (true azimuth, elevation) -> view pixel, applying the current declination and
     * alignment offsets exactly as the overlay does when it draws. Null if behind the camera.
     */
    fun azElToView(azTrueDeg: Double, elDeg: Double): FloatArray? {
        val azMag = Math.toRadians((azTrueDeg + azOffsetDeg) - declinationDeg)
        val ele = Math.toRadians(elDeg + elOffsetDeg)
        val cE = cos(ele)
        return worldDirToView((cE * sin(azMag)).toFloat(), (cE * cos(azMag)).toFloat(), sin(ele).toFloat())
    }

    /**
     * View pixel -> measured magnetic azimuth and elevation, degrees. Magnetic azimuth in [0,360).
     * (The caller adds declination and subtracts the predicted true azimuth to get a desired offset.)
     */
    fun viewToMagAzEl(px: Float, py: Float): DoubleArray {
        val d = viewToWorldDir(px, py)
        var az = Math.toDegrees(atan2(d[0].toDouble(), d[1].toDouble()))
        if (az < 0) az += 360.0
        val el = Math.toDegrees(asin(d[2].toDouble().coerceIn(-1.0, 1.0)))
        return doubleArrayOf(az, el)
    }
}
