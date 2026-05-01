package dev.c0redev.volter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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

@Preview(showBackground = true)
@Composable
private fun SectionCardPreview() {
    VolterTheme {
        SectionCard {
            Text("Preview card")
        }
    }
}
