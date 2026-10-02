package com.aradsb.sound

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Estimates whether an aircraft is loud enough, at its current geometry, to be plausibly HEARD on
 * the ground right now. Conservative by design: a positive verdict should be trustworthy; anything
 * uncertain stays silent.
 *
 * Model, term by term (each grounded, with its source):
 *
 *  1. RETARDED (emission) DISTANCE — exact kinematics. The sound arriving NOW left the aircraft
 *     tens of seconds ago. For constant velocity the emission delay τ solves
 *     |r − v·τ| = c·τ  →  (c²−v²)τ² + 2(r·v)τ − |r|² = 0, taken at the positive root. An
 *     approaching jet's sound was emitted from much farther (v/c ≈ 0.7 for a fast jet!); a
 *     receding jet's from closer — which is why aircraft sound loudest after they pass.
 *  2. GEOMETRIC SPREADING — inverse-square: 20·log10(d / D_REF).
 *  3. ATMOSPHERIC ABSORPTION — ISO 9613-1 pure-tone absorption computed at a representative
 *     low frequency per class (distant aircraft audibility is carried by low-frequency energy),
 *     using the METAR temperature and dewpoint (Magnus relative humidity) when available,
 *     falling back to 10 °C / 70 % RH.
 *  4. LATERAL / LOW-ELEVATION ATTENUATION — air-to-ground attenuation from the FAA AEDT model
 *     (SAE AIR 5662): Λ(β) = 1.137 − 0.0229β + 9.72·e^(−0.142β) dB for elevation β < 50°, 0
 *     above. Adds up to ~10.9 dB for aircraft near the horizon (ground absorption +
 *     engine-installation shielding).
 *  5. SOURCE LEVEL — per-propulsion-class reference at D_REF, adjusted by:
 *     • an engine-GENERATION offset per ICAO type (grounded in ICAO Annex 16 certification
 *       chapters: Chapter 14 types are 17 EPNdB cumulative below Chapter 3 limits, i.e. several
 *       dB per condition; JT8D-era types sit well above). EPNdB→dBA is not a 1:1 mapping, so
 *       offsets are sized conservatively; the ORDERING is what certification guarantees.
 *     • a THRUST proxy from vertical rate (certification distinguishes takeoff vs approach
 *       power precisely because they differ by several dB): climbing +3 dB, descending −3 dB.
 *     • a separate MILITARY FAST-JET class (low-bypass engines; base noise studies measure them
 *       far above transports) — set moderately, well below afterburner, to stay conservative.
 *
 * Known, accepted approximations (all err toward silence or are direction-safe): constant
 * velocity over the delay; wind refraction ignored; single representative frequency; source
 * levels are class representatives since ADS-B carries no engine-power data.
 */
object Audibility {

    enum class Prop { HEAVY_JET, JET, MIL_JET, TURBOPROP, PISTON, HELI, UNKNOWN }

    // Representative source level (dBA) at D_REF_M for a typical flyover of each class.
    private fun refDba(p: Prop): Double = when (p) {
        Prop.MIL_JET -> 94.0
        Prop.HEAVY_JET -> 88.0
        Prop.JET -> 82.0
        Prop.HELI -> 84.0
        Prop.TURBOPROP -> 75.0
        Prop.PISTON -> 67.0
        Prop.UNKNOWN -> Double.NEGATIVE_INFINITY
    }

    // Representative frequency (Hz) carrying each class's audible energy at distance.
    private fun repFreqHz(p: Prop): Double = when (p) {
        Prop.TURBOPROP -> 150.0
        Prop.PISTON -> 120.0
        else -> 200.0
    }

    private const val D_REF_M = 300.0
    private const val THRESHOLD_DBA = 48.0   // clears quiet-rural ambient (~35-40) with margin
    private const val MAX_SLANT_M = 20000.0  // emission range beyond this: never claim
    private const val MIN_SLANT_M = 1.0

    /**
     * True only when we are confident the aircraft is currently audible.
     * [rE],[rN],[rU]: observer→aircraft vector, metres, ENU. [vE],[vN],[vU]: aircraft velocity,
     * m/s, ENU (zeros if unknown → falls back to current range). [tempC]/[dewpC] from the
     * nearest METAR, null → ISA-ish fallback.
     */
    fun isAudible(
        typeCode: String?, category: String?,
        rE: Double, rN: Double, rU: Double,
        vE: Double, vN: Double, vU: Double,
        vertRateMps: Double?, elevationDeg: Double,
        tempC: Double?, dewpC: Double?,
    ): Boolean {
        val p = propulsionOf(typeCode, category)
        if (p == Prop.UNKNOWN) return false
        val t = tempC ?: 10.0
        val rh = if (tempC != null && dewpC != null) relHumidityPct(tempC, dewpC) else 70.0
        val c = 331.3 + 0.606 * t                       // speed of sound, m/s
        val d = emissionRangeM(rE, rN, rU, vE, vN, vU, c)
        if (d < MIN_SLANT_M || d > MAX_SLANT_M) return false
        val level = refDba(p) + typeOffsetDb(typeCode) + thrustOffsetDb(vertRateMps) -
            20.0 * log10(d / D_REF_M) -
            absorptionDbPerKm(repFreqHz(p), t, rh) * (d - D_REF_M) / 1000.0 -
            lateralAttenDb(elevationDeg)
        return level >= THRESHOLD_DBA
    }

