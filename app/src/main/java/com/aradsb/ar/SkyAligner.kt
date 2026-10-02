package com.aradsb.ar

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.aradsb.geo.GeoPoint
import com.aradsb.geo.Geodesy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.hypot
import kotlin.math.min

/**
 * Neural airplane detection + overlay alignment.
 *
 * Each analysis frame (throttled to ~3 Hz) is converted to an upright RGB bitmap and passed to
 * [AirplaneDetector] (SSD MobileNet v1, COCO, on-device). Passes alternate between the full frame
 * and a zoomed region of interest around the highest-priority ADS-B prediction — the ROI pass is
 * an effective digital zoom that lets the 300x300 detector resolve smaller/farther aircraft.
 *
 * Every airplane detection is drawn as a box by the overlay, and is ASSOCIATED with an ADS-B
 * target when its centre falls within an angular gate of that target's predicted position
 * (used to label the box and to report a "locked" count). Detections do NOT calibrate the
 * compass: a false association — a cloud glint or a bright-dot false positive landing near a
 * prediction — would feed a bad residual and pull the heading off, so the orientation offset is
 * driven only by manual calibration and the magnetometer. CV is purely a visual aid here.
 *
 * Frame-to-view mapping is deterministic: the analysis bitmap is rotated upright, and PreviewView
 * is pinned to FILL_CENTER (set explicitly in the layout), so view coordinates are an
 * aspect-fill of the upright bitmap. No experimental CameraX transform APIs are used.
 *
 * Fail-safe: if the detector is unavailable or nothing is detected, no boxes are drawn.
 */
