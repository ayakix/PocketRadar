package com.ayakix.pocketradar.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ayakix.pocketradar.R
import com.ayakix.pocketradar.decoder.Aircraft
import com.ayakix.pocketradar.driver.SdrDriver
import com.ayakix.pocketradar.decoder.IcaoAddress
import com.ayakix.pocketradar.domain.LatLng as DomainLatLng
import com.ayakix.pocketradar.domain.greatCircleDistanceKm
import com.ayakix.pocketradar.domain.initialBearingDegrees
import kotlinx.coroutines.launch
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: RadarViewModel) {
    val context = LocalContext.current

    // Live mode has to go through the SDR driver app: it starts its rtl_tcp
    // listener only when asked via this intent, and the round trip is also
    // what triggers Android's USB permission dialog. We only start collecting
    // once the driver reports success, so a denied dongle shows the driver's
    // own diagnostic instead of a bare socket error.
    val sdrDriverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startLive()
        } else {
            viewModel.reportError(SdrDriver.failureMessage(result.data))
        }
    }
    // 受信局位置は距離・カバレッジ計算の原点になるので、起動時に一度だけ取得する。
    // 拒否されてもフィクスチャ位置にフォールバックするため機能は止まらない。
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        locationGranted = hasLocationPermission(context)
        viewModel.refreshReceiverPosition()
    }
    LaunchedEffect(Unit) {
        viewModel.refreshReceiverPosition()
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
    }

    // RF 診断も Live と同じくドライバの iqsrc:// インテントを経由する。
    // ドライバはクライアント切断で rtl_tcp を終了するため、直前まで Live
    // だった場合ソケットはもう存在せず、インテントで再起動させる必要がある。
    val diagnosticsDriverLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startDiagnostics()
        } else {
            viewModel.onDiagnosticsDriverFailed(SdrDriver.failureMessage(result.data))
        }
    }

    val aircraft by viewModel.aircraft.collectAsState()
    val trails by viewModel.trails.collectAsState()
    val sourceState by viewModel.sourceState.collectAsState()
    val receiver by viewModel.receiverPosition.collectAsState()

    // Surface backend errors (e.g. rtl_tcp connection failure) as a toast.
    LaunchedEffect(Unit) {
        viewModel.errors.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(receiver.position.latitude, receiver.position.longitude),
            9f,
        )
    }

    var selected by remember { mutableStateOf<IcaoAddress?>(null) }
    var debugOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val trailColor = MaterialTheme.colorScheme.primary
    val tint = trailColor.toArgb()
    // 縁取りは surface 色（ライト＝ほぼ白、ダーク＝紺）。ベタ塗りだけだと
    // 機体同士が重なったとき一つの塊に見えるため、輪郭で分離して見せる。
    val outline = MaterialTheme.colorScheme.surface.toArgb()
    var mapLoaded by remember { mutableStateOf(false) }
    var flightBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var flightIcon by remember { mutableStateOf<BitmapDescriptor?>(null) }

    LaunchedEffect(mapLoaded, tint, outline) {
        if (mapLoaded) {
            flightBitmap?.recycle()
            val bitmap = createTintedBitmap(context, R.drawable.flight_48px, tint, outline)
            flightBitmap = bitmap
            flightIcon = BitmapDescriptorFactory.fromBitmap(bitmap)
        }
    }
    DisposableEffect(Unit) {
        onDispose { flightBitmap?.recycle() }
    }

    // ダークテーマ時はレーダー画面らしい夜間スタイルの地図に切り替える。
    val darkTheme = isSystemInDarkTheme()
    val mapProperties = remember(darkTheme, locationGranted) {
        MapProperties(
            mapStyleOptions = if (darkTheme) {
                MapStyleOptions.loadRawResourceStyle(context, R.raw.map_style_dark)
            } else {
                null
            },
            // 現在位置の青ドット。権限が下りるまで有効化すると SecurityException
            // になるため、許可状態と連動させる。
            isMyLocationEnabled = locationGranted,
        )
    }
    val mapUiSettings = remember {
        MapUiSettings(
            // 回転を許すと機体マーカーの向き（真北基準の Track 角）と地図の
            // 向きがずれて読み違えるため、常に北固定にする。
            rotationGesturesEnabled = false,
            // 再センタリングは独自のリセットボタンで行うので標準ボタンは隠す。
            myLocationButtonEnabled = false,
        )
    }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = mapProperties,
            uiSettings = mapUiSettings,
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
                        color = trailColor.copy(alpha = 0.7f),
                        width = 6f,
                    )
                }
            }
        }

        SourceControlBar(
            state = sourceState,
            aircraftCount = aircraft.size,
            onReplay = viewModel::startReplay,
            onLive = {
                try {
                    sdrDriverLauncher.launch(SdrDriver.openIntent())
                } catch (e: ActivityNotFoundException) {
                    viewModel.reportError(SdrDriver.NOT_INSTALLED_MESSAGE)
                }
            },
            onStop = viewModel::stop,
            onDebug = { debugOpen = true },
            modifier = Modifier
                .align(Alignment.TopCenter)
                // statusBarsPadding() pushes the bar below the status bar; without
                // it the overlay slides under the system status bar on edge-to-edge
                // layouts (the default with Material 3 + ComponentActivity).
                .statusBarsPadding()
                .padding(16.dp),
        )

        // 現在位置へ戻すリセットボタン。位置を取り直してからカメラを寄せる。
        SmallFloatingActionButton(
            onClick = {
                viewModel.refreshReceiverPosition()
                val home = viewModel.receiverPosition.value.position
                scope.launch {
                    cameraPositionState.animate(
                        CameraUpdateFactory.newLatLngZoom(
                            LatLng(home.latitude, home.longitude),
                            9f,
                        ),
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.MyLocation, contentDescription = "現在位置に戻る")
        }
    }

    if (debugOpen) {
        val entries by viewModel.logEntries.collectAsState()
        val stats by viewModel.logStats.collectAsState()
        val coverageSectors by viewModel.coverageSectors.collectAsState()
        val farthest by viewModel.farthestContact.collectAsState()
        val diagnostics by viewModel.diagnostics.collectAsState()
        DebugBottomSheet(
            entries = entries,
            stats = stats,
            coverageSectors = coverageSectors,
            farthest = farthest,
            receiver = receiver,
            diagnostics = diagnostics,
            onDismiss = { debugOpen = false },
            onReset = viewModel::resetLog,
            onResetCoverage = viewModel::resetCoverage,
            onStartDiagnostics = {
                scope.launch {
                    viewModel.prepareForDiagnostics()
                    try {
                        diagnosticsDriverLauncher.launch(SdrDriver.openIntent())
                    } catch (e: ActivityNotFoundException) {
                        viewModel.onDiagnosticsDriverFailed(SdrDriver.NOT_INSTALLED_MESSAGE)
                    }
                }
            },
            onCancelDiagnostics = viewModel::cancelDiagnostics,
            onExport = { DebugReportSharer.share(context, viewModel.buildDebugReport()) },
        )
    }

    val selectedAircraft = selected?.let { aircraft[it] }
    if (selectedAircraft != null) {
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = sheetState,
        ) {
            AircraftDetailSheet(selectedAircraft, receiver.position)
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
    onDebug: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
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
                    leadingIcon = { ChipIcon(Icons.Filled.PlayArrow) },
                )
                FilterChip(
                    selected = liveSelected,
                    onClick = { if (!liveSelected) onLive() },
                    label = { Text("Live") },
                    leadingIcon = { ChipIcon(Icons.Filled.Sensors) },
                )
                Spacer(Modifier.weight(1f))
                // Stop / Debug はラベル付きチップだと4つ並べたとき画面幅に収まらず
                // 折り返すため、アイコンのみのボタンにして幅を確保している。
                FilledTonalIconButton(
                    onClick = onStop,
                    enabled = state.running,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(20.dp),
                    )
                }
                FilledTonalIconButton(
                    onClick = onDebug,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.Terminal,
                        contentDescription = "Debug",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            ) {
                StatusDot(running = state.running, live = state.mode == SourceMode.LIVE)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (state.running) "${state.mode.label} — $aircraftCount aircraft tracked"
                    else "Idle — pick a source to start",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChipIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
}

