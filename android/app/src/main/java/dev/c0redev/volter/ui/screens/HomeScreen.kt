package dev.c0redev.volter.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import dev.c0redev.volter.BuildConfig
import dev.c0redev.volter.R
import dev.c0redev.volter.domain.model.ProtectionOptions
import dev.c0redev.volter.domain.model.ServerTraffic
import dev.c0redev.volter.domain.model.SessionRecord
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.components.PageHeader
import dev.c0redev.volter.ui.components.SectionCard

private data class GuideEntry(
    val title: String,
    val hint: String,
    val steps: List<String>,
    val route: String,
    val icon: ImageVector,
)

@Composable
fun HomeScreen(
    vm: ConnectionViewModel,
    padding: PaddingValues,
    onNavigateToTab: (String) -> Unit = {},
) {
    val conn = vm.connection.collectAsState().value
    val logs = vm.logs.collectAsState().value
    val local = vm.localConfigs.collectAsState().value
    val cloud = vm.cloudConfigs.collectAsState().value
    val cloudLoading = vm.cloudLoading.collectAsState().value
    val cloudProgress = vm.cloudRefreshProgress.collectAsState().value
    val connectingName = vm.connectingProfileName.collectAsState().value
    val activeProfile = vm.activeProfileName.collectAsState().value
    val metrics by vm.metrics.collectAsState()
    val activeCfg = remember(activeProfile, local) {
        activeProfile?.let { n -> local.firstOrNull { it.name == n }?.config }
    }
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    var prevReady by remember { mutableStateOf(false) }

    LaunchedEffect(conn.ready) {
        if (conn.ready && !prevReady) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        prevReady = conn.ready
    }

    val homeScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(homeScroll)
                .padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
            verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap),
        ) {
            PageHeader(
                title = stringResource(R.string.home_title),
                subtitle = buildString {
                    append(stringResource(R.string.home_subtitle))
                    append(" · ")
                    append(stringResource(R.string.home_version_fmt, BuildConfig.VERSION_NAME))
                    if (!activeProfile.isNullOrBlank()) {
                        append(" · ")
                        append(stringResource(R.string.home_last_profile_fmt, activeProfile))
                    }
                },
                icon = Icons.Outlined.Home,
                meta = if (conn.connected) stringResource(R.string.home_connected) else stringResource(R.string.home_no),
            )

            SectionCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val err = conn.error
                    if (!err.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.home_error_fmt, err),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    if (!connectingName.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.home_connecting_profile, connectingName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    val socks = conn.socksListen
                    if (conn.connected && !socks.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(VolterSpacing.chipRadius),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.home_socks_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.home_socks_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = socks,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(socks))
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        },
                                    ) { Text(stringResource(R.string.home_socks_copy)) }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.refreshLocalConfigs()
                                vm.refreshCloudConfigs(true)
                            },
                            enabled = !cloudLoading,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(VolterSpacing.controlRadius),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.home_refresh_cd),
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.home_refresh), modifier = Modifier.padding(start = 8.dp))
                        }
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.disconnect()
                            },
                            enabled = conn.connected,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(VolterSpacing.controlRadius),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Stop,
                                contentDescription = stringResource(R.string.home_disconnect_cd),
                                modifier = Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.home_disconnect), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    if (cloudLoading) {
                        Spacer(modifier = Modifier.height(10.dp))
                        if (cloudProgress > 0f) {
                            LinearProgressIndicator(
                                progress = { cloudProgress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (!conn.connected) {
                        Text(
                            text = stringResource(R.string.disabled_not_connected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (local.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.home_servers_pill_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ServerPillRow(
                            servers = local,
                            activeName = activeProfile,
                            connectingName = connectingName,
                            onSelect = { name, cfg -> vm.connect(name, cfg) },
                        )
                    }
                }
            }

        val serverTraffic by vm.serverTraffic.collectAsState()

        TrafficRoutingCard(
            records = metrics.records,
            serverTraffic = serverTraffic,
            routeMode = activeCfg?.protection?.routeMode?.ifBlank { "auto" } ?: "auto",
            profileName = activeProfile,
            canEditRoute = activeCfg != null && !activeProfile.isNullOrBlank(),
            onRouteMode = { mode ->
                val name = activeProfile ?: return@TrafficRoutingCard
                val cfg = local.firstOrNull { it.name == name }?.config ?: return@TrafficRoutingCard
                val prot = (cfg.protection ?: ProtectionOptions()).copy(routeMode = mode)
                vm.upsertLocalConfig(name, cfg.copy(protection = prot))
            },
            onOpenProtection = { onNavigateToTab("protection") },
            onOpenCluster = { onNavigateToTab("cluster") },
        )

        FilledTonalButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onNavigateToTab("configs")
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(VolterSpacing.chipRadius),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ViewList,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.home_primary_profiles),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        HomeGuideCard(onNavigateToTab = onNavigateToTab)

        SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.home_summary),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    InfoRow(label = stringResource(R.string.home_local_configs), value = local.size.toString())
                    InfoRow(label = stringResource(R.string.home_cloud_configs), value = cloud.size.toString())
                    InfoRow(label = stringResource(R.string.home_logs_buffer), value = logs.size.toString())
                }
            }

        if (logs.isNotEmpty()) {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.home_recent_logs),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    logs.takeLast(5).forEach { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun TrafficRoutingCard(
    records: List<SessionRecord>,
    serverTraffic: ServerTraffic?,
    routeMode: String,
    profileName: String?,
    canEditRoute: Boolean,
    onRouteMode: (String) -> Unit,
    onOpenProtection: () -> Unit,
    onOpenCluster: () -> Unit,
) {
    val last = records.lastOrNull()
    val scheme = MaterialTheme.colorScheme
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.home_traffic_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
            Text(
                text = stringResource(R.string.home_traffic_hint),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_traffic_sessions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    text = records.size.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.primary,
                )
            }
            if (last != null) {
                StatusRow(
                    label = stringResource(R.string.home_traffic_last_profile),
                    value = last.configName,
                )
                StatusRow(
                    label = stringResource(R.string.home_traffic_last_server),
                    value = last.server,
                )
                StatusRow(
                    label = stringResource(R.string.home_traffic_last_duration),
                    value = formatSessionDuration(last),
                )
                StatusRow(
                    label = stringResource(R.string.home_traffic_last_hs),
                    value = if (last.handshakeOk) stringResource(R.string.home_yes) else stringResource(R.string.home_no),
                )
                val rx = if (serverTraffic != null) serverTraffic.rxBytes else last?.rxBytes
                val tx = if (serverTraffic != null) serverTraffic.txBytes else last?.txBytes
                if (rx != null && tx != null) {
                    StatusRow(
                        label = stringResource(R.string.home_traffic_rx),
                        value = formatTrafficBytes(rx),
                    )
                    StatusRow(
                        label = stringResource(R.string.home_traffic_tx),
                        value = formatTrafficBytes(tx),
                    )
                } else if (rx != null) {
                    StatusRow(
                        label = stringResource(R.string.home_traffic_rx),
                        value = formatTrafficBytes(rx),
                    )
                } else if (tx != null) {
                    StatusRow(
                        label = stringResource(R.string.home_traffic_tx),
                        value = formatTrafficBytes(tx),
                    )
                }
                if (!last.trafficCollectError.isNullOrBlank()) {
                    StatusRow(
                        label = stringResource(R.string.home_traffic_collect_err),
                        value = last.trafficCollectError ?: "",
                    )
                }
                if (last.byApp.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.home_traffic_apps),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    last.byApp.take(10).forEach { row ->
                        StatusRow(
                            label = row.label,
                            value = "↓ ${formatTrafficBytes(row.rxBytes)} · ↑ ${formatTrafficBytes(row.txBytes)}",
                        )
                    }
                }
                if (last.routePrefixes.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.home_traffic_routes),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    last.routePrefixes.take(12).forEach { p ->
                        Text(
                            text = "· $p",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                        )
                    }
                }
                if (last.excludePrefixes.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.home_traffic_excludes),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    last.excludePrefixes.take(12).forEach { p ->
                        Text(
                            text = "· $p",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurface,
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.home_traffic_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            if (profileName.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.home_traffic_pick_profile),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.tertiary,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text(
                text = stringResource(R.string.home_traffic_split_hint),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TrafficTargetChip(
                    label = stringResource(R.string.home_traffic_chip_apps),
                    modifier = Modifier.weight(1f),
                    onClick = onOpenProtection,
                )
                TrafficTargetChip(
                    label = stringResource(R.string.home_traffic_chip_sites),
                    modifier = Modifier.weight(1f),
                    onClick = onOpenProtection,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.home_routing_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
            )
            Text(
                text = stringResource(R.string.home_routing_body),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            RouteModePills(
                routeMode = routeMode,
                enabled = canEditRoute,
                onSelect = onRouteMode,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onOpenProtection,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(VolterSpacing.controlRadius),
                ) {
                    Text(stringResource(R.string.home_routing_open_protect))
                }
                TextButton(
                    onClick = onOpenCluster,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(VolterSpacing.controlRadius),
                ) {
                    Text(stringResource(R.string.home_routing_open_cluster))
                }
            }
        }
    }
}

@Composable
private fun RouteModePills(
    routeMode: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val norm = routeMode.ifBlank { "auto" }
    val relaySelected = norm == "peer_relay" || norm == "server_relay"
    val scheme = MaterialTheme.colorScheme
    val modes = listOf(
        Triple("auto", stringResource(R.string.home_route_auto), norm == "auto"),
        Triple("direct", stringResource(R.string.home_route_direct), norm == "direct"),
        Triple("peer_relay", stringResource(R.string.home_route_relay), relaySelected),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VolterSpacing.glassRadius),
        color = scheme.surface.copy(alpha = 0.38f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            modes.forEach { (key, label, selected) ->
                val interaction = remember(key) { MutableInteractionSource() }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(VolterSpacing.chipRadius))
                        .clickable(
                            enabled = enabled,
                            interactionSource = interaction,
                            indication = null,
                        ) { onSelect(key) },
                    shape = RoundedCornerShape(VolterSpacing.chipRadius),
                    color = if (selected) {
                        scheme.primary.copy(alpha = 0.28f)
                    } else {
                        Color.Transparent
                    },
                    border = if (selected) {
                        BorderStroke(1.dp, scheme.primary.copy(alpha = 0.55f))
                    } else {
                        BorderStroke(1.dp, Color.Transparent)
                    },
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 11.dp, horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TrafficTargetChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember(label) { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(VolterSpacing.chipRadius))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        shape = RoundedCornerShape(VolterSpacing.chipRadius),
        color = scheme.surfaceContainerHigh.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
        )
    }
}

