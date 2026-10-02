package com.aradsb.data

import com.aradsb.model.Aircraft
import org.json.JSONObject

/**
 * Parses readsb / ADS-B Exchange v2 style responses ({"ac":[...]}). All sources used by
 * [MultiSourceAdsbRepository] return this schema; "aircraft" is accepted as an alternative
 * array key for readsb-style deployments.
 */
object AdsbParser {

    fun parse(body: String): List<Aircraft> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("ac") ?: root.optJSONArray("aircraft") ?: return emptyList()
        val out = ArrayList<Aircraft>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // A valid position is required to place anything.
            if (!o.has("lat") || !o.has("lon")) continue
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue

            // alt_baro may be the JSON string "ground" or a number.
            val baroRaw = o.opt("alt_baro")
            val onGround = (baroRaw is String) && baroRaw.equals("ground", ignoreCase = true)
            val altBaro = (baroRaw as? Number)?.toDouble()
            val altGeom = if (o.has("alt_geom")) o.optDouble("alt_geom") else null

            val vrate = when {
                o.has("geom_rate") -> o.optDouble("geom_rate")
                o.has("baro_rate") -> o.optDouble("baro_rate")
                else -> null
            }

            out.add(
                Aircraft(
                    hex = o.optString("hex", ""),
                    callsign = o.optStringOrNull("flight"),
                    registration = o.optStringOrNull("r"),
                    typeCode = o.optStringOrNull("t"),
                    typeDesc = o.optStringOrNull("desc"),
                    lat = lat,
                    lon = lon,
                    altGeomFt = altGeom,
                    altBaroFt = altBaro,
                    onGround = onGround,
                    trackDeg = o.optDoubleOrNull("track"),
                    groundSpeedKt = o.optDoubleOrNull("gs"),
                    vertRateFpm = vrate,
                    trackRateDegS = o.optDoubleOrNull("track_rate"),
                    rollDeg = o.optDoubleOrNull("roll"),
                    squawk = o.optStringOrNull("squawk"),
                    emergency = o.optStringOrNull("emergency"),
                    category = o.optStringOrNull("category"),
                    seenPosSec = o.optDoubleOrNull("seen_pos"),
                    rawFields = rawFieldsOf(o),
                )
            )
        }
        return out
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) {
        val d = optDouble(key, Double.NaN); if (d.isNaN()) null else d
    } else null

/** Flatten every key in [o] (in insertion order) into display strings, preserving completeness. */
private fun rawFieldsOf(o: JSONObject): Map<String, String> {
    val map = LinkedHashMap<String, String>(o.length())
    val keys = o.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        map[k] = jsonValueToString(o.opt(k))
    }
    return map
}

private fun jsonValueToString(v: Any?): String = when (v) {
    null, JSONObject.NULL -> ""
    is org.json.JSONArray -> (0 until v.length()).joinToString(", ") { jsonValueToString(v.opt(it)) }
    is JSONObject -> v.toString()
    is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    else -> v.toString()
}
