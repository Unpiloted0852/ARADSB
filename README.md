# AR ADS-B

Point your phone's camera at the sky and see live aircraft overlaid on the camera
image, in the style of FlightRadar24's AR view. Traffic comes from the free
[adsb.lol](https://adsb.lol) API. The app compares your phone's GPS position and
altitude against each aircraft's reported position and altitude to compute where
to draw it. Centre an aircraft in the reticle and a detail card shows its data and
a recent photo.

Native Android, Kotlin. No Google Play Services (uses the platform
`LocationManager`), so it runs on de-Googled / microG devices.

---

## How it works

1. **Position** – the platform `LocationManager` gives your latitude, longitude and
   GPS altitude. It subscribes to the platform **fused** provider (Android 12+, which
   merges GNSS + network + sensors with no Play Services dependency) plus the GPS,
   network and passive providers, seeding from the freshest last-known fix so the view
   has a reference immediately while the GNSS chip is still acquiring.
2. **Traffic** – every 2 s the app asks one of four free ADS-B aggregators (adsb.lol,
   adsb.fi, airplanes.live, ADSB One — rotated round-robin with failover) for all
   aircraft within the selected
   range and keeps those that are airborne with a recent position fix. The range is
   adjustable in view (5–250 NM) from the settings panel.
3. **Geometry** – for each aircraft it computes the true azimuth, elevation angle
   and range from you using an exact WGS-84 ECEF → local East/North/Up
   transformation (so Earth curvature is included).
4. **Dead reckoning** – ADS-B positions are a second or two old and arrive only every
   couple of seconds, so between updates each aircraft is advanced along its reported
   track at its ground speed (great-circle step) with altitude carried by its vertical
   rate. This is recomputed every frame, so markers glide instead of jumping. The
   extrapolation is capped at 30 s so a target that stops updating cannot drift away.
5. **Orientation** – the fused `TYPE_ROTATION_VECTOR` sensor (accelerometer +
   gyroscope + magnetometer) gives the phone's attitude. Magnetic declination from
   the World Magnetic Model (`GeomagneticField`) converts the true bearings into the
   sensor's magnetic-north frame.
6. **Projection** – the orientation matrix is remapped for the current screen
   rotation and each aircraft's direction vector is perspective-projected onto the
   camera preview. Targets in front are drawn as markers; targets that are in front
   but outside the frame get an edge arrow pointing toward them.

### Selecting an aircraft

The aircraft nearest the centre reticle is highlighted in amber and its details appear
in a card at the bottom: callsign, type/registration, altitude, ground speed, vertical
rate, distance, true bearing, elevation and squawk (plus an emergency flag if set). The
card also shows a recent photo of that airframe.

The card has a chevron and a "Tap or swipe up for full details" hint. **Tap it or swipe up**
to open a full detail sheet: the app's derived geometry (true/magnetic azimuth, elevation,
slant and ground range, your position) followed by every field adsb.lol returned for that
aircraft, grouped and labelled — identification, position/altitude, speed/heading,
selected/autopilot values, squawk and status, integrity/accuracy (NIC, NACp, SIL, …),
Mode-S air data (airspeeds, Mach, wind, OAT) when transmitted, and reception stats. Fields
the API sends but this build doesn't have a label for are still shown, under "Other", with
their raw key — so the sheet is complete and never invents a meaning. The sheet also has a
"View on Planespotters" link that opens the original photo page (attribution). Swipe down,
tap the ✕, or press Back to close.

### A note on altitude datums (why there is no "EGM" conversion in the math)

Both altitudes used for the geometry are referenced to the **WGS-84 ellipsoid
(height above ellipsoid, HAE)**:

- Android `Location.getAltitude()` returns metres above the WGS-84 ellipsoid, per
  the platform contract.
- The adsb.lol field `alt_geom` is the aircraft's **geometric (GNSS)** altitude,
  also referenced to the WGS-84 ellipsoid, in feet.

