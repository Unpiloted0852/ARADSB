package com.aradsb.data

import android.os.SystemClock
import com.aradsb.model.Aircraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Aggregates several free, keyless ADS-B aggregator APIs for redundancy and coverage.
 *
 * All four are community/volunteer networks exposing the same readsb / ADS-B Exchange v2
 * response schema ({"ac":[...]}), so one parser ([AdsbParser]) covers them all:
 *
 *  - adsb.lol        GET https://api.adsb.lol/v2/lat/{lat}/lon/{lon}/dist/{nm}
 *                    ODbL 1.0; rate limits dynamic with load.
 *  - adsb.fi         GET https://opendata.adsb.fi/api/v3/lat/{lat}/lon/{lon}/dist/{nm}
 *                    1 req/s; personal, non-commercial; requires citing adsb.fi.
 *  - airplanes.live  GET https://api.airplanes.live/v2/point/{lat}/{lon}/{nm}
 *                    1 req/s; non-commercial; no SLA.
 *  - ADSB One        GET https://api.adsb.one/v2/point/{lat}/{lon}/{nm}
 *                    1 req/s; radius up to 250 NM.
 *
 * Strategy — rotate fast, floor per source:
 *  - The app polls every 0.5 s and each poll queries ONE source (round-robin over those eligible).
 *    With four sources that means the app receives a fresh snapshot every ~0.5 s — up to four
 *    position updates per aircraft every 2 s when several sources carry it — while each individual
 *    source is queried only every 2 s.
 *  - A per-source RATE FLOOR ([PER_SOURCE_MIN_MS]) guarantees no source is queried more often than
 *    every 2 s even when the others are cooling down, so the rotation can never collapse onto and
 *    hammer a survivor (which would get it rate-limited too). Cadence degrades gracefully toward
 *    the worst case — an aircraft only one source carries updates every 2 s, the rate-limit floor.
 *  - A source that fails goes into exponential-backoff cooldown (10 s, 20 s, 40 s … max 5 min)
 *    and the next eligible source is tried immediately in the same cycle, so one outage costs
 *    nothing visible.
 *  - Every successful response is merged into a track table keyed by ICAO hex. An aircraft seen
 *    by source B stays displayed (dead-reckoned by the overlay) while later cycles are served by
 *    source A, so coverage is the UNION of all sources rather than whichever answered last.
 *    Entries expire once their position is older than [TRACK_EXPIRY_S].
 *
 * Each merged entry carries its own fetch timestamp ([Fetched.fetchedAtElapsedMs]) because
 * entries in one snapshot may come from different polls.
 */
