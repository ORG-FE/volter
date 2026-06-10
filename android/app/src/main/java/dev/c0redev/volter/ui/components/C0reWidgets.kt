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
    val grid = scheme.outlineVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(scheme.background)
            .border(width = 1.dp, color = scheme.outline, shape = RectangleShape)
            .padding(1.dp),
    ) {
        val w = size.width
        val h = size.height
        // сетка 4x3 ы
        val cols = 4
        val rows = 3
        for (i in 1 until cols) {
            val x = w / cols * i
            drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
        }
        for (i in 1 until rows) {
            val y = h / rows * i
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (values.size < 2) return@Canvas
        val maxV = values.max()
        val minV = values.min()
        val span = (maxV - minV).takeIf { it > 0f } ?: 1f
        val stepX = w / (values.size - 1)
        fun py(v: Float) = h - ((v - minV) / span) * (h - 2f) - 1f
        val line = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val x = stepX * i
            val y = py(v)
            if (i == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, h)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(stepX * (values.size - 1), h)
        fill.close()
        drawPath(path = fill, color = lineColor.copy(alpha = 0.12f))
        drawPath(
            path = line,
            color = lineColor,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
        )
        val lastX = stepX * (values.size - 1)
        val lastY = py(values.last())
        drawRect(
            color = lineColor,
            topLeft = Offset(lastX - 2f, lastY - 2f),
            size = androidx.compose.ui.geometry.Size(4f, 4f),
        )
    }
}