Because both sides are HAE, they are directly comparable. The geoid undulation —
the EGM2008 ellipsoid-vs-mean-sea-level difference, which can reach ~100 m — is
essentially the same at your location and the aircraft's and **cancels in the
relative vertical difference**, so no geoid/EGM model is needed for the look angle.
A geoid model would only be required to *display* an aircraft's mean-sea-level
altitude, which this app does not need to do.

When `alt_geom` is missing the app falls back to `alt_baro` (barometric, referenced
to 1013.25 hPa). That can differ from true height by hundreds of feet depending on
local pressure, so such labels are prefixed with `~`.

---

## What is exact and what is estimated

- **Exact:** the azimuth/elevation/range geometry and the sensor-fusion attitude.
- **Estimated:** the camera's field of view. The app derives an initial horizontal
  FOV from the lens focal length and physical sensor size, but the preview pipeline
  crops the image, so the mapping from angle to pixel is approximate. Use the
  **− / + FOV buttons** in the settings panel to nudge the FOV until the markers line
  up with real aircraft you can see. The angles are right; you are only calibrating
  the lens.
- **Approximate by nature:** the dead-reckoned position between updates. It assumes
  the aircraft holds its last reported track, speed and vertical rate, which is a good
  assumption in cruise and a poorer one during manoeuvres.

Other accuracy factors, all inherent to this kind of sensor-based AR:

- Phone compasses drift and are disturbed by nearby metal/magnets. If the status
  bar shows a calibration warning, wave the phone in a figure-8.
- GPS altitude on phones is noisier than horizontal position; if a fix has no
  altitude the app assumes ground level and notes it in the status bar.

---

## AI airplane detection, alignment, focus and calibration

**Camera focus.** Both the preview and the analysis stream are locked to infinity focus
(0 diopters) via Camera2 interop, so distant aircraft stay sharp instead of the autofocus
hunting on empty sky. On fixed-focus lenses this is a no-op (already at hyperfocal).

**AI airplane detection (on-device, offline).** Camera frames are analysed (~3 Hz) by a
bundled TensorFlow Lite object detector — SSD MobileNet v1, COCO-trained, quantized,
4.2 MB, Apache-2.0 (`assets/detect.tflite`). Every detection labelled *airplane* is drawn
as a green box with its confidence; a box that sits where an ADS-B target is predicted is
labelled with that aircraft's callsign. Passes alternate between the full frame and a
zoomed region around the nearest predicted aircraft, which acts as a digital zoom for
smaller/farther targets.

Boxes are **tracked, not flashed**: a detection must be confirmed on consecutive passes
before it is ever displayed (2 passes when corroborated by ADS-B, 4 passes plus a higher
confidence bar when not), so single-frame false positives never reach the screen.
Established boxes coast through brief detector misses instead of blinking, their position
is exponentially smoothed so they glide with the aircraft, a miss only counts when the
pass actually searched that part of the view (the alternating full/ROI passes therefore
cannot blink a box), and oversized or extreme-aspect detections (cloud banks, frame
edges) are rejected outright. The bundled model's I/O contract and behaviour were
verified by running the exact file through the TFLite interpreter (correct detections on
real photos; *airplane* fires on real aircraft imagery; nothing fires on empty aerial
scenes).

**Physics, honestly stated:** the detector input is 300×300 px, so an aircraft must be
visually resolvable to be boxed — approaches, circuit traffic and nearby GA, yes; a
cruise-altitude dot a few pixels wide, no. That is a resolution limit shared by any
detector, not a tuning issue.

**Auto-alignment.** Only detections *associated* with an ADS-B prediction (inside an
angular gate, confirmed on consecutive passes) are shown as a visual aid (median of
residuals, low-pass smoothed, bounded to plausible compass error). A false positive can
therefore at worst draw a box — it can never steer the overlay. With nothing associated,
no correction is applied and the overlay rides the raw sensors. No experimental CameraX
APIs are used: frames are converted upright and mapped to the view with deterministic
aspect-fill math (the PreviewView is pinned to `fillCenter`).

