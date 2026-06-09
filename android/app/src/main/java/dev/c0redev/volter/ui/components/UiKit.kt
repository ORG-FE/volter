package dev.c0redev.volter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.theme.VolterTheme

object VolterGlassDialogDefaults {
    val tonalElevation = 0.dp

    fun shape() = RectangleShape

    @Composable
    fun containerColor(): Color = MaterialTheme.colorScheme.surface
}

@Composable
fun ScreenContainer(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(padding)
            .padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
        verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap),
        content = content,
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    expandHeight: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (expandHeight) Modifier.fillMaxHeight() else Modifier),
        shape = RectangleShape,
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outline),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (!title.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(scheme.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = title.uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                    )
                }
            }
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
        shape = RectangleShape,
        color = scheme.surfaceVariant,
        contentColor = scheme.onSurface,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, scheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onPrimaryContainer,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (!meta.isNullOrBlank()) {
                Tag(text = meta, kind = TagKind.ACCENT)
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
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
    )
}

enum class TagKind { NEUTRAL, GOOD, WARN, BAD, ACCENT }

@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    kind: TagKind = TagKind.NEUTRAL,
) {
    val scheme = MaterialTheme.colorScheme
    val color = when (kind) {
        TagKind.NEUTRAL -> scheme.onSurfaceVariant
        TagKind.GOOD -> Color(0xFF6FAE6F)
        TagKind.WARN -> scheme.tertiary
        TagKind.BAD -> scheme.error
        TagKind.ACCENT -> scheme.primary
    }
    Surface(
        modifier = modifier,
        shape = RectangleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, color),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
fun StatusDot(
    on: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val c = if (on) Color(0xFF6FAE6F) else scheme.error
    Box(
        modifier = modifier
            .size(8.dp)
            .background(c)
            .border(BorderStroke(1.dp, c), RectangleShape),
    )
}

@Composable
fun KvRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor ?: scheme.onSurface,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SectionCardPreview() {
    VolterTheme {
        SectionCard(title = "Секция") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KvRow("ключ", "значение")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("stable", kind = TagKind.GOOD)
                    Tag("beta", kind = TagKind.WARN)
                    Tag("down", kind = TagKind.BAD)
                }
            }
        }
    }
}
