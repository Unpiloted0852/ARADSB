package com.aradsb.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A point on/above the WGS-84 ellipsoid.
 *
 * @param latDeg  geodetic latitude, degrees
 * @param lonDeg  geodetic longitude, degrees
 * @param heightM height ABOVE THE WGS-84 ELLIPSOID (HAE), metres.
 *
 * IMPORTANT — datum consistency:
 *   - Android's [android.location.Location.getAltitude] returns metres above the WGS-84
 *     ellipsoid (HAE), per the platform contract.
 *   - The adsb.lol field `alt_geom` is the aircraft's geometric (GNSS) altitude, also
 *     referenced to the WGS-84 ellipsoid (HAE), reported in feet.
 *   Because both are HAE, they are directly comparable. The geoid undulation (the EGM2008
 *   geoid-vs-ellipsoid difference, up to ~100 m) cancels out of the *relative* vertical
 *   difference, so no geoid/EGM conversion is needed for the look angle. (A geoid model is
 *   only needed if you want to *display* mean-sea-level altitude to the user.)
 */
data class GeoPoint(val latDeg: Double, val lonDeg: Double, val heightM: Double)

/**
 * Result of [Geodesy.lookAngles].
 *
 * @param azimuthTrueDeg bearing from the observer to the target, degrees clockwise from TRUE north [0,360)
 * @param elevationDeg   elevation angle above the observer's local horizontal plane, degrees [-90,90]
 * @param slantRangeM    straight-line distance, metres
 * @param horizontalRangeM ground-projected distance, metres
 */
data class Look(
    val azimuthTrueDeg: Double,
    val elevationDeg: Double,
    val slantRangeM: Double,
    val horizontalRangeM: Double,
)

object Geodesy {
    // WGS-84 defining constants
    private const val A = 6_378_137.0                 // semi-major axis, m
    private const val F = 1.0 / 298.257223563         // flattening
    private const val E2 = F * (2.0 - F)              // first eccentricity squared
    private const val DEG = Math.PI / 180.0

    /** Geodetic (lat, lon, HAE) -> ECEF (x, y, z) in metres. */
    fun geodeticToEcef(p: GeoPoint): DoubleArray {
        val lat = p.latDeg * DEG
        val lon = p.lonDeg * DEG
        val sinLat = sin(lat)
        val cosLat = cos(lat)
        val n = A / sqrt(1.0 - E2 * sinLat * sinLat)   // prime vertical radius of curvature
        val x = (n + p.heightM) * cosLat * cos(lon)
        val y = (n + p.heightM) * cosLat * sin(lon)
        val z = (n * (1.0 - E2) + p.heightM) * sinLat
        return doubleArrayOf(x, y, z)
    }

    /**
     * Azimuth (true), elevation, and range from [observer] to [target].
     * Uses an exact ECEF difference rotated into the observer's local East-North-Up frame,
     * so Earth curvature is accounted for (a distant "level" target correctly sits slightly
     * below the horizon).
     */
    fun lookAngles(observer: GeoPoint, target: GeoPoint): Look {
        val o = geodeticToEcef(observer)
        val t = geodeticToEcef(target)
        val dx = t[0] - o[0]
        val dy = t[1] - o[1]
        val dz = t[2] - o[2]

        val lat = observer.latDeg * DEG
        val lon = observer.lonDeg * DEG
        val sinLat = sin(lat); val cosLat = cos(lat)
        val sinLon = sin(lon); val cosLon = cos(lon)

        // ENU at the observer
        val east = -sinLon * dx + cosLon * dy
        val north = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz
        val up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz

        val horiz = hypot(east, north)
        var az = Math.toDegrees(atan2(east, north))
        if (az < 0) az += 360.0
        val el = Math.toDegrees(atan2(up, horiz))
        val slant = sqrt(east * east + north * north + up * up)
        return Look(az, el, slant, horiz)
    }

    // Mean Earth radius (IUGG arithmetic mean R1), metres — used only for short dead-reckoning steps.
    private const val MEAN_RADIUS = 6_371_008.8

    /**
     * Dead-reckons a new position by advancing [from] along a great circle.
     *
     * @param bearingTrueDeg  course over ground, degrees from TRUE north
     * @param groundDistanceM horizontal distance travelled, metres
     * @param newHeightM      the height (HAE, metres) to assign to the result
     *
     * Over the few-kilometre steps used here (≈ ground speed × a couple of seconds) the spherical
     * approximation differs from the WGS-84 ellipsoid by well under a metre — far below ADS-B/GPS
     * position error — so it is more than adequate for smoothing marker motion between API updates.
     */
    fun deadReckon(
        from: GeoPoint,
        bearingTrueDeg: Double,
        groundDistanceM: Double,
        newHeightM: Double,
    ): GeoPoint {
        val ad = groundDistanceM / MEAN_RADIUS          // angular distance, radians
        val br = bearingTrueDeg * DEG
        val lat1 = from.latDeg * DEG
        val lon1 = from.lonDeg * DEG
        val sinLat1 = sin(lat1); val cosLat1 = cos(lat1)
        val sinAd = sin(ad); val cosAd = cos(ad)

        val sinLat2 = sinLat1 * cosAd + cosLat1 * sinAd * cos(br)
        val lat2 = kotlin.math.asin(sinLat2.coerceIn(-1.0, 1.0))
        val lon2 = lon1 + atan2(sin(br) * sinAd * cosLat1, cosAd - sinLat1 * sinLat2)
        return GeoPoint(Math.toDegrees(lat2), Math.toDegrees(lon2), newHeightM)
    }

    /**
     * Extrapolate an aircraft [ageS] seconds forward from its last report: vertically with
     * its vertical speed, horizontally along its TURN ARC when a turn rate is known, else
     * along the straight track.
     *
     * The arc endpoint is computed exactly via its chord: chord bearing = track + ω·t/2 and
     * chord length = gs·t·sinc(ω·t/2) (verified against numeric integration of the arc to
     * sub-millimetre). With ω = 0 this reduces exactly to straight dead reckoning, so one
     * code path handles both.
     *
     * @param turnRateDegS rate of change of true track, deg/s, positive = right turn;
     *        null or ~0 means straight.
     */
    fun extrapolate(
        from: GeoPoint,
        trackTrueDeg: Double?,
        groundSpeedMps: Double?,
        vertRateMps: Double?,
        turnRateDegS: Double?,
        ageS: Double,
    ): GeoPoint {
        val newAlt = from.heightM + (vertRateMps ?: 0.0) * ageS
        val gs = groundSpeedMps
        if (ageS <= 0.0 || gs == null || gs <= 0.0 || trackTrueDeg == null) {
            return GeoPoint(from.latDeg, from.lonDeg, newAlt)
        }
        val w = turnRateDegS ?: 0.0
        return if (kotlin.math.abs(w) < 0.05) {
            deadReckon(from, trackTrueDeg, gs * ageS, newAlt)
        } else {
            val halfTurnRad = Math.toRadians(w) * ageS / 2.0
            val sinc = if (kotlin.math.abs(halfTurnRad) < 1e-9) 1.0
                       else sin(halfTurnRad) / halfTurnRad
            deadReckon(from, trackTrueDeg + Math.toDegrees(halfTurnRad), gs * ageS * sinc, newAlt)
        }
    }
}
