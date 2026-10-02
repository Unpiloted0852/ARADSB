package com.aradsb.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.aradsb.geo.GeoPoint

/**
 * Wraps the platform [LocationManager] (no Google Play Services, so the app stays portable to
 * de-Googled devices, where Play Services' FusedLocationProviderClient is unavailable).
 *
 * To be both accurate and likely-to-succeed it does not rely on a single provider:
 *  - It prefers the platform **fused** provider ([LocationManager.FUSED_PROVIDER], API 31+), which
 *    merges GNSS, network and on-device sensors the same way Play Services' fused client does, but
 *    without the dependency.
 *  - It also registers GPS, network and passive providers, so a fix arrives from whichever responds
 *    first (network/last-known give an immediate coarse fix while the GNSS chip is still acquiring).
 *  - It seeds from the freshest last-known fix across every provider on start, so the AR view has a
 *    reference immediately instead of waiting for a cold GNSS lock.
 *
 * A time/accuracy heuristic ([isBetter]) keeps the best fix, so a coarse network update cannot
 * clobber a recent, accurate GPS one.
 *
 * Altitude is reported as HAE: Location.getAltitude() is WGS-84 ellipsoidal per the platform
 * contract — the same datum as the aircraft's alt_geom. [declinationDeg] is the World Magnetic
 * Model declination via [GeomagneticField]; the rotation-vector sensor reports azimuth from
 * MAGNETIC north, so true_az = magnetic_az + declination.
 */
class LocationTracker(
    private val context: Context,
    private val onUpdate: () -> Unit,
) : LocationListener {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile var last: Location? = null
        private set
    @Volatile var declinationDeg: Float = 0f
        private set

    val hasFix: Boolean get() = last != null
    val hasAltitude: Boolean get() = last?.hasAltitude() == true
    /** Horizontal accuracy of the current fix in metres, or null if unknown. */
    val accuracyM: Float? get() = last?.let { if (it.hasAccuracy()) it.accuracy else null }

    /** Observer point in WGS-84 (HAE). Height defaults to 0 m if the fix lacks altitude. */
    fun observer(): GeoPoint? {
        val l = last ?: return null
        val h = if (l.hasAltitude()) l.altitude else 0.0
        return GeoPoint(l.latitude, l.longitude, h)
    }

    private fun hasFine() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasCoarse() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /** Providers to request, best-first. "fused" merges GNSS + network + sensors (API 31+). */
    private fun candidateProviders(): List<String> {
        val out = ArrayList<String>(4)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) out.add(LocationManager.FUSED_PROVIDER)
        out.add(LocationManager.GPS_PROVIDER)
        out.add(LocationManager.NETWORK_PROVIDER)
        out.add(LocationManager.PASSIVE_PROVIDER)
        return out.distinct()
    }

    @SuppressLint("MissingPermission") // permission checked below before any location call
    fun start() {
        if (!hasFine() && !hasCoarse()) return

        // Seed with the freshest last-known fix across all providers (immediate reference).
        var seed: Location? = null
        for (p in runCatching { lm.allProviders }.getOrDefault(emptyList())) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (isBetter(l, seed)) seed = l
        }
        seed?.let { accept(it) }

        // Subscribe to every enabled provider we can use; the first to respond wins.
        var registered = false
        for (p in candidateProviders()) {
            val enabled = runCatching { lm.isProviderEnabled(p) }.getOrDefault(false)
            if (!enabled) continue
            val ok = runCatching {
                lm.requestLocationUpdates(p, MIN_TIME_MS, MIN_DIST_M, this)
            }.isSuccess
            registered = registered || ok
        }
        // Last resort: passive updates piggyback on other apps' requests.
        if (!registered) {
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER, MIN_TIME_MS, MIN_DIST_M, this
                )
            }
        }
    }

    fun stop() {
        runCatching { lm.removeUpdates(this) }
    }

    private fun accept(l: Location) {
        if (!isBetter(l, last)) return
        last = l
        val alt = if (l.hasAltitude()) l.altitude.toFloat() else 0f
        declinationDeg = GeomagneticField(
            l.latitude.toFloat(), l.longitude.toFloat(), alt, l.time
        ).declination
    }

    /**
     * Whether [cand] is a better fix than [current]. Adapted from the standard Android guidance:
     * a much-newer fix wins (the old one is stale); otherwise prefer better accuracy, accepting a
     * newer fix from the same provider that isn't significantly worse.
     */
    private fun isBetter(cand: Location, current: Location?): Boolean {
        if (current == null) return true
        val dt = cand.time - current.time
        if (dt > STALE_MS) return true
        if (dt < -STALE_MS) return false
        val newer = dt > 0
        val accDelta = if (cand.hasAccuracy() && current.hasAccuracy())
            cand.accuracy - current.accuracy else 0f
        return when {
            accDelta < 0f -> true                                   // strictly more accurate
            newer && accDelta <= 0f -> true                         // newer and no worse
            newer && accDelta <= 200f && cand.provider == current.provider -> true
            else -> false
        }
    }

    override fun onLocationChanged(location: Location) {
        com.aradsb.time.TrueTime.onLocation(location)
        accept(location)
        onUpdate()
    }

    @Deprecated("Required by interface on older API levels")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    companion object {
        private const val MIN_TIME_MS = 1000L
        private const val MIN_DIST_M = 0f
        private const val STALE_MS = 30_000L
    }
}