**Compass calibration.** Compass health is judged from signals that actually move — the
rotation vector's own accuracy enum reports HIGH permanently on many devices and is
deliberately ignored. Three inputs, worst wins: the RAW magnetometer's calibration state
(the figure-8 recalibrates that sensor), the rotation vector's per-sample heading-error
estimate where the device provides it, and ground truth that cannot lie: the measured
magnetic field magnitude versus the World Magnetic Model's expected strength at your
location (a phone near a magnet reads a wildly wrong |B| whatever any enum claims). When
health is bad, a full-screen prompt shows the figure-8; the guided calibration shows the
live numbers ("heading ±12° · field 38 µT (expect 49)") so "good" is demonstrable rather
than asserted. The prompt clears automatically once health recovers.

---

## Building

This is a standard Android Studio project (Kotlin DSL Gradle files).

1. Open the `ARADSB` folder in **Android Studio** (Giraffe or newer). Android Studio
   will download the matching Gradle distribution and generate the Gradle wrapper
   `.jar` automatically.
2. Let it sync, then **Run** on a physical device (the camera, GPS and compass are
   not available on an emulator).

> The Gradle wrapper **binary** (`gradle/wrapper/gradle-wrapper.jar`) is intentionally
> not bundled here. Android Studio creates it on first sync. If you build from the
> command line instead, run `gradle wrapper` once (with a system Gradle 8.7+) to
> generate it, then use `./gradlew assembleDebug`.

### Versions

- Gradle 8.7, Android Gradle Plugin 8.5.2, Kotlin 1.9.24
- `compileSdk` / `targetSdk` 34, `minSdk` 26 (Android 8.0)
- CameraX 1.3.4, OkHttp 4.12.0, kotlinx-coroutines 1.8.1
- No third-party image-loading library: photos are fetched with OkHttp and decoded
  with the platform `BitmapFactory`, cached in memory only.

### Permissions

`CAMERA`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `INTERNET`. The app requests
camera and location at first launch. Approximate (coarse) location is accepted — the app
still works, just with lower positional accuracy. `REQUEST_INSTALL_PACKAGES` and
`UPDATE_PACKAGES_WITHOUT_USER_ACTION` are used only by the in-app updater below.

### In-app updates

At each launch the app asks the GitHub Releases API (keyless) for this repository's latest
release. If its tag (e.g. `v1.31`) is newer than the installed `versionName`, an
"Update available — tap to install" pill appears under the status line. One tap downloads
the release's `.apk` asset straight into a `PackageInstaller` session and hands it to
Android, which shows its own confirmation (and, the first time, the "allow installs from
this app" setting and possibly a Play Protect prompt). The app closes when the update is
applied; reopen it from the launcher.

To ship an update: bump `versionCode` and `versionName`, build a release APK signed with the
**same key**, and publish a GitHub release tagged `v<versionName>` with the APK attached.
Debug builds are signed with a different key, so they see the pill but cannot install the
update.

### Derived altitudes (EGM2008 and QNH)

The detail sheet's Derived section adds two altitudes when the data allows:

- **Altitude (EGM2008)** — `alt_geom` is WGS-84 ellipsoidal (HAE); subtracting the EGM2008
  geoid undulation N at the aircraft's position gives height above the geoid (≈ MSL). N
  comes from a bundled 15-arc-minute grid subsampled from the GeographicLib/NGA EGM2008
  5' dataset and verified against published EGM2008 values during generation (N(0°,0°) =
  17.226 m exact; ~6 cm RMS vs the 5' source, worst ~1.7 m at the steepest geoid
  gradients on Earth). The 2 MB grid ships in `assets/egm2008_15min.bin`.
- **Altitude (QNH xxxx)** — `alt_baro` is pressure altitude (1013.25 hPa datum). When the
  aircraft transmits its selected altimeter setting (`nav_qnh`), the row shows the
  baro-corrected altitude using the exact ISA relation (≈ 27 ft/hPa near sea level).
  Crews fly standard above the transition altitude, so this is most meaningful for
  traffic below it.

### Detecting far aircraft (bright-dot detector)

