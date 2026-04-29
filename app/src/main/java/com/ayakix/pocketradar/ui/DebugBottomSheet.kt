package com.ayakix.pocketradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ayakix.pocketradar.domain.MessageLogEntry
import com.ayakix.pocketradar.domain.MessageStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Console-style debug sheet that shows the live stream of Mode S frames the
 * receiver is picking up, together with running counters. Open from the
 * `SourceControlBar` "Debug" button.
 *
 * Layout:
 *   - Header row with title + Reset button
 *   - One-line summary: total / CRC OK / unique ICAOs / DF distribution
 *   - Scrollable log with newest at the top; each row shows
 *     timestamp, CRC marker (✓/✗), DF + Type Code descriptor,
 *     ICAO (where carried explicitly), and the raw hex
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugBottomSheet(
    entries: List<MessageLogEntry>,
    stats: MessageStats,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Default to "valid only" so the noise the demodulator emits doesn't drown
    // out the real traffic. Toggle OFF to see every candidate (useful for
    // diagnosing why valid frames are missing).
    var validOnly by remember { mutableStateOf(true) }
    val visibleEntries = remember(entries, validOnly) {
        if (validOnly) entries.filter { it.inspection.isValidCrc } else entries
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(fraction = 0.9f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Debug log", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = validOnly,
                    onClick = { validOnly = !validOnly },
                    label = { Text("CRC ✓ only") },
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onReset) { Text("Reset") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            StatsBar(stats)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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

@Composable
private fun StatsBar(stats: MessageStats) {
    val crcRate = if (stats.totalReceived == 0) 0
    else (stats.crcValid * 100 / stats.totalReceived)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "Total ${stats.totalReceived}   CRC ✓ ${stats.crcValid} ($crcRate%)   ICAOs ${stats.uniqueIcaos}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (stats.byDownlinkFormat.isNotEmpty()) {
            Text(
                text = "DF " + stats.byDownlinkFormat
                    .toSortedMap()
                    .entries.joinToString("  ") { "${it.key}:${it.value}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
