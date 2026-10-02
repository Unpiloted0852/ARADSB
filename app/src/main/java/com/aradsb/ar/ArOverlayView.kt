package com.aradsb.ar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import com.aradsb.geo.GeoPoint
import com.aradsb.geo.Geodesy
import com.aradsb.geo.Look
import com.aradsb.model.Aircraft
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * A target to render: the aircraft plus the kinematic state needed to dead-reckon its position.
 * The geometry (azimuth/elevation/range) is recomputed every frame from the live observer position
 * and the extrapolated aircraft position, so markers track smoothly between API updates.
 *
 * @param baseLat/baseLon/baseAltM  position (HAE, metres) at [baseElapsedMs]
 * @param baseElapsedMs             SystemClock.elapsedRealtime() of the epoch the position is valid
 *                                  for (already corrected for the API's reported position age)
 */
data class RenderTarget(
    val aircraft: Aircraft,
    val baseLat: Double,
    val baseLon: Double,
    val baseAltM: Double,
    val altitudeIsGeometric: Boolean,
    val trackDeg: Double?,
    val groundSpeedMps: Double?,
    val vertRateMps: Double?,
    val turnRateDegS: Double?,
    /** Pre-formatted display altitude ("FL363", "4,250 ft", "~36,500 ft", "GND"). */
    val labelAltText: String,
    val baseElapsedMs: Long,
    /** True when the aircraft is above a nearby BKN/OVC cloud deck (may be hidden from view). */
    val aboveCloud: Boolean = false,
    /** True when the audibility model says this aircraft can probably be heard right now. */
    val audible: Boolean = false,
    /** Recent position history as [azTrueDeg, elDeg] pairs, oldest→newest, for the comet trail. */
    val trail: List<FloatArray>? = null,
)

/**
 * Draws aircraft markers over the camera preview and reports which target is nearest the reticle.
 *
 * Projection model (validated): the device->world (magnetic ENU) rotation matrix is remapped to a
 * screen-aligned frame for the current display rotation. Each target's world unit vector (built
 * from its azimuth corrected to magnetic north, and its elevation) is transformed into that
 * screen-aligned device frame via the transpose, then perspective-projected. The rear camera looks
 * along screen -Z, so a target is in front iff its device-frame z is negative.
 */
class ArOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    @Volatile private var haveRotation = false
    @Volatile private var displayRotation = Surface.ROTATION_0
    @Volatile private var declinationDeg = 0f
    @Volatile private var horizontalFovDeg = CameraFov.DEFAULT_HORIZONTAL_FOV_DEG
    @Volatile private var observer: GeoPoint? = null
    @Volatile private var targets: List<RenderTarget> = emptyList()

    // Orientation-bias correction estimated by the CV aligner (degrees), applied to every target.
    @Volatile private var azOffsetDeg = 0f
    @Volatile private var elOffsetDeg = 0f
    // Manual, user-set heading correction (persisted by the activity, bounded). Applied to BOTH
    // markers and the rose, on top of the ephemeral CV auto-align offset above.
    @Volatile private var userHeadingOffsetDeg = 0f
    // Whether the compass rose is referenced to TRUE north (subtract declination) or MAGNETIC.
    @Volatile private var roseUsesTrueNorth = true
    // Calibration mode: the rose turns orange and horizontal drags adjust userHeadingOffsetDeg.
    @Volatile private var calibrating = false

    private var selectedHex: String? = null
    /** Invoked (on the UI thread) when the aircraft nearest the reticle changes; null when none. */
    var onSelectionChanged: ((RenderTarget?) -> Unit)? = null

    /** A neural-network airplane detection in view coordinates, drawn as a box. */
    data class DetectionBox(val rect: RectF, val score: Float, val label: String?)

    @Volatile private var detections: List<DetectionBox> = emptyList()
    @Volatile private var detectionsAtMs = 0L

    /** Set the latest AI airplane detections (view coordinates). Thread-safe. */
    fun setDetections(boxes: List<DetectionBox>) {
        detections = boxes
        detectionsAtMs = SystemClock.elapsedRealtime()
        postInvalidate()
    }

    companion object {
        // Cap dead-reckoning so a target that stops updating cannot drift arbitrarily far.
        private const val MAX_EXTRAPOLATION_S = 30.0
        // Detection boxes hold full opacity while updates flow, then fade out.
        private const val DETECTION_HOLD_MS = 900L
        private const val CLOUD_DIM_ALPHA = 105   // faded marker for above-cloud traffic (0-255)
        private const val DETECTION_TTL_MS = 2000L
        // Switchover residual blending: decay window and snap thresholds.
        private const val BLEND_MS = 1200.0
        private const val MAX_BLEND_M = 1500.0
        private const val MAX_BLEND_ALT_M = 1000.0
        // Manual compass calibration: hold duration to enter, and the bounded offset range.
        private const val HOLD_MS = 1200L
        private const val MAX_USER_OFFSET = 20f
    }

    // --- Paints ---
    /** Returns a thumbnail bitmap for an aircraft hex, or null. Set by the activity. */
    var thumbnailProvider: ((String) -> android.graphics.Bitmap?)? = null

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val thumbBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(230, 235, 240, 245); strokeWidth = 1.5f * density
    }

    private val detBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(80, 255, 160); strokeWidth = 2.5f * density
    }
    private val detLabelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(170, 6, 40, 24)
    }
    private val detLabelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 255, 215); textSize = 11f * density; isFakeBoldText = true
    }

    // Grey horizon compass rose — bearing ticks + cardinal/intercardinal letters at 0° elevation.
    private val roseTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(150, 175, 185, 195); strokeWidth = 1.5f * density
    }
    private val roseTickMajorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(190, 195, 205, 215); strokeWidth = 2f * density
    }
    private val roseLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 200, 210, 220); textSize = 15f * density
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val roseCardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 210, 220, 230); textSize = 20f * density
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    // Orange variants shown while the rose is in user-calibration mode.
    private val roseCalTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(255, 255, 150, 40); strokeWidth = 2.5f * density
    }
    private val roseCalLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 165, 60); textSize = 20f * density
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    // --- Paints ---
    private val markerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = 2f * density
    }
    private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(180, 90, 200, 250)
    }
    private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(255, 200, 60); strokeWidth = 2.5f * density
    }
    private val selFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(210, 255, 200, 60)
    }
    // Audible-aircraft identity: a distinct green for the dot / edge arrow plus a tiny speaker
    // glyph, so the aircraft you can probably HEAR are identifiable at a glance.
    private val audFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(230, 70, 220, 130)
    }
    private val audGlyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.rgb(70, 220, 130)
    }
    private val audGlyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(70, 220, 130); strokeCap = Paint.Cap.ROUND
    }
    private val speakerPath = android.graphics.Path()
    private val speakerRect = android.graphics.RectF()

    /** Tiny speaker glyph (box + cone + two sound arcs), centred at (x,y), half-height ≈ s. */
    private fun drawSpeaker(canvas: Canvas, x: Float, y: Float, s: Float) {
        speakerPath.reset()
        speakerPath.moveTo(x - 0.95f * s, y - 0.38f * s)
        speakerPath.lineTo(x - 0.40f * s, y - 0.38f * s)
        speakerPath.lineTo(x + 0.10f * s, y - 0.85f * s)
        speakerPath.lineTo(x + 0.10f * s, y + 0.85f * s)
        speakerPath.lineTo(x - 0.40f * s, y + 0.38f * s)
        speakerPath.lineTo(x - 0.95f * s, y + 0.38f * s)
        speakerPath.close()
        canvas.drawPath(speakerPath, audGlyphFill)
        audGlyphStroke.strokeWidth = (0.20f * s).coerceAtLeast(1f)
        val r1 = 0.55f * s
        speakerRect.set(x + 0.10f * s - r1, y - r1, x + 0.10f * s + r1, y + r1)
        canvas.drawArc(speakerRect, -45f, 90f, false, audGlyphStroke)
        val r2 = 0.95f * s
        speakerRect.set(x + 0.10f * s - r2, y - r2, x + 0.10f * s + r2, y + r2)
        canvas.drawArc(speakerRect, -40f, 80f, false, audGlyphStroke)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 10.5f * density; isFakeBoldText = true
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 220, 235, 245); textSize = 9f * density
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(140, 0, 0, 0)
    }
    private val reticle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(120, 255, 255, 255); strokeWidth = 1.5f * density
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(200, 90, 200, 250)
    }

    fun setDisplayRotation(r: Int) { displayRotation = r }
    fun setDeclination(deg: Float) { declinationDeg = deg }
    fun setHorizontalFov(deg: Float) { horizontalFovDeg = deg.coerceIn(4f, 130f) }
    fun getHorizontalFov(): Float = horizontalFovDeg

    // --- Compass reference + manual calibration -----------------------------

    fun setRoseReference(useTrueNorth: Boolean) { roseUsesTrueNorth = useTrueNorth; postInvalidateOnAnimation() }
    fun roseUsesTrueNorth(): Boolean = roseUsesTrueNorth

    fun setUserHeadingOffset(deg: Float) {
        userHeadingOffsetDeg = deg.coerceIn(-MAX_USER_OFFSET, MAX_USER_OFFSET)
        postInvalidateOnAnimation()
    }
    fun userHeadingOffset(): Float = userHeadingOffsetDeg

    /** Notified (UI thread) when a 1.2-second press starts calibration, so the activity can show controls. */
    var onCalibrationStarted: (() -> Unit)? = null
    /** Reports a pinch zoom step (relative scale factor); the activity applies it to the camera. */
    var onZoomChanged: ((Float) -> Unit)? = null

    private val scaleDetector = android.view.ScaleGestureDetector(
        context,
        object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                onZoomChanged?.invoke(d.scaleFactor)
                return true
            }
        }
    )
    /** Notified while calibrating as the offset changes, so the activity can show the live value. */
    var onUserOffsetChanged: (() -> Unit)? = null

    fun finishCalibration(): Float { calibrating = false; postInvalidateOnAnimation(); return userHeadingOffsetDeg }
    fun cancelCalibration() { calibrating = false; postInvalidateOnAnimation() }
    fun resetCalibration() { userHeadingOffsetDeg = 0f; calibrating = false; postInvalidateOnAnimation() }
    fun isCalibrating(): Boolean = calibrating

    private val holdRunnable = Runnable {
        if (!calibrating) {
            calibrating = true
            postInvalidateOnAnimation()
            post { onCalibrationStarted?.invoke() }   // activity fires the unlock haptic
        }
    }
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Pinch-to-zoom takes precedence. While a pinch is active (or any second finger is down),
        // the tap-and-hold calibration trigger is cancelled so the two gestures never conflict.
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) removeCallbacks(holdRunnable)
        if (scaleDetector.isInProgress) { removeCallbacks(holdRunnable); return true }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; lastX = event.x
                if (!calibrating) postDelayed(holdRunnable, HOLD_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (calibrating) {
                    val degPerPx = horizontalFovDeg / width.coerceAtLeast(1)
                    val delta = (event.x - lastX) * degPerPx
                    lastX = event.x
                    setUserHeadingOffset(userHeadingOffsetDeg + delta)
                    post { onUserOffsetChanged?.invoke() }
                } else {
                    // A real drag (not a still hold) cancels the pending calibration trigger.
                    if (hypot(event.x - downX, event.y - downY) > 12f * density) {
                        removeCallbacks(holdRunnable)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!calibrating) removeCallbacks(holdRunnable)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    fun setObserver(p: GeoPoint?) { observer = p; postInvalidateOnAnimation() }

    /** Apply the CV-estimated orientation bias (degrees). Thread-safe; triggers a redraw. */
    fun setAlignmentOffset(azDeg: Float, elDeg: Float) {
        azOffsetDeg = azDeg.coerceIn(-30f, 30f)
        elOffsetDeg = elDeg.coerceIn(-15f, 15f)
        postInvalidateOnAnimation()
    }

    fun alignmentAzOffset(): Float = azOffsetDeg
    fun alignmentElOffset(): Float = elOffsetDeg

    /**
     * Build an immutable [Projection] for the current orientation, FOV and offsets. Safe to call
     * from any thread; reads of the rotation matrix are synchronized against [setRotationMatrix].
     * Returns null until the first orientation sample and a non-zero layout are available.
     */
    fun projectionSnapshot(): Projection? {
        val w = width; val h = height
        if (!haveRotation || w == 0 || h == 0) return null
        val rmp = FloatArray(9)
        synchronized(rotation) {
            remapInto(rmp)
        }
        val hFovRad = Math.toRadians(horizontalFovDeg.toDouble())
        val fpx = (w / 2f) / kotlin.math.tan(hFovRad / 2.0).toFloat()
        return Projection(rmp, fpx, w / 2f, h / 2f, declinationDeg,
            azOffsetDeg + userHeadingOffsetDeg, elOffsetDeg, w, h)
    }

    /** Remap the raw device->world matrix for the current display rotation into [out] (size 9). */
    private fun remapInto(out: FloatArray) {
        when (displayRotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, out)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                rotation, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
            else -> System.arraycopy(rotation, 0, out, 0, 9)
        }
    }

    fun setRotationMatrix(src: FloatArray) {
        synchronized(rotation) { System.arraycopy(src, 0, rotation, 0, 9) }
        haveRotation = true
        postInvalidateOnAnimation()
    }

    fun setTargets(list: List<RenderTarget>) {
        // Error-correction blending: at an update, the new report's extrapolation never agrees
        // exactly with the old one's (position noise, ±0.5 s Date-header quantization, velocity
        // drift since the last report) — switching instantly makes the marker hop. Instead,
        // carry the residual (old prediction − new prediction, evaluated NOW) and decay it to
        // zero over BLEND_MS: the marker is continuous at the switch and converges to the new
        // track before the next update. Residuals chain if an update lands mid-blend. Large
        // residuals (a genuinely corrected track) snap immediately rather than glide.
        val now = SystemClock.elapsedRealtime()
        val old = targets
        if (old.isNotEmpty() && list.isNotEmpty()) {
            val oldByHex = old.associateBy { it.aircraft.hex }
            for (nt in list) {
                val ot = oldByHex[nt.aircraft.hex] ?: continue
                if (ot.baseElapsedMs == nt.baseElapsedMs &&
                    ot.baseLat == nt.baseLat && ot.baseLon == nt.baseLon
                ) continue   // same underlying report — keep any running blend untouched
                val po = positionAt(ot, now)
                val pn = positionAt(nt, now)
                var dLat = po.latDeg - pn.latDeg
                var dLon = po.lonDeg - pn.lonDeg
                var dAlt = po.heightM - pn.heightM
                // Fold in the remainder of any blend still running, for continuity.
                blends[nt.aircraft.hex]?.let { b ->
                    val k = b.remaining(now)
                    dLat += b.dLat * k; dLon += b.dLon * k; dAlt += b.dAlt * k
                }
                val mLat = dLat * 111_320.0
                val mLon = dLon * 111_320.0 * cos(Math.toRadians(pn.latDeg))
                if (hypot(mLat, mLon) > MAX_BLEND_M || kotlin.math.abs(dAlt) > MAX_BLEND_ALT_M) {
                    blends.remove(nt.aircraft.hex)   // real correction: snap
                } else {
                    blends[nt.aircraft.hex] = Blend(dLat, dLon, dAlt, now)
                }
            }
            blends.keys.retainAll(list.mapTo(HashSet()) { it.aircraft.hex })
        } else {
            blends.clear()
        }
        targets = list
        postInvalidateOnAnimation()
    }

    private class Blend(val dLat: Double, val dLon: Double, val dAlt: Double, val startMs: Long) {
        /** Fraction of the residual still applied at [nowMs]: 1 at start, 0 after BLEND_MS. */
        fun remaining(nowMs: Long): Double =
            (1.0 - (nowMs - startMs) / BLEND_MS).coerceIn(0.0, 1.0)
    }

    private val blends = HashMap<String, Blend>()

    /** The target currently nearest the reticle, or null. */
    fun selectedTarget(): RenderTarget? = targets.firstOrNull { it.aircraft.hex == selectedHex }

    /** Extrapolated current position of [t] for time [nowMs] (monotonic). */
    private fun positionAt(t: RenderTarget, nowMs: Long): GeoPoint {
        val age = ((nowMs - t.baseElapsedMs) / 1000.0).coerceIn(0.0, MAX_EXTRAPOLATION_S)
        return Geodesy.extrapolate(
            GeoPoint(t.baseLat, t.baseLon, t.baseAltM),
            t.trackDeg, t.groundSpeedMps, t.vertRateMps, t.turnRateDegS, age
        )
    }

    /** [positionAt] plus the decaying switchover residual — what is actually rendered. */
    private fun displayedPositionAt(t: RenderTarget, nowMs: Long): GeoPoint {
        val pos = positionAt(t, nowMs)
        val b = blends[t.aircraft.hex] ?: return pos
        val k = b.remaining(nowMs)
        if (k <= 0.0) { blends.remove(t.aircraft.hex); return pos }
        return GeoPoint(pos.latDeg + b.dLat * k, pos.lonDeg + b.dLon * k, pos.heightM + b.dAlt * k)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f

        drawReticle(canvas, cx, cy)
        val obs = observer
        if (!haveRotation || obs == null) return

        // Remap device->world matrix into a screen-aligned device frame for this display rotation.
        synchronized(rotation) { remapInto(remapped) }

        val hFovRad = Math.toRadians(horizontalFovDeg.toDouble())
        val fpx = (w / 2f) / tan(hFovRad / 2.0).toFloat()
        val now = SystemClock.elapsedRealtime()
        val azOff = azOffsetDeg + userHeadingOffsetDeg
        val elOff = elOffsetDeg

        // Project a true-bearing/elevation direction to screen pixels (null if behind the camera).
        fun project(azTrueDeg: Double, elDeg: Double): FloatArray? {
            val azMag = Math.toRadians((azTrueDeg + azOff) - declinationDeg)
            val elr = Math.toRadians(elDeg + elOff)
            val cE = cos(elr)
            val we = (cE * sin(azMag)).toFloat()
            val wn = (cE * cos(azMag)).toFloat()
            val wu = sin(elr).toFloat()
            val sx = remapped[0] * we + remapped[3] * wn + remapped[6] * wu
            val sy = remapped[1] * we + remapped[4] * wn + remapped[7] * wu
            val sz = remapped[2] * we + remapped[5] * wn + remapped[8] * wu
            val depth = -sz
            if (depth <= 1e-3f) return null
            return floatArrayOf(cx + (sx / depth) * fpx, cy - (sy / depth) * fpx)
        }

        // Project an aircraft's recent-history trail (az/el pairs) to screen points, oldest→newest.
        fun projectTrail(t: RenderTarget): ArrayList<FloatArray>? {
            val tr = t.trail ?: return null
            if (tr.size < 2) return null
            val pts = ArrayList<FloatArray>(tr.size)
            for (p in tr) project(p[0].toDouble(), p[1].toDouble())?.let { pts.add(it) }
            return if (pts.size >= 2) pts else null
        }

        // Grey horizon compass rose (behind the aircraft layer).
        drawCompassRose(canvas, cx, cy, w, h, fpx, azOff)

        // Pick the in-front target nearest the screen centre as the "reticle" selection.
        val selectRadius = min(w, h) * 0.45f
        var bestHex: String? = null
        var bestDist = Float.MAX_VALUE

        // First pass: project everything, draw non-selected, remember the selected for a top draw.
        var selPx = 0f; var selPy = 0f; var selTarget: RenderTarget? = null

        for (t in targets) {
            val pos = displayedPositionAt(t, now)
            val look = Geodesy.lookAngles(obs, pos)

            val azMag = Math.toRadians((look.azimuthTrueDeg + azOff) - declinationDeg)
            val el = Math.toRadians(look.elevationDeg + elOff)
            val cE = cos(el)
            val we = (cE * sin(azMag)).toFloat()
            val wn = (cE * cos(azMag)).toFloat()
            val wu = sin(el).toFloat()

            val sx = remapped[0] * we + remapped[3] * wn + remapped[6] * wu
            val sy = remapped[1] * we + remapped[4] * wn + remapped[7] * wu
            val sz = remapped[2] * we + remapped[5] * wn + remapped[8] * wu

            val depth = -sz
            if (depth <= 1e-3f) continue   // behind the camera

            val px = cx + (sx / depth) * fpx
            val py = cy - (sy / depth) * fpx

            val onScreen = px >= -40 * density && px <= w + 40 * density &&
                py >= -40 * density && py <= h + 40 * density

            // Selection candidacy (must be near the reticle and on screen).
            val dCentre = hypot(px - cx, py - cy)
            if (onScreen && dCentre < selectRadius && dCentre < bestDist) {
                bestDist = dCentre; bestHex = t.aircraft.hex
            }

            if (t.aircraft.hex == selectedHex) {
                // Defer drawing the currently-selected target until the end (draw on top).
                selPx = px; selPy = py; selTarget = t
            } else if (onScreen) {
                projectTrail(t)?.let { drawCometTrail(canvas, it, px, py, false) }
                drawMarker(canvas, px, py, t, look, selected = false)
            } else {
                drawEdgeArrow(canvas, cx, cy, w, h, px, py, t)
            }
        }

        // Draw the selected target last so it sits above the rest.
        selTarget?.let {
            val onScreen = selPx >= -40 * density && selPx <= w + 40 * density &&
                selPy >= -40 * density && selPy <= h + 40 * density
            if (onScreen) {
                val pos = displayedPositionAt(it, now)
                projectTrail(it)?.let { pts -> drawCometTrail(canvas, pts, selPx, selPy, true) }
                drawMarker(canvas, selPx, selPy, it, Geodesy.lookAngles(obs, pos), selected = true)
            } else {
                drawEdgeArrow(canvas, cx, cy, w, h, selPx, selPy, it)
            }
        }

        // AI airplane-detection boxes on top of everything; fade out when stale.
        val dets = detections
        if (dets.isNotEmpty() && now - detectionsAtMs <= DETECTION_TTL_MS) {
            // Full opacity while updates keep arriving; fade only once they stop.
            val age = now - detectionsAtMs
            val alpha = if (age <= DETECTION_HOLD_MS) 255 else {
                val f = 1f - (age - DETECTION_HOLD_MS).toFloat() / (DETECTION_TTL_MS - DETECTION_HOLD_MS)
                (255 * f).toInt().coerceIn(0, 255)
            }
            detBoxPaint.alpha = alpha
            detLabelText.alpha = alpha
            for (d in dets) {
                val r = 4f * density
                canvas.drawRoundRect(d.rect, r, r, detBoxPaint)
                val label = buildString {
                    append(d.label ?: "✈")
                    append("  ")
                    append((d.score * 100).toInt())
                    append('%')
                }
                val tw = detLabelText.measureText(label)
                val pad = 3f * density
                val lx = d.rect.left
                val ly = (d.rect.top - 4f * density).coerceAtLeast(detLabelText.textSize + pad)
                canvas.drawRoundRect(
                    lx - pad, ly - detLabelText.textSize - pad, lx + tw + pad, ly + pad,
                    r, r, detLabelBg
                )
                canvas.drawText(label, lx, ly, detLabelText)
            }
        }

        if (bestHex != selectedHex) {
            selectedHex = bestHex
            val sel = targets.firstOrNull { it.aircraft.hex == bestHex }
            // Notify after the draw pass completes.
            post { onSelectionChanged?.invoke(sel) }
        }
    }

    /**
     * Grey compass rose on the horizon: a tick at every 15° of TRUE bearing, cardinals (N/E/S/W)
     * and intercardinals (NE/SE/SW/NW) labelled. Each bearing is projected at 0° elevation through
     * the same camera model as the aircraft markers (declination + the CV alignment offset applied
     * identically), so the rose is geometrically consistent with the overlay and tilts with the
     * phone. It also lets the user sanity-check the compass: if "N" is not where they know north is,
     * the heading is off. Drawn behind the aircraft layer.
     */
    private fun drawCompassRose(canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float, fpx: Float, azOff: Float) {
        val tickHalfMajor = 16f * density   // cardinal/intercardinal tick half-length
        val tickHalfMinor = 9f * density
        val labelGap = 12f * density

        var bearing = 0
        while (bearing < 360) {
            val isCardinal = bearing % 90 == 0
            val isInter = bearing % 45 == 0
            // True mode: labels are true bearings, convert to the magnetic sensor frame by
            // subtracting declination. Magnetic mode: labels already magnetic, no conversion.
            val decl = if (roseUsesTrueNorth) declinationDeg else 0f
            val azMag = Math.toRadians(((bearing.toDouble() + azOff) - decl))
            // Endpoints straddling the horizon (±0.9° elevation) give the local horizon tilt.
            val pTop = projectHorizon(azMag, Math.toRadians(0.9), cx, cy, fpx)
            val pBot = projectHorizon(azMag, Math.toRadians(-0.9), cx, cy, fpx)
            val pMid = projectHorizon(azMag, 0.0, cx, cy, fpx)
            if (pTop != null && pBot != null && pMid != null &&
                pMid[0] >= -30 * density && pMid[0] <= w + 30 * density &&
                pMid[1] >= -30 * density && pMid[1] <= h + 30 * density
            ) {
                val major = isInter
                val half = if (major) tickHalfMajor else tickHalfMinor
                // "Down" on the projected horizon = from the +elevation point to the −elevation
                // point. The tick runs along it; the horizon line is perpendicular. Rotating the
                // canvas by this vector's angle makes ticks AND letters bank with the horizon at
                // any phone roll.
                val dnx = pBot[0] - pTop[0]; val dny = pBot[1] - pTop[1]
                val downAngleDeg = Math.toDegrees(atan2(dny.toDouble(), dnx.toDouble())).toFloat()

                canvas.save()
                canvas.rotate(downAngleDeg - 90f, pMid[0], pMid[1])  // +x axis -> horizon-down
                // In the rotated frame the tick is vertical about pMid; the horizon is horizontal.
                canvas.drawLine(pMid[0], pMid[1] - half, pMid[0], pMid[1] + half,
                    if (calibrating) roseCalTickPaint else if (major) roseTickMajorPaint else roseTickPaint)
                val label = when (bearing) {
                    0 -> "N"; 45 -> "NE"; 90 -> "E"; 135 -> "SE"
                    180 -> "S"; 225 -> "SW"; 270 -> "W"; 315 -> "NW"; else -> null
                }
                if (label != null) {
                    val paint = if (calibrating) roseCalLabelPaint
                        else if (isCardinal) roseCardinalPaint else roseLabelPaint
                    // Below the tick on the horizon-down side, upright relative to the horizon.
                    canvas.drawText(label, pMid[0], pMid[1] + half + labelGap + paint.textSize * 0.5f, paint)
                }
                canvas.restore()
            }
            bearing += 15
        }
    }

    /** Project a (magnetic azimuth, elevation) direction to a view pixel, or null if behind. */
    private fun projectHorizon(azMagRad: Double, elRad: Double, cx: Float, cy: Float, fpx: Float): FloatArray? {
        val cE = cos(elRad)
        val we = (cE * sin(azMagRad)).toFloat()
        val wn = (cE * cos(azMagRad)).toFloat()
        val wu = sin(elRad).toFloat()
        val sx = remapped[0] * we + remapped[3] * wn + remapped[6] * wu
        val sy = remapped[1] * we + remapped[4] * wn + remapped[7] * wu
        val sz = remapped[2] * we + remapped[5] * wn + remapped[8] * wu
        val depth = -sz
        if (depth <= 1e-3f) return null
        return floatArrayOf(cx + (sx / depth) * fpx, cy - (sy / depth) * fpx)
    }

    private fun drawReticle(canvas: Canvas, cx: Float, cy: Float) {
        val r = 9f * density
        canvas.drawCircle(cx, cy, r, reticle)
        canvas.drawLine(cx - r * 1.8f, cy, cx - r, cy, reticle)
        canvas.drawLine(cx + r, cy, cx + r * 1.8f, cy, reticle)
        canvas.drawLine(cx, cy - r * 1.8f, cx, cy - r, reticle)
        canvas.drawLine(cx, cy + r, cx, cy + r * 1.8f, reticle)
    }

    /**
     * A comet trail: the aircraft's actual recent path (its logged positions, so it curves through
     * turns and slopes through climbs/descents), drawn as a tapered streak that fades and narrows
     * toward the tail and brightens to a head at the dot. No arrowhead — the trailing mass reads
     * as "moving this way / came from there", never as a pointer to look along.
     */
    private fun drawCometTrail(
        canvas: Canvas, pts: List<FloatArray>, headX: Float, headY: Float, selected: Boolean
    ) {
        val n = pts.size
        if (n < 2) return
        val maxW = (if (selected) 4.5f else 3f) * density
        val maxA = if (selected) 205 else 130
        for (i in 0 until n - 1) {
            val f = (i + 1).toFloat() / (n - 1)        // 0 at tail → 1 toward the head
            trailPaint.strokeWidth = 0.6f * density + (maxW - 0.6f * density) * f
            trailPaint.alpha = (maxA * f * f).toInt().coerceIn(0, 255)   // fade fast at the tail
            canvas.drawLine(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1], trailPaint)
        }
        // Final segment from the newest logged point to the live dot, full strength.
        trailPaint.strokeWidth = maxW
        trailPaint.alpha = maxA
        val last = pts[n - 1]
        canvas.drawLine(last[0], last[1], headX, headY, trailPaint)
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(150, 210, 255)
    }

    private fun drawMarker(
        canvas: Canvas, px: Float, py: Float, t: RenderTarget,
        look: Look, selected: Boolean
    ) {
        // Above a nearby cloud deck and not being pointed at → draw faded (it may be hidden behind
        // cloud). The moment it's selected it renders at full strength so you can investigate it.
        val dim = t.aboveCloud && !selected
        val dimSave = if (dim) canvas.saveLayerAlpha(null, CLOUD_DIM_ALPHA) else -1

        val rad = (if (selected) 9f else 7f) * density
        // Audible aircraft draw with the green identity (selection is still marked by the ring).
        val fill = if (t.audible) audFill else if (selected) selFill else markerFill
        canvas.drawCircle(px, py, rad, fill)
        canvas.drawCircle(px, py, rad, if (selected) selStroke else markerStroke)
        if (selected) canvas.drawCircle(px, py, rad + 5f * density, selStroke)
        if (t.audible) drawSpeaker(canvas, px + rad + 6.5f * density, py - rad * 0.55f, 4.5f * density)

        // Tiny photo thumbnail above the dot, for aircraft the activity has chosen (selected +
        // nearest). The provider returns null for everything else, so most markers have none.
        thumbnailProvider?.invoke(t.aircraft.hex)?.let { bmp ->
            val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
            if (bw > 0f && bh > 0f) {
                // Box matches the PHOTO's aspect ratio, so the whole image shows with no crop and
                // no distortion. Fixed height; width follows the photo (typically landscape).
                val h = (if (selected) 30f else 24f) * density
                val w = h * (bw / bh)
                val cxTh = px
                val top = py - rad - 6f * density - h
                val dst = RectF(cxTh - w / 2f, top, cxTh + w / 2f, top + h)
                val r = 5f * density
                canvas.save()
                val clip = android.graphics.Path().apply { addRoundRect(dst, r, r, android.graphics.Path.Direction.CW) }
                canvas.clipPath(clip)
                canvas.drawBitmap(bmp, null, dst, thumbPaint)   // whole bitmap → box of same aspect
                canvas.restore()
                canvas.drawRoundRect(dst, r, r, thumbBorder)
            }
        }

        val name = t.aircraft.displayName()
        val altStr = t.labelAltText
        val distNm = look.horizontalRangeM * Aircraft.M_TO_NM
        val vs = t.vertRateMps ?: 0.0
        val vsGlyph = when {
            vs > 0.5 -> "\u25B2"
            vs < -0.5 -> "\u25BC"
            else -> ""
        }
        val gsKt = t.groundSpeedMps?.let { it / Aircraft.KT_TO_MPS }
        val gsStr = if (gsKt != null && gsKt >= 1.0) String.format("%.0f kt", gsKt) else ""
        // ADS-B track is degrees TRUE; show as a 3-digit heading (e.g. 087°T).
        val trkStr = t.trackDeg?.let { String.format("%03.0f°T", ((it % 360) + 360) % 360) } ?: ""
        val line2 = listOf(
            altStr, gsStr, trkStr, String.format("%.1f NM %s", distNm, vsGlyph).trim()
        ).filter { it.isNotEmpty() }.joinToString("  ·  ")

        val padding = 5f * density
        val tw = maxOf(textPaint.measureText(name), subTextPaint.measureText(line2))
        val lh1 = textPaint.fontMetrics.let { it.descent - it.ascent }
        val lh2 = subTextPaint.fontMetrics.let { it.descent - it.ascent }
        val boxLeft = px + rad + 4f * density
        val boxTop = py - (lh1 + lh2) / 2f - padding
        val box = RectF(boxLeft, boxTop, boxLeft + tw + 2 * padding, boxTop + lh1 + lh2 + 2 * padding)
        val dx = if (box.right > width) width - box.right else 0f
        box.offset(dx, 0f)
        canvas.drawRoundRect(box, 6f * density, 6f * density, labelBg)
        val tx = box.left + padding
        val ty1 = box.top + padding - textPaint.fontMetrics.ascent
        canvas.drawText(name, tx, ty1, textPaint)
        val ty2 = box.top + padding + lh1 - subTextPaint.fontMetrics.ascent
        canvas.drawText(line2, tx, ty2, subTextPaint)
        if (dim) canvas.restoreToCount(dimSave)
    }

    private fun drawEdgeArrow(
        canvas: Canvas, cx: Float, cy: Float, w: Float, h: Float,
        px: Float, py: Float, t: RenderTarget
    ) {
        val dx = px - cx; val dy = py - cy
        val len = hypot(dx, dy).coerceAtLeast(1e-3f)
        val ux = dx / len; val uy = dy / len
        val margin = 26f * density
        val tx = if (ux != 0f) ((if (ux > 0) (w - margin - cx) else (margin - cx)) / ux) else Float.MAX_VALUE
        val ty = if (uy != 0f) ((if (uy > 0) (h - margin - cy) else (margin - cy)) / uy) else Float.MAX_VALUE
        val s = min(tx, ty)
        val ex = cx + ux * s; val ey = cy + uy * s

        val a = (if (t.audible) 11f else 9f) * density   // audible: a bit bolder
        val ang = atan2(uy, ux)
        val sel = t.aircraft.hex == selectedHex
        val p = android.graphics.Path()
        p.moveTo(ex + a * cos(ang), ey + a * sin(ang))
        p.lineTo(ex + a * cos(ang + 2.5f), ey + a * sin(ang + 2.5f))
        p.lineTo(ex + a * cos(ang - 2.5f), ey + a * sin(ang - 2.5f))
        p.close()
        canvas.drawPath(p, if (t.audible) audFill else if (sel) selFill else arrowPaint)
        if (t.audible) {
            // Tiny speaker just inward of the arrow, so it reads as "this one is audible".
            drawSpeaker(canvas, ex - ux * (a + 11f * density), ey - uy * (a + 11f * density), 4.5f * density)
        }
        val name = t.aircraft.displayName()
        canvas.drawText(name, ex - subTextPaint.measureText(name) / 2f, ey - 12f * density, subTextPaint)
    }

}
