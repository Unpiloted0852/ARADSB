package com.aradsb.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nearby cloud conditions from METAR, used for a SOFT visibility hint and a schematic cloud
 * visualisation. Two truthful properties are extracted per layer — COVERAGE (FEW/SCT/BKN/OVC)
 * and BASE ALTITUDE — and nothing else; layer shape/thickness/tops are unknown.
 *
 * Honest limits: a METAR is a POINT observation possibly tens of km away and cloud is patchy, so
 * we use only the NEAREST recent station (within [MAX_DIST_KM], under [MAX_AGE_S]). Cloud base is
 * feet AGL at the station, converted to MSL via the station's elevation (metres). METAR is hourly.
 *
 * Data: NOAA Aviation Weather Center (aviationweather.gov), free, no key, global.
 */
class MetarRepository {

    /** One reported cloud layer. */
    data class Layer(val cover: String, val baseMslM: Double, val baseAglFt: Int)

    /** Nearest-station cloud picture. [ceilingMslM] is the lowest BKN/OVC base (the visibility
     *  barrier), or null if the station reports only FEW/SCT. */
    data class Weather(
        val layers: List<Layer>,
        val ceilingMslM: Double?,
        val ceilingCover: String?,
        val stationName: String,
        val distanceKm: Double,
        val obsEpochS: Long,
        /** Surface temperature / dewpoint at the station, °C ("temp"/"dewp"), null if unreported. */
        val tempC: Double? = null,
        val dewpC: Double? = null,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile private var cached: Weather? = null
    private var lastFetchMs = 0L
    private var haveFetched = false

    /** True once the first network fetch has been attempted (success or not). */
    val hasFetched: Boolean get() = haveFetched

    /** Current nearby weather, refetched at most every [REFRESH_MS]; cached value otherwise. */
    fun weatherNear(lat: Double, lon: Double): Weather? {
        val now = System.currentTimeMillis()
        if (haveFetched && now - lastFetchMs < REFRESH_MS) return cached
        lastFetchMs = now
        return try {
            cached = fetch(lat, lon); haveFetched = true; cached
        } catch (e: Exception) {
            cached
        }
    }

    private fun fetch(lat: Double, lon: Double): Weather? {
        val dLat = 0.6
        val dLon = 0.6 / cos(Math.toRadians(lat)).coerceAtLeast(0.2)
        val bbox = String.format(
            Locale.US, "%.3f,%.3f,%.3f,%.3f", lat - dLat, lon - dLon, lat + dLat, lon + dLon
        )
        val url = "https://aviationweather.gov/api/data/metar".toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("bbox", bbox)
            .build()
        val req = Request.Builder().url(url)
            .header("User-Agent", "ARADSB/1.0 (personal aircraft AR viewer)")
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return cached
            return parseNearest(resp.body?.string().orEmpty(), lat, lon)
        }
    }

    private fun parseNearest(body: String, lat: Double, lon: Double): Weather? {
        val arr = try { JSONArray(body) } catch (e: Exception) { return null }
        val nowS = System.currentTimeMillis() / 1000
        var nearest: org.json.JSONObject? = null
        var nearestDist = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sLat = o.optDouble("lat", Double.NaN)
            val sLon = o.optDouble("lon", Double.NaN)
            if (sLat.isNaN() || sLon.isNaN()) continue
            val obs = o.optLong("obsTime", 0L)
            if (obs <= 0L || nowS - obs > MAX_AGE_S) continue
            val dist = haversineKm(lat, lon, sLat, sLon)
            if (dist > MAX_DIST_KM) continue
            if (dist < nearestDist) { nearestDist = dist; nearest = o }
        }
        val o = nearest ?: return null

        val elevM = o.optDouble("elev", 0.0)
        // Clear skies still yield a Weather (temp/dewp feed the audibility model); layers empty.
        val cloudsJson = o.optJSONArray("clouds") ?: org.json.JSONArray()
        val layers = ArrayList<Layer>()
        var ceilingMsl: Double? = null
        var ceilingCover: String? = null
        for (j in 0 until cloudsJson.length()) {
            val cl = cloudsJson.optJSONObject(j) ?: continue
            val cv = cl.optString("cover", "")
            if (cv != "FEW" && cv != "SCT" && cv != "BKN" && cv != "OVC") continue
            val baseFt = cl.optInt("base", -1)
            if (baseFt < 0) continue
            val baseMsl = elevM + baseFt * FT_TO_M
            layers.add(Layer(cv, baseMsl, baseFt))
            if ((cv == "BKN" || cv == "OVC") && ceilingMsl == null) {
                ceilingMsl = baseMsl; ceilingCover = cv     // clouds are ascending → first BKN/OVC is the ceiling
            }
        }
        val temp = o.optDouble("temp", Double.NaN).takeIf { !it.isNaN() }
        val dewp = o.optDouble("dewp", Double.NaN).takeIf { !it.isNaN() }
        return Weather(
            layers, ceilingMsl, ceilingCover,
            o.optString("name", ""), nearestDist, o.optLong("obsTime", 0L), temp, dewp
        )
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        private const val REFRESH_MS = 10 * 60 * 1000L
        private const val MAX_AGE_S = 90 * 60L
        private const val MAX_DIST_KM = 60.0
        private const val FT_TO_M = 0.3048
    }
}
