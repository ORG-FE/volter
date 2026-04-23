package dev.c0redev.volter.ui.components

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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.theme.VolterTheme

@Composable
fun ScreenContainer(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (expandHeight) Modifier.fillMaxHeight() else Modifier)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(20.dp),
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