The neural detector recognises aircraft by their **shape**, so it only fires when one is close
enough to look like an airplane. A distant jet is just a bright (sunlit/strobe) or dark point a
few pixels wide with no shape to recognise — no model tuning changes that. So a second,
classical detector handles distance: because ADS-B already gives the exact bearing/elevation of
every aircraft, it searches a small window **around each predicted position the neural net did
not cover** for a compact high-contrast point against sky. Textured/cloudy windows and large
bright regions are rejected, so only point-like sources survive, and (being ADS-B-matched) a
found dot both draws a box and sharpens the compass alignment. Near aircraft use the neural net,
far ones use the dot detector — automatically. Tuning constants (σ threshold, max sky texture,
max extent) are grouped at the top of `SkyAligner.kt` if false positives/negatives need trimming.

### Marker thumbnails & groundspeed

The nearest few aircraft (plus the selected one) show a tiny photo thumbnail above their marker
dot, using the same cached photo pipeline. The marker's info tag now also includes groundspeed
(e.g. "FL363 · 450 kt · 12.3 NM ▲").

### Haptic clicks

Two moments give tactile feedback via the device vibrator: a crisp single click when the
reticle locks onto an aircraft ("captured"), and a firmer two-pulse "ka-chunk" when the horizon
compass is unlocked for manual calibration (the 1.2-second hold). Uses calibrated VibrationEffect
clicks where available and degrades cleanly on older devices or hardware without a vibrator.

### "You can probably hear this aircraft" hint

For the selected aircraft, the card can show "🔊 You can probably hear this aircraft right now" —
only when the model is confident the sound would actually reach you; otherwise it stays silent.
The estimate now uses, term by term: the RETARDED distance (the sound arriving now was emitted
tens of seconds ago — exact constant-velocity solution, so an approaching jet's sound comes from
much farther away and a receding jet's from closer, which is why aircraft sound loudest after
they pass); inverse-square spreading; ISO 9613-1 atmospheric absorption computed from the METAR
temperature and dewpoint (fallback 10 °C / 70 %); FAA-AEDT (SAE AIR 5662) low-elevation lateral
attenuation, up to ~11 dB near the horizon; a thrust proxy from vertical rate (±3 dB, climb vs
descent — the power dependence ICAO noise certification measures); per-type engine-GENERATION
offsets grounded in ICAO Annex 16 chapter margins (new-generation −4 dB, JT8D-era +5 dB); and a
separate, deliberately moderate military fast-jet class. Source levels remain class
representatives (ADS-B carries no engine-power data), wind refraction is not modelled, and the
EPNdB→dBA mapping behind the generation offsets is approximate — all chosen to under-claim
rather than over-claim, so a shown hint is one to trust.

### Audible-aircraft identity (green + speaker glyph)

Every aircraft the audibility model marks as "you can probably hear this right now" is drawn with
a distinct green identity: the on-screen dot turns green with a tiny speaker glyph beside it, and
for off-screen aircraft the edge-of-screen pointer turns green, slightly bolder, with the same
glyph. Since several aircraft can be audible at once, this lets you match what your ears hear to
the specific marker(s) at a glance. Selection is still shown by the orange ring.

### Gyro-only heading (for magnetically hostile places)

If you spot from somewhere with permanent magnetic interference (steel structures, vehicles), the
"Gyro-only heading" switch makes the heading ignore the magnetometer entirely: you set the
direction once (tap-and-hold calibration against a known reference), and the gyroscope holds it;
pitch/roll still correct from gravity, which magnets cannot disturb, and the detection auto-trim
keeps refining the heading whenever a real aircraft is spotted. The honest cost: consumer gyros
drift slowly (typically a degree or so over minutes), so expect to re-set occasionally, and after
an app restart the initial heading is re-seeded from the magnetometer once before going
gyro-only again.

### Comet trail (motion history)

Each aircraft dot trails a comet streak showing where it has actually been: its recent logged
positions (recorded as fixes arrive, ~one point/second, up to ~30 s) projected through the same
AR pipeline as the markers. Because it's the real flown path, it curves through turns and slopes
through climbs and descents — a diving turn shows a descending, curving wake. The streak is
brightest and widest at the dot and fades/narrows toward the tail, with no arrowhead, so it reads
as "moving this way / came from there" rather than as a pointer to look along. The selected
aircraft's trail is brighter. The trail is empty for the first couple of seconds after an aircraft
appears (no history yet) and builds up from there.

