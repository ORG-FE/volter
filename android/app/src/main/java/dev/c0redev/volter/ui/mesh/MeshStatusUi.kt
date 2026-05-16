package dev.c0redev.volter.ui.mesh

import android.content.res.Resources
import dev.c0redev.volter.R
import org.json.JSONObject

data class MeshPeerNodeUi(
    val id: String,
    val role: String,
    val status: String,
    val endpointsShort: String,
)

data class ParsedMeshStatus(
    val coreError: String?,
    val clusterNodeId: String,
    val iceSrflxRttMs: Double,
    val srflx: String,
    val storeForwardSent: Long,
    val storeForwardRecv: Long,
    val routeTarget: String,
    val routePlan: String,
    val activeHop: String,
    val lastHopReason: String,
    val clusterMapAgeMs: Long,
    val clusterSessionsAgeMs: Long,
    val clusterClientsAgeMs: Long,
    val clientsSource: String,
    val clusterNodes: List<String>,
    val clusterClientsLines: List<String>,
    val meshNodes: List<MeshPeerNodeUi>,
    val serversSummary: String,
    val clientsSummary: String,
)

fun parseMeshStatusJson(raw: String, res: Resources): ParsedMeshStatus {
    return try {
        val j = JSONObject(raw)
        if (j.has("error")) {
            val err = j.optString("error", "").trim().ifBlank { null }
            return emptyParsed(res, err)
        }
        val nodeId = j.optString("clusterNodeId", "").ifBlank { "-" }
        val iceRtt = runCatching { j.getDouble("iceSrflxRttEwmaMs") }.getOrDefault(0.0)
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
        val clients = buildClientsList(j)
        val meshNodes = parseMeshPeerNodes(j)
        ParsedMeshStatus(
            coreError = null,
            clusterNodeId = nodeId,
            iceSrflxRttMs = iceRtt,
            srflx = srflx,
            storeForwardSent = sfSent,
            storeForwardRecv = sfRecv,
            routeTarget = j.optString("routeTarget", "").ifBlank { "-" },
            routePlan = j.optString("routePlan", "").ifBlank { "-" },
            activeHop = j.optString("activeHop", "").ifBlank { "-" },
            lastHopReason = j.optString("lastHopReason", "").ifBlank { "-" },
            clusterMapAgeMs = j.optLong("clusterMapAgeMs", -1L),
            clusterSessionsAgeMs = j.optLong("clusterSessionsAgeMs", -1L),
            clusterClientsAgeMs = j.optLong("clusterClientsAgeMs", -1L),
            clientsSource = j.optString("clientsSource", "unknown"),
            clusterNodes = servers,
            clusterClientsLines = clients,
            meshNodes = meshNodes,
            serversSummary = "${servers.size} srv",
            clientsSummary = "${clients.size} cli",
        )
    } catch (_: Exception) {
        emptyParsed(res, null)
    }
}

private fun emptyParsed(res: Resources, err: String?): ParsedMeshStatus =
    ParsedMeshStatus(
        coreError = err ?: res.getString(R.string.cluster_status_unavailable),
        clusterNodeId = "-",
        iceSrflxRttMs = 0.0,
        srflx = "-",
        storeForwardSent = 0L,
        storeForwardRecv = 0L,
        routeTarget = "-",
        routePlan = "-",
        activeHop = "-",
        lastHopReason = "-",
        clusterMapAgeMs = -1L,
        clusterSessionsAgeMs = -1L,
        clusterClientsAgeMs = -1L,
        clientsSource = "unknown",
        clusterNodes = emptyList(),
        clusterClientsLines = emptyList(),
        meshNodes = emptyList(),
        serversSummary = "0 srv",
        clientsSummary = "0 cli",
    )

private fun buildClientsList(j: JSONObject): List<String> {
    return buildList {
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
}

private fun parseMeshPeerNodes(j: JSONObject): List<MeshPeerNodeUi> {
    val arr = j.optJSONArray("nodes") ?: return emptyList()
    val out = ArrayList<MeshPeerNodeUi>(arr.length())
    for (i in 0 until arr.length()) {
        val row = arr.optJSONObject(i) ?: continue
        val id = row.optString("id", "").trim()
        if (id.isBlank()) continue
        val role = row.optString("class", "").trim().ifBlank { "—" }
        val ep = row.optString("endpoints", "").trim()
        val quic = row.optString("quic", "").trim()
        val srflx = row.optString("srflx", "").trim()
        val status =
            when {
                ep.isNotBlank() -> "ok"
                srflx.isNotBlank() -> "srflx"
                quic.isNotBlank() -> "quic"
                else -> "idle"
            }
        val shortened = ep.take(52).let { s -> if (ep.length > 52) "$s…" else s }
        out.add(
            MeshPeerNodeUi(
                id = id,
                role = role,
                status = status,
                endpointsShort = shortened.ifBlank { "—" },
            ),
        )
    }
    return out
}