/**
 * 受信状態インジケーター。Live 受信中はレーダーの掃引を思わせるパルス
 * アニメーションで「電波を拾っている」ことを直感的に示す。
 */
@Composable
private fun StatusDot(running: Boolean, live: Boolean) {
    val color = when {
        running && live -> MaterialTheme.colorScheme.secondary
        running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val alpha = if (running) {
        val transition = rememberInfiniteTransition(label = "statusDot")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "statusDotAlpha",
        ).value
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(color, CircleShape),
    )
}

private val SourceMode.label: String
    get() = when (this) {
        SourceMode.REPLAY -> "Replay"
        SourceMode.LIVE -> "Live"
    }

@Composable
private fun AircraftDetailSheet(ac: Aircraft, receiver: DomainLatLng) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.flight_48px),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(32.dp)
                    .rotate(ac.trackDegrees?.toFloat() ?: 0f),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = ac.callsign ?: "Unknown",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "ICAO ${ac.icao}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Altitude", ac.altitudeFeet?.let { "$it" }, "ft", Modifier.weight(1f))
            StatCard("Speed", ac.groundSpeedKnots?.let { "$it" }, "kt", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Track", ac.trackDegrees?.let { "%.1f".format(it) }, "°", Modifier.weight(1f))
            StatCard("V/S", ac.verticalRateFpm?.let { "$it" }, "fpm", Modifier.weight(1f))
        }
        // 受信局からの距離は、その機体が「どこまで届いた実績か」を示す値。
        // カバレッジの記録と同じ大圏距離で計算している。
        val lat = ac.latitude
        val lon = ac.longitude
        val distanceKm = if (lat != null && lon != null) {
            greatCircleDistanceKm(receiver, DomainLatLng(lat, lon))
        } else {
            null
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Range", distanceKm?.let { "%.1f".format(it) }, "km", Modifier.weight(1f))
            StatCard(
                "Bearing",
                if (lat != null && lon != null) {
                    "%.0f".format(initialBearingDegrees(receiver, DomainLatLng(lat, lon)))
                } else {
                    null
                },
                "°",
                Modifier.weight(1f),
            )
        }
        StatCard("Position", formatPosition(ac.latitude, ac.longitude), "", Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String?, unit: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                )
                if (value != null && unit.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}