### Cloud-ceiling visibility hint

Using the nearest recent METAR report (NOAA Aviation Weather Center, free/keyless), the app
estimates the local cloud ceiling: the base of the lowest BKN (broken) or OVC (overcast) layer.
Aircraft whose altitude is above that deck — and which may therefore be hidden behind cloud from
the ground — are drawn faded on the overlay, and their detail card shows a "May be hidden by
clouds" note near the top. Pointing at a faded marker selects it and restores it to full
strength so you can investigate.

This is deliberately a SOFT hint, not a guarantee: a METAR is a point observation possibly tens
of km away and cloud cover is patchy, so it only uses the single nearest recent station (within
60 km, under 90 min old) and only acts on BKN/OVC. Cloud base (feet AGL at the station) is
converted to MSL via the station's elevation and compared against each aircraft's MSL altitude
(its WGS-84 geometric height minus the EGM2008 geoid undulation). Broken layers have gaps, so an
aircraft above BKN can still occasionally show through.

### Pinch to zoom

Pinch the camera view to zoom in on distant traffic. This is a true optical-style zoom: it
drives the camera's zoom ratio (magnifying the live image) and narrows the AR field of view by
the exact matching amount (effFOV = 2·atan(tan(baseFOV/2)/zoom)), so markers, labels, the
compass rose, and detection boxes all spread apart in step with the magnified image and stay
locked to the aircraft. Zoom is bounded by the lens's supported range and composes with the FOV
calibration (which sets the 1× field of view). The current zoom shows in the FOV readout
(e.g. "35° · 2.0×").

### Gyro-stabilised heading

The heading is produced by a complementary filter rather than the raw rotation-vector sensor:
the gyroscope is integrated for smooth, magnetically-immune short-term motion, and the result is
slowly corrected (≈1.2 s time constant) toward the rotation vector's absolute orientation so the
heading stays true over time. This rejects the magnetic jitter that makes a raw phone compass
twitch near metal, cars, or the phone's own electronics, while tracking real turns instantly
(the gyro has no lag). Quaternions are used throughout, so there is no gimbal lock when pointing
near-vertical at overhead aircraft. On a device with no gyroscope it falls back to the rotation
vector directly. This steadies the markers but does not change the underlying magnetometer
accuracy — for an exact reference, use the manual calibration below.

On every open or resume the filter runs a brief fast-align: for ~1.8 s it tracks the absolute
reference tightly (≈0.05 s time constant) so the heading snaps to true within a fraction of a
second instead of sliding in slowly, then ramps back to the steady ≈1.2 s constant. A sustained
divergence (e.g. waking up pointed elsewhere) likewise re-converges quickly, while sub-second
magnetic spikes are still rejected — so startup is snappy without trading away steady-state
jitter rejection. Note the ultimate settling speed is still bounded by how fast the phone's
magnetometer itself calibrates; if it is genuinely off, the figure-8 prompt is the real fix.

### Magnetic-disturbance rejection (split-trust filter)

Pitch and roll come from gravity (accelerometer) and are immune to magnets; only YAW depends on
the magnetometer. The orientation filter therefore corrects the two separately: tilt is always
corrected fully, while yaw corrections are frozen to a very slow leak whenever the field-magnitude
check says the reading is corrupted (metal/magnet nearby) or the sensor reports itself unreliable
— the gyroscope carries the heading through the disturbance. Verified in simulation: a 15-s, 25°
magnetic yank (walking past a car) drags the old filter almost fully off course but moves the
gated filter ~3°, recovering to zero afterwards. If interference persists for many minutes the
heading still slowly converges to the only absolute reference available.

### Automatic compass fine-trim from detections