class SkyAligner(
    private val detector: AirplaneDetector?,
    private val projectionProvider: () -> Projection?,
    private val applyOffset: (Float, Float) -> Unit,
    private val onDetections: (List<ArOverlayView.DetectionBox>) -> Unit,
    private val onStatus: (boxed: Int, associated: Int, azOffDeg: Float, elOffDeg: Float) -> Unit,
    private val userOffsetProvider: () -> Float = { 0f },
) : ImageAnalysis.Analyzer {

    @Volatile var enabled: Boolean = true
    @Volatile private var scene: Scene? = null
    @Volatile private var resetRequested = false

    private var offAz = 0.0
    private var offEl = 0.0
    /** Auto fine-trim of the compass from confirmed detections (see processDetections). */
    @Volatile var autoTrim = true
    private val residBuf = ArrayDeque<Pair<Long, Double>>()
    private var lastNudgeMs = 0L
    private var lastRunMs = 0L
    private var roiTurn = false


    private class Scene(val obs: GeoPoint, val targets: List<RenderTarget>)

    val available: Boolean get() = detector != null

    fun setScene(obs: GeoPoint, targets: List<RenderTarget>) { scene = Scene(obs, targets) }

    fun reset() { resetRequested = true }

    override fun analyze(image: ImageProxy) {
        try {
            if (resetRequested) {
                boxTracks.clear(); offAz = 0.0; offEl = 0.0; residBuf.clear(); lastNudgeMs = 0L; resetRequested = false
                applyOffset(0f, 0f); onDetections(emptyList()); onStatus(0, 0, 0f, 0f)
            }
            if (!enabled || detector == null) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastRunMs < DETECT_INTERVAL_MS) return
            lastRunMs = now

            val proj = projectionProvider() ?: return
            val sc = scene
            val upright = toUprightBitmap(image) ?: return

            try {
                val viewW = proj.viewW.toFloat(); val viewH = proj.viewH.toFloat()
                val bw = upright.width; val bh = upright.height
                val scale = max(viewW / bw, viewH / bh)
                val ox = (viewW - bw * scale) / 2f
                val oy = (viewH - bh * scale) / 2f
                fun toViewX(bx: Float) = bx * scale + ox
                fun toViewY(by: Float) = by * scale + oy

                // --- Neural pass: full frame, or (alternating) a zoomed ROI around the nearest
                // predicted aircraft. This boxes aircraft close enough to show an airplane SHAPE.
                var crop: RectF? = null
                if (roiTurn && sc != null) crop = roiAroundBestTarget(proj, sc, upright, now)
                roiTurn = !roiTurn

                val neural: List<AirplaneDetector.Detection>
                val frame: RectF
                if (crop != null) {
                    val c = Bitmap.createBitmap(
                        upright, crop.left.toInt(), crop.top.toInt(),
                        crop.width().toInt(), crop.height().toInt()
                    )
                    neural = try { detector.detect(c, ROI_MIN_SCORE) } finally { c.recycle() }
                    frame = crop
                } else {
                    neural = detector.detect(upright, FULL_MIN_SCORE)
                    frame = RectF(0f, 0f, bw.toFloat(), bh.toFloat())
                }
                val neuralSearched = RectF(
                    toViewX(frame.left), toViewY(frame.top), toViewX(frame.right), toViewY(frame.bottom)
                )

                val cands = ArrayList<Cand>(neural.size + 4)
                for (d in neural) {
                    val r = RectF(
                        toViewX(frame.left + d.box.left * frame.width()),
                        toViewY(frame.top + d.box.top * frame.height()),
                        toViewX(frame.left + d.box.right * frame.width()),
                        toViewY(frame.top + d.box.bottom * frame.height())
                    )
                    val areaFrac = (r.width() * r.height()) / (viewW * viewH)
                    val aspect = max(r.width(), r.height()) / max(1f, min(r.width(), r.height()))
                    if (areaFrac > MAX_AREA_FRAC || aspect > MAX_ASPECT) continue
                    cands.add(Cand(r, d.score, false))
                }

                // --- Dot pass: a distant jet is just a bright/dark POINT against sky with no
                // shape the neural net can see. ADS-B tells us where it should be, so we search a
                // small window around each in-frame prediction NOT already covered by a neural box
                // for a small high-contrast point. Found dots are boxed and (being ADS-B-matched)
                // also sharpen the compass alignment.
                val preds = buildPreds(proj, sc, now)
                val dotSearchedHexes = HashSet<String>()
                val gatePx = min(viewW, viewH) * ASSOC_GATE_FRAC
                val half = (gatePx / scale / 2f).coerceIn(DOT_WIN_MIN.toFloat(), DOT_WIN_MAX.toFloat())
                for (p in preds) {
                    if (p.px < 0f || p.px > viewW || p.py < 0f || p.py > viewH) continue
                    var covered = false
                    for (c in cands) {
                        if (!c.isDot && hypot(c.rect.centerX() - p.px, c.rect.centerY() - p.py) < gatePx) {
                            covered = true; break
                        }
                    }
                    if (covered) continue
                    dotSearchedHexes.add(p.hex)
                    val bx = ((p.px - ox) / scale).toInt()
                    val by = ((p.py - oy) / scale).toInt()
                    val dot = findDot(upright, bx, by, half.toInt()) ?: continue
                    val vx = toViewX(dot[0]); val vy = toViewY(dot[1])
                    val hb = min(viewW, viewH) * DOT_BOX_HALF_FRAC
                    cands.add(Cand(RectF(vx - hb, vy - hb, vx + hb, vy + hb), dot[2], true))
                }

                processDetections(proj, preds, cands, neuralSearched, dotSearchedHexes, now)
            } finally {
                upright.recycle()
            }
        } catch (_: Throwable) {
            // Vision is best-effort; never crash the camera pipeline.
        } finally {
            image.close()
        }
    }

    // ---- detection handling -------------------------------------------------

    /**
     * A persistent box track. Detections are only DISPLAYED once confirmed on consecutive
     * passes ([CONFIRM_ASSOC]/[CONFIRM_UNASSOC]); an established track coasts through brief
     * misses instead of vanishing (no flashing), and its rectangle is exponentially smoothed
     * so it glides rather than jumps. A miss is only counted when the searched region of the
     * pass actually covered the track — the alternating full/ROI passes can therefore never
     * blink a box that simply lay outside the current ROI.
     */
    private class BoxTrack(
        var rect: RectF,
        var score: Float,
        var hits: Int = 1,
        var misses: Int = 0,
        var assocHex: String? = null,
        var assocCallsign: String? = null,
        var isDot: Boolean = false,
    )

    /** A candidate detection in VIEW coordinates: a neural box ([isDot]=false) or a dot box. */
    private class Cand(val rect: RectF, val score: Float, val isDot: Boolean)

    /** An ADS-B target's predicted screen position and look angles. */
    private class Pred(
        val hex: String, val callsign: String?, val px: Float, val py: Float,
        val azTrue: Double, val el: Double,
    )

    private val boxTracks = ArrayList<BoxTrack>()
    // Reusable luminance window for the dot detector (max DOT_WIN_MAX*2 square).
    private val dotBuf = IntArray((2 * DOT_WIN_MAX + 1) * (2 * DOT_WIN_MAX + 1))

    private fun buildPreds(proj: Projection, sc: Scene?, nowMs: Long): List<Pred> {
        val preds = ArrayList<Pred>()
        if (sc != null) {
            for (t in sc.targets) {
                val pos = positionAt(sc.obs, t, nowMs)
                val look = Geodesy.lookAngles(sc.obs, pos)
                val v = proj.azElToView(look.azimuthTrueDeg, look.elevationDeg) ?: continue
                preds.add(Pred(t.aircraft.hex, t.aircraft.callsign?.trim(),
                    v[0], v[1], look.azimuthTrueDeg, look.elevationDeg))
            }
        }
        return preds
    }

    private fun processDetections(
        proj: Projection,
        preds: List<Pred>,
        cands: List<Cand>,
        neuralSearched: RectF,
        dotSearchedHexes: Set<String>,
        nowMs: Long,
    ) {
        val viewMin = min(proj.viewW, proj.viewH).toFloat()
        val gatePx = viewMin * ASSOC_GATE_FRAC
        val matchPx = viewMin * MATCH_DIST_FRAC
        val desiredAz = ArrayList<Double>()
        val matched = HashSet<BoxTrack>()

        for (c in cands) {
            val cx = c.rect.centerX(); val cy = c.rect.centerY()

            // Associate with the nearest ADS-B prediction inside the gate; track the runner-up
            // distance so a residual is only trusted when no OTHER aircraft could plausibly be
            // this detection (two predictions close together → ambiguous → never auto-trim).
            var best: Pred? = null; var bestD = gatePx; var secondD = Float.MAX_VALUE
            for (p in preds) {
                val dist = hypot(p.px - cx, p.py - cy)
                if (dist < bestD) { secondD = bestD; bestD = dist; best = p }
                else if (dist < secondD) secondD = dist
            }
            val unambiguous = secondD > gatePx && secondD > 2f * bestD

            // Match to an existing track (nearest centre within the match gate), else create.
            var track: BoxTrack? = null; var trackD = matchPx
            for (t in boxTracks) {
                if (t in matched) continue
                val dist = hypot(t.rect.centerX() - cx, t.rect.centerY() - cy)
                if (dist < trackD) { trackD = dist; track = t }
            }
            if (track == null) {
                track = BoxTrack(RectF(c.rect), c.score, isDot = c.isDot)
                boxTracks.add(track)
            } else {
                track.rect.left += RECT_SMOOTH * (c.rect.left - track.rect.left)
                track.rect.top += RECT_SMOOTH * (c.rect.top - track.rect.top)
                track.rect.right += RECT_SMOOTH * (c.rect.right - track.rect.right)
                track.rect.bottom += RECT_SMOOTH * (c.rect.bottom - track.rect.bottom)
                track.score = max(track.score * 0.7f + c.score * 0.3f, c.score * 0.8f)
                track.hits = min(track.hits + 1, 12)
                track.misses = 0
                track.isDot = c.isDot && track.isDot   // a neural hit "upgrades" a dot track
            }
            matched.add(track)
            if (best != null) {
                track.assocHex = best.hex
                track.assocCallsign = best.callsign
            }

            // Residual between a confirmed, ADS-B-associated detection and its predicted
            // position. Reported as the "locked" count, and — under strict gates — fed to the
            // AUTO-TRIM: only associated (not free-floating), unambiguous (no other aircraft
            // nearby that could be this detection), confirmed-over-frames detections, with the
            // residual small enough to be a plausible fine-trim rather than a misassociation.
            if (best != null && track.hits >= confirmFor(track)) {
                val magAzEl = proj.viewToMagAzEl(cx, cy)
                val dAz = wrap180(magAzEl[0] + proj.declinationDeg - best.azTrue)
                val dEl = magAzEl[1] - best.el
                if (abs(dAz) <= MAX_AZ && abs(dEl) <= MAX_EL) desiredAz.add(dAz)
                if (unambiguous && abs(dAz) <= AUTO_RESID_MAX && abs(dEl) <= MAX_EL) {
                    residBuf.addLast(nowMs to dAz)
                    while (residBuf.size > AUTO_BUF_CAP) residBuf.removeFirst()
                }
            }
        }

        // Tracks not matched this pass: a miss counts only if this pass actually looked there —
        // the neural region for any track it covers, or the dot windows for associated preds.
        val it = boxTracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t in matched) continue
            val searchedHere = RectF.intersects(neuralSearched, t.rect) ||
                (t.assocHex != null && t.assocHex in dotSearchedHexes)
            if (searchedHere) {
                t.misses++
                if (t.misses > MISS_DROP || (t.hits < confirmFor(t) && t.misses >= 2)) it.remove()
            }
        }

        // Display: confirmed tracks only; established ones coast through misses (no flashing).
        val boxes = ArrayList<ArOverlayView.DetectionBox>(boxTracks.size)
        for (t in boxTracks) {
            if (t.hits < confirmFor(t)) continue
            if (t.assocHex == null && t.score < DISPLAY_SCORE_UNASSOC) continue
            boxes.add(ArOverlayView.DetectionBox(RectF(t.rect), t.score,
                t.assocCallsign ?: t.assocHex))
        }

        // AUTO-TRIM: with enough fresh, strictly-gated residuals, nudge the azimuth trim toward
        // their MEDIAN (robust to a stray outlier that survived the gates). Slow gain, rate
        // limited, and the total auto-trim is hard-capped to ±AUTO_MAX_TRIM — this is a fine-trim
        // that quietly zeroes the last few degrees whenever a real aircraft is visible; large
        // errors remain the figure-8's job and are never chased.
        while (residBuf.isNotEmpty() && nowMs - residBuf.first().first > AUTO_WINDOW_MS) residBuf.removeFirst()
        if (autoTrim && residBuf.size >= AUTO_MIN_SAMPLES && nowMs - lastNudgeMs >= AUTO_NUDGE_MS) {
            lastNudgeMs = nowMs
            val sorted = residBuf.map { it.second }.sorted()
            val median = sorted[sorted.size / 2]
            val target = (median - userOffsetProvider()).coerceIn(-AUTO_MAX_TRIM, AUTO_MAX_TRIM)
            offAz += AUTO_GAIN * wrap180(target - offAz)
            offAz = offAz.coerceIn(-AUTO_MAX_TRIM, AUTO_MAX_TRIM)
            applyOffset(offAz.toFloat(), 0f)
        }
        onDetections(boxes)
        onStatus(boxes.size, desiredAz.size, offAz.toFloat(), offEl.toFloat())
    }

    /**
     * Confirmation passes required before a track is shown: ADS-B-backed neural boxes are
     * quickest; dot tracks need an extra pass (a single bright pixel could be noise);
     * uncorroborated neural boxes need the most.
     */
    private fun confirmFor(t: BoxTrack): Int = when {
        t.assocHex != null && t.isDot -> CONFIRM_DOT
        t.assocHex != null -> CONFIRM_ASSOC
        else -> CONFIRM_UNASSOC
    }

    /**
     * Search a [half]-radius window of [bmp] centred on (cx,cy) for a small high-contrast point
     * (a sunlit fuselage/strobe, or a dark airframe) against sky. Returns {x, y, confidence} in
     * bitmap pixels, or null. Rejects textured windows (cloud) and large bright regions (not a
     * point source), so only compact outliers survive.
     */
    private fun findDot(bmp: Bitmap, cx: Int, cy: Int, half: Int): FloatArray? {
        val l = (cx - half).coerceIn(0, bmp.width - 1)
        val t = (cy - half).coerceIn(0, bmp.height - 1)
        val r = (cx + half).coerceIn(0, bmp.width - 1)
        val b = (cy + half).coerceIn(0, bmp.height - 1)
        val w = r - l; val h = b - t
        if (w < 6 || h < 6) return null
        val n = w * h
        if (n > dotBuf.size) return null
        bmp.getPixels(dotBuf, 0, w, l, t, w, h)

        var sum = 0.0; var sumSq = 0.0
        for (i in 0 until n) {
            val p = dotBuf[i]
            // Luma approx (0.21 R, 0.72 G, 0.07 B) in fixed point.
            val lum = (((p ushr 16) and 0xFF) * 54 + ((p ushr 8) and 0xFF) * 183 +
                (p and 0xFF) * 19) shr 8
            sum += lum; sumSq += (lum * lum).toDouble()
        }
        val mean = sum / n
        val std = kotlin.math.sqrt((sumSq / n - mean * mean).coerceAtLeast(0.0))
        if (std > DOT_MAX_SKY_STD) return null   // textured/cloudy → unreliable

        var peakIdx = -1; var peakDev = 0.0
        for (i in 0 until n) {
            val p = dotBuf[i]
            val lum = (((p ushr 16) and 0xFF) * 54 + ((p ushr 8) and 0xFF) * 183 +
                (p and 0xFF) * 19) shr 8
            val dev = kotlin.math.abs(lum - mean)
            if (dev > peakDev) { peakDev = dev; peakIdx = i }
        }
        if (peakIdx < 0 || peakDev < DOT_MIN_ABS || peakDev < DOT_SIGMA * std) return null

        // Compact-extent check: a point source has few pixels near the peak deviation; a cloud
        // edge or bright bank has many.
        var extent = 0
        val thresh = 0.5 * peakDev
        for (i in 0 until n) {
            val p = dotBuf[i]
            val lum = (((p ushr 16) and 0xFF) * 54 + ((p ushr 8) and 0xFF) * 183 +
                (p and 0xFF) * 19) shr 8
            if (kotlin.math.abs(lum - mean) > thresh) extent++
        }
        if (extent > DOT_MAX_EXTENT) return null

        val px = l + peakIdx % w
        val py = t + peakIdx / w
        val conf = (peakDev / (DOT_SIGMA * std.coerceAtLeast(1.0))).toFloat().coerceIn(0.4f, 0.95f)
        return floatArrayOf(px.toFloat(), py.toFloat(), conf)
    }

    /** ROI (in upright-bitmap px) around the in-front predicted target nearest the view centre. */
    private fun roiAroundBestTarget(
        proj: Projection, sc: Scene, upright: Bitmap, nowMs: Long,
    ): RectF? {
        val bw = upright.width.toFloat(); val bh = upright.height.toFloat()
        val scale = max(proj.viewW / bw, proj.viewH / bh)
        val ox = (proj.viewW - bw * scale) / 2f
        val oy = (proj.viewH - bh * scale) / 2f

        var bestPx = 0f; var bestPy = 0f; var bestD = Float.MAX_VALUE; var found = false
        for (t in sc.targets) {
            val pos = positionAt(sc.obs, t, nowMs)
            val look = Geodesy.lookAngles(sc.obs, pos)
            val v = proj.azElToView(look.azimuthTrueDeg, look.elevationDeg) ?: continue
            val dx = v[0] - proj.viewW / 2f; val dy = v[1] - proj.viewH / 2f
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; bestPx = v[0]; bestPy = v[1]; found = true }
        }
        if (!found) return null

        // View px -> bitmap px (inverse FILL_CENTER), then a square crop clamped to the bitmap.
        val bx = (bestPx - ox) / scale
        val by = (bestPy - oy) / scale
        val half = (min(bw, bh) * ROI_FRAC / 2f).coerceAtLeast(AirplaneDetector.INPUT_SIZE / 2f)
        if (2f * half > bw || 2f * half > bh) return null   // buffer smaller than the crop
        val l = (bx - half).coerceIn(0f, bw - 2f * half)
        val t = (by - half).coerceIn(0f, bh - 2f * half)
        return RectF(l, t, l + 2f * half, t + 2f * half)
    }

    // ---- image conversion ---------------------------------------------------

    /**
     * RGBA_8888 ImageProxy -> upright ARGB bitmap (rotated by the frame's rotationDegrees).
     * Requires ImageAnalysis OUTPUT_IMAGE_FORMAT_RGBA_8888.
     */
    private fun toUprightBitmap(image: ImageProxy): Bitmap? {
        val plane = image.planes.getOrNull(0) ?: return null
        val rowStridePx = plane.rowStride / plane.pixelStride
        val raw = Bitmap.createBitmap(rowStridePx, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        raw.copyPixelsFromBuffer(plane.buffer)

        val cropped = if (rowStridePx != image.width) {
            val c = Bitmap.createBitmap(raw, 0, 0, image.width, image.height)
            raw.recycle(); c
        } else raw

        val deg = image.imageInfo.rotationDegrees
        if (deg == 0) return cropped
        val m = Matrix(); m.postRotate(deg.toFloat())
        val rotated = Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height, m, true)
        if (rotated !== cropped) cropped.recycle()
        return rotated
    }

    // ---- shared helpers -----------------------------------------------------

    private fun positionAt(obs: GeoPoint, t: RenderTarget, nowMs: Long): GeoPoint {
        val age = ((nowMs - t.baseElapsedMs) / 1000.0).coerceIn(0.0, MAX_EXTRAPOLATION_S)
        return Geodesy.extrapolate(
            GeoPoint(t.baseLat, t.baseLon, t.baseAltM),
            t.trackDeg, t.groundSpeedMps, t.vertRateMps, t.turnRateDegS, age
        )
    }

    private fun wrap180(deg: Double): Double {
        var d = (deg + 180.0) % 360.0
        if (d < 0) d += 360.0
        return d - 180.0
    }

    companion object {
        private const val DETECT_INTERVAL_MS = 350L
        // Raw detector gates (what enters the tracker at all).
        private const val FULL_MIN_SCORE = 0.50f
        private const val ROI_MIN_SCORE = 0.45f
        // Uncorroborated tracks must ALSO reach this smoothed score to be displayed.
        private const val DISPLAY_SCORE_UNASSOC = 0.62f
        private const val ROI_FRAC = 0.40f             // ROI side = 40% of the short bitmap side
        private const val ASSOC_GATE_FRAC = 0.18f      // ADS-B association gate (view fraction)
        private const val MATCH_DIST_FRAC = 0.12f      // track-to-detection match gate
        private const val RECT_SMOOTH = 0.45f          // box position smoothing factor
        private const val CONFIRM_ASSOC = 2            // passes to confirm an ADS-B-backed box
        private const val CONFIRM_DOT = 3              // passes to confirm a bright-dot detection
        private const val CONFIRM_UNASSOC = 4          // passes to confirm an uncorroborated box
        private const val MISS_DROP = 6                // searched-region misses before dropping
        private const val MAX_AREA_FRAC = 0.45f        // reject boxes covering >45% of the view
        private const val MAX_ASPECT = 6f              // reject extreme strips (cloud edges)
        // Bright-dot detector (far aircraft): window size in bitmap px, and acceptance gates.
        private const val DOT_WIN_MIN = 16             // min search half-window (bitmap px)
        private const val DOT_WIN_MAX = 48             // max search half-window (bitmap px)
        private const val DOT_BOX_HALF_FRAC = 0.018f   // drawn dot box half-size (view fraction)
        private const val DOT_SIGMA = 4.0              // peak must exceed mean by this many σ
        private const val DOT_MIN_ABS = 16.0           // and by this absolute luma (0..255)
        private const val DOT_MAX_SKY_STD = 26.0       // reject textured/cloudy windows
        private const val DOT_MAX_EXTENT = 24          // max px near the peak (point source)
        // Auto-trim gates (see processDetections). Damage from any residual failure mode is
        // bounded by AUTO_MAX_TRIM; everything else buys confidence before a nudge happens.
        private const val AUTO_RESID_MAX = 4.0      // deg — larger residuals: not a fine-trim
        private const val AUTO_MAX_TRIM = 6.0       // deg — hard cap on total auto correction
        private const val AUTO_MIN_SAMPLES = 8      // fresh gated residuals before any nudge
        private const val AUTO_WINDOW_MS = 20_000L  // residual freshness window
        private const val AUTO_BUF_CAP = 24
        private const val AUTO_GAIN = 0.25          // fraction of remaining error per nudge
        private const val AUTO_NUDGE_MS = 1_000L    // min interval between nudges
        private const val MAX_AZ = 30.0
        private const val MAX_EL = 15.0
        private const val MAX_EXTRAPOLATION_S = 30.0
    }
}
