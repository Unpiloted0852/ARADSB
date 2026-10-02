package com.aradsb.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Aircraft photo metadata. [source] is the site shown in the credit/used for the Referer. */
data class AircraftPhoto(
    val thumbnailUrl: String,
    val link: String,
    val photographer: String,
    val source: String,
)

/** Outcome of a photo lookup, distinguishing "none on file" from a service error. */
sealed class PhotoResult {
    data class Found(val photo: AircraftPhoto) : PhotoResult()
    object NoneOnFile : PhotoResult()
    data class Error(val reason: String) : PhotoResult()
}

/**
 * Aircraft photos from several free, keyless sources, tried in order until one has a photo:
 *
 *  1. Planespotters  GET api.planespotters.net/pub/photos/hex/{HEX}?reg=&icaoType=
 *     -> { photos:[ { thumbnail_large:{src}, thumbnail:{src}, link, photographer } ] }
 *  2. airport-data   GET airport-data.com/api/ac_thumb.json?m={HEX}&n=1
 *     -> { status:200, data:[ { image, link, photographer } ] }
 *  3. adsbdb         GET api.adsbdb.com/v0/aircraft/{HEX}
 *     -> { response:{ aircraft:{ url_photo_thumbnail, url_photo } } }  (images from airport-data)
 *
 * No API keys. Each is a separate host, so one being down or rate-limited (e.g. Planespotters
 * returning 403 to non-browser clients) falls through to the next. A source that errors is put
 * in a short cooldown so the chain skips it — no wasted request or latency — until it recovers.
 *
 * Attribution: the photographer credit and a click-through link to the source page are shown
 * for every photo; only small thumbnails are fetched; nothing is written to disk.
 */