private fun formatPosition(lat: Double?, lon: Double?): String? {
    if (lat == null || lon == null) return null
    return "%.4f, %.4f".format(lat, lon)
}

private fun DomainLatLng.toGoogleLatLng(): LatLng = LatLng(latitude, longitude)

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

private const val MarkerScale = 0.7f

private fun createTintedBitmap(
    context: Context,
    @DrawableRes resId: Int,
    @ColorInt tint: Int,
    @ColorInt outline: Int,
): Bitmap {
    val drawable = ContextCompat.getDrawable(context, resId)
        ?: error("Drawable resource $resId not found")

    // 素材の 48dp をそのまま描くと地図上で大きすぎるため縮小して描画する
    val width = (drawable.intrinsicWidth * MarkerScale).toInt().coerceAtLeast(1)
    val height = (drawable.intrinsicHeight * MarkerScale).toInt().coerceAtLeast(1)
    // 縁取り幅はアイコンサイズ比で決め、拡大縮小しても見た目が揃うようにする
    val stroke = (width * 0.04f).coerceAtLeast(1.5f)
    val pad = kotlin.math.ceil(stroke).toInt()

    // 縁取り色のシルエットを 8 方向にずらして重ねることで輪郭線を作る。
    // ベクターを単純に拡大すると凹形状で縁の太さが不均一になるための措置。
    drawable.setTint(outline)
    drawable.setBounds(0, 0, width, height)
    val silhouette = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    drawable.draw(Canvas(silhouette))

    val bitmap = Bitmap.createBitmap(width + pad * 2, height + pad * 2, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    for (i in 0 until 8) {
        val angle = Math.PI / 4 * i
        val dx = pad + (kotlin.math.cos(angle) * stroke).toFloat()
        val dy = pad + (kotlin.math.sin(angle) * stroke).toFloat()
        canvas.drawBitmap(silhouette, dx, dy, null)
    }
    silhouette.recycle()

    drawable.setTint(tint)
    drawable.setBounds(pad, pad, pad + width, pad + height)
    drawable.draw(canvas)
    return bitmap
}
