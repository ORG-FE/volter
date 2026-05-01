package dev.c0redev.volter.ui.screens

import android.content.res.Resources
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.R
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.core.CoreBridge
import dev.c0redev.volter.domain.model.PeerTicket
import dev.c0redev.volter.domain.model.RelayOptions
import dev.c0redev.volter.domain.model.VolterMeshDefaults
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.components.VolterGlassDialogDefaults
import dev.c0redev.volter.ui.mesh.MeshRelayEditor
import dev.c0redev.volter.ui.qr.buildQrBitmap
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun MeshScreen(vm: ConnectionViewModel, contentPadding: PaddingValues) {
    val ctx = LocalContext.current
    val local by vm.localConfigs.collectAsState()
    var selected by remember { mutableStateOf(local.firstOrNull()?.name.orEmpty()) }
    LaunchedEffect(local.map { it.name }) {
        if (selected.isNotEmpty() && local.none { it.name == selected }) {
            selected = local.firstOrNull()?.name.orEmpty()
        }
        if (selected.isEmpty() && local.isNotEmpty()) {
            selected = local.first().name
        }
    }
    val item = local.find { it.name == selected }
    var shareTicketTarget by remember { mutableStateOf<Pair<String, dev.c0redev.volter.domain.model.Config>?>(null) }
    var draft by remember { mutableStateOf(RelayOptions()) }
    LaunchedEffect(item) {
        draft = item?.let { VolterMeshDefaults.relayForEditor(it.config.server, it.config.relay) }
            ?: RelayOptions()
    }

    var body by remember { mutableStateOf("{}") }
    var meshSummary by remember { mutableStateOf(ctx.getString(R.string.mesh_stats_empty)) }
    LaunchedEffect(Unit) {
        while (true) {
            val raw = CoreBridge.meshStatus()
            body = prettyMeshJson(raw)
            meshSummary = meshSummaryFrom(raw, ctx.resources)
            delay(2000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(R.string.mesh_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
        )
        Text(
            text = stringResource(R.string.mesh_profile_label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = VolterSpacing.screenHorizontal),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
        ) {
            items(local, key = { it.name }) { it ->
                FilterChip(
                    selected = selected == it.name,
                    onClick = { selected = it.name },
                    label = { Text(it.name) },
                    shape = RoundedCornerShape(VolterSpacing.chipRadius),
                )
            }
        }
        if (item != null) {
            MeshRelayEditor(
                relay = draft,
                onRelayChange = { draft = it },
                modifier = Modifier.padding(horizontal = VolterSpacing.screenHorizontal),
            )
            FilledTonalButton(
                onClick = {
                    vm.upsertLocalConfig(selected, item.config.copy(relay = draft))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(VolterSpacing.screenHorizontal),
            ) {
                Text(stringResource(R.string.mesh_save))
            }
            Button(
                onClick = { shareTicketTarget = selected to item.config.copy(relay = draft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VolterSpacing.screenHorizontal),
            ) {
                Text(stringResource(R.string.mesh_peer_ticket_share_button))
            }
        } else {
            Text(
                text = stringResource(R.string.mesh_add_profile_hint),
                modifier = Modifier.padding(VolterSpacing.screenHorizontal),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            text = stringResource(R.string.mesh_status_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
        )
        Text(
            text = meshSummary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = VolterSpacing.screenHorizontal, vertical = 4.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
        )
    }
    if (shareTicketTarget != null) {
        val (name, cfg) = shareTicketTarget!!
        SharePeerTicketDialog(name = name, cfg = cfg, context = ctx, onDismiss = { shareTicketTarget = null })
    }
}

private fun prettyMeshJson(raw: String): String {
    return try {
        JSONObject(raw).toString(2)
    } catch (_: Exception) {
        raw
    }
}

private fun meshSummaryFrom(raw: String, res: Resources): String {
    return try {
        val j = JSONObject(raw)
        val nodeId = j.optString("clusterNodeId", "").ifBlank { "-" }
        val sent = j.optLong("storeForwardSent", 0L)
        val recv = j.optLong("storeForwardRecv", 0L)
        val nodes = j.optJSONArray("clusterNodes")?.length() ?: 0
        val srflx = j.optString("clientSrflx", "").ifBlank { "-" }
        val csCnt = if (j.has("clusterSessionsCount")) j.optInt("clusterSessionsCount", -1) else -1
        val csNode = j.optString("clusterSessionsNodeId", "").ifBlank { "-" }
        val csTail =
            if (csCnt >= 0) {
                " | cluster resume: node=$csNode count=$csCnt"
            } else {
                ""
            }
        val lastSwitch = run {
            val arr = j.optJSONArray("pathEvents") ?: return@run ""
            for (i in arr.length() - 1 downTo 0) {
                val e = arr.optJSONObject(i) ?: continue
                val kind = e.optString("kind", "")
                if (!kind.contains("switch", ignoreCase = true)) continue
                val note = e.optString("note", "").ifBlank { kind }
                return@run " | last switch: $note"
            }
            ""
        }
        "Cluster node: $nodeId | cluster servers: $nodes | store-forward sent/recv: $sent/$recv | srflx: $srflx$csTail$lastSwitch"
    } catch (_: Exception) {
        res.getString(R.string.cluster_status_unavailable)
    }
}

@Composable
private fun SharePeerTicketDialog(name: String, cfg: dev.c0redev.volter.domain.model.Config, context: Context, onDismiss: () -> Unit) {
    val relay = cfg.relay
    val peerId = relay?.peerId?.takeIf { it.isNotBlank() } ?: "peer-$name"
    val pub = relay?.bootstrapPubKey?.takeIf { it.isNotBlank() } ?: "mesh-key"
    val addrs = buildList {
        add(cfg.server)
        relay?.peerRelayUdpAdvertise?.takeIf { it.isNotBlank() }?.let { add(it) }
        relay?.peerRelayUdpListen?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    val ticket = remember(name, cfg.server, relay?.peerId, relay?.bootstrapPubKey, relay?.peerRelayUdpAdvertise, relay?.peerRelayUdpListen) {
        PeerTicket.create(peerId = peerId, pubKey = pub, addrs = addrs)
    }
    val uri = remember(ticket) { PeerTicket.buildUri(ticket) }
    val img = remember(uri) { buildQrBitmap(uri).asImageBitmap() }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = VolterGlassDialogDefaults.shape(),
        containerColor = VolterGlassDialogDefaults.containerColor(),
        tonalElevation = VolterGlassDialogDefaults.tonalElevation,
        title = { Text(stringResource(R.string.mesh_peer_ticket_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(
                    bitmap = img,
                    contentDescription = stringResource(R.string.mesh_peer_ticket_image_cd),
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                )
                Text(
                    stringResource(R.string.mesh_peer_ticket_peer_id_fmt, ticket.peerId),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, uri)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.mesh_peer_ticket_share_chooser)))
            }) { Text(stringResource(R.string.action_share)) }
        },
        dismissButton = {
            FilledTonalButton(onClick = {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("peer-ticket", uri))
                onDismiss()
            }) { Text(stringResource(R.string.action_copy)) }
        },
    )
}