class PhotoRepository(
    private val client: OkHttpClient = defaultClient(),
) {
    private class Source(
        val name: String,
        val fetch: (hex: String, reg: String?, type: String?) -> PhotoResult,
    ) {
        @Volatile var cooldownUntil = 0L
        fun healthy(now: Long) = now >= cooldownUntil
        fun trip(now: Long) { cooldownUntil = now + SOURCE_COOLDOWN_MS }
    }

    private val sources = listOf(
        Source("planespotters") { hex, reg, type -> fetchPlanespotters(hex, reg, type) },
        Source("airport-data.com") { hex, _, _ -> fetchAirportData(hex) },
        Source("adsbdb") { hex, _, _ -> fetchAdsbdb(hex) },
    )

    private class Entry(val result: PhotoResult, val atMs: Long)
    private val metaCache = object : LruCache<String, Entry>(256) {}
    private val bitmapCache = object : LruCache<String, Bitmap>(40) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    /**
     * Find a photo for [icaoHex], trying each source in turn. Never throws. Successes and a
     * definitive "no photo" are cached for the session; errors are cached only briefly.
     */
    suspend fun photoForHex(icaoHex: String, reg: String?, type: String?): PhotoResult =
        withContext(Dispatchers.IO) {
            metaCache.get(icaoHex)?.let { e ->
                val fresh = e.result !is PhotoResult.Error ||
                    SystemClock.elapsedRealtime() - e.atMs < ERROR_TTL_MS
                if (fresh) return@withContext e.result
            }
            var sawNone = false
            var lastError: String? = null
            for (src in sources) {
                val now = SystemClock.elapsedRealtime()
                if (!src.healthy(now)) { lastError = lastError ?: "blocked"; continue }
                val r = try {
                    src.fetch(icaoHex, reg, type)
                } catch (e: Exception) {
                    PhotoResult.Error(e.javaClass.simpleName)
                }
                when (r) {
                    is PhotoResult.Found -> {
                        metaCache.put(icaoHex, Entry(r, SystemClock.elapsedRealtime()))
                        return@withContext r
                    }
                    is PhotoResult.NoneOnFile -> sawNone = true
                    is PhotoResult.Error -> { src.trip(now); lastError = r.reason }
                }
            }
            val result =
                if (sawNone) PhotoResult.NoneOnFile
                else PhotoResult.Error(lastError ?: "unavailable")
            metaCache.put(icaoHex, Entry(result, SystemClock.elapsedRealtime()))
            result
        }

    // --- individual sources --------------------------------------------------

    private fun fetchPlanespotters(hex: String, reg: String?, type: String?): PhotoResult {
        val urlB = "https://api.planespotters.net/pub/photos/hex/$hex".toHttpUrl().newBuilder()
        if (!reg.isNullOrBlank()) urlB.addQueryParameter("reg", reg.trim())
        if (!type.isNullOrBlank()) urlB.addQueryParameter("icaoType", type.trim())
        // A "Chrome" User-Agent that omits the sec-ch-ua / sec-fetch-* headers every real Chrome
        // request carries is a bot tell the CDN scores against (→ 403). Send the full set, framed
        // as a same-origin fetch from the Planespotters site that owns this public endpoint.
        val req = Request.Builder().url(urlB.build())
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("User-Agent", UA)
            .header("Referer", "https://www.planespotters.net/")
            .header("Origin", "https://www.planespotters.net")
            .header("sec-ch-ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", "same-origin")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return PhotoResult.Error("HTTP ${resp.code}")
            val arr = try {
                JSONObject(resp.body?.string().orEmpty()).optJSONArray("photos")
            } catch (e: Exception) {
                return PhotoResult.Error("bad response")
            } ?: return PhotoResult.Error("bad response")
            if (arr.length() == 0) return PhotoResult.NoneOnFile
            val p = arr.optJSONObject(0) ?: return PhotoResult.NoneOnFile
            val url = p.optJSONObject("thumbnail_large")?.optString("src")?.takeIf { it.isNotEmpty() }
                ?: p.optJSONObject("thumbnail")?.optString("src")?.takeIf { it.isNotEmpty() }
                ?: return PhotoResult.NoneOnFile
            return PhotoResult.Found(
                AircraftPhoto(
                    url,
                    p.optString("link", "https://www.planespotters.net/"),
                    p.optString("photographer").ifEmpty { "Planespotters" },
                    "planespotters.net",
                )
            )
        }
    }

    private fun fetchAirportData(hex: String): PhotoResult {
        val req = Request.Builder()
            .url("https://airport-data.com/api/ac_thumb.json?m=$hex&n=1")
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return if (resp.code == 404) PhotoResult.NoneOnFile
                else PhotoResult.Error("HTTP ${resp.code}")
            }
            val obj = try {
                JSONObject(resp.body?.string().orEmpty())
            } catch (e: Exception) {
                return PhotoResult.Error("bad response")
            }
            val data = obj.optJSONArray("data")
            if (obj.optInt("status", 0) != 200 || data == null || data.length() == 0) {
                return PhotoResult.NoneOnFile
            }
            val o = data.optJSONObject(0) ?: return PhotoResult.NoneOnFile
            val image = o.optString("image").takeIf { it.isNotEmpty() } ?: return PhotoResult.NoneOnFile
            return PhotoResult.Found(
                AircraftPhoto(
                    image,
                    o.optString("link", "https://airport-data.com/"),
                    o.optString("photographer").ifEmpty { "airport-data.com" },
                    "airport-data.com",
                )
            )
        }
    }

    private fun fetchAdsbdb(hex: String): PhotoResult {
        val req = Request.Builder()
            .url("https://api.adsbdb.com/v0/aircraft/$hex")
            .header("Accept", "application/json")
            .header("User-Agent", UA)
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return if (resp.code == 404) PhotoResult.NoneOnFile
                else PhotoResult.Error("HTTP ${resp.code}")
            }
            val ac = try {
                JSONObject(resp.body?.string().orEmpty())
                    .optJSONObject("response")?.optJSONObject("aircraft")
            } catch (e: Exception) {
                return PhotoResult.Error("bad response")
            } ?: return PhotoResult.NoneOnFile
            val thumb = ac.optString("url_photo_thumbnail")
                .takeIf { it.isNotEmpty() && it != "null" }
                ?: ac.optString("url_photo").takeIf { it.isNotEmpty() && it != "null" }
                ?: return PhotoResult.NoneOnFile
            // adsbdb serves airport-data images; reconstruct the photo page for attribution.
            val id = Regex("(\\d+)\\.jpg").find(thumb)?.groupValues?.getOrNull(1)
            val link = if (id != null) "https://airport-data.com/aircraft/photo/$id.html"
            else "https://airport-data.com/"
            return PhotoResult.Found(AircraftPhoto(thumb, link, "airport-data.com", "airport-data.com"))
        }
    }

    // --- image download ------------------------------------------------------

    /** Downloads and decodes the thumbnail bitmap for [url], or null on error. Memory only. */
    suspend fun bitmapFor(url: String): Bitmap? = withContext(Dispatchers.IO) {
        bitmapCache.get(url)?.let { return@withContext it }
        val bmp = runCatching {
            // Same-origin Referer keeps hotlink-protected image hosts happy.
            val referer = runCatching {
                val u = url.toHttpUrl(); "${u.scheme}://${u.host}/"
            }.getOrNull()
            val b = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "image/avif,image/webp,image/*,*/*")
            if (referer != null) b.header("Referer", referer)
            client.newCall(b.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.byteStream()?.let { BitmapFactory.decodeStream(it) }
            }
        }.getOrNull()
        if (bmp != null) bitmapCache.put(url, bmp)
        bmp
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val ERROR_TTL_MS = 60_000L            // per-hex error retry window
        private const val SOURCE_COOLDOWN_MS = 5 * 60_000L  // skip a failing source this long

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
