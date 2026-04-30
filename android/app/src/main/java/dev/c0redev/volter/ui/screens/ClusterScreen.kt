package dev.c0redev.volter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.R
import dev.c0redev.volter.core.CoreBridge
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.components.SectionCard
import kotlinx.coroutines.delay
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
    val localCfgs = vm.localConfigs.collectAsState().value
    val activeName = vm.activeProfileName.collectAsState().value
    val activeCfg = localCfgs.firstOrNull { it.name == activeName }?.config
    val selectedRouteMode = activeCfg?.protection?.routeMode?.ifBlank { "auto" } ?: "auto"
    val selectedServer = activeCfg?.protection?.clusterPreferredServer
    var state by remember {
        mutableStateOf(
            ClusterViewState("-", emptyList(), emptyList(), "Cluster data will appear after mesh sync", "unknown"),
        )
    }
    LaunchedEffect(Unit) {
        while (true) {
            state = parseClusterState(CoreBridge.meshStatus())
            delay(2000L)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Cluster",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Current server: ${state.nodeId}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Clients source: ${state.clientsSource}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Route mode", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                        text = "Cluster servers (${state.servers.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.servers.isEmpty()) {
                        Text(
                            text = "No servers discovered yet",
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
                                        .copy(clusterPreferredServer = normalized)
                                    vm.upsertLocalConfig(name, cfg.copy(protection = prot))
                                },
                                label = { Text(row) },
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
                        text = "Mesh clients (${state.clients.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.clients.isEmpty()) {
                        Text(
                            text = "No client nodes in DHT snapshot yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.clients.take(80).forEach { row ->
                            Text(text = "• $row", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

private fun parseClusterState(raw: String): ClusterViewState {
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
        ClusterViewState("-", emptyList(), emptyList(), "Mesh status unavailable", "unknown")
    }
}
