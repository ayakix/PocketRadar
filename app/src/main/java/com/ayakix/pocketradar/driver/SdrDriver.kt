package com.ayakix.pocketradar.driver

import android.content.Intent
import android.net.Uri
import com.ayakix.pocketradar.radio.RtlTcpProtocol

/**
 * Launcher for the external "SDR driver" app (`marto.rtl_tcp_andro`), which
 * owns the USB dongle and serves its I/Q samples over `rtl_tcp`.
 *
 * The driver is **not** a resident server. Its launcher screen deliberately
 * offers no start button; it spins up `rtl_tcp` only when another app asks it
 * to through an `iqsrc://` VIEW intent. Connecting straight to the port
 * without firing that intent first therefore fails with a bare connection
 * refused, no matter how healthy the dongle is.
 *
 * Going through the intent is also what makes Android show the USB permission
 * dialog. That permission is granted per **uid**, not per package name, so
 * reinstalling the driver silently revokes it — another failure mode that only
 * the intent can repair.
 */
object SdrDriver {

    /**
     * Loopback address handed to the driver and used for our own connect.
     * Spelled numerically rather than as `localhost`, because resolving that
     * name can yield `::1` first while the driver binds IPv4 only — a
     * connection refused that has nothing to do with the driver's state.
     */
    const val HOST: String = "127.0.0.1"

    /**
     * Intent asking the driver to open the dongle and serve `rtl_tcp`. The
     * URI carries the same argument string the `rtl_tcp` binary takes on the
     * command line, so the port and sample rate travel with the request.
     */
    fun openIntent(
        host: String = HOST,
        port: Int = RtlTcpProtocol.DEFAULT_PORT,
        sampleRateHz: Int = RtlTcpProtocol.ADSB_SAMPLE_RATE_HZ,
    ): Intent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("iqsrc://-a $host -p $port -s $sampleRateHz"),
    )

    /**
     * Human-readable reason for a non-OK result. The driver reports the
     * underlying libusb / librtlsdr failure in this extra; without it the user
     * would only see that live mode "did not start".
     */
    fun failureMessage(data: Intent?): String =
        data?.getStringExtra(EXTRA_ERROR)
            ?: "SDR driver did not open the dongle (permission denied or cancelled)"

    /** Shown when the driver app is not installed at all. */
    const val NOT_INSTALLED_MESSAGE: String =
        "SDR driver app not installed — live mode needs marto.rtl_tcp_andro"

    private const val EXTRA_ERROR = "detailed_exception_message"
}
