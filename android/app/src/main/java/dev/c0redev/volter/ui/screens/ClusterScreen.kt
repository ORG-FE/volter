package dev.c0redev.volter.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.R
import dev.c0redev.volter.core.CoreBridge
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.components.SectionCard
import dev.c0redev.volter.ui.mesh.MeshPeerNodeMiniCard
import dev.c0redev.volter.ui.mesh.ParsedMeshStatus
import dev.c0redev.volter.ui.mesh.parseMeshStatusJson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
fun ClusterScreen(vm: ConnectionViewModel, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val res = context.resources
    val configDir = remember { File(context.filesDir, "volter").absolutePath }
    val localCfgs = vm.localConfigs.collectAsState().value
    val activeName = vm.activeProfileName.collectAsState().value
    val activeCfg = localCfgs.firstOrNull { it.name == activeName }?.config
    val connection by vm.connection.collectAsState()
    val selectedRouteMode = activeCfg?.protection?.routeMode?.ifBlank { "auto" } ?: "auto"
    val selectedServer = activeCfg?.protection?.clusterPreferredServer
    var parsed by remember { mutableStateOf(parseMeshStatusJson("{}", res)) }
    var refreshing by remember { mutableStateOf(false) }
    val iceRttLabel = stringResource(R.string.mesh_ice_rtt_suffix)
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(Unit) {
        while (true) {
            parsed = parseMeshStatusJson(CoreBridge.meshStatus(), res)
            delay(2000L)
        }
    }

    LaunchedEffect(connection.connected, activeName) {
        val cfg = activeCfg ?: return@LaunchedEffect
        if (!connection.connected) return@LaunchedEffect
        delay(600L)
        withContext(Dispatchers.IO) {
            CoreBridge.refreshClusterServers(cfg.toJson().toString(), configDir)
        }
        parsed = parseMeshStatusJson(CoreBridge.meshStatus(), res)
    }

    fun runClusterPull(fromUser: Boolean) {
        val cfg = activeCfg ?: run {
            if (fromUser) {
                Toast.makeText(context, context.getString(R.string.cluster_need_profile_refresh), Toast.LENGTH_SHORT).show()
            }
            return
        }
        scope.launch {
            refreshing = true
            try {
                val r =
                    withContext(Dispatchers.IO) {
                        CoreBridge.refreshClusterServers(cfg.toJson().toString(), configDir)
                    }
                parsed = parseMeshStatusJson(CoreBridge.meshStatus(), res)
                if (!r.ok) {
                    val msg =
                        r.error?.takeIf { it.isNotBlank() }
                            ?: buildList {
                                if (!r.mapOk) add("map")
                                if (!r.sessionsOk) add("sessions")
                                if (!r.clientsOk) add("clients")
                            }.joinToString().ifBlank { "?" }
                    Toast.makeText(
                        context,
                        context.getString(R.string.cluster_refresh_failed_fmt, msg),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else if (fromUser && !r.serverUsed.isNullOrBlank()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.cluster_refreshed_via_fmt, r.serverUsed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } finally {
                refreshing = false
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { runClusterPull(fromUser = true) },
        state = pullState,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap),
        ) {
            item {
                Text(
                    text = stringResource(R.string.nav_cluster),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        stringResource(
                            if (connection.connected) {
                                R.string.cluster_pool_hint_connected
                            } else {
                                R.string.cluster_pool_hint_disconnected
                            },
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            parsed.coreError?.let { err ->
                item {
                    ErrorBanner(text = stringResource(R.string.mesh_status_core_error_fmt, err))
                }
            }

            item {
                SectionCard {
                    OverviewBlock(parsed = parsed, iceRttSuffix = iceRttLabel)
                }
            }

            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.cluster_route_mode),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf("auto", "direct", "peer_relay", "server_relay").forEach { mode ->
                                FilterChip(
                                    selected = selectedRouteMode == mode,
                                    onClick = {
                                        val name = activeName ?: return@FilterChip
                                        val cfg = activeCfg ?: return@FilterChip
                                        var prot =
                                            (cfg.protection ?: dev.c0redev.volter.domain.model.ProtectionOptions()).copy(routeMode = mode)
                                        if (mode == "server_relay") {
                                            prot = prot.applyClusterDefaults()
                                        }
                                        vm.upsertLocalConfig(name, cfg.copy(protection = prot))
                                    },
                                    label = {
                                        Text(
                                            mode,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                    },
                                    shape = RoundedCornerShape(VolterSpacing.chipRadius),
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    val name = activeName ?: return@FilledTonalButton
                                    val cfg = activeCfg ?: return@FilledTonalButton
                                    val prot =
                                        (cfg.protection ?: dev.c0redev.volter.domain.model.ProtectionOptions())
                                            .applyClusterDefaults()
                                    vm.upsertLocalConfig(name, cfg.copy(protection = prot))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.cluster_fill_defaults_done),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                enabled = activeName != null && activeCfg != null,
                            ) {
                                Text(stringResource(R.string.cluster_fill_defaults))
                            }
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.mesh_nodes_section_title_fmt, parsed.meshNodes.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (parsed.meshNodes.isEmpty()) {
                            Text(
                                text = stringResource(R.string.mesh_nodes_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            parsed.meshNodes.take(48).forEach { n ->
                                MeshPeerNodeMiniCard(node = n)
                                Spacer(Modifier.height(6.dp))
                            }
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.cluster_servers_title_fmt, parsed.clusterNodes.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (parsed.clusterNodes.isEmpty()) {
                            Text(
                                text = stringResource(R.string.cluster_servers_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                parsed.clusterNodes.forEach { row ->
                                    val normalized = clusterRowHostPort(row)
                                    FilterChip(
                                        selected = !selectedServer.isNullOrBlank() && (selectedServer == normalized || selectedServer == row),
                                        onClick = {
                                            val name = activeName ?: return@FilterChip
                                            val cfg = activeCfg ?: return@FilterChip
                                            val prot =
                                                (cfg.protection ?: dev.c0redev.volter.domain.model.ProtectionOptions())
                                                    .copy(
                                                        clusterPreferredServer = canonicalClusterExit(normalized),
                                                        routeMode = "server_relay",
                                                    )
                                            vm.upsertLocalConfig(name, cfg.copy(protection = prot))
                                        },
                                        label = {
                                            Text(
                                                row,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        },
                                        shape = RoundedCornerShape(VolterSpacing.chipRadius),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.cluster_mesh_clients_title_fmt, parsed.clusterClientsLines.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = stringResource(R.string.cluster_clients_source_fmt, parsed.clientsSource),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (parsed.clusterClientsLines.isEmpty()) {
                            Text(
                                text = stringResource(R.string.cluster_mesh_clients_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            SelectionContainer {
                                parsed.clusterClientsLines.take(80).forEach { row ->
                                    Text(
                                        text = stringResource(R.string.cluster_client_line_fmt, row),
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ErrorBanner(text: String) {
    OutlinedCard(
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
            ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun OverviewBlock(parsed: ParsedMeshStatus, iceRttSuffix: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.Lan,
                contentDescription = null,
                modifier = Modifier.padding(end = 2.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.cluster_current_server_fmt, parsed.clusterNodeId),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text =
                if (parsed.iceSrflxRttMs > 0.1) {
                    stringResource(
                        R.string.cluster_overview_rtt_srflx_fmt,
                        parsed.iceSrflxRttMs,
                        iceRttSuffix,
                        parsed.srflx,
                        parsed.storeForwardSent,
                        parsed.storeForwardRecv,
                    )
                } else {
                    stringResource(
                        R.string.cluster_overview_srflx_sf_fmt,
                        parsed.srflx,
                        parsed.storeForwardSent,
                        parsed.storeForwardRecv,
                    )
                },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
        )
        Text(
            text =
                stringResource(
                    R.string.cluster_overview_sync_fmt,
                    ageOrDash(parsed.clusterMapAgeMs),
                    ageOrDash(parsed.clusterSessionsAgeMs),
                    ageOrDash(parsed.clusterClientsAgeMs),
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                stringResource(
                    R.string.cluster_overview_route_fmt,
                    parsed.routeTarget,
                    parsed.routePlan,
                    parsed.activeHop,
                    parsed.lastHopReason,
                ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun ageOrDash(v: Long) = if (v >= 0L) "${v}ms" else "—"

private fun clusterRowHostPort(row: String): String {
    val p = row.indexOf('(')
    val q = row.indexOf(')', p + 1)
    if (p >= 0 && q > p) {
        return row.substring(p + 1, q).trim()
    }
    return row.substringBefore(' ').trim()
}

private fun canonicalClusterExit(raw: String): String {
    val s = raw.trim().substringBefore(" ").trim()
    if (s.isEmpty()) return ""
    // strip http(s):// prefix if present
    val clean = when {
        s.startsWith("https://") -> s.removePrefix("https://")
        s.startsWith("http://") -> s.removePrefix("http://")
        else -> s
    }
    val hostPort = clean.substringBefore('/').trim()
    if (hostPort.startsWith("[")) return hostPort
    val colon = hostPort.lastIndexOf(':')
    if (colon <= 0 || colon >= hostPort.length - 1) return hostPort
    val host = hostPort.substring(0, colon).trim()
    val port = hostPort.substring(colon + 1).trim()
    return "${host.lowercase()}:$port"
}
