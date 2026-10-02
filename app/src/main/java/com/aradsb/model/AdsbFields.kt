package com.aradsb.model

/**
 * Human-readable metadata for the JSON fields adsb.lol serves (the readsb / ADS-B Exchange v2
 * schema). Used to label the expanded detail panel.
 *
 * Only fields documented in the readsb output schema are given labels/units here; any key the API
 * returns that is not in this table is still shown in the panel, under "Other", with its raw key —
 * so the panel is always complete and never invents a meaning for a field.
 *
 * Notes on provenance: rssi / messages / seen describe the receiving feeder network, not the
 * aircraft. ias / tas / mach / wind / temperature are decoded from Mode S Comm-B (BDS) registers
 * and are only present when the aircraft transmits them.
 */
object AdsbFields {

    enum class Cat(val title: String) {
        ID("Identification"),
        POS("Position & altitude"),
        VEL("Speed & heading"),
        NAV("Selected / autopilot"),
        STATUS("Squawk & status"),
        INTEGRITY("Integrity & accuracy"),
        WX("Air data (derived)"),
        SIGNAL("Reception"),
        OTHER("Other"),
    }

    data class Field(val label: String, val unit: String?, val cat: Cat, val note: String? = null)

    /** Order in which categories are rendered. */
    val order: List<Cat> = listOf(
        Cat.ID, Cat.POS, Cat.VEL, Cat.NAV, Cat.STATUS, Cat.INTEGRITY, Cat.WX, Cat.SIGNAL, Cat.OTHER
    )

    val map: Map<String, Field> = mapOf(
        // Identification
        "hex" to Field("ICAO address", null, Cat.ID, "24-bit ICAO aircraft address (Mode S)"),
        "type" to Field("Data source", null, Cat.ID, "message type, e.g. adsb_icao, mlat, tisb"),
        "flight" to Field("Callsign", null, Cat.ID),
        "r" to Field("Registration", null, Cat.ID),
        "t" to Field("Type code", null, Cat.ID, "ICAO type designator"),
        "desc" to Field("Type", null, Cat.ID),
        "ownOp" to Field("Operator", null, Cat.ID),
        "year" to Field("Year built", null, Cat.ID),
        "category" to Field("Emitter category", null, Cat.ID, "ADS-B category code"),
        "version" to Field("ADS-B version", null, Cat.ID, "0, 1 or 2"),
        "dbFlags" to Field("Database flags", null, Cat.ID, "bitmask: military / interesting / PIA / LADD"),

        // Position & altitude
        "lat" to Field("Latitude", "\u00b0", Cat.POS),
        "lon" to Field("Longitude", "\u00b0", Cat.POS),
        "alt_baro" to Field("Barometric altitude", "ft", Cat.POS, "referenced to 1013.25 hPa"),
        "alt_geom" to Field("Geometric altitude", "ft", Cat.POS, "GNSS, WGS-84 ellipsoid (HAE)"),
        "dst" to Field("Range from query centre", "NM", Cat.POS),
        "dir" to Field("Bearing from query centre", "\u00b0", Cat.POS),
        "seen_pos" to Field("Position age", "s", Cat.POS),
        "rr_lat" to Field("Approx. latitude", "\u00b0", Cat.POS, "last rough position"),
        "rr_lon" to Field("Approx. longitude", "\u00b0", Cat.POS),

        // Speed & heading
        "gs" to Field("Ground speed", "kt", Cat.VEL),
        "ias" to Field("Indicated airspeed", "kt", Cat.VEL),
        "tas" to Field("True airspeed", "kt", Cat.VEL),
        "mach" to Field("Mach", null, Cat.VEL),
        "track" to Field("Track", "\u00b0", Cat.VEL, "true track over ground"),
        "track_rate" to Field("Track rate", "\u00b0/s", Cat.VEL),
        "roll" to Field("Roll", "\u00b0", Cat.VEL, "negative = left"),
        "mag_heading" to Field("Magnetic heading", "\u00b0", Cat.VEL),
        "true_heading" to Field("True heading", "\u00b0", Cat.VEL),
        "baro_rate" to Field("Barometric climb rate", "ft/min", Cat.VEL),
        "geom_rate" to Field("Geometric climb rate", "ft/min", Cat.VEL),

        // Selected / autopilot
        "nav_qnh" to Field("Selected QNH", "hPa", Cat.NAV, "altimeter setting"),
        "nav_altitude_mcp" to Field("Selected altitude (MCP/FCU)", "ft", Cat.NAV),
        "nav_altitude_fms" to Field("Selected altitude (FMS)", "ft", Cat.NAV),
        "nav_heading" to Field("Selected heading", "\u00b0", Cat.NAV),
        "nav_modes" to Field("Autopilot modes", null, Cat.NAV),

        // Squawk & status
        "squawk" to Field("Squawk", null, Cat.STATUS, "Mode 3/A code — 4-digit octal identity"),
        "emergency" to Field("Emergency", null, Cat.STATUS),
        "alert" to Field("Alert", null, Cat.STATUS, "flight-status alert bit"),
        "spi" to Field("SPI", null, Cat.STATUS, "special position identification"),

        // Integrity & accuracy
        "nic" to Field("NIC", null, Cat.INTEGRITY, "Navigation Integrity Category"),
        "rc" to Field("Radius of containment", "m", Cat.INTEGRITY),
        "nic_baro" to Field("NIC baro", null, Cat.INTEGRITY, "barometric-altitude integrity"),
        "nac_p" to Field("NACp", null, Cat.INTEGRITY, "position-accuracy category"),
        "nac_v" to Field("NACv", null, Cat.INTEGRITY, "velocity-accuracy category"),
        "sil" to Field("SIL", null, Cat.INTEGRITY, "Source Integrity Level"),
        "sil_type" to Field("SIL type", null, Cat.INTEGRITY, "per hour / per sample"),
        "gva" to Field("GVA", null, Cat.INTEGRITY, "Geometric Vertical Accuracy"),
        "sda" to Field("SDA", null, Cat.INTEGRITY, "System Design Assurance"),

        // Air data (derived from Mode S BDS registers)
        "oat" to Field("Outside air temperature", "\u00b0C", Cat.WX),
        "tat" to Field("Total air temperature", "\u00b0C", Cat.WX),
        "ws" to Field("Wind speed", "kt", Cat.WX),
        "wd" to Field("Wind direction", "\u00b0", Cat.WX),

        // Reception (feeder network, not the aircraft)
        "messages" to Field("Messages received", null, Cat.SIGNAL),
        "seen" to Field("Last message age", "s", Cat.SIGNAL),
        "rssi" to Field("Signal power", "dBFS", Cat.SIGNAL, "receiver-dependent"),
        "mlat" to Field("MLAT-derived fields", null, Cat.SIGNAL),
        "tisb" to Field("TIS-B-derived fields", null, Cat.SIGNAL),
    )

    fun categoryOf(key: String): Cat = map[key]?.cat ?: Cat.OTHER
}
