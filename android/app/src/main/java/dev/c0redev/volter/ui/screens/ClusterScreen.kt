package dev.c0redev.volter.ui.screens

import android.content.res.Resources
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.R
import dev.c0redev.volter.core.CoreBridge
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.components.SectionCard
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private data class ClusterViewState(
    val nodeId: String,
    val servers: List<String>,
    val clients: List<String>,
    val summary: String,
    val clientsSource: String,
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ClusterScreen(vm: ConnectionViewModel, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configDir = remember { File(context.filesDir, "volter").absolutePath }
    val localCfgs = vm.localConfigs.collectAsState().value
    val activeName = vm.activeProfileName.collectAsState().value
    val activeCfg = localCfgs.firstOrNull { it.name == activeName }?.config
    val selectedRouteMode = activeCfg?.protection?.routeMode?.ifBlank { "auto" } ?: "auto"
    val selectedServer = activeCfg?.protection?.clusterPreferredServer
    var state by remember {
        mutableStateOf(
            ClusterViewState(
                "-",
                emptyList(),
                emptyList(),
                context.getString(R.string.cluster_summary_waiting),
                "unknown",
            ),
        )
    }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            state = parseClusterState(CoreBridge.meshStatus(), context.resources)
            delay(2000L)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
        verticalArrangement = Arrangement.spacedBy(VolterSpacing.sectionGap),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.nav_cluster),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(
                    onClick = {
                        val cfg = activeCfg ?: return@Button
                        scope.launch {
                            refreshing = true
                            try {
                                val r = CoreBridge.refreshClusterServers(cfg.toJson().toString(), configDir)
                                state = parseClusterState(CoreBridge.meshStatus(), context.resources)
                                if (!r.ok) {
                                    val msg = r.error?.takeIf { it.isNotBlank() }
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
                                }
                            } finally {
                                refreshing = false
                            }
                        }
                    },
                    enabled = activeCfg != null && !refreshing,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                        }
                        Text(stringResource(R.string.cluster_refresh_servers))
                    }
                }
            }
        }
        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.cluster_current_server_fmt, state.nodeId),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.cluster_clients_source_fmt, state.clientsSource),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.cluster_route_mode),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto", "direct", "peer_relay", "server_relay").forEach { mode ->
                            FilterChip(
                                selected = selectedRouteMode == mode,
                                onClick = {
                                    val name = activeName ?: return@FilterChip
                                    val cfg = activeCfg ?: return@FilterChip
                                    val prot = (cfg.protection ?: dev.c0redev.volter.domain.model.ProtectionOptions()).copy(routeMode = mode)
                                    vm.upsertLocalConfig(name, cfg.copy(protection = prot))
                                },
                                label = { Text(mode) },
                                shape = RoundedCornerShape(VolterSpacing.chipRadius),
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.cluster_servers_title_fmt, state.servers.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.servers.isEmpty()) {
                        Text(
                            text = stringResource(R.string.cluster_servers_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.servers.forEach { row ->
                            val normalized = row.substringAfter("(").substringBefore(")").ifBlank { row.substringBefore(" ").trim() }
                            FilterChip(
                                selected = !selectedServer.isNullOrBlank() && (selectedServer == normalized || selectedServer == row),
                                onClick = {
                                    val name = activeName ?: return@FilterChip
                                    val cfg = activeCfg ?: return@FilterChip
                                    val prot = (cfg.protection ?: dev.c0redev.volter.domain.model.ProtectionOptions())
                                        .copy(
                                            clusterPreferredServer = canonicalClusterExit(normalized),
                                            routeMode = "server_relay",
                                        )
                                    vm.upsertLocalConfig(name, cfg.copy(protection = prot))
                                },
                                label = { Text(row) },
                                shape = RoundedCornerShape(VolterSpacing.chipRadius),
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.cluster_mesh_clients_title_fmt, state.clients.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.clients.isEmpty()) {
                        Text(
                            text = stringResource(R.string.cluster_mesh_clients_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.clients.take(80).forEach { row ->
                            Text(
                                text = stringResource(R.string.cluster_client_line_fmt, row),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun canonicalClusterExit(raw: String): String {
    val s = raw.trim().substringBefore(" ").trim()
    if (s.isEmpty()) return ""
    if (s.startsWith("[")) return s
    val colon = s.lastIndexOf(':')
    if (colon <= 0 || colon >= s.length - 1) return s
    val host = s.substring(0, colon).trim()
    val port = s.substring(colon + 1).trim()
    return "${host.lowercase()}:$port"
}

private fun parseClusterState(raw: String, res: Resources): ClusterViewState {
    return try {
        val j = JSONObject(raw)
        val nodeId = j.optString("clusterNodeId", "").ifBlank { "-" }
        val srflx = j.optString("clientSrflx", "").ifBlank { "-" }
        val sfSent = j.optLong("storeForwardSent", 0L)
        val sfRecv = j.optLong("storeForwardRecv", 0L)
        val servers = buildList {
            val arr = j.optJSONArray("clusterNodes")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "").trim()
                    if (v.isNotBlank()) add(v)
                }
            }
        }
        val clients = buildList {
            val carr = j.optJSONArray("clusterClients")
            if (carr != null) {
                for (i in 0 until carr.length()) {
                    val v = carr.optString(i, "").trim()
                    if (v.isNotBlank()) add(v)
                }
                if (isNotEmpty()) return@buildList
            }
            val arr = j.optJSONArray("nodes")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONObject(i) ?: continue
                    val id = row.optString("id", "").trim()
                    if (id.isBlank()) continue
                    val klass = row.optString("class", "").trim()
                    val ep = row.optString("endpoints", "").trim()
                    val parts = listOf(id, klass, ep).filter { it.isNotBlank() }
                    add(parts.joinToString(" | "))
                }
            }
        }
        ClusterViewState(
            nodeId = nodeId,
            servers = servers,
            clients = clients,
            summary = "srflx: $srflx | store-forward: $sfSent/$sfRecv | mapAge=${j.optLong("clusterMapAgeMs", -1)}ms | sessAge=${j.optLong("clusterSessionsAgeMs", -1)}ms | clientsAge=${j.optLong("clusterClientsAgeMs", -1)}ms | target=${j.optString("routeTarget", "-")} | plan=${j.optString("routePlan", "-")} | hop=${j.optString("activeHop", "-")} | reason=${j.optString("lastHopReason", "-")}",
            clientsSource = j.optString("clientsSource", "unknown"),
        )
    } catch (_: Exception) {
        ClusterViewState(
            "-",
            emptyList(),
            emptyList(),
            res.getString(R.string.cluster_status_unavailable),
            "unknown",
        )
    }
}
