package dev.c0redev.volter.ui.screens

import android.content.res.Resources
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.c0redev.volter.domain.model.MeshConfig
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.components.VolterGlassDialogDefaults
import dev.c0redev.volter.ui.components.StyledTextField
import dev.c0redev.volter.ui.qr.buildQrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun MeshScreen(vm: ConnectionViewModel, contentPadding: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var draft by remember { mutableStateOf(MeshConfig()) }
    LaunchedEffect(item) {
        draft = item?.config?.mesh ?: MeshConfig()
    }

    var body by remember { mutableStateOf("{}") }
    var meshSummary by remember { mutableStateOf(ctx.getString(R.string.mesh_stats_empty)) }
    var selfTest by remember { mutableStateOf("") }
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
            MeshConfigQuickEditor(
                mesh = draft,
                onMeshChange = { draft = it },
                modifier = Modifier.padding(horizontal = VolterSpacing.screenHorizontal),
            )
            FilledTonalButton(
                onClick = {
                    vm.upsertLocalConfig(selected, item.config.withMeshKeepingCarryOver(draft))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(VolterSpacing.screenHorizontal),
            ) {
                Text(stringResource(R.string.mesh_save))
            }
            Button(
                onClick = { shareTicketTarget = selected to item.config.withMeshKeepingCarryOver(draft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VolterSpacing.screenHorizontal),
            ) {
                Text(stringResource(R.string.mesh_peer_ticket_share_button))
            }
            FilledTonalButton(
                onClick = {
                    selfTest = "Running self-test..."
                    scope.launch(Dispatchers.IO) {
                        val cfgJson = item.config.withMeshKeepingCarryOver(draft).toJson().toString()
                        val r = CoreBridge.meshSelfTest(cfgJson, 10_000)
                        val warn = if (r.warnings.isEmpty()) "-" else r.warnings.joinToString("; ")
                        val err = r.error ?: "-"
                        val msg =
                            "ok=${r.ok} serverReachable=${r.serverReachable} mode=${r.serverMode.ifBlank { "-" }} " +
                                "serverRelay=${r.serverRelay} peerRelayReady=${r.peerRelayReady} stunOk=${r.stunOk} " +
                                "srflx=${r.stunSrflx.ifBlank { "-" }} warnings=$warn error=$err"
                        withContext(Dispatchers.Main) { selfTest = msg }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = VolterSpacing.screenHorizontal),
            ) {
                Text(stringResource(R.string.mesh_self_test_button))
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
        if (selfTest.isNotBlank()) {
            Text(
                text = selfTest,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(horizontal = VolterSpacing.screenHorizontal, vertical = 4.dp),
            )
        }
    }
    if (shareTicketTarget != null) {
        val (name, cfg) = shareTicketTarget!!
        SharePeerTicketDialog(name = name, cfg = cfg, context = ctx, onDismiss = { shareTicketTarget = null })
    }
}

@Composable
private fun MeshConfigQuickEditor(
    mesh: MeshConfig,
    onMeshChange: (MeshConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MeshSwitchRow(
            title = "Mesh",
            subtitle = "Use cluster, p2p and server relay routing for this profile",
            checked = mesh.enabled,
            onCheckedChange = { onMeshChange(mesh.copy(enabled = it)) },
        )
        MeshSwitchRow(
            title = "Volunteer",
            subtitle = "Allow this client to publish endpoint and forward Volter traffic",
            checked = mesh.volunteer.enabled,
            onCheckedChange = { onMeshChange(mesh.copy(volunteer = mesh.volunteer.copy(enabled = it))) },
        )
        MeshSwitchRow(
            title = "P2P",
            subtitle = "Use healthy volunteer peers before server relay fallback",
            checked = mesh.p2p.enabled,
            onCheckedChange = { onMeshChange(mesh.copy(p2p = mesh.p2p.copy(enabled = it))) },
        )
        MeshSwitchRow(
            title = "Server relay",
            subtitle = "Use Volter servers as fallback path",
            checked = mesh.serverRelay.enabled,
            onCheckedChange = { onMeshChange(mesh.copy(serverRelay = mesh.serverRelay.copy(enabled = it))) },
        )
        MeshSwitchRow(
            title = "STUN",
            subtitle = "Discover public endpoint on the same peer UDP port",
            checked = mesh.stun.enabled,
            onCheckedChange = { onMeshChange(mesh.copy(stun = mesh.stun.copy(enabled = it))) },
        )
        MeshSwitchRow(
            title = "P2P UDP",
            subtitle = "Allow direct UDP peer paths",
            checked = mesh.p2p.useUdp,
            onCheckedChange = { onMeshChange(mesh.copy(p2p = mesh.p2p.copy(useUdp = it))) },
        )
        MeshSwitchRow(
            title = "P2P QUIC",
            subtitle = "Allow QUIC peer paths",
            checked = mesh.p2p.useQuic,
            onCheckedChange = { onMeshChange(mesh.copy(p2p = mesh.p2p.copy(useQuic = it))) },
        )
        MeshSwitchRow(
            title = "P2P TCP",
            subtitle = "Allow TCP peer paths",
            checked = mesh.p2p.useTcp,
            onCheckedChange = { onMeshChange(mesh.copy(p2p = mesh.p2p.copy(useTcp = it))) },
        )
        MeshSwitchRow(
            title = "Publish srflx",
            subtitle = "Publish STUN discovered endpoint when volunteering",
            checked = mesh.stun.publishSrflx,
            onCheckedChange = { onMeshChange(mesh.copy(stun = mesh.stun.copy(publishSrflx = it))) },
        )
        MeshSwitchRow(
            title = "Symmetric NAT punch",
            subtitle = "Send UDP punch bursts through the peer socket",
            checked = mesh.stun.symmetricNatHolePunch,
            onCheckedChange = { onMeshChange(mesh.copy(stun = mesh.stun.copy(symmetricNatHolePunch = it))) },
        )
        MeshSwitchRow(
            title = "Aggressive paths",
            subtitle = "Switch to fallback paths faster after failures",
            checked = mesh.policy.pathAggressive,
            onCheckedChange = { onMeshChange(mesh.copy(policy = mesh.policy.copy(pathAggressive = it))) },
        )
        MeshSwitchRow(
            title = "Gossip discovery",
            subtitle = "Exchange relay and peer hints through gossip peers",
            checked = mesh.discovery.gossipEnabled,
            onCheckedChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(gossipEnabled = it))) },
        )
        StyledTextField(
            value = mesh.volunteer.peerId ?: "",
            onValueChange = { onMeshChange(mesh.copy(volunteer = mesh.volunteer.copy(peerId = it.trim().takeIf(String::isNotBlank)))) },
            label = "peerId",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.volunteer.privateKey ?: "",
            onValueChange = { onMeshChange(mesh.copy(volunteer = mesh.volunteer.copy(privateKey = it.trim().takeIf(String::isNotBlank)))) },
            label = "volunteer privateKey",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.volunteer.udpListen ?: "",
            onValueChange = { onMeshChange(mesh.copy(volunteer = mesh.volunteer.copy(udpListen = it.trim().takeIf(String::isNotBlank)))) },
            label = "volunteer udpListen",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.volunteer.udpAdvertise ?: "",
            onValueChange = { onMeshChange(mesh.copy(volunteer = mesh.volunteer.copy(udpAdvertise = it.trim().takeIf(String::isNotBlank)))) },
            label = "volunteer udpAdvertise",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.volunteer.maxConcurrent.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { onMeshChange(mesh.copy(volunteer = mesh.volunteer.copy(maxConcurrent = intOrZero(it)))) },
            label = "volunteer maxConcurrent",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.volunteer.budgetKbps.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { onMeshChange(mesh.copy(volunteer = mesh.volunteer.copy(budgetKbps = intOrZero(it)))) },
            label = "volunteer budgetKbps",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.p2p.quicServerName ?: "",
            onValueChange = { onMeshChange(mesh.copy(p2p = mesh.p2p.copy(quicServerName = it.trim().takeIf(String::isNotBlank)))) },
            label = "p2p quicServerName",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.serverRelay.bootstrapPubKey ?: "",
            onValueChange = { onMeshChange(mesh.copy(serverRelay = mesh.serverRelay.copy(bootstrapPubKey = it.trim().takeIf(String::isNotBlank)))) },
            label = "bootstrap public key",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.serverRelay.discoveryUrl ?: "",
            onValueChange = { onMeshChange(mesh.copy(serverRelay = mesh.serverRelay.copy(discoveryUrl = it.trim().takeIf(String::isNotBlank)))) },
            label = "server relay discoveryURL",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.serverRelay.allowedClasses.orEmpty().joinToString("\n"),
            onValueChange = { onMeshChange(mesh.copy(serverRelay = mesh.serverRelay.copy(allowedClasses = linesList(it)))) },
            label = "server relay allowedClasses",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.stun.servers.orEmpty().joinToString("\n"),
            onValueChange = { onMeshChange(mesh.copy(stun = mesh.stun.copy(servers = linesList(it)))) },
            label = "stun servers",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.discovery.gossipPeers.orEmpty().joinToString("\n"),
            onValueChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(gossipPeers = linesList(it)))) },
            label = "gossip peers",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.discovery.dhtFindUrls.orEmpty().joinToString("\n"),
            onValueChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(dhtFindUrls = linesList(it)))) },
            label = "dhtFindUrls",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.discovery.dhtRpcSeedPeers.orEmpty().joinToString("\n"),
            onValueChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(dhtRpcSeedPeers = linesList(it)))) },
            label = "dhtRpcSeedPeers",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.discovery.dhtRpcListenUdp ?: "",
            onValueChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(dhtRpcListenUdp = it.trim().takeIf(String::isNotBlank)))) },
            label = "dhtRpcListenUdp",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.discovery.dhtRpcSecret ?: "",
            onValueChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(dhtRpcSecret = it.trim().takeIf(String::isNotBlank)))) },
            label = "dhtRpcSecret",
            modifier = Modifier.fillMaxWidth(),
        )
        StyledTextField(
            value = mesh.discovery.gossipIntervalSec.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(gossipIntervalSec = intOrZero(it)))) },
            label = "gossipIntervalSec",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.discovery.gossipMaxAgeSec.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(gossipMaxAgeSec = intOrZero(it)))) },
            label = "gossipMaxAgeSec",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.discovery.dhtRpcIntervalSec.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(dhtRpcIntervalSec = intOrZero(it)))) },
            label = "dhtRpcIntervalSec",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.discovery.dhtRpcFindK.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(dhtRpcFindK = intOrZero(it)))) },
            label = "dhtRpcFindK",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.discovery.dhtIterativeRounds.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(dhtIterativeRounds = intOrZero(it)))) },
            label = "dhtIterativeRounds",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.discovery.dhtIterativeAlpha.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { onMeshChange(mesh.copy(discovery = mesh.discovery.copy(dhtIterativeAlpha = intOrZero(it)))) },
            label = "dhtIterativeAlpha",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.policy.routeMode,
            onValueChange = { onMeshChange(mesh.copy(policy = mesh.policy.copy(routeMode = it.trim().ifBlank { "auto" }))) },
            label = "policy routeMode",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.policy.maxPeerHops.toString(),
            onValueChange = { onMeshChange(mesh.copy(policy = mesh.policy.copy(maxPeerHops = intOrZero(it).coerceAtLeast(1)))) },
            label = "policy maxPeerHops",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.policy.budgetKbps.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { onMeshChange(mesh.copy(policy = mesh.policy.copy(budgetKbps = intOrZero(it)))) },
            label = "policy budgetKbps",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.policy.pathCooldownMs.takeIf { it > 0 }?.toString().orEmpty(),
            onValueChange = { onMeshChange(mesh.copy(policy = mesh.policy.copy(pathCooldownMs = intOrZero(it)))) },
            label = "policy pathCooldownMs",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        StyledTextField(
            value = mesh.policy.healthMaxAgeSec.toString(),
            onValueChange = { onMeshChange(mesh.copy(policy = mesh.policy.copy(healthMaxAgeSec = intOrZero(it).coerceAtLeast(1)))) },
            label = "policy healthMaxAgeSec",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

@Composable
private fun MeshSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    val ticket = remember(name, cfg.mesh) { buildPeerTicket(cfg) }
    val uri = remember(ticket) { ticket?.let(PeerTicket::buildUri).orEmpty() }
    val img = remember(uri) { if (uri.isNotBlank()) buildQrBitmap(uri).asImageBitmap() else null }
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
                if (img != null) {
                    Image(
                        bitmap = img,
                        contentDescription = stringResource(R.string.mesh_peer_ticket_image_cd),
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    )
                }
                if (ticket == null) {
                    Text(
                        "Cannot share ticket: set peerId, bootstrap public key and udpAdvertise or udpListen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        stringResource(R.string.mesh_peer_ticket_peer_id_fmt, ticket.peerId),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, uri)
                }
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.mesh_peer_ticket_share_chooser)))
            }, enabled = uri.isNotBlank()) { Text(stringResource(R.string.action_share)) }
        },
        dismissButton = {
            FilledTonalButton(onClick = {
                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("peer-ticket", uri))
                onDismiss()
            }, enabled = uri.isNotBlank()) { Text(stringResource(R.string.action_copy)) }
        },
    )
}

private fun buildPeerTicket(cfg: dev.c0redev.volter.domain.model.Config): PeerTicket? {
    val mesh = cfg.mesh
    val peerId = mesh.volunteer.peerId?.trim().takeIf { !it.isNullOrBlank() } ?: return null
    val pub = mesh.serverRelay.bootstrapPubKey?.trim().takeIf { !it.isNullOrBlank() } ?: return null
    val addrs = listOfNotNull(
        mesh.volunteer.udpAdvertise?.trim()?.takeIf { it.isNotBlank() },
        mesh.volunteer.udpListen?.trim()?.takeIf { it.isNotBlank() },
    ).distinct()
    if (addrs.isEmpty()) return null
    return PeerTicket.create(peerId = peerId, pubKey = pub, addrs = addrs)
}

private fun linesList(raw: String): List<String>? {
    return raw.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }
}

private fun intOrZero(raw: String): Int = raw.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
