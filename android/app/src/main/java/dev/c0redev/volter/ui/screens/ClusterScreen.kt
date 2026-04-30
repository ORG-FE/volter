package dev.c0redev.volter.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
)

@Composable
fun ClusterScreen(vm: ConnectionViewModel, contentPadding: PaddingValues) {
    var state by remember {
        mutableStateOf(
            ClusterViewState("-", emptyList(), emptyList(), "Cluster data will appear after mesh sync"),
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
                            Text(text = "• $row", style = MaterialTheme.typography.bodyMedium)
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
            summary = "srflx: $srflx | store-forward: $sfSent/$sfRecv",
        )
    } catch (_: Exception) {
        ClusterViewState("-", emptyList(), emptyList(), "Mesh status unavailable")
    }
}
