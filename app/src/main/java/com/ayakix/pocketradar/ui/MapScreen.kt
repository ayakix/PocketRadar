package com.ayakix.pocketradar.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ayakix.pocketradar.R
import com.ayakix.pocketradar.decoder.Aircraft
import com.ayakix.pocketradar.decoder.IcaoAddress
import com.ayakix.pocketradar.domain.LatLng as DomainLatLng
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * The receiving station's home position. Used as the initial map camera target.
 * Tokyo Bay (~35.85N 139.93E) matches the location of the captured fixture.
 * Phase 3 may swap this for the device's actual location.
 */
private val ReceiverHome = LatLng(35.85, 139.93)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: RadarViewModel) {
    val context = LocalContext.current
    val aircraft by viewModel.aircraft.collectAsState()
    val trails by viewModel.trails.collectAsState()
    val sourceState by viewModel.sourceState.collectAsState()

    // Surface backend errors (e.g. rtl_tcp connection failure) as a toast.
    LaunchedEffect(Unit) {
        viewModel.errors.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(ReceiverHome, 9f)
    }

    var selected by remember { mutableStateOf<IcaoAddress?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val tint = MaterialTheme.colorScheme.primary.toArgb()
    var mapLoaded by remember { mutableStateOf(false) }
    var flightBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var flightIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }

    LaunchedEffect(mapLoaded, tint) {
        if (mapLoaded) {
            flightBitmap?.recycle()
            val bitmap = createTintedBitmap(context, R.drawable.flight_48px, tint)
            flightBitmap = bitmap
            flightIcon = BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }
    DisposableEffect(Unit) {
        onDispose { flightBitmap?.recycle() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            onMapLoaded = { mapLoaded = true },
        ) {
            val icon = flightIcon
            if (icon != null) {
                aircraft.values.forEach { ac ->
                    val lat = ac.latitude
                    val lon = ac.longitude
                    if (lat != null && lon != null) {
                        val markerState = remember(ac.icao) {
                            MarkerState(position = LatLng(lat, lon))
                        }
                        LaunchedEffect(lat, lon) {
                            markerState.position = LatLng(lat, lon)
                        }
                        Marker(
                            state = markerState,
                            title = ac.callsign ?: ac.icao.toString(),
                            snippet = "${ac.altitudeFeet ?: "—"} ft · ${ac.groundSpeedKnots ?: "—"} kt",
                            icon = icon,
                            anchor = Offset(0.5f, 0.5f),
                            rotation = ac.trackDegrees?.toFloat() ?: 0f,
                            flat = true,
                            onClick = {
                                selected = ac.icao
                                false
                            },
                        )
                    }
                }
            }

            trails.forEach { (_, points) ->
                if (points.size >= 2) {
                    Polyline(
                        points = points.map { it.toGoogleLatLng() },
                        color = Color(0xFF00B0FF),
                        width = 6f,
                    )
                }
            }
        }

        SourceControlBar(
            state = sourceState,
            aircraftCount = aircraft.size,
            onReplay = viewModel::startReplay,
            onLive = viewModel::startLive,
            onStop = viewModel::stop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(16.dp),
        )
    }

    val selectedAircraft = selected?.let { aircraft[it] }
    if (selectedAircraft != null) {
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = sheetState,
        ) {
            AircraftDetailSheet(selectedAircraft)
        }
    }
}

@Composable
private fun SourceControlBar(
    state: SourceState,
    aircraftCount: Int,
    onReplay: () -> Unit,
    onLive: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val replaySelected = state.running && state.mode == SourceMode.REPLAY
                val liveSelected = state.running && state.mode == SourceMode.LIVE
                FilterChip(
                    selected = replaySelected,
                    // Guard against re-tapping the already-selected chip:
                    // FilterChip fires onClick on every tap, which would
                    // restart the running source for no reason.
                    onClick = { if (!replaySelected) onReplay() },
                    label = { Text("Replay") },
                )
                FilterChip(
                    selected = liveSelected,
                    onClick = { if (!liveSelected) onLive() },
                    label = { Text("Live (rtl_tcp)") },
                )
                AssistChip(
                    onClick = onStop,
                    label = { Text("Stop") },
                    enabled = state.running,
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
            Text(
                text = if (state.running) "${state.mode.label}: $aircraftCount aircraft tracked"
                else "Idle — pick a source to start",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private val SourceMode.label: String
    get() = when (this) {
        SourceMode.REPLAY -> "Replay"
        SourceMode.LIVE -> "Live"
    }

@Composable
private fun AircraftDetailSheet(ac: Aircraft) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = ac.callsign ?: "Unknown",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "ICAO ${ac.icao}",
            style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider()

        DetailRow("Altitude",      ac.altitudeFeet?.let { "$it ft" })
        DetailRow("Ground speed",  ac.groundSpeedKnots?.let { "$it kt" })
        DetailRow("Track",         ac.trackDegrees?.let { "%.1f°".format(it) })
        DetailRow("Vertical rate", ac.verticalRateFpm?.let { "$it fpm" })
        DetailRow("Position",      formatPosition(ac.latitude, ac.longitude))

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatPosition(lat: Double?, lon: Double?): String? {
    if (lat == null || lon == null) return null
    return "%.4f, %.4f".format(lat, lon)
}

private fun DomainLatLng.toGoogleLatLng(): LatLng = LatLng(latitude, longitude)

private fun createTintedBitmap(
    context: Context,
    @DrawableRes resId: Int,
    @ColorInt tint: Int,
): Bitmap {
    val drawable = ContextCompat.getDrawable(context, resId)
        ?: error("Drawable resource $resId not found")
    drawable.setTint(tint)

    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    drawable.setBounds(0, 0, width, height)

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    drawable.draw(Canvas(bitmap))
    return bitmap
}
