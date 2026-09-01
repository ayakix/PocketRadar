package com.ayakix.pocketradar.domain

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the receiving station is, and how sure we are of it.
 *
 * Every range figure is measured *from* this point, so a wrong fix silently
 * corrupts the whole coverage record. Keeping the origin explicit — rather
 * than assuming a constant — lets the UI say which one is in use.
 */
data class ReceiverPosition(
    val position: LatLng,
    val source: Source,
    /** Antenna height above ground in metres, used for the horizon estimate. */
    val heightMetres: Double = DefaultHeightMetres,
) {
    enum class Source {
        /** Compiled-in fallback matching the captured replay fixture. */
        FIXTURE,

        /** Android's last known GPS/network fix. */
        DEVICE_GPS,
    }

    companion object {
        /**
         * Assumed antenna height when the user has not said otherwise.
         * Roughly a person holding a dongle at a window on a low floor; the
         * horizon estimate is dominated by the aircraft's altitude anyway.
         */
        const val DefaultHeightMetres: Double = 10.0

        /**
         * Tokyo Bay (~35.85N 139.93E) — the location the bundled replay
         * fixture was captured at, so replay distances stay meaningful
         * before a GPS fix arrives.
         */
        val Fixture = ReceiverPosition(
            position = LatLng(35.85, 139.93),
            source = Source.FIXTURE,
        )
    }
}

/**
 * Reads the device's last known position through the platform
 * [LocationManager].
 *
 * Deliberately not the fused location provider: that would pull in
 * `play-services-location` for a single coarse fix that never needs to be
 * live. A receiving station does not move, so one fix at startup is enough
 * and a cached one is fine.
 */
class ReceiverPositionProvider(private val context: Context) {

    private val _position = MutableStateFlow(ReceiverPosition.Fixture)
    val position: StateFlow<ReceiverPosition> = _position.asStateFlow()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Adopt the device's last known fix, if one is available and permitted.
     * Returns true when the stored position changed to a real fix.
     */
    @SuppressLint("MissingPermission") // guarded by hasPermission() above
    fun refresh(): Boolean {
        if (!hasPermission()) return false
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false

        // Take the most recent fix across providers. GPS is the most accurate
        // but goes stale indoors, where the network provider still has one.
        val best: Location? = manager.allProviders
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

        if (best == null) return false
        _position.value = ReceiverPosition(
            position = LatLng(best.latitude, best.longitude),
            source = ReceiverPosition.Source.DEVICE_GPS,
            // Altitude is above the WGS84 ellipsoid and often absent; when we
            // do have it, it is a far better horizon input than the default.
            heightMetres = if (best.hasAltitude()) best.altitude.coerceAtLeast(0.0)
            else ReceiverPosition.DefaultHeightMetres,
        )
        return true
    }
}