    /** Distance from which the sound now arriving was emitted (constant-velocity retarded range). */
    fun emissionRangeM(
        rE: Double, rN: Double, rU: Double,
        vE: Double, vN: Double, vU: Double, c: Double,
    ): Double {
        val r2 = rE * rE + rN * rN + rU * rU
        val rNow = sqrt(r2)
        val v2 = vE * vE + vN * vN + vU * vU
        if (v2 < 1.0 || v2 >= 0.8 * c * c) return rNow      // stationary or nonsensical speed
        val rv = rE * vE + rN * vN + rU * vU
        val a = c * c - v2
        val disc = rv * rv + a * r2
        if (disc <= 0.0) return rNow
        val tau = (-rv + sqrt(disc)) / a
        return if (tau > 0.0) c * tau else rNow
    }

    /** ISO 9613-1 pure-tone atmospheric absorption, dB/km, at sea-level pressure. */
    fun absorptionDbPerKm(fHz: Double, tC: Double, rhPct: Double): Double {
        val T = tC + 273.15
        val T0 = 293.15
        val T01 = 273.16
        val psatOverPr = 10.0.pow(-6.8346 * (T01 / T).pow(1.261) + 4.6151)
        val h = rhPct.coerceIn(1.0, 100.0) * psatOverPr            // molar water-vapour conc., %
        val frO = 24.0 + 40400.0 * h * (0.02 + h) / (0.391 + h)
        val frN = (T / T0).pow(-0.5) *
            (9.0 + 280.0 * h * exp(-4.170 * ((T / T0).pow(-1.0 / 3.0) - 1.0)))
        val f2 = fHz * fHz
        val alphaDbPerM = 8.686 * f2 * (
            1.84e-11 * (T / T0).pow(0.5) +
                (T / T0).pow(-2.5) * (
                    0.01275 * exp(-2239.1 / T) / (frO + f2 / frO) +
                        0.1068 * exp(-3352.0 / T) / (frN + f2 / frN)
                    )
            )
        return (alphaDbPerM * 1000.0).coerceIn(0.05, 50.0)
    }

    /** Magnus (August–Roche–Magnus) relative humidity from temperature and dewpoint, %. */
    fun relHumidityPct(tC: Double, dC: Double): Double {
        fun es(x: Double) = exp(17.625 * x / (243.04 + x))
        return (100.0 * es(dC) / es(tC)).coerceIn(1.0, 100.0)
    }

    /** SAE AIR 5662 / FAA AEDT air-to-ground lateral attenuation vs elevation angle, dB. */
    fun lateralAttenDb(elevDeg: Double): Double {
        if (elevDeg >= 50.0) return 0.0
        val b = elevDeg.coerceAtLeast(0.0)
        return (1.137 - 0.0229 * b + 9.72 * exp(-0.142 * b)).coerceAtLeast(0.0)
    }

    /** Thrust proxy: climb power is louder, approach/descent power quieter. Bounded ±3 dB. */
    fun thrustOffsetDb(vertRateMps: Double?): Double = when {
        vertRateMps == null -> 0.0
        vertRateMps >= 2.5 -> 3.0
        vertRateMps <= -2.5 -> -3.0
        else -> 0.0
    }

    /** Engine-generation offset per ICAO type (see class doc for grounding). */
    fun typeOffsetDb(typeCode: String?): Double {
        val t = typeCode?.trim()?.uppercase().orEmpty()
        return when {
            t in NEW_GEN -> -4.0
            t in OLD_GEN -> 5.0
            else -> 0.0
        }
    }

