package com.aradsb.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Provides the device's orientation as a rotation matrix, gyro-stabilised via a complementary
 * filter: the GYROSCOPE is integrated for smooth, magnetically-immune short-term motion, and the
 * fused orientation is slowly corrected toward the fused TYPE_ROTATION_VECTOR (accelerometer +
 * gyroscope + magnetometer) so the absolute heading stays true. This rejects the magnetic jitter
 * that makes the raw rotation-vector heading twitch near metal/electronics, without lagging on
 * real motion (the gyro tracks turns instantly). Quaternions avoid gimbal lock when the phone
 * points near-vertical at overhead aircraft. With no gyroscope it follows the rotation vector
 * directly.
 *
 * The matrix R maps DEVICE coordinates -> WORLD coordinates, where the world frame is
 * East / (magnetic) North / Up:  worldVec = R * deviceVec.
 * North here is MAGNETIC north (the magnetometer reference); convert target bearings with the
 * declination from [com.aradsb.location.LocationTracker] before projecting.
 *
 * COMPASS HEALTH. The rotation vector's own accuracy callback is useless on many devices — it
 * reports HIGH permanently because the fused virtual sensor never updates it. Health is
 * therefore judged from signals that actually move:
 *  1. The RAW magnetometer's accuracy enum (the figure-8 recalibrates THIS sensor, and vendors
 *     do maintain its state).
 *  2. The rotation vector's per-sample estimated heading error (values[4], radians; -1 or
 *     absent on devices that do not provide it), low-pass filtered.
 *  3. Ground truth that cannot lie: the measured magnetic field MAGNITUDE versus the World
 *     Magnetic Model's expected strength at the current location (set via
 *     [expectedFieldUt]). A phone near a magnet/speaker reads a wildly wrong |B| regardless
 *     of what any accuracy enum claims.
 * The combined verdict is the WORST of the three.
 */