An aircraft's ADS-B position is GPS-derived (~10 m), so a detection in the camera that is
CONFIDENTLY matched to its ADS-B prediction measures the true heading error directly — a far
better reference than any phone magnetometer. When such matches occur, the compass is fine-trimmed
toward them automatically; no user action needed.

An earlier version of this idea was removed (v1.13) because unassociated detections — birds,
contrails, cloud glints — could steer the compass. The re-enabled trim is gated hard against that
failure mode: only detections ASSOCIATED with an ADS-B prediction count; the association must be
UNAMBIGUOUS (no second aircraft nearby that could be the real match); the detection must persist
over multiple frames; the correction applied is the MEDIAN of many recent sightings, nudged in
slowly; and the total auto-trim is hard-capped at ±6°, so the worst case any residual failure can
cause is bounded — large errors are never chased and remain the figure-8's job. Verified in
simulation: converges on a real 3.5° error, rejects 20% outlier contamination, and a fully-false
detection stream stays inside the cap. Toggle: "Auto-trim compass from detections" in settings.

### Compass reference & manual calibration

Settings has a **Compass shows true north** switch: on, the rose is referenced to true
(geographic) north; off, to magnetic north (matching a magnetic compass and runway
headings). This affects only the rose — aircraft markers are always placed at their correct
physical bearings. The choice persists.

For manual fine-tuning, **press and hold the horizon compass for 1.2 seconds**: the grey
markings turn orange (calibration mode) and the rose becomes draggable — slide it sideways
until a marking sits on a bearing you know to be correct. **Done** saves, **Reset** clears.
The manual offset is bounded (±20°) and persists across launches, because it corrects a
largely static, per-device magnetometer (hard-iron) bias plus habitual mounting; that error
does not reset when the app closes, so re-entering it every launch would be busywork. It is
applied to both the rose and the markers, on top of (and independent of) the ephemeral CV
auto-align offset, and Reset removes it entirely if you later calibrate somewhere with
different local interference.

### Horizon compass rose

A grey compass rose is drawn on the horizon: a tick every 15° of true bearing, with
cardinals (N/E/S/W) and intercardinals (NE/SE/SW/NW) labelled. Each bearing is projected
at 0° elevation through the same camera model as the aircraft markers (declination and the
CV alignment offset applied identically). Each tick and label is rotated to the projected
horizon's tilt at its bearing — derived from the on-screen vector between the bearing's
+0.9° and −0.9° elevation points — so the rose banks with the true horizon at any phone
roll, not just when upright. Besides orientation, it is a built-in compass check — if "N"
is not where you know north to be, the heading is off.

### Marker label altitude

The altitude on the marker label and detail card shows what matters operationally
(placement in the AR view always uses the geometric altitude — this is display only):
**QNH-corrected feet** when the aircraft transmits a non-standard altimeter setting
(`nav_qnh`) below 18,000 ft pressure altitude — i.e. what its own altimeter reads;
**flight levels** ("FL363") at/above 18,000 ft or whenever the crew is on standard
(on standard the jet broadcasts ~1013, the correction is ~0, and the FL display takes
over by itself); **"~" prefix** marks aircraft transmitting only geometric (GNSS)
altitude with no barometric data.

### True-time correction

The staleness estimate compares a local receive timestamp with the server's HTTP Date
header, so it is only as good as the phone's clock. The app therefore derives the clock's
offset from true UTC: primarily from **GNSS** (GPS fixes carry satellite-derived UTC
paired with elapsedRealtimeNanos — millisecond-level, offline, no extra permissions), with
**SNTP** (RFC 4330, pool.ntp.org, lowest-round-trip of three samples) as a fallback before
the first fix. GNSS always wins when fresh. The applied correction is visible in the
detail sheet's Derived section ("Clock correction +2.13 s (GNSS)"). With no reference yet
the correction is zero — the previous behaviour. The HTTP Date header itself has 1 s
resolution, which bounds the staleness estimate's precision to about ±0.5 s.

### Marker extrapolation