    fun propulsionOf(typeCode: String?, category: String?): Prop {
        val cat = category?.trim()?.uppercase()
        if (cat == "A7") return Prop.HELI
        if (cat != null && (cat.startsWith("B") || cat.startsWith("C"))) return Prop.UNKNOWN

        val t = typeCode?.trim()?.uppercase().orEmpty()
        if (t.isNotEmpty()) {
            if (t in MIL_FAST_JETS) return Prop.MIL_JET
            if (t in HEAVY) return Prop.HEAVY_JET
            if (t in JETS) return Prop.JET
            if (t in TURBOPROPS) return Prop.TURBOPROP
            if (t in PISTONS) return Prop.PISTON
            if (isHelicopter(t)) return Prop.HELI
        }
        return when (cat) {
            "A5" -> Prop.HEAVY_JET
            "A3", "A4", "A6" -> Prop.JET
            "A2" -> Prop.TURBOPROP
            else -> Prop.PISTON
        }
    }

    private fun isHelicopter(t: String): Boolean =
        t.startsWith("EC") || t.startsWith("AS3") || t.startsWith("AS5") ||
            t.startsWith("R22") || t.startsWith("R44") || t.startsWith("R66") ||
            t.startsWith("S76") || t.startsWith("S92") || t.startsWith("B06") ||
            t.startsWith("B41") || t.startsWith("B42") ||
            (t.startsWith("A10") && t != "A10") ||    // A109/A10x Agusta helis; bare A10 = mil jet
            t.startsWith("A11") || t.startsWith("A13") || t.startsWith("A16") ||
            t.startsWith("A18") || (t.startsWith("H") && t.length == 4 && t[1].isDigit())

    // Military fast jets with unambiguous ICAO designators. Representative, not exhaustive —
    // unlisted military types simply fall through to the ordinary classes (conservative).
    private val MIL_FAST_JETS = setOf(
        "F16", "F15", "F18", "F35", "F22", "EUFI", "MG29", "SU27", "SU30", "A10", "T38", "HAWK"
    )

    // New-generation types (ICAO Annex 16 Chapter 14 era: geared/high-bypass re-engined).
    private val NEW_GEN = setOf(
        "A19N", "A20N", "A21N", "B37M", "B38M", "B39M", "B3XM",
        "B788", "B789", "B78X", "A359", "A35K", "A338", "A339",
        "BCS1", "BCS3", "E290", "E295"
    )
    // Old-generation low-bypass survivors (JT8D family and 747 Classic era).
    private val OLD_GEN = setOf(
        "MD82", "MD83", "MD87", "MD88", "B721", "B722", "B731", "B732",
        "DC91", "DC92", "DC93", "DC94", "DC95", "B741", "B742", "B743", "B74S", "IL76"
    )

    private val HEAVY = setOf(
        "A306", "A30B", "A310", "A332", "A333", "A342", "A343", "A345", "A346",
        "A388", "A124", "B744", "B748", "B762", "B763", "B764", "B772", "B773", "B77L", "B77W",
        "MD11", "IL96", "DC10", "L101", "C17", "A400"
    )
    private val JETS = setOf(
        "A318", "A319", "A320", "A321",
        "B733", "B734", "B735", "B736", "B737", "B738", "B739", "B73J", "B752", "B753", "B712",
        "E170", "E75L", "E75S", "E190", "E195",
        "CRJ1", "CRJ2", "CRJ7", "CRJ9", "CRJX", "RJ85", "RJ1H", "B461", "B462", "B463",
        "C25A", "C25B", "C25C", "C510", "C525", "C56X", "C680", "C68A", "C700", "CL30", "CL35",
        "CL60", "GLF4", "GLF5", "GLF6", "GLEX", "G280", "LJ35", "LJ45", "LJ60", "LJ75",
        "F2TH", "FA7X", "FA8X", "E50P", "E55P", "H25B", "MD90", "F100", "F70"
    )
    private val TURBOPROPS = setOf(
        "DH8A", "DH8B", "DH8C", "DH8D", "DHC6", "AT43", "AT45", "AT72", "AT75", "AT76",
        "SF34", "SB20", "E120", "B190", "BE20", "BE9L", "B350", "C208", "PC12", "PC6T",
        "TBM7", "TBM8", "TBM9", "D328", "JS31", "JS32", "JS41", "SW4", "F50", "F27",
        "L410", "AN12", "AN26", "AN30", "AN32", "C130", "L188", "AT8T"
    )
    private val PISTONS = setOf(
        "C152", "C162", "C172", "C72R", "C150", "C170", "C175", "C177", "C180", "C182",
        "C185", "C206", "C207", "C210", "C310", "C337", "P28A", "P28B", "P28R", "P28T",
        "P32R", "PA18", "PA24", "PA34", "PA44", "SR20", "SR22", "DA40", "DA42", "DA62",
        "BE33", "BE35", "BE36", "BE55", "BE58", "BE76", "M20P", "M20T", "AA5", "AA1",
        "GA8", "DR40", "DV20", "C42"
    )
}
