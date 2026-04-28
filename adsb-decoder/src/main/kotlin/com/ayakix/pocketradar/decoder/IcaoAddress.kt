package com.ayakix.pocketradar.decoder

/**
 * 24-bit ICAO aircraft address. Wraps an Int so we get a meaningful toString
 * (six uppercase hex digits, matching dump1090 / FlightAware) without paying
 * for boxing on the hot path.
 */
@JvmInline
value class IcaoAddress(val value: Int) {

    init {
        require(value in 0..0xFFFFFF) {
            "ICAO address must fit in 24 bits, got 0x${value.toString(16)}"
        }
    }

    override fun toString(): String = "%06X".format(value)
}
