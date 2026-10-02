package com.aradsb.ar

import android.hardware.camera2.CameraCharacteristics
import android.util.SizeF
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min

/**
 * Estimates the camera's angular field of view from the lens focal length and physical sensor
 * size:  FOV = 2 * atan( sensorDimension / (2 * focalLength) ).
 *
 * This is an INITIAL estimate. It uses the full physical sensor size; the actual preview stream
 * may be cropped, and PreviewView (FILL_CENTER) crops again to fill the view. The AR screen
 * therefore exposes a manual FOV calibration so markers can be nudged onto real aircraft.
 * The angular geometry (azimuth/elevation) is exact; only this lens mapping is approximate.
 */
object CameraFov {

    const val DEFAULT_HORIZONTAL_FOV_DEG = 66f

    @OptIn(ExperimentalCamera2Interop::class)
    fun estimateHorizontalFovDeg(cameraInfo: CameraInfo, isLandscape: Boolean): Float? {
        return try {
            val c = Camera2CameraInfo.from(cameraInfo)
            val focals = c.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            ) ?: return null
            val size: SizeF = c.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            ) ?: return null
            if (focals.isEmpty()) return null

            // Widest lens => smallest focal length => largest FOV.
            val focal = focals.min()
            if (focal <= 0f) return null

            // Sensor width is the longer physical dimension; height the shorter.
            val longMm = max(size.width, size.height)
            val shortMm = min(size.width, size.height)

            val fovLong = 2.0 * atan(longMm / (2.0 * focal))   // across the long sensor axis (wider)
            val fovShort = 2.0 * atan(shortMm / (2.0 * focal))  // across the short axis (narrower)

            // Back cameras are usually mounted at 90°: in portrait the long sensor axis maps to the
            // screen's vertical, so the screen's HORIZONTAL FOV is the narrower one; in landscape it's
            // the wider one.
            val horizontalRad = if (isLandscape) fovLong else fovShort
            Math.toDegrees(horizontalRad).toFloat()
        } catch (_: Throwable) {
            null
        }
    }
}
