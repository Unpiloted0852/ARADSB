package com.aradsb.model

/**
 * One aircraft from the adsb.lol v2 API response (`ac[]` array).
 * Field names mirror the ADS-B Exchange v2 / readsb JSON that adsb.lol serves.
 *
 * Altitudes:
 *   - [altGeomFt]  : geometric (GNSS) altitude, feet, referenced to the WGS-84 ellipsoid (HAE).
 *                    This is the value we want for the AR geometry — same datum as the phone's GPS.
 *   - [altBaroFt]  : barometric (pressure) altitude, feet, referenced to 1013.25 hPa. Used only as
 *                    a fallback when alt_geom is absent; it can differ from true height by hundreds
 *                    of feet depending on local pressure, so the fallback is flagged.
 */
data class Aircraft(
    val hex: String,
    val callsign: String?,      // "flight"
    val registration: String?,  // "r"
    val typeCode: String?,      // "t"  e.g. "A21N"
    val typeDesc: String?,      // "desc" e.g. "AIRBUS A-321neo" (not always present)
    val lat: Double,
    val lon: Double,
    val altGeomFt: Double?,     // "alt_geom"
    val altBaroFt: Double?,     // "alt_baro" (null if the API reported "ground")
    val onGround: Boolean,      // "alt_baro" == "ground"
    val trackDeg: Double?,      // "track" — true track over ground, degrees
    val groundSpeedKt: Double?, // "gs" — knots
    val vertRateFpm: Double?,   // "geom_rate" else "baro_rate" — feet/minute
    val trackRateDegS: Double?, // "track_rate" — rate of change of track, deg/s, + = right
    val rollDeg: Double?,       // "roll" — bank angle, degrees, + = right wing down
    val squawk: String?,        // "squawk" — Mode A code, e.g. "7000"
    val emergency: String?,     // "emergency" — e.g. "none", "general", "lifeguard"
    val category: String?,      // emitter category, e.g. "A3"
    val seenPosSec: Double?,    // "seen_pos" — age of the last position fix, seconds
    /** Every field the API returned for this aircraft, in order, as display strings. */
    val rawFields: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FT_TO_M = 0.3048
        const val KT_TO_MPS = 0.514444     // knots -> metres/second
        const val FPM_TO_MPS = 0.3048 / 60.0  // feet/minute -> metres/second
        const val M_TO_NM = 1.0 / 1852.0   // metres -> nautical miles

        /** ADS-B emitter category (DO-260B) to a short human label. Returns null if unknown. */
        fun categoryLabel(code: String?): String? = when (code?.uppercase()) {
            "A1" -> "Light"
            "A2" -> "Small"
            "A3" -> "Large"
            "A4" -> "High-vortex large"
            "A5" -> "Heavy"
            "A6" -> "High performance"
            "A7" -> "Rotorcraft"
            "B1" -> "Glider"
            "B2" -> "Lighter-than-air"
            "B3" -> "Parachutist"
            "B4" -> "Ultralight"
            "B6" -> "UAV"
            "B7" -> "Space vehicle"
            "C1", "C2", "C3" -> "Surface vehicle"
            "C4", "C5" -> "Obstacle"
            else -> null
        }
    }

    /** True if we have a usable airborne geometric position to place in AR. */
    /** Whether this aircraft can be placed in 3D: needs an altitude, or is on the ground. */
    val isPlaceable: Boolean
        get() = altGeomFt != null || altBaroFt != null || onGround

    /**
     * Best available altitude in metres HAE, plus whether it came from the geometric source.
     * Prefers alt_geom (HAE, matches the phone). Falls back to alt_baro (flagged inexact).
     */
    fun altitudeMetersHae(): Pair<Double, Boolean>? {
        altGeomFt?.let { return it * FT_TO_M to true }
        altBaroFt?.let { return it * FT_TO_M to false }
        return null
    }

    /** Ground speed in m/s, or null if unknown. */
    fun groundSpeedMps(): Double? = groundSpeedKt?.let { it * KT_TO_MPS }

    /** Vertical rate in m/s (positive = climbing), or null if unknown. */
    fun vertRateMps(): Double? = vertRateFpm?.let { it * FPM_TO_MPS }

    /** Trimmed callsign for display; falls back to registration then hex. */
    fun displayName(): String {
        val cs = callsign?.trim()
        if (!cs.isNullOrEmpty()) return cs
        val r = registration?.trim()
        if (!r.isNullOrEmpty()) return r
        return hex.uppercase()
    }

    /** A one-line type description for the detail card, e.g. "AIRBUS A-321neo (A21N) · Large". */
    fun typeLine(): String {
        val parts = ArrayList<String>(3)
        val desc = typeDesc?.trim()
        val code = typeCode?.trim()
        when {
            !desc.isNullOrEmpty() && !code.isNullOrEmpty() -> parts.add("$desc ($code)")
            !desc.isNullOrEmpty() -> parts.add(desc)
            !code.isNullOrEmpty() -> parts.add(code)
        }
        categoryLabel(category)?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }

    /** ICAO 24-bit address suitable for the photo API, or null if this is not a real ICAO hex. */
    fun icaoHexOrNull(): String? {
        val h = hex.trim()
        // adsb.lol prefixes non-ICAO addresses (TIS-B / ADS-R) with '~'.
        if (h.isEmpty() || h.startsWith("~")) return null
        return h.uppercase()
    }
}
