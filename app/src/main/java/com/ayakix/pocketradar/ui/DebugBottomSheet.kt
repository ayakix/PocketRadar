package com.ayakix.pocketradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ayakix.pocketradar.domain.CoverageRecord
import com.ayakix.pocketradar.domain.MessageLogEntry
import com.ayakix.pocketradar.domain.MessageStats
import com.ayakix.pocketradar.domain.ReceiverPosition
import com.ayakix.pocketradar.domain.feetToMetres
import com.ayakix.pocketradar.domain.radioHorizonKm
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pages of the debug sheet. */
private enum class DebugTab(val label: String) {
    FRAMES("Frames"),
    COVERAGE("Coverage"),
    DIAGNOSTICS("RF 診断"),
}

/**
 * Diagnostic sheet with three views onto the receiver, opened from the
 * `SourceControlBar` "Debug" button:
 *
 *   - **Frames**: console-style stream of Mode S frames plus running counters.
 *   - **Coverage**: how far the site has actually heard, per bearing, against
 *     the theoretical radio horizon.
 *   - **RF 診断**: scripted band scan and gain sweep that explains *why*
 *     nothing is being received.
 *
 * They live behind one entry point because they answer the same question at
 * three different layers — bits, geometry, and radio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugBottomSheet(
    entries: List<MessageLogEntry>,
    stats: MessageStats,
    coverageSectors: List<CoverageRecord?>,
    farthest: CoverageRecord?,
    receiver: ReceiverPosition,
    diagnostics: DiagnosticsState,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onResetCoverage: () -> Unit,
    onStartDiagnostics: () -> Unit,
    onCancelDiagnostics: () -> Unit,
    onExport: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tab by remember { mutableStateOf(DebugTab.FRAMES) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction = 0.92f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                DebugTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        text = { Text(entry.label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            when (tab) {
                DebugTab.FRAMES -> FramesTab(entries, stats, onReset, onExport)
                DebugTab.COVERAGE -> CoverageTab(
                    sectors = coverageSectors,
                    farthest = farthest,
                    receiver = receiver,
                    onReset = onResetCoverage,
                )
                DebugTab.DIAGNOSTICS -> DiagnosticsPanel(
                    state = diagnostics,
                    onStart = onStartDiagnostics,
                    onCancel = onCancelDiagnostics,
                    onExport = onExport,
                )
            }
        }
    }
}

@Composable
private fun FramesTab(
    entries: List<MessageLogEntry>,
    stats: MessageStats,
    onReset: () -> Unit,
    onExport: () -> Unit,
) {
    // Default to "valid only" so the noise the demodulator emits doesn't drown
    // out the real traffic. Toggle OFF to see every candidate (useful for
    // diagnosing why valid frames are missing).
    var validOnly by remember { mutableStateOf(true) }
    val visibleEntries = remember(entries, validOnly) {
        if (validOnly) entries.filter { it.inspection.isValidCrc } else entries
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatsBar(stats)
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = validOnly,
                onClick = { validOnly = !validOnly },
                label = { Text("CRC ✓ only") },
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onReset) { Text("Reset") }
            TextButton(onClick = onExport) { Text("Export") }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ログはターミナルを模した一段暗いパネルに載せ、地の文と視覚的に分離する。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background)
                .padding(12.dp),
        ) {
            when {
                entries.isEmpty() -> Text(
                    text = "No frames received yet — start a source from the control bar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                visibleEntries.isEmpty() -> Text(
                    text = "No CRC-valid frames in the buffer yet. Toggle the filter off to inspect the raw stream.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(
                        visibleEntries,
                        key = { "${it.timestampMillis}-${it.inspection.hex}" },
                    ) { entry -> LogEntryRow(entry) }
                }
            }
        }
    }
}

/**
 * How far the receiver has actually heard, per bearing, against the horizon
 * the site's geometry allows.
 *
 * The horizon reference uses a typical cruising altitude rather than each
 * contact's own: the question the plot answers is "how much of what is
 * physically reachable am I getting", and cruising traffic is what defines
 * that outer limit.
 */
