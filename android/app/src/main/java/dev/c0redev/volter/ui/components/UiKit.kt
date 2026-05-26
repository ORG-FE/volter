package dev.c0redev.volter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.theme.VolterTheme

object VolterGlassDialogDefaults {
    val tonalElevation = 0.dp

    fun shape() = RoundedCornerShape(VolterSpacing.glassRadius)

    @Composable
    fun containerColor(): Color =
        MaterialTheme.colorScheme.surface.copy(alpha = 0.93f)
}

@Composable
fun ScreenContainer(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        scheme.background,
                        scheme.surfaceContainerLowest.copy(alpha = 0.92f),
                    ),
                ),
            )
            .padding(padding)
            .padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
        verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap),
        content = content,
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    expandHeight: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(VolterSpacing.glassRadius)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (expandHeight) Modifier.fillMaxHeight() else Modifier),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = scheme.surface.copy(alpha = 0.48f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandHeight) Modifier.fillMaxSize() else Modifier)
                .padding(VolterSpacing.cardInner),
        ) {
            content()
        }
    }
}

@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    meta: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VolterSpacing.glassRadius),
        color = scheme.primaryContainer.copy(alpha = 0.62f),
        contentColor = scheme.onPrimaryContainer,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.primary.copy(alpha = 0.16f),
                    contentColor = scheme.primary,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer.copy(alpha = 0.78f))
            }
            if (!meta.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = scheme.surface.copy(alpha = 0.55f),
                    contentColor = scheme.onSurface,
                ) {
                    Text(
                        text = meta,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VolterSpacing.glassRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SectionCardPreview() {
    VolterTheme {
        SectionCard {
            Text("Preview card")
        }
    }
}
