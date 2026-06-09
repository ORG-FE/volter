package dev.c0redev.volter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class MetricCell(
    val label: String,
    val value: String,
    val accent: Boolean = false,
)

@Composable
fun MetricsTable(
    rows: List<MetricCell>,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, cell ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (i % 2 == 0) scheme.surface else scheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = cell.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = cell.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (cell.accent) scheme.primary else scheme.onSurface,
                )
            }
        }
    }
}

@Composable
fun StatusBar(
    connected: Boolean,
    version: String,
    uptime: String,
    mode: String?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.surfaceVariant)
            .border(width = 1.dp, color = scheme.outline, shape = RectangleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(on = connected)
        Text(
            text = if (connected) "UP" else "DOWN",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (connected) Color(0xFF6FAE6F) else scheme.error,
        )
        if (!mode.isNullOrBlank()) {
            Text(
                text = mode.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Text(
            text = uptime,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "v$version",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
) {
    val scheme = MaterialTheme.colorScheme
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(scheme.background)
            .border(width = 1.dp, color = scheme.outlineVariant, shape = RectangleShape)
            .padding(4.dp),
    ) {
        if (values.size < 2) return@Canvas
        val maxV = values.max()
        val minV = values.min()
        val span = (maxV - minV).takeIf { it > 0f } ?: 1f
        val w = size.width
        val h = size.height
        val stepX = w / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = stepX * i
            val y = h - ((v - minV) / span) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        )
        val lastX = stepX * (values.size - 1)
        val lastY = h - ((values.last() - minV) / span) * h
        drawCircle(color = lineColor, radius = 3f, center = Offset(lastX, lastY))
    }
}