@Composable
private fun CoverageTab(
    sectors: List<CoverageRecord?>,
    farthest: CoverageRecord?,
    receiver: ReceiverPosition,
    onReset: () -> Unit,
) {
    val horizonKm = remember(receiver.heightMetres) {
        radioHorizonKm(receiver.heightMetres, ReferenceCruiseAltitudeFeet.feetToMetres())
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = farthest?.let { "最大受信距離 ${"%.1f".format(it.distanceKm)} km" }
                        ?: "まだ測位済みの機体がありません",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = farthest?.let {
                        "${it.callsign ?: it.icao}  方位 ${"%.0f".format(it.bearingDegrees)}°" +
                            (it.altitudeFeet?.let { alt -> "  $alt ft" } ?: "")
                    } ?: "位置が復号できた機体から順に記録されます",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onReset) { Text("Reset") }
        }

        Text(
            text = receiverLabel(receiver),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        CoveragePlot(sectors = sectors, horizonKm = horizonKm)

        Spacer(Modifier.height(12.dp))
        Text(
            text = "実測が地平線の円に届いている方角は、そこが物理的な上限です。" +
                "内側に大きく凹んでいる方角は、地形や建物で遮られています。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun receiverLabel(receiver: ReceiverPosition): String {
    val source = when (receiver.source) {
        ReceiverPosition.Source.DEVICE_GPS -> "端末 GPS"
        ReceiverPosition.Source.FIXTURE -> "固定値 (フィクスチャ位置)"
    }
    return "受信局 %.4f, %.4f  高さ %.0f m  · %s".format(
        receiver.position.latitude,
        receiver.position.longitude,
        receiver.heightMetres,
        source,
    )
}

/** 地平線計算の基準高度。国際線・国内線の巡航高度に相当する FL350。 */
private const val ReferenceCruiseAltitudeFeet = 35_000

@Composable
private fun StatsBar(stats: MessageStats) {
    val crcRate = if (stats.totalReceived == 0) 0
    else (stats.crcValid * 100 / stats.totalReceived)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatItem("Total", "${stats.totalReceived}")
            StatItem("CRC ✓", "${stats.crcValid} ($crcRate%)", MaterialTheme.colorScheme.primary)
            StatItem("ICAOs", "${stats.uniqueIcaos}", MaterialTheme.colorScheme.secondary)
        }
        if (stats.byDownlinkFormat.isNotEmpty()) {
            Text(
                text = "DF " + stats.byDownlinkFormat
                    .toSortedMap()
                    .entries.joinToString("  ") { "${it.key}:${it.value}" },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
        )
    }
}

@Composable
private fun LogEntryRow(entry: MessageLogEntry) {
    val ins = entry.inspection
    val time = remember(entry.timestampMillis) {
        TimeFormatter.format(Date(entry.timestampMillis))
    }
    val crcMark = if (ins.isValidCrc) "✓" else "✗"
    val crcColor =
        if (ins.isValidCrc) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error

    val descriptor = buildString {
        append("DF=")
        append(ins.downlinkFormat)
        if (ins.typeCode != null) {
            append(" TC=").append(ins.typeCode)
        }
        val label = describeFrame(ins.downlinkFormat, ins.typeCode)
        if (label.isNotEmpty()) {
            append("  ").append(label)
        }
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = time,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$crcMark $descriptor",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = crcColor,
            )
        }
        val icaoText = ins.icao?.toString() ?: "—"
        Text(
            text = "ICAO=$icaoText  ${ins.hex}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val TimeFormatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun describeFrame(df: Int, tc: Int?): String {
    if (df == 17 || df == 18) {
        return when (tc) {
            in 1..4 -> "Identification"
            in 5..8 -> "Surface position"
            in 9..18 -> "Airborne position (baro alt)"
            19 -> "Airborne velocity"
            in 20..22 -> "Airborne position (GNSS alt)"
            in 23..27 -> "Reserved"
            28 -> "Aircraft status"
            29 -> "Target state and status"
            31 -> "Operational status"
            null -> if (df == 18) "TIS-B" else "ADS-B"
            else -> ""
        }
    }
    return when (df) {
        0 -> "Short air-air"
        4 -> "Surveillance (alt)"
        5 -> "Surveillance (ID)"
        11 -> "All-call reply"
        16 -> "Long air-air"
        20 -> "Comm-B (alt)"
        21 -> "Comm-B (ID)"
        24 -> "Comm-D"
        else -> ""
    }
}
