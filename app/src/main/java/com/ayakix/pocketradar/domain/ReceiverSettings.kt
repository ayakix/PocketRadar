package com.ayakix.pocketradar.domain

import android.content.Context
import com.ayakix.pocketradar.radio.RtlTcpProtocol

/**
 * Small persisted settings for the receiver, backed by SharedPreferences.
 *
 * Exists so the RF diagnostics result can carry over into normal use: the
 * gain sweep finds the gain that decodes best *at this site*, and pinning
 * the live tuner to it beats the one-size-fits-all maximum-gain default
 * (field data from Haneda: 44.5 dB decoded twice as much as 49.6 dB).
 */
class ReceiverSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Tuner gain (tenths of a dB) for live reception. Written by the
     * diagnostics flow when a sweep finds a best-performing gain; read every
     * time a live source is constructed.
     */
    var liveTunerGainTenthsDb: Int
        get() = prefs.getInt(KEY_LIVE_GAIN, RtlTcpProtocol.ADSB_TUNER_GAIN_TENTHS_DB)
        set(value) {
            prefs.edit().putInt(KEY_LIVE_GAIN, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "receiver_settings"
        const val KEY_LIVE_GAIN = "live_tuner_gain_tenths_db"
    }
}