class OrientationTracker(
    context: Context,
    private val onChanged: () -> Unit,
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnetometer: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val gyroscope: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val matrix = FloatArray(9)
    @Volatile private var hasValue = false

    // Complementary filter state. The fused orientation is integrated from the GYROSCOPE for
    // smooth, magnetically-immune short-term motion, and slowly corrected toward the rotation
    // vector (the absolute mag/accel reference) so heading stays true over seconds. This rejects
    // the magnetic jitter (rebar, vehicles, the phone's own electronics) that makes the raw
    // rotation-vector heading twitch. Quaternions are used so there is no gimbal lock when the
    // phone points near-vertical at overhead aircraft. With no gyroscope, it degrades to using
    // the rotation vector directly.
    private val fusedQ = floatArrayOf(1f, 0f, 0f, 0f)   // [w,x,y,z], device->world
    private var haveQ = false
    private var lastGyroNs = 0L
    private var lastRvNs = 0L
    // Fast initial alignment: for a short window after every start()/resume the fused orientation
    // tracks the absolute reference tightly so the heading snaps to true instead of sliding in
    // slowly; it then ramps into the steady, jitter-rejecting time constant.
    private var alignStartNs = 0L
    @Volatile private var realign = true
    private var bigErrSince = 0L
    /** When true, heading ignores the magnetometer entirely and is held by the gyroscope
     *  (yaw corrections disabled after the initial seed). Tilt still corrects from gravity. */
    @Volatile var gyroOnlyYaw = false

    /** Raw-magnetometer accuracy (SensorManager.SENSOR_STATUS_*). */
    @Volatile var magAccuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        private set
    /** Smoothed rotation-vector heading-error estimate in degrees, or null if not provided. */
    @Volatile var headingErrDeg: Float? = null
        private set
    /** Smoothed measured magnetic field magnitude in microtesla, or null before first sample. */
    @Volatile var fieldUt: Float? = null
        private set
    /** WMM-expected field magnitude (µT) at the current location; set by the location layer. */
    @Volatile var expectedFieldUt: Float? = null

    /** Combined verdict levels. */
    companion object {
        const val HEALTH_GOOD = 2
        // Complementary-filter time constant: how quickly the gyro-integrated orientation is
        // pulled back to the absolute (rotation-vector) heading. ~1.2 s is "balanced" — fast
        // turns are tracked instantly by the gyro (no lag), while magnetic heading noise and
        // slow drift settle out over about a second. Larger = steadier but slower to settle.
        private const val FUSION_TAU_S = 1.2
        // Fast-align: time constant during the initial snap (≈0.05 s ≈ near-instant follow) and how
        // long it ramps back to the steady value. Keeps startup snappy without losing steady-state
        // jitter rejection.
        private const val ALIGN_TAU_FAST_S = 0.05
        private const val ALIGN_RAMP_S = 1.8
        // Recovery: if the gyro-integrated heading sits more than RECOVER_DEG from the absolute
        // reference for longer than RECOVER_PERSIST_S (i.e. a real divergence, not a sub-second
        // magnetic spike), converge at RECOVER_TAU_S instead of crawling back over ~1.2 s.
        private const val RECOVER_DEG = 18f
        private const val RECOVER_PERSIST_S = 0.4
        private const val RECOVER_TAU_S = 0.15
        // Yaw correction time constant while the magnetometer is untrusted (interference or
        // sensor-reported unreliable). A slow leak rather than a hard freeze: a 15-s walk past a
        // car tugs heading ~3-4°, yet if the environment is permanently disturbed the heading
        // still converges to the only absolute reference available within a few minutes.
        private const val TAU_YAW_UNTRUSTED_S = 120.0
        const val HEALTH_MEDIUM = 1
        const val HEALTH_BAD = 0
        private const val HDG_MED_DEG = 5f
        private const val HDG_BAD_DEG = 15f
        private const val FIELD_DEV_MED_UT = 10f
        private const val FIELD_DEV_BAD_UT = 20f
        private const val FIELD_ABS_MIN_UT = 20f
        private const val FIELD_ABS_MAX_UT = 70f
    }

    /** Invoked on a sensor thread whenever any health input changes. */
    var onHealthChanged: ((Int) -> Unit)? = null
    private var lastHealth = -1

    val isAvailable: Boolean get() = rotationVector != null

    /** The combined compass health: worst of mag accuracy, heading error, field plausibility. */
    fun compassHealth(): Int {
        var level = when (magAccuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HEALTH_GOOD
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> HEALTH_MEDIUM
            else -> HEALTH_BAD
        }
        headingErrDeg?.let { e ->
            val h = when {
                e > HDG_BAD_DEG -> HEALTH_BAD
                e > HDG_MED_DEG -> HEALTH_MEDIUM
                else -> HEALTH_GOOD
            }
            if (h < level) level = h
        }
        fieldUt?.let { b ->
            var h = HEALTH_GOOD
            if (b < FIELD_ABS_MIN_UT || b > FIELD_ABS_MAX_UT) h = HEALTH_BAD
            expectedFieldUt?.let { exp ->
                val dev = abs(b - exp)
                if (dev > FIELD_DEV_BAD_UT) h = HEALTH_BAD
                else if (dev > FIELD_DEV_MED_UT && h > HEALTH_MEDIUM) h = HEALTH_MEDIUM
            }
            if (h < level) level = h
        }
        return level
    }

    /**
     * True when the measured field magnitude is implausible or far from the World Magnetic Model
     * expectation — the signature of nearby ferrous metal or a magnet (a car, a magnetic case or
     * mount, speakers). This is interference a figure-8 cannot fix; the cure is distance from the
     * metal. Distinct from low accuracy, which a figure-8 does fix.
     */
    fun magneticInterference(): Boolean {
        val b = fieldUt ?: return false
        if (b < FIELD_ABS_MIN_UT || b > FIELD_ABS_MAX_UT) return true
        val exp = expectedFieldUt ?: return false
        return abs(b - exp) > FIELD_DEV_BAD_UT
    }

    /** Copies the latest device->world rotation matrix into [out] (size 9). Returns false if none yet. */
    fun copyRotationMatrix(out: FloatArray): Boolean {
        if (!hasValue) return false
        synchronized(matrix) { System.arraycopy(matrix, 0, out, 0, 9) }
        return true
    }

    fun start() {
        realign = true   // re-run the fast-align snap on every open/resume
        rotationVector?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        magnetometer?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        gyroscope?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                // Absolute reference. Seed the fused quaternion on the first sample; thereafter
                // nudge it toward this absolute orientation by a small fraction set by the time
                // constant, low-passing the magnetometer's heading noise.
                val qAbs = FloatArray(4)
                SensorManager.getQuaternionFromVector(qAbs, event.values)  // [w,x,y,z]
                if (!haveQ || gyroscope == null) {
                    if (!haveQ) {
                        System.arraycopy(qAbs, 0, fusedQ, 0, 4); haveQ = true
                        alignStartNs = event.timestamp; realign = false
                    } else { quatSlerpInto(fusedQ, qAbs, 1f) }   // no gyro → follow RV directly
                } else {
                    val dt = if (lastRvNs == 0L) 0.02 else (event.timestamp - lastRvNs) / 1e9
                    if (realign) { alignStartNs = event.timestamp; realign = false; bigErrSince = 0L }
                    // Ramp the time constant from the fast snap up to the steady value.
                    val elapsed = (event.timestamp - alignStartNs) / 1e9
                    val frac = (elapsed / ALIGN_RAMP_S).coerceIn(0.0, 1.0)
                    var tau = ALIGN_TAU_FAST_S + (FUSION_TAU_S - ALIGN_TAU_FAST_S) * frac
                    // Persistent large divergence → converge fast; transient spikes are ignored so
                    // steady-state magnetic-jitter rejection is preserved.
                    var d = abs(fusedQ[0] * qAbs[0] + fusedQ[1] * qAbs[1] + fusedQ[2] * qAbs[2] + fusedQ[3] * qAbs[3])
                    if (d > 1f) d = 1f
                    val errDeg = Math.toDegrees(2.0 * kotlin.math.acos(d.toDouble())).toFloat()
                    if (errDeg > RECOVER_DEG) {
                        if (bigErrSince == 0L) bigErrSince = event.timestamp
                        else if ((event.timestamp - bigErrSince) / 1e9 > RECOVER_PERSIST_S) {
                            tau = minOf(tau, RECOVER_TAU_S)
                        }
                    } else bigErrSince = 0L
                    val alpha = (dt / tau).coerceIn(0.0, 1.0).toFloat()
                    // Split the correction: TILT (pitch/roll) comes from gravity via the
                    // accelerometer and is immune to magnetic disturbance — always correct it
                    // fully. YAW is the magnetometer's contribution; when the field magnitude
                    // says the reading is corrupted (or the sensor reports itself unreliable),
                    // freeze yaw to a slow leak and let the gyro carry heading through the
                    // disturbance. Walking past a car then barely tugs the heading (~2° per
                    // 10 s) instead of dragging it wholesale.
                    val yawTrusted = magAccuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
                        !magneticInterference()
                    // Gyro-only mode: heading NEVER follows the magnetometer (for permanently
                    // disturbed sites). The user sets the direction once (tap-hold calibration
                    // or the detection auto-trim) and the gyroscope holds it; tilt corrections
                    // (gravity-based, magnet-immune) continue as normal. Gyro bias drift slowly
                    // accumulates, so an occasional re-set is expected.
                    val alphaYaw = when {
                        gyroOnlyYaw -> 0f
                        yawTrusted -> alpha
                        else -> (dt / TAU_YAW_UNTRUSTED_S).coerceIn(0.0, 1.0).toFloat()
                    }
                    applySplitCorrection(qAbs, alphaTilt = alpha, alphaYaw = alphaYaw)
                }
                lastRvNs = event.timestamp
                writeMatrixFromFused()
                hasValue = true

                // values[4] = estimated heading accuracy (radians), -1 if unavailable.
                if (event.values.size >= 5 && event.values[4] >= 0f) {
                    val deg = Math.toDegrees(event.values[4].toDouble()).toFloat()
                    val prev = headingErrDeg
                    headingErrDeg = if (prev == null) deg else prev + 0.1f * (deg - prev)
                }
                onChanged()
                notifyHealth()
            }
            Sensor.TYPE_GYROSCOPE -> {
                // Integrate body-frame angular velocity for smooth, noise-free short-term motion.
                if (!haveQ) { lastGyroNs = event.timestamp; return }
                val dt = (event.timestamp - lastGyroNs) / 1e9
                lastGyroNs = event.timestamp
                if (dt <= 0.0 || dt > 0.1) return          // first sample or a gap → skip
                val wx = event.values[0]; val wy = event.values[1]; val wz = event.values[2]
                val omega = sqrt(wx * wx + wy * wy + wz * wz)
                if (omega < 1e-6f) return
                val theta = omega * dt
                val s = (kotlin.math.sin(theta / 2.0) / omega).toFloat()
                // dq = [cos(θ/2), axis·sin(θ/2)] with axis = ω/|ω|; q' = q ⊗ dq (body frame).
                val dq = floatArrayOf(kotlin.math.cos(theta / 2.0).toFloat(), wx * s, wy * s, wz * s)
                quatMulInto(fusedQ, dq)
                writeMatrixFromFused()
                onChanged()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                val v = event.values
                val mag = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                val prev = fieldUt
                fieldUt = if (prev == null) mag else prev + 0.2f * (mag - prev)
                notifyHealth()
            }
        }
    }

    /** q ⊗ p, result back into q (both [w,x,y,z]); normalised. */
    /**
     * Correct [fusedQ] toward [qAbs] with SEPARATE gains for yaw and tilt. The world-frame error
     * e = qAbs ⊗ conj(fused) is decomposed as e = twist ⊗ swing, where twist is the rotation
     * about world-up (yaw error, the magnetometer's domain) and swing the remaining tilt error
     * (gravity's domain). Each factor is angle-scaled by its own gain and re-applied:
     * fused ← twist^aYaw ⊗ swing^aTilt ⊗ fused. Twist must sit on the LEFT (outermost world
     * rotation): only then does correcting the swing alone leave a residual that is still a pure
     * world-up twist. (Decomposition, freeze-yaw and full-correction behaviour verified
     * numerically over 500 random orientations.)
     */
    private fun applySplitCorrection(qAbs: FloatArray, alphaTilt: Float, alphaYaw: Float) {
        // e = qAbs ⊗ conj(fused), forced to the short arc.
        val cw = fusedQ[0]; val cx = -fusedQ[1]; val cy = -fusedQ[2]; val cz = -fusedQ[3]
        var ew = qAbs[0] * cw - qAbs[1] * cx - qAbs[2] * cy - qAbs[3] * cz
        var ex = qAbs[0] * cx + qAbs[1] * cw + qAbs[2] * cz - qAbs[3] * cy
        var ey = qAbs[0] * cy - qAbs[1] * cz + qAbs[2] * cw + qAbs[3] * cx
        var ez = qAbs[0] * cz + qAbs[1] * cy - qAbs[2] * cx + qAbs[3] * cw
        if (ew < 0f) { ew = -ew; ex = -ex; ey = -ey; ez = -ez }
        // twist about world up: normalize (ew, 0, 0, ez); degenerate → identity twist.
        val tn = sqrt(ew * ew + ez * ez)
        val tw: Float; val tz: Float
        if (tn < 1e-6f) { tw = 1f; tz = 0f } else { tw = ew / tn; tz = ez / tn }
        // swing = conj(twist) ⊗ e   (conj(twist) = (tw, 0, 0, -tz))
        val sw = tw * ew + tz * ez
        val sx = tw * ex + tz * ey
        val sy = tw * ey - tz * ex
        // (z-component of swing is 0 by construction)
        val twistScaled = quatPow(tw, 0f, 0f, tz, alphaYaw)
        val corr = quatPow(sw, sx, sy, 0f, alphaTilt)
        // fused ← twist^aYaw ⊗ swing^aTilt ⊗ fused
        quatMulInto(corr, fusedQ)          // corr = swing^aTilt ⊗ fused
        quatMulInto(twistScaled, corr)     // twistScaled = twist^aYaw ⊗ swing^aTilt ⊗ fused
        System.arraycopy(twistScaled, 0, fusedQ, 0, 4)
    }

    /** q^t: scale the rotation angle of quaternion (w,x,y,z) by t. Returns a new array. */
    private fun quatPow(w: Float, x: Float, y: Float, z: Float, t: Float): FloatArray {
        val wc = w.coerceIn(-1f, 1f)
        val half = kotlin.math.acos(wc)
        if (half < 1e-6f || t == 0f) {
            return if (t == 0f || half < 1e-6f) floatArrayOf(1f, 0f, 0f, 0f)
            else floatArrayOf(w, x, y, z)
        }
        val s = kotlin.math.sin(half)
        val nh = half * t
        val sn = kotlin.math.sin(nh)
        return floatArrayOf(kotlin.math.cos(nh), x / s * sn, y / s * sn, z / s * sn)
    }

    private fun quatMulInto(q: FloatArray, p: FloatArray) {
        val w = q[0] * p[0] - q[1] * p[1] - q[2] * p[2] - q[3] * p[3]
        val x = q[0] * p[1] + q[1] * p[0] + q[2] * p[3] - q[3] * p[2]
        val y = q[0] * p[2] - q[1] * p[3] + q[2] * p[0] + q[3] * p[1]
        val z = q[0] * p[3] + q[1] * p[2] - q[2] * p[1] + q[3] * p[0]
        val n = sqrt(w * w + x * x + y * y + z * z)
        if (n > 1e-9f) { q[0] = w / n; q[1] = x / n; q[2] = y / n; q[3] = z / n }
    }

    /** SLERP q toward target by fraction t (in place), taking the short way around. */
    private fun quatSlerpInto(q: FloatArray, target: FloatArray, t: Float) {
        var d = q[0] * target[0] + q[1] * target[1] + q[2] * target[2] + q[3] * target[3]
        val tg = floatArrayOf(target[0], target[1], target[2], target[3])
        if (d < 0f) { for (i in 0..3) tg[i] = -tg[i]; d = -d }
        if (d > 0.9995f) {                                   // nearly aligned → nlerp
            for (i in 0..3) q[i] += t * (tg[i] - q[i])
        } else {
            val th0 = kotlin.math.acos(d.toDouble())
            val th = th0 * t
            val q2 = FloatArray(4)
            var n = 0f
            for (i in 0..3) { q2[i] = tg[i] - q[i] * d; n += q2[i] * q2[i] }
            n = sqrt(n)
            val c = kotlin.math.cos(th).toFloat(); val sn = kotlin.math.sin(th).toFloat()
            if (n > 1e-9f) for (i in 0..3) q[i] = q[i] * c + (q2[i] / n) * sn
        }
        val nn = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (nn > 1e-9f) for (i in 0..3) q[i] /= nn
    }

    /** Write the device->world rotation matrix for [fusedQ] into [matrix]. */
    private fun writeMatrixFromFused() {
        val w = fusedQ[0]; val x = fusedQ[1]; val y = fusedQ[2]; val z = fusedQ[3]
        synchronized(matrix) {
            matrix[0] = 1 - 2 * (y * y + z * z); matrix[1] = 2 * (x * y - w * z); matrix[2] = 2 * (x * z + w * y)
            matrix[3] = 2 * (x * y + w * z); matrix[4] = 1 - 2 * (x * x + z * z); matrix[5] = 2 * (y * z - w * x)
            matrix[6] = 2 * (x * z - w * y); matrix[7] = 2 * (y * z + w * x); matrix[8] = 1 - 2 * (x * x + y * y)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, acc: Int) {
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            magAccuracy = acc
            notifyHealth()
        }
        // The rotation vector's accuracy callback is deliberately ignored: see class KDoc.
    }

    private fun notifyHealth() {
        val h = compassHealth()
        if (h != lastHealth) {
            lastHealth = h
            onHealthChanged?.invoke(h)
        }
    }
}