private fun formatTrafficBytes(n: Long): String {
    if (n <= 0L) return "0 B"
    var v = n.toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var i = 0
    while (v >= 1024.0 && i < units.lastIndex) {
        v /= 1024.0
        i++
    }
    return if (i == 0) {
        "${n} ${units[i]}"
    } else {
        String.format(Locale.US, "%.1f %s", v, units[i])
    }
}

private fun formatSessionDuration(r: SessionRecord): String {
    val ns = r.durationNs ?: return "—"
    if (ns <= 0L) return "—"
    val sec = ns / 1_000_000_000L
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

@Composable
private fun ServerPillRow(
    servers: List<dev.c0redev.volter.ui.ConfigItemState>,
    activeName: String?,
    connectingName: String?,
    onSelect: (String, dev.c0redev.volter.domain.model.Config) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(VolterSpacing.barPillRadius),
        color = scheme.surfaceContainerLow.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.28f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        servers.forEach { item ->
            val selected = item.name == activeName || item.name == connectingName
            Box(
                modifier = Modifier
                    .size(width = 122.dp, height = 36.dp)
                    .clip(RoundedCornerShape(VolterSpacing.fullPill))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    )
                    .clickable { onSelect(item.name, item.config) }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun HomeGuideCard(onNavigateToTab: (String) -> Unit) {
    val entries = listOf(
        GuideEntry(
            title = stringResource(R.string.nav_configs),
            hint = stringResource(R.string.home_guide_configs_hint),
            steps = stringArrayResource(R.array.guide_configs_steps).toList(),
            route = "configs",
            icon = Icons.AutoMirrored.Outlined.ViewList,
        ),
        GuideEntry(
            title = stringResource(R.string.nav_protection),
            hint = stringResource(R.string.home_guide_protection_hint),
            steps = stringArrayResource(R.array.guide_protection_steps).toList(),
            route = "protection",
            icon = Icons.Outlined.VerifiedUser,
        ),
        GuideEntry(
            title = stringResource(R.string.nav_logs),
            hint = stringResource(R.string.home_guide_logs_hint),
            steps = stringArrayResource(R.array.guide_logs_steps).toList(),
            route = "logs",
            icon = Icons.AutoMirrored.Outlined.Article,
        ),
        GuideEntry(
            title = stringResource(R.string.nav_settings),
            hint = stringResource(R.string.home_guide_settings_hint),
            steps = stringArrayResource(R.array.guide_settings_steps).toList(),
            route = "settings",
            icon = Icons.Outlined.Settings,
        ),
    )
    var openRoute by remember { mutableStateOf<String?>(null) }

    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.home_guide_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.home_guide_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            entries.forEachIndexed { index, e ->
                if (index > 0) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
                val expanded = openRoute == e.route
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                openRoute = if (expanded) null else e.route
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = e.icon,
                            contentDescription = e.title,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = e.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = e.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(
                            modifier = Modifier.padding(start = 34.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            e.steps.forEachIndexed { i, step ->
                                Text(
                                    text = "${i + 1}. $step",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    onNavigateToTab(e.route)
                                    openRoute = null
                                },
                                shape = RoundedCornerShape(VolterSpacing.controlRadius),
                            ) {
                                Text(stringResource(R.string.home_guide_open))
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.home_guide_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
