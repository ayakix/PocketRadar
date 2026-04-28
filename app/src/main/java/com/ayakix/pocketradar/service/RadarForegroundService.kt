package com.ayakix.pocketradar.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ayakix.pocketradar.MainActivity
import com.ayakix.pocketradar.PocketRadarApp
import com.ayakix.pocketradar.R
import com.ayakix.pocketradar.domain.AircraftStore
import com.ayakix.pocketradar.ui.SourceMode
import com.ayakix.pocketradar.ui.SourceState
import com.ayakix.pocketradar.ui.toMessageSource
import com.ayakix.pocketradar.ui.toMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the ADS-B receive coroutine. Either the
 * captured-fixture replay (`MockMessageSource`) or the live `rtl_tcp` stream
 * (`RtlTcpMessageSource`) feeds the shared [AircraftStore].
 *
 * Lives independently of the Activity so the map keeps updating while the app
 * is in the background or the screen is off — Android requires a foreground
 * service for that, with `FOREGROUND_SERVICE_TYPE_DATA_SYNC` on Android 14+.
 * (We talk to the SDR driver app over a localhost TCP socket; the USB device
 * ownership is in the driver app's process, so `connectedDevice` does not
 * apply. See the manifest comment for the full reasoning.)
 */
class RadarForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectJob: Job? = null
    private var notificationUpdateJob: Job? = null

    private val store: AircraftStore by lazy { (application as PocketRadarApp).aircraftStore }
    private val app: PocketRadarApp get() = application as PocketRadarApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra(EXTRA_MODE)?.toMode() ?: SourceMode.REPLAY
        promoteToForeground(mode)
        startCollecting(mode, startId)
        startNotificationUpdates(mode)
        sourceState.value = SourceState(mode = mode, running = true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collectJob?.cancel()
        notificationUpdateJob?.cancel()
        scope.cancel()
        sourceState.value = SourceState(mode = SourceMode.REPLAY, running = false)
        super.onDestroy()
    }

    private fun startCollecting(mode: SourceMode, startId: Int) {
        collectJob?.cancel()
        val source = mode.toMessageSource(applicationContext)
        collectJob = scope.launch {
            try {
                source.stream().collect { hex ->
                    store.ingest(hex, System.currentTimeMillis())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Stream failed", t)
                app.postError(t.userMessage(mode))
                // stopSelf(startId) — only stops the service if no newer
                // onStartCommand has arrived since this collect was launched.
                // Bare stopSelf() would race against a fresh mode switch.
                stopSelf(startId)
            }
        }
    }

    private fun startNotificationUpdates(mode: SourceMode) {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = scope.launch {
            while (true) {
                delay(2_000)
                val count = store.aircraft.value.size
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification(mode, count))
            }
        }
    }

    /**
     * Promote this service to foreground status (or, if already promoted,
     * refresh the persistent notification with the current mode). Calling
     * `startForeground` repeatedly on a live service is allowed by Android
     * and just updates the notification.
     */
    private fun promoteToForeground(mode: SourceMode) {
        val notification = buildNotification(mode, aircraftCount = 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(mode: SourceMode, aircraftCount: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when (mode) {
            SourceMode.LIVE -> "PocketRadar — receiving via rtl_tcp"
            SourceMode.REPLAY -> "PocketRadar — replaying captured fixture"
        }
        val text = "$aircraftCount aircraft tracked"
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.flight_48px)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ADS-B receiver",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Status of the foreground ADS-B receiver"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun Throwable.userMessage(mode: SourceMode): String = when (mode) {
        SourceMode.LIVE -> "rtl_tcp connection failed: ${this::class.java.simpleName}: ${message ?: "no detail"}"
        SourceMode.REPLAY -> "Replay failed: ${message ?: this::class.java.simpleName}"
    }

    companion object {
        private const val TAG = "RadarFgService"
        const val CHANNEL_ID = "pocketradar.receiver"
        private const val NOTIFICATION_ID = 1001
        const val EXTRA_MODE = "mode"

        /** Read-only state observable by the UI. */
        val sourceState: MutableStateFlow<SourceState> =
            MutableStateFlow(SourceState(mode = SourceMode.REPLAY, running = false))

        fun start(context: Context, mode: SourceMode) {
            val intent = Intent(context, RadarForegroundService::class.java).apply {
                putExtra(EXTRA_MODE, mode.name)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RadarForegroundService::class.java))
        }
    }
}
