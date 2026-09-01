package com.ayakix.pocketradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ayakix.pocketradar.radio.BandResult
import com.ayakix.pocketradar.radio.BandRole
import com.ayakix.pocketradar.radio.DiagnosticsReport
import com.ayakix.pocketradar.radio.Finding
import com.ayakix.pocketradar.radio.GainResult
import com.ayakix.pocketradar.radio.Severity
import com.ayakix.pocketradar.radio.SignalMetrics

/**
 * RF self-test screen: runs the band scan + gain sweep and explains what the
 * numbers mean. Lives inside the debug sheet because it is a diagnostic tool,
 * not part of the normal receiving flow.
 */
@Composable
fun DiagnosticsPanel(
    state: DiagnosticsState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (state) {
            is DiagnosticsState.Idle -> IdleIntro(onStart)
            is DiagnosticsState.Running -> RunningProgress(state, onCancel)
            is DiagnosticsState.Failed -> FailureNotice(state, onStart)
            is DiagnosticsState.Complete -> ReportView(state.report, onStart, onExport)
        }
    }
}

@Composable
private fun IdleIntro(onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "受信できない原因を、ハードウェア構成を変えずに切り分けます。" +
                "ドングルを一時的に別の周波数へ同調させ、帯域外にどれだけ強い電波が" +
                "入っているかを測ったうえで、1090 MHz で利得を振って復調数の変化を見ます。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "所要 約1分。実行中はライブ受信を停止します。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onStart) { Text("RF 診断を実行") }
    }
}

@Composable
private fun RunningProgress(state: DiagnosticsState.Running, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(state.label, style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(
            progress = { state.fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "${state.completed} / ${state.total} ステップ",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCancel) { Text("中止") }
    }
}

@Composable
private fun FailureNotice(state: DiagnosticsState.Failed, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "診断を実行できませんでした",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(state.reason, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "ドングルが接続され、SDR ドライバアプリが起動していることを確認してください。" +
                "先に Live を一度押して USB 権限を通しておくと確実です。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onRetry) { Text("再実行") }
    }
}

@Composable
private fun ReportView(report: DiagnosticsReport, onRerun: () -> Unit, onExport: () -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "チューナー ${report.tunerName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onExport) { Text("Export") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRerun) { Text("再実行") }
            }
        }

        item { SectionTitle("判定") }
        items(report.findings) { finding -> FindingCard(finding) }

        item { SectionTitle("帯域スキャン (同一利得での相対レベル)") }
        item { BandTable(report.bands) }

        item { SectionTitle("ゲインスイープ (1090 MHz)") }
        item { GainTable(report.gains) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun FindingCard(finding: Finding) {
    val accent = finding.severity.accentColor()
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp, end = 10.dp)
                    .size(10.dp)
                    .background(accent, CircleShape),
            )
            Column {
                Text(
                    text = finding.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                )
                Text(
                    text = finding.detail,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Severity.accentColor(): Color = when (this) {
    Severity.CRITICAL -> MaterialTheme.colorScheme.error
    Severity.WARNING -> MaterialTheme.colorScheme.secondary
    Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    Severity.GOOD -> MaterialTheme.colorScheme.primary
}

@Composable
private fun BandTable(bands: List<BandResult>) {
    // 対照チャンネル基準の相対値で見せる。dBFS の絶対値は較正されていないため、
    // 「空きチャンネルより何 dB 高いか」のほうが意味を持つ。
    val reference = bands.filter { it.target.role == BandRole.QUIET_REFERENCE }
        .minOfOrNull { it.metrics.meanLevelDbfs }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        bands.forEach { band ->
            val excess = reference?.let { band.metrics.meanLevelDbfs - it }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = band.target.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = excess?.let { "+${"%.1f".format(it)} dB" } ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = excessColor(excess),
                    )
                }
                LevelBar(excessDb = excess)
                band.target.note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun excessColor(excessDb: Double?): Color = when {
    excessDb == null -> MaterialTheme.colorScheme.onSurfaceVariant
    excessDb >= 25 -> MaterialTheme.colorScheme.error
    excessDb >= 12 -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurface
}

/** Horizontal bar, 0 dB to [BarFullScaleDb] above the quiet reference. */
@Composable
private fun LevelBar(excessDb: Double?) {
    val fraction = ((excessDb ?: 0.0) / BarFullScaleDb).coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .background(excessColor(excessDb)),
        )
    }
}

private const val BarFullScaleDb = 40.0

@Composable
private fun GainTable(gains: List<GainResult>) {
    val peak = gains.maxOfOrNull { it.crcValidFrames } ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            HeaderCell("利得", 0.9f)
            HeaderCell("CRC✓", 1f)
            HeaderCell("候補", 1f)
            HeaderCell("レベル", 1.1f)
            HeaderCell("飽和", 0.9f)
        }
        gains.forEach { gain ->
            val isBest = peak > 0 && gain.crcValidFrames == peak
            Row(verticalAlignment = Alignment.CenterVertically) {
                ValueCell("${"%.0f".format(gain.gainDb)}dB", 0.9f, isBest)
                ValueCell("${gain.crcValidFrames}", 1f, isBest)
                ValueCell("${gain.preambleMatches}", 1f, isBest)
                ValueCell("${"%.0f".format(gain.metrics.meanLevelDbfs)}dBFS", 1.1f, isBest)
                ValueCell(clipLabel(gain.metrics), 0.9f, isBest)
            }
        }
        Text(
            text = "「候補」はプリアンブル検出数。候補が多いのに CRC✓ が伸びない場合、" +
                "検出器がノイズを踏んでいます。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun clipLabel(metrics: SignalMetrics): String =
    if (metrics.clipRate <= 0.0) "—" else "${"%.2f".format(metrics.clipRate * 100)}%"

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, weight: Float) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.weight(weight),
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ValueCell(
    text: String,
    weight: Float,
    highlight: Boolean,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = if (highlight) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.weight(weight),
    )
}
