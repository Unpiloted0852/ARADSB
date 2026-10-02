package com.aradsb.time

import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Estimates the phone wall clock's offset from true UTC, because the data-age (staleness)
 * estimate compares a local receive timestamp against the server's HTTP Date header — a
 * phone clock that is 2 s off injects 2 s of marker-lag error (~460 m at 450 kt).
 *
 * Two references, best first:
 *  1. GNSS — [Location.getTime] from the GPS provider is satellite-derived UTC, paired with
 *     [Location.getElapsedRealtimeNanos]. Offset error is at the millisecond level, needs no
 *     network, and the app already receives these fixes.
 *  2. SNTP (RFC 4330) against pool.ntp.org as a fallback before the first GNSS fix; the
 *     classic offset = ((t1−t0)+(t2−t3))/2 with the lowest-round-trip sample of three.
 *
 * trueWallMs(localWallMs) = localWallMs + offset. With no reference yet the offset is 0,
 * i.e. exactly the previous behaviour.
 */
object TrueTime {

    @Volatile private var offsetMs: Long? = null
    @Volatile private var sourceName: String = "none"
    @Volatile private var lastGnssAtElapsed = 0L

    /** Current best clock offset (trueUTC − phoneClock) in ms, or null if no reference yet. */
    val offset: Long? get() = offsetMs

    /** "GNSS", "NTP" or "none" — for the diagnostics row. */
    val source: String get() = sourceName

    /** Convert a local wall-clock timestamp to true UTC ms using the current offset. */
    fun trueWallMs(localWallMs: Long): Long = localWallMs + (offsetMs ?: 0L)

    /**
     * Feed a location fix. Only GPS-provider fixes are used: their time field is set from
     * the satellite signal, whereas network/fused fixes may simply copy the system clock.
     */
    fun onLocation(loc: Location) {
        if (loc.provider != LocationManager.GPS_PROVIDER) return
        val ertNanos = loc.elapsedRealtimeNanos
        if (ertNanos <= 0L) return
        // Wall-clock reading of the phone at the instant of the fix:
        val wallAtFix = System.currentTimeMillis() -
            (SystemClock.elapsedRealtime() - ertNanos / 1_000_000L)
        val off = loc.time - wallAtFix
        if (kotlin.math.abs(off) > 24L * 3600_000L) return   // reject nonsense
        offsetMs = off
        sourceName = "GNSS"
        lastGnssAtElapsed = SystemClock.elapsedRealtime()
    }

    /**
     * One SNTP sync (blocking; call from a background thread). Used only when no recent
     * GNSS reference exists, so GNSS — the better clock — always wins.
     */
    fun syncNtpIfNeeded(host: String = "pool.ntp.org") {
        val gnssFresh = sourceName == "GNSS" &&
            SystemClock.elapsedRealtime() - lastGnssAtElapsed < 3600_000L
        if (gnssFresh) return
        var best: Pair<Long, Long>? = null   // (roundTripMs, offsetMs)
        repeat(3) {
            runCatching { sntpQuery(host) }.getOrNull()?.let { (rtt, off) ->
                if (best == null || rtt < best!!.first) best = rtt to off
            }
        }
        best?.let { (_, off) ->
            if (kotlin.math.abs(off) <= 24L * 3600_000L && sourceName != "GNSS") {
                offsetMs = off
                sourceName = "NTP"
            }
        }
    }

    /** Single RFC 4330 query. Returns (roundTripMs, offsetMs) or throws. */
    private fun sntpQuery(host: String): Pair<Long, Long> {
        val buf = ByteArray(48)
        buf[0] = 0x1B   // LI=0, VN=3, Mode=3 (client)
        DatagramSocket().use { sock ->
            sock.soTimeout = 4000
            val addr = InetAddress.getByName(host)
            val t0 = System.currentTimeMillis()
            writeNtpTimestamp(buf, 40, t0)   // transmit timestamp = our send time
            sock.send(DatagramPacket(buf, buf.size, addr, 123))
            val resp = DatagramPacket(ByteArray(48), 48)
            sock.receive(resp)
            val t3 = System.currentTimeMillis()
            val d = resp.data
            val t1 = readNtpTimestamp(d, 32)  // server receive
            val t2 = readNtpTimestamp(d, 40)  // server transmit
            if (t1 == 0L || t2 == 0L) throw IllegalStateException("empty NTP timestamps")
            val offset = ((t1 - t0) + (t2 - t3)) / 2
            val rtt = (t3 - t0) - (t2 - t1)
            return rtt.coerceAtLeast(0) to offset
        }
    }

    private const val NTP_EPOCH_S = 2_208_988_800L   // 1900 -> 1970

    private fun writeNtpTimestamp(b: ByteArray, off: Int, wallMs: Long) {
        val sec = wallMs / 1000L + NTP_EPOCH_S
        val frac = ((wallMs % 1000L).toDouble() / 1000.0 * 4294967296.0).toLong()
        for (i in 0..3) b[off + i] = (sec ushr (24 - 8 * i) and 0xFF).toByte()
        for (i in 0..3) b[off + 4 + i] = (frac ushr (24 - 8 * i) and 0xFF).toByte()
    }

    private fun readNtpTimestamp(b: ByteArray, off: Int): Long {
        var sec = 0L; var frac = 0L
        for (i in 0..3) sec = (sec shl 8) or (b[off + i].toLong() and 0xFF)
        for (i in 0..3) frac = (frac shl 8) or (b[off + 4 + i].toLong() and 0xFF)
        if (sec == 0L) return 0L
        return (sec - NTP_EPOCH_S) * 1000L + Math.round(frac / 4294967296.0 * 1000.0)
    }
}
