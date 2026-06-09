package dev.c0redev.volter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.c0redev.volter.R
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.data.servergeo.serverHostFromField
import dev.c0redev.volter.ui.ConfigItemState

private val pingGood = Color(0xFF6FAE6F)

private fun pingLabelColor(ms: Long?, failed: Boolean, scheme: androidx.compose.material3.ColorScheme): Color {
    if (failed || ms == null) return scheme.error
    return when {
        ms < 85L -> pingGood
        ms < 200L -> scheme.tertiary
        else -> scheme.error
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfigProfileCard(
    item: ConfigItemState,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    primaryBusy: Boolean = false,
    primaryBusyLabel: String = "",
    primaryLabel: String,
    onPrimary: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    importLabel: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val hostLine = serverHostFromField(item.config.server)
    val geo = item.geo

    val cardShape = RectangleShape
    val borderColor = when {
        isActive -> scheme.primary
        else -> scheme.outlineVariant
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, borderColor),
        colors = CardDefaults.cardColors(
            containerColor = scheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(VolterSpacing.cardInner + 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RectangleShape)
                        .background(scheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = geo?.flagEmoji ?: "🌐",
                        fontSize = 28.sp,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Lan,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = scheme.onSurfaceVariant,
                        )
                        Text(
                            text = hostLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (geo != null) {
                        Text(
                            text = "${geo.countryName} · ${geo.asnLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.secondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val pingColor = pingLabelColor(item.pingMs, item.pingFailed, scheme)
                Surface(
                    shape = RectangleShape,
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, pingColor),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Speed,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = pingColor,
                        )
                        Text(
                            text = if (item.pingFailed || item.pingMs == null) {
                                stringResource(R.string.config_card_ping_na)
                            } else {
                                stringResource(R.string.config_card_ping_ms, item.pingMs)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = pingColor,
                        )
                    }
                }
                Tag(
                    text = when {
                        item.probeUncertain -> stringResource(R.string.config_chip_probe_unknown)
                        item.probeOk -> stringResource(R.string.config_chip_probe_ok)
                        else -> stringResource(R.string.config_chip_probe_fail)
                    },
                    kind = when {
                        item.probeUncertain -> TagKind.NEUTRAL
                        item.probeOk -> TagKind.GOOD
                        else -> TagKind.BAD
                    },
                )
                Tag(
                    text = when {
                        item.ipv6Uncertain -> stringResource(R.string.config_chip_ipv6_unknown)
                        item.ipv6Support -> stringResource(R.string.config_chip_ipv6_y)
                        else -> stringResource(R.string.config_chip_ipv6_n)
                    },
                    kind = when {
                        item.ipv6Uncertain -> TagKind.NEUTRAL
                        item.ipv6Support -> TagKind.GOOD
                        else -> TagKind.BAD
                    },
                )
                Tag(text = item.config.transportSummary(), kind = TagKind.NEUTRAL)
                if (item.serverMode.isNotBlank()) {
                    Tag(text = item.serverMode, kind = TagKind.NEUTRAL)
                }
            }

            if (onEdit == null && onDelete == null) {
                if (isActive) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RectangleShape,
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, scheme.primary),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.config_profile_active),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.primary,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onPrimary,
                            enabled = !primaryBusy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RectangleShape,
                        ) {
                            if (primaryBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = if (primaryBusyLabel.isNotBlank()) primaryBusyLabel else primaryLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Text(
                                    text = primaryLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        if (onImport != null && importLabel != null) {
                            FilledTonalButton(
                                onClick = onImport,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RectangleShape,
                            ) {
                                Text(importLabel)
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isActive) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RectangleShape,
                            color = Color.Transparent,
                            border = BorderStroke(1.dp, scheme.primary),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.config_profile_active),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = scheme.primary,
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = onPrimary,
                            enabled = !primaryBusy,
                            modifier = Modifier.weight(1f, fill = true),
                            shape = RectangleShape,
                        ) {
                            if (primaryBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = if (primaryBusyLabel.isNotBlank()) primaryBusyLabel else primaryLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.configs_connect_short),
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    if (onEdit != null) {
                        FilledTonalButton(
                            onClick = onEdit,
                            shape = RectangleShape,
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.config_cd_edit))
                        }
                    }
                    if (onDelete != null) {
                        TextButton(onClick = onDelete) {
                            Text(
                                stringResource(R.string.config_delete),
                                color = scheme.error,
                            )
                        }
                    }
                    if (onShare != null) {
                        FilledTonalButton(
                            onClick = onShare,
                            shape = RectangleShape,
                        ) {
                            Icon(Icons.Outlined.QrCode2, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}
