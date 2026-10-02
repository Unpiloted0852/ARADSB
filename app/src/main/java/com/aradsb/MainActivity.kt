package com.aradsb

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aradsb.ar.AirplaneDetector
import com.aradsb.ar.CameraFov
import com.aradsb.ar.RenderTarget
import com.aradsb.ar.SkyAligner
import com.aradsb.data.MultiSourceAdsbRepository
import com.aradsb.data.PhotoRepository
import com.aradsb.data.PhotoResult
import com.aradsb.databinding.ActivityMainBinding
import com.aradsb.geo.GeoPoint
import com.aradsb.geo.Geodesy
import com.aradsb.location.LocationTracker
import com.aradsb.model.AdsbFields
import com.aradsb.model.Aircraft
import com.aradsb.sensor.OrientationTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var orientation: OrientationTracker
    private lateinit var location: LocationTracker
    private val repo = MultiSourceAdsbRepository()
    private val photoRepo = PhotoRepository()
    // Per-aircraft recent position history for the comet trail (the real flown path).
    private class TrailPt(val lat: Double, val lon: Double, val altM: Double, val tMs: Long)
    private val trails = HashMap<String, ArrayDeque<TrailPt>>()
    private val metarRepo = com.aradsb.data.MetarRepository()
    @Volatile private var currentWeather: com.aradsb.data.MetarRepository.Weather? = null
    private val haptics by lazy { com.aradsb.ui.Haptics(this) }
    private val updater by lazy { com.aradsb.update.AppUpdater(this) }
    // Small bitmaps drawn on the nearest markers; provider returns null for everything else.
    private val markerThumbs = java.util.Collections.synchronizedMap(HashMap<String, android.graphics.Bitmap>())
    private val thumbInFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    private val rotationBuf = FloatArray(9)
    private var cameraInfo: CameraInfo? = null
    private var cameraControl: androidx.camera.core.CameraControl? = null
    private var zoomRatio = 1f
    private var minZoom = 1f
    private var maxZoom = 1f

    private lateinit var prefs: SharedPreferences

    private lateinit var analysisExecutor: java.util.concurrent.ExecutorService
    private lateinit var aligner: SkyAligner
    private var calibrationDismissed = false
    private var calibGuideStep = 0          // 0 = closed, 1 = compass, 2 = FOV
    @Volatile private var lastFetchOkMs = 0L

    // UI-level stability guarantees (defense in depth on top of the repository merge):
    // identityCache: per-hex, the richest "type · registration" line seen this session —
    // the identity text can update in place but can never regress to a shorter version.
    // sheetFieldUnion: while the detail sheet is open, the union of every raw field seen
    // for the pinned aircraft — rows are append-only, so the sheet can never jump.
    private val identityCache = HashMap<String, String>()
    private val sheetFieldUnion = LinkedHashMap<String, String>()
    private var sheetUnionHex: String? = null

    // EGM2008 geoid (bundled 15' grid); null if the asset ever fails to load.
    private val geoid: com.aradsb.geo.Geoid? by lazy {
        runCatching { com.aradsb.geo.Geoid(this) }.getOrNull()
    }

    @Volatile private var radiusNm = 60          // search radius (NM), adjustable in view
    private val pollIntervalMs = 500L
    private val fetchMutex = Mutex()

    private var baseFov = CameraFov.DEFAULT_HORIZONTAL_FOV_DEG
    private var fovScale = 1f
    private var granted = false

    companion object {
        private const val PREFS = "aradsb_settings"
        private const val KEY_RANGE_NM = "range_nm"
        private const val KEY_FOV_SCALE = "fov_scale"
        private const val KEY_AI_DETECT = "ai_detect"
        private const val KEY_HINT_SHOWN = "hint_shown"
        private const val KEY_ROSE_TRUE = "rose_true_north"
        private const val KEY_AUTO_TRIM = "auto_trim"
        private const val KEY_GYRO_ONLY = "gyro_only_heading"
        private const val KEY_COMPASS_OFFSET = "compass_offset_deg"
        private const val MARKER_THUMB_COUNT = 4   // nearest aircraft to show a marker thumbnail
        private const val CLOUD_MARGIN_M = 30.0    // aircraft must clear the ceiling by this much
        private const val TRAIL_MAX_PTS = 30       // comet-trail history length (points)
        private const val TRAIL_MIN_GAP_MS = 1000L // min spacing between recorded trail points
        private const val MIN_RANGE_NM = 5
        private const val RANGE_STEPS = 200
    }

    // Currently selected (reticle) aircraft and its photo link, for the detail card.
    private var selectedHex: String? = null
    private var photoLink: String? = null

    // Expanded sheet state.
    private var currentSelection: RenderTarget? = null
    private var expanded = false
    private var pinnedHex: String? = null
    private val swipeVelThreshold = 700f   // px/s

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = collapsePanel()
    }

    private val guideBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeCalibGuide()
    }

    private val calibBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // Back while calibrating commits the current offset (same as Done).
            val off = binding.overlay.finishCalibration()
            prefs.edit().putFloat(KEY_COMPASS_OFFSET, off).apply()
            binding.calibBar.visibility = View.GONE
            isEnabled = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cam = result[Manifest.permission.CAMERA] == true
        val loc = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        granted = cam && loc
        if (granted) startEverything() else {
            binding.statusText.text = getString(R.string.perm_rationale)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // An AR viewer is glanced at hands-free; never let the screen sleep mid-use.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        orientation = OrientationTracker(this) { onOrientation() }
        location = LocationTracker(this) { onLocation() }

        analysisExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        // The detector loads the bundled TFLite model; if that ever fails, vision features
        // degrade gracefully (no boxes, no auto-align) rather than crashing.
        val detector = runCatching { AirplaneDetector(this) }.getOrNull()
        aligner = SkyAligner(
            detector = detector,
            projectionProvider = { binding.overlay.projectionSnapshot() },
            applyOffset = { az, el -> binding.overlay.setAlignmentOffset(az, el) },
            onDetections = { boxes -> binding.overlay.setDetections(boxes) },
            onStatus = { boxed, assoc, az, el ->
                runOnUiThread { updateAlignStatus(boxed, assoc, az, el) }
            },
            userOffsetProvider = { binding.overlay.userHeadingOffset() },
        )
        if (detector == null) {
            binding.alignSwitch.isEnabled = false
            binding.alignSwitch.isChecked = false
            aligner.enabled = false
        }
        orientation.onHealthChanged = { h -> runOnUiThread { onCompassHealth(h) } }

        // Restore persisted settings (range + FOV calibration + AI detection).
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        radiusNm = prefs.getInt(KEY_RANGE_NM, 60).coerceIn(5, MultiSourceAdsbRepository.MAX_RADIUS_NM)
        fovScale = prefs.getFloat(KEY_FOV_SCALE, 1f).coerceIn(0.3f, 3f)
        val aiSaved = prefs.getBoolean(KEY_AI_DETECT, true)
        if (detector != null) {
            binding.alignSwitch.isChecked = aiSaved
            aligner.enabled = aiSaved
        }
        // Compass reference (true/magnetic) and any persisted manual heading offset.
        val trueNorth = prefs.getBoolean(KEY_ROSE_TRUE, true)
        binding.trueNorthSwitch.isChecked = trueNorth
        binding.overlay.setRoseReference(trueNorth)
        binding.overlay.setUserHeadingOffset(prefs.getFloat(KEY_COMPASS_OFFSET, 0f))

        binding.overlay.onSelectionChanged = { target -> onSelectionChanged(target) }

        binding.fovMinus.setOnClickListener { nudgeFov(-2f) }
        binding.fovPlus.setOnClickListener { nudgeFov(+2f) }

        binding.settingsButton.setOnClickListener {
            binding.controlsPanel.visibility =
                if (binding.controlsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // Range slider: logarithmic mapping (fine 1-NM resolution at short range where it
        // matters, coarser toward 250 NM), with −/+ steppers for exact 1-NM adjustments.
        binding.rangeSeek.max = RANGE_STEPS
        binding.rangeSeek.progress = rangeNmToProgress(radiusNm)
        binding.rangeText.text = String.format(Locale.US, "Range  ·  %d NM", radiusNm)

        binding.rangeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                radiusNm = progressToRangeNm(progress)
                binding.rangeText.text = String.format(Locale.US, "Range  ·  %d NM", radiusNm)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { commitRange() }
        })
        binding.rangeMinus.setOnClickListener { stepRange(-1) }
        binding.rangePlus.setOnClickListener { stepRange(+1) }

        // Swipe up on the compact card to open the full detail sheet; tap opens the photo.
        val cardGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                expandPanel(); return true
            }
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (velocityY < -swipeVelThreshold && abs(velocityY) > abs(velocityX)) {
                    expandPanel(); return true
                }
                return false
            }
        })
        binding.detailCard.setOnTouchListener { v, ev ->
            val handled = cardGestures.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            handled
        }

        // Swipe down (or tap the handle / X) to close the sheet.
        val handleGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (velocityY > swipeVelThreshold && abs(velocityY) > abs(velocityX)) {
                    collapsePanel(); return true
                }
                return false
            }
        })
        binding.expHandleTouch.setOnTouchListener { _, ev -> handleGestures.onTouchEvent(ev) }
        binding.expClose.setOnClickListener { collapsePanel() }
        binding.expLink.setOnClickListener { openPhotoLink() }
        onBackPressedDispatcher.addCallback(this, backCallback)
        onBackPressedDispatcher.addCallback(this, guideBackCallback)
        onBackPressedDispatcher.addCallback(this, calibBackCallback)

        val autoTrimSaved = prefs.getBoolean(KEY_AUTO_TRIM, true)
        binding.autoTrimSwitch.isChecked = autoTrimSaved
        aligner.autoTrim = autoTrimSaved

        val gyroOnlySaved = prefs.getBoolean(KEY_GYRO_ONLY, false)
        binding.gyroOnlySwitch.isChecked = gyroOnlySaved
        orientation.gyroOnlyYaw = gyroOnlySaved
        binding.gyroOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
            orientation.gyroOnlyYaw = isChecked
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_GYRO_ONLY, isChecked).apply()
            if (isChecked) {
                android.widget.Toast.makeText(
                    this,
                    "Heading is now gyro-only. Set it once: tap-and-hold, then drag until the rose points true.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        binding.autoTrimSwitch.setOnCheckedChangeListener { _, isChecked ->
            aligner.autoTrim = isChecked
            if (!isChecked) aligner.reset()   // also zeroes any applied auto-trim
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_AUTO_TRIM, isChecked).apply()
        }

        binding.alignSwitch.setOnCheckedChangeListener { _, isChecked ->
            aligner.enabled = isChecked
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_AI_DETECT, isChecked).apply()
            if (!isChecked) {
                aligner.reset()
                binding.overlay.setAlignmentOffset(0f, 0f)
                updateAlignStatus(0, 0, 0f, 0f)
            }
        }
        binding.calibrationDismiss.setOnClickListener {
            calibrationDismissed = true
            binding.calibrationOverlay.visibility = View.GONE
        }

        // Compass reference toggle (persisted).
        binding.trueNorthSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.overlay.setRoseReference(isChecked)
            if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_ROSE_TRUE, isChecked).apply()
        }

        // Manual compass calibration: 1.2-second hold on the horizon compass opens the orange
        // adjust mode; the bar's buttons commit or clear it. The offset persists across runs
        // (it corrects a largely static, per-device magnetometer bias), is bounded, and Reset
        // removes it.
        binding.overlay.onCalibrationStarted = {
            haptics.unlock()
            binding.calibBar.visibility = View.VISIBLE
            binding.controlsPanel.visibility = View.GONE
            calibBackCallback.isEnabled = true
            updateCalibValue()
        }
        binding.overlay.thumbnailProvider = { hex -> markerThumbs[hex] }
        binding.overlay.onZoomChanged = { factor -> applyZoom(zoomRatio * factor) }
        binding.overlay.onUserOffsetChanged = { updateCalibValue() }
        binding.calibDone.setOnClickListener {
            val off = binding.overlay.finishCalibration()
            prefs.edit().putFloat(KEY_COMPASS_OFFSET, off).apply()
            binding.calibBar.visibility = View.GONE
            calibBackCallback.isEnabled = false
        }


        binding.calibReset.setOnClickListener {
            binding.overlay.resetCalibration()
            prefs.edit().remove(KEY_COMPASS_OFFSET).apply()
            binding.calibBar.visibility = View.GONE
            calibBackCallback.isEnabled = false
        }

        // One-time first-run hint.
        if (!prefs.getBoolean(KEY_HINT_SHOWN, false)) {
            binding.hintOverlay.visibility = View.VISIBLE
        }
        binding.hintDismiss.setOnClickListener {
            prefs.edit().putBoolean(KEY_HINT_SHOWN, true).apply()
            binding.hintOverlay.visibility = View.GONE
        }

        // Guided calibration (settings -> Help me calibrate).
        binding.helpCalibrate.setOnClickListener {
            binding.controlsPanel.visibility = View.GONE
            binding.calibrationOverlay.visibility = View.GONE   // the automatic prompt yields
            showCalibGuideStep(1)
        }
        binding.calibGuideClose.setOnClickListener { closeCalibGuide() }
        binding.calibGuideNext.setOnClickListener {
            if (calibGuideStep == 1) showCalibGuideStep(2) else {
                closeCalibGuide()
                binding.controlsPanel.visibility = View.VISIBLE   // land on the FOV controls
            }
        }

        checkForUpdate()

        if (hasPermissions()) {
            granted = true
            startEverything()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    /** Once per launch: if GitHub has a newer release, offer it as a tappable pill under the status. */
    private fun checkForUpdate() {
        lifecycleScope.launch {
            val release = updater.checkForUpdate() ?: return@launch
            val offer = "Update available: v${release.versionName} — tap to install"
            var busy = false
            binding.updatePill.text = offer
            binding.updatePill.visibility = View.VISIBLE
            binding.updatePill.setOnClickListener {
                if (busy) return@setOnClickListener
                busy = true
                lifecycleScope.launch {
                    val error = updater.downloadAndInstall(release) { pct ->
                        binding.updatePill.text =
                            if (pct < 100) "Downloading update… $pct%" else "Installing update…"
                    }
                    // Only reached if the update did not replace the running app.
                    busy = false
                    binding.updatePill.text = offer
                    if (error != null && error != "cancelled") {
                        android.widget.Toast.makeText(
                            this@MainActivity, "Update failed: $error", android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun hasPermissions(): Boolean {
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        val loc = fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
        return cam == PackageManager.PERMISSION_GRANTED && loc
    }

    private fun startEverything() {
        startCamera()
        if (!orientation.isAvailable) {
            binding.statusText.text = "No rotation-vector sensor on this device — AR cannot work."
        }
        binding.overlay.setDisplayRotation(currentDisplayRotation())
        startPolling()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val previewBuilder = Preview.Builder()
            forceInfinityFocus(androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder))
            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            androidx.camera.core.resolutionselector.ResolutionStrategy(
                                android.util.Size(1280, 720),
                                androidx.camera.core.resolutionselector.ResolutionStrategy
                                    .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        ).build()
                )
            forceInfinityFocus(androidx.camera.camera2.interop.Camera2Interop.Extender(analysisBuilder))
            val analysis = analysisBuilder.build().also {
                it.setAnalyzer(analysisExecutor, aligner)
            }

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                cameraInfo = camera.cameraInfo
                cameraControl = camera.cameraControl
                camera.cameraInfo.zoomState.value?.let {
                    minZoom = it.minZoomRatio
                    maxZoom = it.maxZoomRatio
                    zoomRatio = it.zoomRatio.coerceIn(minZoom, maxZoom)
                }
                updateFovEstimate()
            } catch (e: Exception) {
                binding.statusText.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Lock focus at infinity (0 diopters) so distant aircraft are sharp instead of hunting. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun forceInfinityFocus(extender: androidx.camera.camera2.interop.Camera2Interop.Extender<*>) {
        extender.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
            android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_OFF
        )
        extender.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE, 0f
        )
    }

    private fun updateAlignStatus(boxed: Int, associated: Int, azOff: Float, elOff: Float) {
        if (!aligner.enabled) {
            binding.alignText.visibility = View.GONE
            return
        }
        binding.alignText.visibility = View.VISIBLE
        binding.alignText.text = when {
            associated >= 1 -> String.format(
                Locale.US, "AI: %d ✈ boxed · aligned to ADS-B · Δhdg %+.1f°", boxed, azOff
            )
            boxed >= 1 -> String.format(Locale.US, "AI: %d ✈ boxed", boxed)
            else -> "AI: scanning for aircraft…"
        }
    }

    private fun onCompassHealth(level: Int) {
        // The guided flow shows live, quantitative feedback while the user does the figure-8.
        if (calibGuideStep == 1) {
            binding.calibGuideStatus.text = compassHealthLabel(level)
            return
        }
        if (level >= OrientationTracker.HEALTH_MEDIUM) {
            calibrationDismissed = false
            binding.calibrationOverlay.visibility = View.GONE
        } else if (!calibrationDismissed && calibGuideStep == 0) {
            binding.calibrationOverlay.visibility = View.VISIBLE
        }
    }

    /**
     * Quantitative compass status. Shows the actual numbers (heading-error estimate, measured
     * vs WMM-expected field strength) so "good" is demonstrable rather than asserted — the
     * rotation vector's own HIGH/MEDIUM enum is permanently optimistic on many devices and is
     * deliberately not used.
     */
    private fun compassHealthLabel(level: Int): String {
        val parts = ArrayList<String>(2)
        orientation.headingErrDeg?.let { parts.add(String.format(Locale.US, "heading ±%.0f°", it)) }
        val b = orientation.fieldUt
        val exp = orientation.expectedFieldUt
        if (b != null) {
            parts.add(
                if (exp != null) String.format(Locale.US, "field %.0f µT (expect %.0f)", b, exp)
                else String.format(Locale.US, "field %.0f µT", b)
            )
        }
        val detail = if (parts.isEmpty()) "" else " — " + parts.joinToString(" · ")
        return when (level) {
            OrientationTracker.HEALTH_GOOD -> "Compass good ✓$detail"
            OrientationTracker.HEALTH_MEDIUM -> "Almost there$detail"
            else -> "Keep going$detail"
        }
    }

    private fun showCalibGuideStep(step: Int) {
        calibGuideStep = step
        guideBackCallback.isEnabled = true
        binding.calibGuideOverlay.visibility = View.VISIBLE
        if (step == 1) {
            binding.calibGuideStep.text = "Step 1 of 2"
            binding.calibGuideTitle.text = "Calibrate the compass"
            binding.calibGuideBody.text = if (orientation.magneticInterference())
                "Something magnetic nearby — a car, a magnetic case or mount, speakers — is " +
                "distorting the compass, and a figure-8 won't fix that. Move into the open, away " +
                "from metal, then sweep the phone through a figure-8. Watch the live accuracy:"
            else
                "Hold the phone in front of you and sweep it through a figure-8 a few times, " +
                "rotating your wrist as you go — the motion below. This lets the magnetometer " +
                "cancel out nearby magnetic interference. Watch the live accuracy:"
            binding.calibGuideFigure.visibility = View.VISIBLE
            binding.calibGuideStatus.text = compassHealthLabel(orientation.compassHealth())
            binding.calibGuideNext.text = "Next"
        } else {
            binding.calibGuideStep.text = "Step 2 of 2"
            binding.calibGuideTitle.text = "Set the field of view"
            binding.calibGuideBody.text =
                "The field of view (FOV) controls how far apart markers are spread on screen. " +
                "The angles to each aircraft are computed exactly — FOV only matches them to " +
                "your lens.\n\n" +
                "1. Find an aircraft you can actually see (an approach or low traffic works best).\n" +
                "2. Centre it in the camera view.\n" +
                "3. In settings, tap − / + under Field of view until its marker sits on the " +
                "aircraft itself.\n\n" +
                "One aircraft is enough — the setting is per-lens and is saved. Heading drift " +
                "is corrected automatically by the AI auto-align, so calibrate FOV by marker " +
                "spacing, not left-right offset."
            binding.calibGuideFigure.visibility = View.GONE
            binding.calibGuideStatus.text = ""
            binding.calibGuideNext.text = "Open FOV controls"
        }
    }

    private fun closeCalibGuide() {
        calibGuideStep = 0
        guideBackCallback.isEnabled = false
        binding.calibGuideOverlay.visibility = View.GONE
        // Re-evaluate the automatic prompt against the current accuracy.
        onCompassHealth(orientation.compassHealth())
    }

    private fun startPolling() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Clock-offset reference: GNSS fixes feed TrueTime continuously; SNTP runs as
                // a fallback before the first fix and is rechecked occasionally.
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    while (true) {
                        runCatching { com.aradsb.time.TrueTime.syncNtpIfNeeded() }
                        delay(30 * 60_000L)
                    }
                }
                // Nearby cloud ceiling (METAR), for the "may be hidden by clouds" hint. Retry
                // quickly until the first fetch lands (it's gated on the GPS fix being ready),
                // then back off — the repository self-throttles its network calls to ~10 min.
                launch(kotlinx.coroutines.Dispatchers.IO) {
                    while (true) {
                        location.observer()?.let { obs ->
                            currentWeather = runCatching { metarRepo.weatherNear(obs.latDeg, obs.lonDeg) }.getOrNull()
                        }
                        delay(if (metarRepo.hasFetched) 60_000L else 2_000L)
                    }
                }
                while (true) {
                    val obs = location.observer()
                    if (obs == null) {
                        binding.statusText.text = "Acquiring location (fused / GPS / network)…"
                    } else {
                        fetchAndUpdate(obs)
                    }
                    delay(pollIntervalMs)
                }
            }
        }
    }

    /** Fetch once and refresh the overlay. Skips if a fetch is already in flight. */
    private suspend fun fetchAndUpdate(obs: GeoPoint) {
        if (!fetchMutex.tryLock()) return
        try {
            val list = repo.fetchAround(obs.latDeg, obs.lonDeg, radiusNm)
            lastFetchOkMs = SystemClock.elapsedRealtime()
            updateTargets(obs, list)
        } catch (e: Exception) {
            // All sources down AND no recent data: a calm, actionable message — never a raw
            // exception string. The merged track table keeps recent traffic on screen meanwhile.
            binding.statusText.text = "Reconnecting to ADS-B network…"
        } finally {
            fetchMutex.unlock()
        }
    }

    private fun updateTargets(obs: GeoPoint, list: List<MultiSourceAdsbRepository.Fetched>) {
        val out = ArrayList<RenderTarget>(list.size)
        for (f in list) {
            val ac = f.aircraft
            if (!ac.isPlaceable) continue
            val seen = ac.seenPosSec ?: 0.0
            // Effective position age: time since fetch plus the source-reported position age.
            val baseElapsedMs = f.fetchedAtElapsedMs - (seen * 1000.0).toLong()
            if (SystemClock.elapsedRealtime() - baseElapsedMs > 60_000L) continue   // stale
            // Altitude: geometric/baro when transmitted. Ground aircraft often transmit none —
            // place them at the observer's own ellipsoidal height (terrain nearby is within a
            // few tens of metres, well inside marker tolerance at taxi/runway distances).
            val altPair = ac.altitudeMetersHae()
            val altM: Double
            val geom: Boolean
            if (altPair != null) {
                altM = altPair.first; geom = altPair.second
            } else if (ac.onGround) {
                altM = obs.heightM; geom = false
            } else continue
            // Coarse below-horizon cull using the reported position.
            val look = Geodesy.lookAngles(obs, GeoPoint(ac.lat, ac.lon, altM))
            if (look.elevationDeg < -15.0) continue
            // Respect the CURRENT range immediately (the repo also filters; this covers the
            // window between moving the slider and the next fetch).
            if (look.horizontalRangeM * Aircraft.M_TO_NM > radiusNm + 0.5) continue
            // Above a nearby BKN/OVC ceiling? Aircraft MSL (HAE − geoid N) vs the ceiling MSL base.
            val aboveCloud = run {
                val ceil = currentWeather?.ceilingMslM ?: return@run false
                val g = geoid ?: return@run false
                val agFt = ac.altGeomFt ?: return@run false
                val acMslM = agFt * Aircraft.FT_TO_M - g.undulation(ac.lat, ac.lon)
                acMslM > ceil + CLOUD_MARGIN_M
            }
            // Audible right now? Computed for EVERY aircraft so the overlay can mark the ones
            // you're probably hearing (green dot / green edge arrow with a speaker glyph).
            val vr = if (ac.onGround) null else ac.vertRateMps()
            val audible = run {
                if (ac.onGround) return@run false
                val azR = Math.toRadians(look.azimuthTrueDeg)
                val elR = Math.toRadians(look.elevationDeg)
                val cosEl = Math.cos(elR)
                val trkR = ac.trackDeg?.let { Math.toRadians(it) }
                val gsA = ac.groundSpeedMps() ?: 0.0
                com.aradsb.sound.Audibility.isAudible(
                    ac.typeCode, ac.category,
                    look.slantRangeM * cosEl * Math.sin(azR),
                    look.slantRangeM * cosEl * Math.cos(azR),
                    look.slantRangeM * Math.sin(elR),
                    if (trkR != null) gsA * Math.sin(trkR) else 0.0,
                    if (trkR != null) gsA * Math.cos(trkR) else 0.0,
                    vr ?: 0.0,
                    vr, look.elevationDeg,
                    currentWeather?.tempC, currentWeather?.dewpC
                )
            }
            // Record actual recent position history (the real flown path — curved in turns,
            // sloping in climbs/descents) for the comet trail. One point per ~second of new fix.
            val dq = trails.getOrPut(ac.hex) { ArrayDeque() }
            val lastPt = dq.lastOrNull()
            if (lastPt == null ||
                ((ac.lat != lastPt.lat || ac.lon != lastPt.lon) && baseElapsedMs - lastPt.tMs >= TRAIL_MIN_GAP_MS)
            ) {
                dq.addLast(TrailPt(ac.lat, ac.lon, altM, baseElapsedMs))
                while (dq.size > TRAIL_MAX_PTS) dq.removeFirst()
            }
            val trailAzEl: List<FloatArray>? = if (dq.size >= 2) {
                dq.map {
                    val l = Geodesy.lookAngles(obs, GeoPoint(it.lat, it.lon, it.altM))
                    floatArrayOf(l.azimuthTrueDeg.toFloat(), l.elevationDeg.toFloat())
                }
            } else null

            out.add(
                RenderTarget(
                    aircraft = ac,
                    baseLat = ac.lat,
                    baseLon = ac.lon,
                    baseAltM = altM,
                    altitudeIsGeometric = geom,
                    trackDeg = ac.trackDeg,
                    groundSpeedMps = ac.groundSpeedMps(),
                    vertRateMps = if (ac.onGround) null else ac.vertRateMps(),
                    turnRateDegS = turnRateOf(ac),
                    labelAltText = displayAltitude(ac),
                    baseElapsedMs = baseElapsedMs,
                    aboveCloud = aboveCloud,
                    audible = audible,
                    trail = trailAzEl,
                )
            )
        }
        trails.keys.retainAll(list.map { it.aircraft.hex }.toHashSet())   // forget vanished aircraft
        binding.overlay.setObserver(obs)
        binding.overlay.setTargets(out)
        aligner.setScene(obs, out)
        prefetchThumbnails(out, obs)

        // Keep the open card / sheet's live data fresh.
        if (expanded) {
            pinnedHex?.let { hex ->
                out.firstOrNull { it.aircraft.hex == hex }?.let {
                    currentSelection = it
                    populateCard(it, obs)
                    populateExpanded(it, obs)
                }
            }
        } else {
            selectedHex?.let { hex ->
                out.firstOrNull { it.aircraft.hex == hex }?.let {
                    currentSelection = it
                    populateCard(it, obs)
                }
            }
        }

        val compassWarn = when {
            orientation.magneticInterference() ->
                "  ·  ⚠ metal nearby may skew the compass — works best in the open"
            orientation.compassHealth() == OrientationTracker.HEALTH_BAD ->
                "  ·  ⚠ compass needs a figure-8"
            else -> ""
        }
        val altNote = if (location.hasAltitude) "" else "  ·  ~elevations (no GPS altitude)"
        val accNote = location.accuracyM?.let { String.format(Locale.US, "  ·  ±%.0f m", it) } ?: ""
        val healthy = repo.healthySourceCount()
        val srcNote = when {
            healthy == repo.sourceCount() -> ""                       // all well: say nothing
            healthy > 0 -> "  ·  ${repo.sourceCount() - healthy} source(s) recovering"
            else -> "  ·  reconnecting…"
        }
        binding.statusText.text = if (out.isEmpty()) {
            String.format(
                Locale.US,
                "No aircraft within %d NM — try increasing the range%s%s",
                radiusNm, srcNote, compassWarn
            )
        } else {
            String.format(
                Locale.US,
                "%d aircraft  ·  %d NM%s%s%s%s",
                out.size, radiusNm, accNote, srcNote, altNote, compassWarn
            )
        }
    }

    // --- Detail card --------------------------------------------------------

    private fun onSelectionChanged(target: RenderTarget?) {
        // While the sheet is open it stays pinned to one aircraft; ignore reticle drift.
        if (expanded) return
        // A crisp click when the reticle locks onto a (new) aircraft — "captured".
        if (target != null && target.aircraft.hex != selectedHex) {
            haptics.capture()
        }
        currentSelection = target
        if (target == null) {
            selectedHex = null
            photoLink = null
            binding.detailCard.visibility = View.GONE
            return
        }
        val isNew = target.aircraft.hex != selectedHex
        selectedHex = target.aircraft.hex
        binding.detailCard.visibility = View.VISIBLE
        location.observer()?.let { populateCard(target, it) }
        if (isNew) {
            // Reset photo for the new selection, then load.
            photoLink = null
            binding.acPhoto.setImageDrawable(null)
            binding.acCredit.text = "Loading photo…"
            loadPhoto(target)
        }
    }

    private fun populateCard(t: RenderTarget, obs: GeoPoint) {
        val ac = t.aircraft
        binding.cloudNote.visibility = if (t.aboveCloud) View.VISIBLE else View.GONE
        binding.acCallsign.text = ac.displayName()

        binding.acType.text = stableIdentity(ac)

        val look = Geodesy.lookAngles(obs, GeoPoint(t.baseLat, t.baseLon, t.baseAltM))

        // Audibility: computed once per target in updateTargets; the card just reflects it.
        binding.audibleNote.visibility = if (t.audible) View.VISIBLE else View.GONE

        val gs = ac.groundSpeedKt?.let { String.format(Locale.US, "%.0f kt", it) } ?: "— kt"
        binding.acStatsA.text = String.format(
            Locale.US, "%s · %s · %s",
            t.labelAltText,
            gs, fmtVertRate(t.vertRateMps)
        )

        val distNm = look.horizontalRangeM * Aircraft.M_TO_NM
        val sq = ac.squawk?.trim()?.takeIf { it.isNotEmpty() }?.let { " · sq $it" } ?: ""
        val emerg = ac.emergency?.trim()?.lowercase()
        val emergStr = if (!emerg.isNullOrEmpty() && emerg != "none") "⚠ ${emerg.uppercase()} · " else ""
        binding.acStatsB.text = String.format(
            Locale.US, "%s%.1f NM · brg %03.0f°T · el %+.0f°%s",
            emergStr, distNm, look.azimuthTrueDeg, look.elevationDeg, sq
        )
    }

    /**
     * Fetch photo thumbnails for the nearest few aircraft (plus the selected one) and keep them
     * in [markerThumbs] for the overlay to draw on those markers. Bounded to keep clutter and
     * memory low; uses the same cached photo pipeline, so it adds no extra requests once warm.
     */
    private fun prefetchThumbnails(targets: List<RenderTarget>, obs: GeoPoint) {
        val wanted = LinkedHashSet<String>()
        selectedHex?.let { wanted.add(it) }
        targets.sortedBy {
            Geodesy.lookAngles(obs, GeoPoint(it.aircraft.lat, it.aircraft.lon, it.baseAltM)).horizontalRangeM
        }.take(MARKER_THUMB_COUNT).forEach { wanted.add(it.aircraft.hex) }

        // Drop thumbnails no longer wanted (keep memory tiny).
        synchronized(markerThumbs) {
            markerThumbs.keys.retainAll(wanted)
        }
        val byHex = targets.associateBy { it.aircraft.hex }
        for (hex in wanted) {
            if (markerThumbs.containsKey(hex) || thumbInFlight.contains(hex)) continue
            val ac = byHex[hex]?.aircraft ?: continue
            val icao = ac.icaoHexOrNull() ?: continue
            thumbInFlight.add(hex)
            lifecycleScope.launch {
                val res = photoRepo.photoForHex(icao, ac.registration, ac.typeCode)
                if (res is PhotoResult.Found) {
                    val bmp = photoRepo.bitmapFor(res.photo.thumbnailUrl)
                    if (bmp != null) {
                        markerThumbs[hex] = bmp
                        binding.overlay.postInvalidateOnAnimation()
                    }
                }
                thumbInFlight.remove(hex)
            }
        }
    }

    private fun loadPhoto(target: RenderTarget) {
        val hex = target.aircraft.icaoHexOrNull()
        if (hex == null) {
            binding.acCredit.text = ""
            return
        }
        binding.acCredit.text = "Loading photo…"
        lifecycleScope.launch {
            val result = photoRepo.photoForHex(hex, target.aircraft.registration, target.aircraft.typeCode)
            if (selectedHex != target.aircraft.hex) return@launch   // selection changed meanwhile
            when (result) {
                is PhotoResult.Found -> {
                    photoLink = result.photo.link
                    binding.acCredit.text = "© ${result.photo.photographer} · ${result.photo.source}"
                    val bmp = photoRepo.bitmapFor(result.photo.thumbnailUrl)
                    if (selectedHex == target.aircraft.hex && bmp != null) {
                        binding.acPhoto.setImageBitmap(bmp)
                    }
                }
                is PhotoResult.NoneOnFile -> binding.acCredit.text = "No photo on file"
                is PhotoResult.Error ->
                    binding.acCredit.text = "Photo service unavailable (${result.reason})"
            }
        }
    }

    private fun updateCalibValue() {
        binding.calibBarValue.text = String.format(
            Locale.US,
            "Drag the orange compass sideways until a marking sits on a bearing you know.  Offset %+.1f°",
            binding.overlay.userHeadingOffset()
        )
    }

    private fun openPhotoLink() {
        photoLink?.let { url ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    // --- Expanded detail sheet ---------------------------------------------

    private fun expandPanel() {
        val t = currentSelection ?: return
        pinnedHex = t.aircraft.hex
        expanded = true
        populateExpanded(t, location.observer())

        val panel = binding.expandedPanel
        panel.visibility = View.VISIBLE
        panel.post {
            panel.translationY = panel.height.toFloat()
            panel.animate().translationY(0f).setDuration(220).start()
        }
        backCallback.isEnabled = true
    }

    private fun collapsePanel() {
        if (!expanded) return
        expanded = false
        pinnedHex = null
        backCallback.isEnabled = false
        val panel = binding.expandedPanel
        panel.animate().translationY(panel.height.toFloat()).setDuration(190)
            .withEndAction {
                panel.visibility = View.GONE
                panel.translationY = 0f
            }.start()
        // Resync the compact card to whatever the reticle is on now.
        onSelectionChanged(binding.overlay.selectedTarget())
    }

    private fun populateExpanded(t: RenderTarget, obs: GeoPoint?) {
        val ac = t.aircraft
        binding.expCallsign.text = ac.displayName()
        binding.expType.text = stableIdentity(ac)
        binding.expPhoto.setImageDrawable(binding.acPhoto.drawable)
        binding.expCredit.text = binding.acCredit.text
        binding.expLink.visibility = if (photoLink != null) View.VISIBLE else View.GONE

        val scrollY = binding.expScroll.scrollY
        val c = binding.expContent
        c.removeAllViews()

        // Derived geometry (computed by this app from your GPS + the reported position).
        if (obs != null) {
            addSection(c, "Derived (computed by this app)")
            val look = Geodesy.lookAngles(obs, GeoPoint(t.baseLat, t.baseLon, t.baseAltM))
            val magAz = ((look.azimuthTrueDeg - location.declinationDeg) % 360.0 + 360.0) % 360.0
            addRow(c, "Azimuth (true)", String.format(Locale.US, "%.1f\u00b0", look.azimuthTrueDeg), null)
            addRow(c, "Azimuth (magnetic)", String.format(Locale.US, "%.1f\u00b0", magAz), null)
            addRow(c, "Magnetic declination", String.format(Locale.US, "%+.1f\u00b0", location.declinationDeg),
                "WMM via GeomagneticField at your position")
            addRow(c, "Elevation", String.format(Locale.US, "%+.1f\u00b0", look.elevationDeg),
                "angle above the horizon")
            addRow(c, "Slant range", String.format(Locale.US, "%.2f NM  (%.1f km)",
                look.slantRangeM * Aircraft.M_TO_NM, look.slantRangeM / 1000.0), "line-of-sight distance")
            addRow(c, "Ground range", String.format(Locale.US, "%.2f NM  (%.1f km)",
                look.horizontalRangeM * Aircraft.M_TO_NM, look.horizontalRangeM / 1000.0), null)
            addRow(c, "Your position", String.format(Locale.US, "%.5f, %.5f", obs.latDeg, obs.lonDeg), null)
            addRow(c, "Your altitude", String.format(Locale.US, "%.0f m HAE", obs.heightM), null)
            com.aradsb.time.TrueTime.offset?.let { off ->
                addRow(c, "Clock correction",
                    String.format(Locale.US, "%+.2f s (%s)", off / 1000.0, com.aradsb.time.TrueTime.source),
                    "your phone clock vs true UTC; applied to the data-age estimate that drives marker extrapolation")
            }

            // Altitude above the EGM2008 geoid (≈ MSL): alt_geom (HAE) minus undulation N.
            val nUnd = geoid?.undulation(ac.lat, ac.lon)
            val altGeomFt = ac.altGeomFt
            if (nUnd != null && altGeomFt != null) {
                val egmFt = altGeomFt - nUnd / Aircraft.FT_TO_M
                addRow(c, "Altitude (EGM2008)",
                    String.format(Locale.US, "%,.0f ft  (%,.0f m)", egmFt, egmFt * Aircraft.FT_TO_M),
                    String.format(Locale.US,
                        "alt_geom (WGS-84) minus geoid undulation N = %+.1f m here; ≈ height above MSL",
                        nUnd))
            }

            // Pressure altitude corrected to the altimeter setting the jet transmits (nav_qnh).
            val qnh = ac.rawFields["nav_qnh"]?.toDoubleOrNull()
            val paFt = ac.altBaroFt
            if (qnh != null && qnh > 800.0 && qnh < 1100.0 && paFt != null && !ac.onGround) {
                val corrFt = qnhCorrectedFt(paFt, qnh)
                addRow(c, String.format(Locale.US, "Altitude (QNH %.0f)", qnh),
                    String.format(Locale.US, "%,.0f ft  (%,.0f m)", corrFt, corrFt * Aircraft.FT_TO_M),
                    "baro altitude corrected to the altimeter setting the aircraft transmits " +
                        "(crews fly standard 1013 above the transition altitude, so this matters " +
                        "mainly below it)")
            }
        }

        // Everything the API reported, grouped by category; unknown keys fall under "Other".
        // Rows render from the UNION of all fields seen for this aircraft while the sheet is
        // open: a field missing from one source's record keeps its last value instead of its
        // row vanishing, so the layout can never jump as sources rotate. The union resets
        // when a different aircraft is opened.
        if (ac.hex != sheetUnionHex) {
            sheetUnionHex = ac.hex
            sheetFieldUnion.clear()
        }
        for ((k, v) in ac.rawFields) {
            if (v.isNotEmpty() || k !in sheetFieldUnion) sheetFieldUnion[k] = v
        }
        val raw: Map<String, String> = sheetFieldUnion
        for (cat in AdsbFields.order) {
            val keys = raw.keys.filter { AdsbFields.categoryOf(it) == cat }
            if (keys.isEmpty()) continue
            addSection(c, cat.title)
            for (k in keys) {
                val f = AdsbFields.map[k]
                val unit = f?.unit?.let { " $it" } ?: ""
                var value = raw[k].orEmpty()
                value = if (value.isEmpty()) "—" else value + unit
                if (k == "category") {
                    Aircraft.categoryLabel(raw[k])?.let { value = "${raw[k]} ($it)" }
                }
                addRow(c, f?.label ?: k, value, f?.note)
            }
        }

        addSection(c, "Notes")
        val note = TextView(this)
        note.text = "Signal power, message counts and reception age describe the feeder network, " +
            "not the aircraft. Airspeeds, Mach, wind and temperature are decoded from Mode S " +
            "registers and appear only when the aircraft transmits them.\n\n" +
            repo.attributionLine() + ". Community ADS-B networks; non-commercial use."
        note.setTextColor(Color.parseColor("#9FB4C4"))
        note.textSize = 12f
        c.addView(note)

        binding.expScroll.post { binding.expScroll.scrollTo(0, scrollY) }
    }

    /** Baro/pressure altitude [paFt] corrected to altimeter setting [qnhHpa] (exact ISA). */
    private fun qnhCorrectedFt(paFt: Double, qnhHpa: Double): Double =
        paFt - (1.0 - Math.pow(qnhHpa / 1013.25, 0.190284)) * 145366.45

    /**
     * The altitude string shown on the marker label and the detail card. Rules (the AR
     * placement always uses geometric altitude — this is display only):
     *  - "GND" on the ground.
     *  - QNH-corrected feet ("4,250 ft") when the aircraft transmits a non-standard
     *    altimeter setting (nav_qnh) and is below 18,000 ft pressure altitude — i.e. what
     *    its altimeter reads. Crews on standard broadcast ~1013, where the correction is
     *    ~0 and the FL display takes over by itself.
     *  - "FL363" at/above 18,000 ft pressure altitude (flight levels ARE pressure altitude).
     *  - "~36,500 ft" when only the geometric (GNSS) altitude is transmitted.
     */
    private fun displayAltitude(ac: Aircraft): String {
        if (ac.onGround) return "GND"
        val baro = ac.altBaroFt
        if (baro != null) {
            val qnh = ac.rawFields["nav_qnh"]?.toDoubleOrNull()
            if (qnh != null && qnh > 900.0 && qnh < 1080.0 &&
                kotlin.math.abs(qnh - 1013.25) > 0.5 && baro < 18000.0
            ) {
                return String.format(Locale.US, "%,d ft", qnhCorrectedFt(baro, qnh).toInt())
            }
            return if (baro >= 18000.0) String.format(Locale.US, "FL%03d", (baro / 100).toInt())
            else String.format(Locale.US, "%,d ft", baro.toInt())
        }
        val geom = ac.altGeomFt ?: return "GND"
        return if (geom >= 18000.0) String.format(Locale.US, "~FL%03d", (geom / 100).toInt())
        else String.format(Locale.US, "~%,d ft", geom.toInt())
    }

    /**
     * Turn rate (deg/s, + = right) for extrapolating along the turn arc: the transmitted
     * track_rate (BDS 6,0) when available, else derived from the bank angle via the
     * coordinated-turn relation ω = g·tan(φ)/v (using ground speed for v — a small error
     * vs TAS, irrelevant at marker scale). Null on the ground or for implausible values.
     */
    private fun turnRateOf(ac: Aircraft): Double? {
        if (ac.onGround) return null
        ac.trackRateDegS?.let { return it.takeIf { r -> kotlin.math.abs(r) <= 10.0 } }
        val roll = ac.rollDeg ?: return null
        val v = ac.groundSpeedMps() ?: return null
        if (v < 30.0 || kotlin.math.abs(roll) < 2.0 || kotlin.math.abs(roll) > 60.0) return null
        return Math.toDegrees(9.80665 * kotlin.math.tan(Math.toRadians(roll)) / v)
    }

    /**
     * The identity line ("Boeing 777-31H (B77W) · D-ABYP") for [ac], guaranteed never to
     * regress within a session: if a terser source momentarily yields a shorter line for the
     * same airframe, the richest version seen so far is shown instead.
     */
    private fun stableIdentity(ac: Aircraft): String {
        val candidate = listOf(ac.typeLine(), ac.registration?.trim().orEmpty())
            .filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "Unknown type" }
        if (identityCache.size > 600) identityCache.clear()
        val cached = identityCache[ac.hex]
        return if (cached == null ||
            (candidate.length >= cached.length && candidate != "Unknown type") ||
            cached == "Unknown type"
        ) {
            identityCache[ac.hex] = candidate; candidate
        } else cached
    }

    private fun addSection(parent: ViewGroup, title: String) {
        val tv = TextView(this)
        tv.text = title.uppercase(Locale.US)
        tv.setTextColor(Color.parseColor("#5AC8FA"))
        tv.textSize = 12f
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.letterSpacing = 0.06f
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(16f)
        lp.bottomMargin = dp(4f)
        tv.layoutParams = lp
        parent.addView(tv)
    }

    private fun addRow(parent: ViewGroup, label: String, value: String, note: String?) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(5f), 0, dp(5f))

        val l = TextView(this)
        l.text = label
        l.setTextColor(Color.parseColor("#9FB4C4"))
        l.textSize = 13f
        l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val v = TextView(this)
        v.text = value
        v.setTextColor(Color.WHITE)
        v.textSize = 13f
        v.gravity = Gravity.END
        val vlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f)
        vlp.marginStart = dp(12f)
        v.layoutParams = vlp

        row.addView(l)
        row.addView(v)
        parent.addView(row)

        if (!note.isNullOrEmpty()) {
            val n = TextView(this)
            n.text = note
            n.setTextColor(Color.parseColor("#6F8494"))
            n.textSize = 11f
            val nlp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            nlp.bottomMargin = dp(2f)
            n.layoutParams = nlp
            parent.addView(n)
        }

        val divider = View(this)
        val dlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        divider.layoutParams = dlp
        divider.setBackgroundColor(Color.parseColor("#14FFFFFF"))
        parent.addView(divider)
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmtVertRate(mps: Double?): String {
        if (mps == null) return "level"
        val fpm = (mps / Aircraft.FPM_TO_MPS).toInt()
        return when {
            fpm > 50 -> String.format(Locale.US, "+%,d fpm", fpm)
            fpm < -50 -> String.format(Locale.US, "%,d fpm", fpm)
            else -> "level"
        }
    }

    // --- Sensors / lifecycle ------------------------------------------------

    private fun onOrientation() {
        if (orientation.copyRotationMatrix(rotationBuf)) {
            binding.overlay.setRotationMatrix(rotationBuf)
        }
    }

    private fun onLocation() {
        binding.overlay.setDeclination(location.declinationDeg)
        location.last?.let { l ->
            val alt = if (l.hasAltitude()) l.altitude.toFloat() else 0f
            orientation.expectedFieldUt = android.hardware.GeomagneticField(
                l.latitude.toFloat(), l.longitude.toFloat(), alt, l.time
            ).fieldStrength / 1000f   // nT -> µT
        }
    }

    // --- Range mapping ------------------------------------------------------
    // progress in [0, RANGE_STEPS] <-> nm in [MIN_RANGE, MAX_RANGE], exponential.

    private fun progressToRangeNm(progress: Int): Int {
        val minR = MIN_RANGE_NM.toDouble()
        val maxR = MultiSourceAdsbRepository.MAX_RADIUS_NM.toDouble()
        val nm = minR * Math.pow(maxR / minR, progress.toDouble() / RANGE_STEPS)
        return Math.round(nm).toInt().coerceIn(MIN_RANGE_NM, MultiSourceAdsbRepository.MAX_RADIUS_NM)
    }

    private fun rangeNmToProgress(nm: Int): Int {
        val minR = MIN_RANGE_NM.toDouble()
        val maxR = MultiSourceAdsbRepository.MAX_RADIUS_NM.toDouble()
        val p = RANGE_STEPS * (Math.log(nm.toDouble() / minR) / Math.log(maxR / minR))
        return Math.round(p).toInt().coerceIn(0, RANGE_STEPS)
    }

    /** Exact ±1 NM adjustment via the stepper buttons; persists and refetches immediately. */
    private fun stepRange(delta: Int) {
        radiusNm = (radiusNm + delta).coerceIn(MIN_RANGE_NM, MultiSourceAdsbRepository.MAX_RADIUS_NM)
        binding.rangeSeek.progress = rangeNmToProgress(radiusNm)
        binding.rangeText.text = String.format(Locale.US, "Range  ·  %d NM", radiusNm)
        commitRange()
    }

    private fun commitRange() {
        prefs.edit().putInt(KEY_RANGE_NM, radiusNm).apply()
        lifecycleScope.launch { location.observer()?.let { fetchAndUpdate(it) } }
    }

    private fun nudgeFov(delta: Float) {
        // Calibration adjusts the 1× base FOV (not the zoomed effective FOV), so it composes
        // cleanly with pinch zoom.
        val calBase = (baseFov * fovScale + delta).coerceIn(20f, 120f)
        fovScale = if (baseFov > 0f) calBase / baseFov else 1f
        if (::prefs.isInitialized) prefs.edit().putFloat(KEY_FOV_SCALE, fovScale).apply()
        applyFov()
    }

    private fun updateFovEstimate() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        baseFov = cameraInfo?.let { CameraFov.estimateHorizontalFovDeg(it, landscape) }
            ?: CameraFov.DEFAULT_HORIZONTAL_FOV_DEG
        applyFov()
    }

    private fun applyFov() {
        // Calibrated FOV at 1× zoom (the camera estimate, corrected by the user's fovScale).
        val calBase = (baseFov * fovScale).coerceIn(20f, 120f)
        // Pinch zoom narrows the effective FOV optically, so markers spread apart in step with
        // the magnified camera image and stay locked to the aircraft.
        val eff = effectiveFovDeg(calBase, zoomRatio)
        binding.overlay.setHorizontalFov(eff)
        binding.fovText.text = if (zoomRatio > 1.05f)
            String.format(Locale.US, "%.0f° · %.1f×", eff, zoomRatio)
        else String.format(Locale.US, "%.0f°", eff)
    }

    /** Effective FOV after a rectilinear zoom: 2·atan(tan(base/2)/zoom). */
    private fun effectiveFovDeg(baseDeg: Float, zoom: Float): Float {
        if (zoom <= 1f) return baseDeg
        val half = Math.toRadians(baseDeg.toDouble() / 2.0)
        return Math.toDegrees(2.0 * Math.atan(Math.tan(half) / zoom)).toFloat()
    }

    /** Apply a new camera zoom ratio (clamped to the lens range) and re-derive the overlay FOV. */
    private fun applyZoom(ratio: Float) {
        if (maxZoom <= minZoom) return
        zoomRatio = ratio.coerceIn(minZoom, maxZoom)
        cameraControl?.setZoomRatio(zoomRatio)
        applyFov()
    }

    private fun currentDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION") windowManager.defaultDisplay.rotation
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.overlay.setDisplayRotation(currentDisplayRotation())
        updateFovEstimate()
    }

    override fun onResume() {
        super.onResume()
        if (granted) {
            orientation.start()
            location.start()
            binding.overlay.setDisplayRotation(currentDisplayRotation())
        }
    }

    override fun onPause() {
        super.onPause()
        orientation.stop()
        location.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::analysisExecutor.isInitialized) analysisExecutor.shutdown()
    }
}
