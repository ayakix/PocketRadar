package com.ayakix.pocketradar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ayakix.pocketradar.domain.CoverageRecord
import kotlin.math.cos
import kotlin.math.sin

/**
 * Polar plot of the farthest contact in each bearing sector, drawn over the
 * theoretical radio horizon.
 *
 * Reading it: the filled shape is what the site actually achieved. The dashed
 * circle is the horizon the antenna height and a cruising altitude allow. Where
 * the shape reaches the circle, the site is horizon-limited and no antenna
 * change will help. Where it falls short, something is in the way.
 */
@Composable
fun CoveragePlot(
    sectors: List<CoverageRecord?>,
    horizonKm: Double,
    modifier: Modifier = Modifier,
) {
    val reached = MaterialTheme.colorScheme.primary
    val horizonColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Scale so both the measurements and the horizon ring always fit.
    val maxMeasured = sectors.filterNotNull().maxOfOrNull { it.distanceKm } ?: 0.0
    val scaleKm = maxOf(maxMeasured, horizonKm, 1.0) * 1.08

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                val centre = Offset(size.width / 2, size.height / 2)
                val radius = size.minDimension / 2 * 0.88f

                drawRangeRings(centre, radius, scaleKm, gridColor)
                drawCardinalSpokes(centre, radius, gridColor)

                // 理論地平線。実測がここに届いていれば、その方角は
                // 「これ以上は物理的に無理」と判断できる。
                val horizonRadius = (horizonKm / scaleKm).toFloat() * radius
                drawCircle(
                    color = horizonColor.copy(alpha = 0.85f),
                    radius = horizonRadius,
                    center = centre,
                    style = Stroke(width = 2.dp.toPx()),
                )

                drawCoverage(sectors, centre, radius, scaleKm, reached)
            }

            Text(
                text = "N",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            Text(
                text = "S",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            Text(
                text = "W",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            Text(
                text = "E",
                style = MaterialTheme.typography.labelSmall,
                color = labelColor,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            LegendSwatch("実測カバレッジ", reached)
            LegendSwatch("理論地平線 ${"%.0f".format(horizonKm)} km", horizonColor)
        }
        Text(
            text = "外周 ${"%.0f".format(scaleKm)} km / リング間隔 ${"%.0f".format(scaleKm / RingCount)} km",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = labelColor,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun LegendSwatch(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .size(10.dp)
                .background(color, CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val RingCount = 4

private fun DrawScope.drawRangeRings(
    centre: Offset,
    radius: Float,
    scaleKm: Double,
    color: Color,
) {
    for (ring in 1..RingCount) {
        drawCircle(
            color = color.copy(alpha = 0.35f),
            radius = radius * ring / RingCount,
            center = centre,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

private fun DrawScope.drawCardinalSpokes(centre: Offset, radius: Float, color: Color) {
    for (step in 0 until 8) {
        val angle = Math.toRadians(step * 45.0)
        drawLine(
            color = color.copy(alpha = if (step % 2 == 0) 0.45f else 0.2f),
            start = centre,
            end = centre + polarOffset(angle, radius),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun DrawScope.drawCoverage(
    sectors: List<CoverageRecord?>,
    centre: Offset,
    radius: Float,
    scaleKm: Double,
    color: Color,
) {
    if (sectors.none { it != null }) return
    val sectorCount = sectors.size
    val sectorWidth = 360.0 / sectorCount

    // 実測のない方角は 0 として閉じる。補間して繋ぐと「聞こえていない方角」が
    // 埋まって見えてしまい、遮蔽の発見というこの図の目的を損なう。
    val path = Path()
    var started = false
    for (index in 0..sectorCount) {
        val sector = sectors[index % sectorCount]
        val distance = sector?.distanceKm ?: 0.0
        val angle = Math.toRadians((index % sectorCount) * sectorWidth + sectorWidth / 2)
        val point = centre + polarOffset(angle, (distance / scaleKm).toFloat() * radius)
        if (!started) {
            path.moveTo(point.x, point.y)
            started = true
        } else {
            path.lineTo(point.x, point.y)
        }
    }
    path.close()

    drawPath(path = path, color = color.copy(alpha = 0.28f))
    drawPath(path = path, color = color, style = Stroke(width = 2.dp.toPx()))

    sectors.filterNotNull().forEach { sector ->
        val angle = Math.toRadians(sector.bearingDegrees)
        val point = centre + polarOffset(angle, (sector.distanceKm / scaleKm).toFloat() * radius)
        drawCircle(color = color, radius = 2.5.dp.toPx(), center = point)
    }
}

/**
 * Compass bearing → screen offset. Bearing 0° is north (up) and increases
 * clockwise, which is a quarter turn from the maths convention and flipped in
 * Y because screen coordinates grow downwards.
 */
private fun polarOffset(bearingRadians: Double, radius: Float): Offset = Offset(
    x = (sin(bearingRadians) * radius).toFloat(),
    y = (-cos(bearingRadians) * radius).toFloat(),
)