Markers are dead-reckoned forward every rendered frame to cover the full age of the data
(position age + network + measured response-cache staleness): vertically with the
transmitted vertical rate, and horizontally along the **turn arc** when the aircraft's
turn rate is known — from the transmitted `track_rate` (BDS 6,0) when present, else
derived from the bank angle via the coordinated-turn relation ω = g·tan(φ)/v. The arc
endpoint is computed exactly through its chord (bearing = track + ω·t/2, length =
gs·t·sinc(ω·t/2); verified against numeric integration to sub-millimetre), which reduces
to straight dead reckoning when ω = 0. Extrapolation is capped at 30 s; an aircraft that
rolls out or pulls up between updates departs from any prediction until the next report.

### Settings persistence

All settings — search range, FOV calibration, the AI-detection toggle, and the one-time
intro hint — are saved on-device with `SharedPreferences` (`aradsb_settings`) and restored
on the next launch. Nothing is sent anywhere.

### Ground traffic

Aircraft squawking `alt_baro: "ground"` are shown too, labelled **GND**. Many transmit no
altitude on the ground, so they are placed at your own ellipsoidal height — at taxi/runway
distances the terrain difference is a few tens of metres at most, well within marker
tolerance. Watching departures line up from the threshold works.

### UX details

The range slider is logarithmic (fine 1-NM steps at short range, where precision matters)
with −/+ steppers for exact values. The screen stays awake while the app is open. The
reticle gives a subtle haptic tick when it locks onto an aircraft. Network problems never
show raw errors: recent traffic stays on screen from the merged track table and the HUD
says "reconnecting" until a source answers.

**Settings → Help me calibrate** opens a guided two-step flow: step 1 shows the animated
figure-8 with *live* compass-accuracy feedback (the status flips to "HIGH ✓" the moment
the magnetometer reports calibrated); step 2 explains FOV calibration — centre a visible
aircraft and nudge − / + until its marker sits on the aircraft itself; one aircraft is
enough, the setting is per-lens and persists, and heading drift is handled separately by
the AI auto-align. The "Open FOV controls" button lands directly on the settings panel.

---

## Attribution & licensing

**Aircraft data** comes from a pool of free, keyless community ADS-B networks, rotated
round-robin (each is queried at most ~once per 8 s, well below every documented limit):

- [adsb.lol](https://adsb.lol) — © adsb.lol contributors, **ODbL 1.0**
- [adsb.fi](https://adsb.fi) — personal, non-commercial use; attribution required
- [airplanes.live](https://airplanes.live) — non-commercial use, no SLA, 1 req/s limit
- [ADSB One](https://adsb.one) — 1 req/s limit

All are volunteer, feeder-supported networks — keep polling reasonable (this app polls
every 2 s across the whole pool) and set a real contact in the `User-Agent` string in
`MultiSourceAdsbRepository.kt` if you publish a build. Sources that fail are put in
exponential-backoff cooldown and traffic is merged across sources into one track table,
so one outage or rate-limit never blanks the screen.

**Detection model**: `assets/detect.tflite` is SSD MobileNet v1 (quantized, COCO),
© The TensorFlow Authors, **Apache License 2.0**; `assets/labelmap.txt` is the matching
COCO label list.

**Aircraft photos** are fetched from several free, keyless sources tried in order until one has a photo — [Planespotters.net](https://www.planespotters.net), [airport-data.com](https://airport-data.com), and [adsbdb](https://www.adsbdb.com) (whose images come from airport-data.com). Each is a separate host, so if one is down or blocks non-browser clients the next is used; a source that errors is skipped for a few minutes. For every photo the app shows the photographer credit and links the card to the source page; only thumbnails are fetched and nothing is written to disk.

- shows the photographer credit ("© {photographer} · planespotters.net") and links
  the card back to the original photo page;
- uses the image URLs unchanged, as returned by the API;
- does **not** persist photos or API responses to disk — they are held in memory only
  for the session and served from the original URL.

If you publish a build, set a real contact in the `User-Agent` string in
`PhotoRepository.kt` and review the Planespotters API terms.

This is a personal/hobby project provided as-is, with no warranty. Do not rely on it
for navigation, separation, or any safety-of-flight purpose.