class MultiSourceAdsbRepository(
    private val client: OkHttpClient = defaultClient(),
) {
    /** One aircraft plus the elapsedRealtime at which its data was fetched. */
    data class Fetched(val aircraft: Aircraft, val fetchedAtElapsedMs: Long)

    private class Source(
        val name: String,
        val attribution: String,
        val urlOf: (Double, Double, Int) -> String,
    ) {
        var consecutiveFailures = 0
        var cooldownUntilMs = 0L
        // 0 (not Long.MIN_VALUE) so the first eligibility check — now - lastQueriedMs — cannot
        // overflow to a negative value and lock every source out permanently. elapsedRealtime is
        // always well past PER_SOURCE_MIN_MS at launch, so every source starts eligible.
        var lastQueriedMs = 0L
        fun healthy(now: Long) = now >= cooldownUntilMs
        /** Eligible to query: not in failure-cooldown AND not queried within the rate floor. */
        fun eligible(now: Long) = healthy(now) && now - lastQueriedMs >= PER_SOURCE_MIN_MS
        fun recordSuccess() { consecutiveFailures = 0; cooldownUntilMs = 0L }
        fun recordFailure(now: Long) {
            consecutiveFailures++
            val backoffMs = (BASE_BACKOFF_MS shl (consecutiveFailures - 1).coerceAtMost(5))
                .coerceAtMost(MAX_BACKOFF_MS)
            cooldownUntilMs = now + backoffMs
        }
    }

    private val sources = listOf(
        Source("adsb.lol", "adsb.lol (ODbL 1.0)") { lat, lon, nm ->
            String.format(Locale.US, "https://api.adsb.lol/v2/lat/%.6f/lon/%.6f/dist/%d", lat, lon, nm)
        },
        Source("adsb.fi", "adsb.fi") { lat, lon, nm ->
            String.format(Locale.US, "https://opendata.adsb.fi/api/v3/lat/%.6f/lon/%.6f/dist/%d", lat, lon, nm)
        },
        Source("airplanes.live", "airplanes.live") { lat, lon, nm ->
            String.format(Locale.US, "https://api.airplanes.live/v2/point/%.6f/%.6f/%d", lat, lon, nm)
        },
        Source("adsb.one", "ADSB One") { lat, lon, nm ->
            String.format(Locale.US, "https://api.adsb.one/v2/point/%.6f/%.6f/%d", lat, lon, nm)
        },
    )

    private var rotation = 0
    private val tracks = HashMap<String, Fetched>()

    /** Name of the source that served the most recent successful fetch (for the HUD). */
    @Volatile var lastSourceName: String = "—"
        private set

    /** Count of sources currently healthy (not in failure cooldown). */
    fun healthySourceCount(): Int {
        val now = SystemClock.elapsedRealtime()
        return sources.count { it.healthy(now) }
    }

    fun sourceCount(): Int = sources.size

    /** Attribution string for docs/UI. */
    fun attributionLine(): String =
        "Data: " + sources.joinToString(", ") { it.attribution }

    /**
     * Fetch around (lat, lon) from the next healthy source and return the MERGED current picture
     * (this source's fresh data plus recent aircraft from other sources, expired entries dropped).
     *
     * Tries each source at most once per call, starting at the rotation cursor. Throws
     * [java.io.IOException] only if EVERY source fails or is cooling down.
     */
    suspend fun fetchAround(lat: Double, lon: Double, radiusNm: Int): List<Fetched> =
        withContext(Dispatchers.IO) {
            val nm = radiusNm.coerceIn(1, MAX_RADIUS_NM)
            var lastError: Exception? = null
            var fetched: List<Aircraft>? = null
            var stalenessMs = 0L
            var attempted = false

            for (i in sources.indices) {
                val idx = (rotation + i) % sources.size
                val src = sources[idx]
                val now = SystemClock.elapsedRealtime()
                // Per-source rate floor: skip a source queried within PER_SOURCE_MIN_MS even if it
                // is "next" and others are down — this stops the rotation collapsing onto and
                // hammering a survivor when most sources are cooling down.
                if (!src.eligible(now)) continue
                attempted = true
                src.lastQueriedMs = now
                try {
                    val (list, stale) = fetchFrom(src, lat, lon, nm)
                    fetched = list
                    stalenessMs = stale
                    src.recordSuccess()
                    lastSourceName = src.name
                    rotation = (idx + 1) % sources.size
                    break
                } catch (e: Exception) {
                    src.recordFailure(SystemClock.elapsedRealtime())
                    lastError = e
                }
            }

            val now = SystemClock.elapsedRealtime()
            // The position data was generated `stalenessMs` ago (CDN/cache + transit), so its
            // effective receipt time is earlier than now: dead reckoning then covers the FULL
            // data age and fast jets stop trailing their markers.
            val effFetchedAt = now - stalenessMs
            synchronized(tracks) {
                if (fetched != null) {
                    for (ac in fetched) {
                        if (ac.hex.isEmpty()) continue
                        val effAge = effFetchedAt - ((ac.seenPosSec ?: 0.0) * 1000.0).toLong()
                        val prev = tracks[ac.hex]
                        val prevEff = prev?.let {
                            it.fetchedAtElapsedMs -
                                ((it.aircraft.seenPosSec ?: 0.0) * 1000.0).toLong()
                        } ?: Long.MIN_VALUE
                        // Keep whichever record has the newer effective position time, but
                        // merge the STATIC identity fields stickily: type, registration and
                        // description come from each aggregator's own enrichment database and
                        // differ in completeness ("A359" vs "Airbus A350-941"). A source that
                        // omits or abbreviates a field must never downgrade what a richer
                        // source already provided — that churn makes the UI text jump.
                        if (effAge >= prevEff) {
                            val merged = if (prev == null) ac else mergeStatic(prev.aircraft, ac)
                            tracks[ac.hex] = Fetched(merged, effFetchedAt)
                        }
                    }
                }
                // Expire entries whose position is too old to trust.
                val it = tracks.values.iterator()
                while (it.hasNext()) {
                    val f = it.next()
                    val posAgeS = (now - f.fetchedAtElapsedMs) / 1000.0 +
                        (f.aircraft.seenPosSec ?: 0.0)
                    if (posAgeS > TRACK_EXPIRY_S) it.remove()
                }
                // Throw only on a GENUINE failure (we tried ≥1 eligible source and all failed)
                // with nothing cached. A tick where every source was simply within its rate
                // floor is normal — return the current (extrapolated) picture, not an error.
                if (attempted && fetched == null && tracks.isEmpty()) {
                    throw java.io.IOException(
                        "all sources failed (${lastError?.message ?: "unknown"})"
                    )
                }
                // Return only traffic within the CURRENTLY requested radius. The cache may
                // hold farther aircraft from when the range was larger — without this filter
                // a range decrease appears to do nothing until those tracks expire (or the
                // app restarts), which made the slider feel broken.
                tracks.values.filter { groundRangeNm(lat, lon, it.aircraft.lat, it.aircraft.lon) <= nm + 0.5 }
            }
        }

    /** Great-circle ground distance in NM (spherical, mean radius — ample for filtering). */
    private fun groundRangeNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371008.8
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) +
            Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        val d = 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return d / 1852.0
    }

    /**
     * Merge [new] (the record with the newer position/velocity) with the sticky static
     * identity fields of [prev]. Dynamic fields always come from [new]; identity fields
     * prefer non-null, and free-text descriptions prefer the longer (richer) string.
     * The same is applied to the static keys of the raw field map, so the expanded
     * sheet's rows do not appear and disappear as sources rotate.
     */
    private fun mergeStatic(prev: Aircraft, new: Aircraft): Aircraft {
        fun richer(a: String?, b: String?): String? = when {
            a == null -> b
            b == null -> a
            else -> if (b.length > a.length) b else a
        }
        val raw = LinkedHashMap(new.rawFields)
        for (k in STATIC_RAW_KEYS) {
            val pv = prev.rawFields[k]
            val nv = raw[k]
            if (nv.isNullOrEmpty() && !pv.isNullOrEmpty()) raw[k] = pv
            else if (k == "desc" || k == "ownOp") {
                val r = richer(nv, pv); if (r != null) raw[k] = r
            }
        }
        return new.copy(
            callsign = new.callsign ?: prev.callsign,
            registration = new.registration ?: prev.registration,
            typeCode = new.typeCode ?: prev.typeCode,
            typeDesc = richer(prev.typeDesc, new.typeDesc),
            category = new.category ?: prev.category,
            rawFields = raw,
        )
    }

    /**
     * Fetch + parse one source. Returns the aircraft plus the response STALENESS in ms:
     * aggregators and their CDNs cache responses for a few seconds, and seen_pos is the
     * position age at response GENERATION — cache time after that is invisible to it. The
     * HTTP Date header timestamps generation, so (received − Date) ≈ cache age + transit.
     * At 450 kt every hidden second is ~230 m of marker lag, so this matters for close
     * fast traffic. Clamped to [0, 15 s] to be safe against client clock skew.
     */
    private fun fetchFrom(src: Source, lat: Double, lon: Double, nm: Int): Pair<List<Aircraft>, Long> {
        // Locale.US is mandatory: a comma decimal separator would corrupt the URL path.
        val request = Request.Builder()
            .url(src.urlOf(lat, lon, nm))
            .header("Accept", "application/json")
            .header("User-Agent", "ar-adsb-viewer/1.1 (personal; +https://github.com/Unpiloted0852/ARADSB)")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code} from ${src.name}")
            val staleness = resp.headers.getDate("Date")?.let { d ->
                // receivedResponseAtMillis is the PHONE's wall clock; correct it to true
                // UTC (GNSS/NTP-derived offset) before differencing with the server Date —
                // a 2 s phone-clock error otherwise becomes 2 s of marker-lag error.
                (com.aradsb.time.TrueTime.trueWallMs(resp.receivedResponseAtMillis) - d.time)
                    .coerceIn(0L, 15_000L)
            } ?: 0L
            return AdsbParser.parse(resp.body?.string().orEmpty()) to staleness
        }
    }

    companion object {
        const val MAX_RADIUS_NM = 250          // documented cap for adsb.lol and ADSB One
        private const val TRACK_EXPIRY_S = 60.0
        private const val BASE_BACKOFF_MS = 10_000L
        private const val MAX_BACKOFF_MS = 300_000L
        // Rate floor: never query a single source more often than this, regardless of the poll
        // tick or how many other sources are down. 4 sources × 500 ms tick → each every 2 s
        // (0.5 req/s, half the documented 1 req/s limit of the volunteer networks).
        private const val PER_SOURCE_MIN_MS = 2_000L
        // Per-airframe identity/enrichment keys that must never churn between sources.
        private val STATIC_RAW_KEYS = listOf(
            "r", "t", "desc", "ownOp", "year", "category", "dbFlags"
        )

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}
