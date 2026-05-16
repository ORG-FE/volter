package dev.c0redev.volter.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
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
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.R
import dev.c0redev.volter.theme.VolterSpacing
import dev.c0redev.volter.core.CoreBridge
import dev.c0redev.volter.domain.model.PeerTicket
import dev.c0redev.volter.domain.model.MeshConfig
import dev.c0redev.volter.ui.ConnectionViewModel
import dev.c0redev.volter.ui.mesh.MeshPeerNodeMiniCard
import dev.c0redev.volter.ui.mesh.parseMeshStatusJson
import dev.c0redev.volter.ui.components.VolterGlassDialogDefaults
import dev.c0redev.volter.ui.components.StyledTextField
import dev.c0redev.volter.ui.components.SectionCard
import dev.c0redev.volter.ui.qr.buildQrBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun MeshScreen(vm: ConnectionViewModel, contentPadding: PaddingValues) {
    val ctx = LocalContext.current
    val res = ctx.resources
    val scope = rememberCoroutineScope()
    val configDir = remember { File(ctx.filesDir, "volter").absolutePath }
    val scroll = rememberScrollState()
    val local by vm.localConfigs.collectAsState()
    val connection by vm.connection.collectAsState()
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

    var parsed by remember { mutableStateOf(parseMeshStatusJson("{}", res)) }
    var selfTest by remember { mutableStateOf("") }
    var advancedExpanded by remember { mutableStateOf(false) }
    var rawExpanded by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var lastRawSnap by remember { mutableStateOf("{}") }
    val pullState = rememberPullToRefreshState()
    LaunchedEffect(Unit) {
        while (true) {
            val raw = CoreBridge.meshStatus()
            lastRawSnap = raw
            parsed = parseMeshStatusJson(raw, res)
            delay(2000L)
        }
    }

    LaunchedEffect(connection.connected, item?.name) {
        val cfg = item?.config ?: return@LaunchedEffect
        if (!connection.connected) return@LaunchedEffect
        delay(600L)
        withContext(Dispatchers.IO) {
            CoreBridge.refreshClusterServers(cfg.toJson().toString(), configDir)
        }
        val raw = CoreBridge.meshStatus()
        lastRawSnap = raw
        parsed = parseMeshStatusJson(raw, res)
    }

    fun runPull() {
        val cfg = item?.config ?: run {
            Toast.makeText(ctx, ctx.getString(R.string.cluster_need_profile_refresh), Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            refreshing = true
            try {
                withContext(Dispatchers.IO) {
                    CoreBridge.refreshClusterServers(cfg.toJson().toString(), configDir)
                }
                val rawRefresh = CoreBridge.meshStatus()
                lastRawSnap = rawRefresh
                parsed = parseMeshStatusJson(rawRefresh, res)
            } finally {
                refreshing = false
            }
        }
    }

    val iceSuffix = stringResource(R.string.mesh_ice_rtt_suffix)

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { runPull() },
        state = pullState,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = VolterSpacing.screenHorizontal, vertical = VolterSpacing.screenVertical),
        ) {
            Text(
                text = stringResource(R.string.mesh_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    stringResource(
                        if (connection.connected) R.string.cluster_pool_hint_connected else R.string.cluster_pool_hint_disconnected,
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = stringResource(R.string.mesh_profile_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp),
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
                MeshSwitchRow(
                    title = stringResource(R.string.mesh_title),
                    subtitle = stringResource(R.string.mesh_switch_main_sub),
                    checked = draft.enabled,
                    onCheckedChange = { draft = draft.copy(enabled = it) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalButton(
                        onClick = {
                            vm.upsertLocalConfig(selected, item.config.withMeshKeepingCarryOver(draft))
                        },
                    ) {
                        Text(stringResource(R.string.mesh_save))
                    }
                }
                Button(
                    onClick = { shareTicketTarget = selected to item.config.withMeshKeepingCarryOver(draft) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.mesh_peer_ticket_share_button))
                }
                FilledTonalButton(
                    onClick = {
                        selfTest = ctx.getString(R.string.mesh_self_test_running)
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.mesh_self_test_button))
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.mesh_expand_advanced),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Switch(checked = advancedExpanded, onCheckedChange = { advancedExpanded = it })
                }
                if (advancedExpanded) {
                    Spacer(Modifier.height(8.dp))
                    MeshConfigQuickEditor(
                        mesh = draft,
                        onMeshChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.mesh_add_profile_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            Text(
                text = stringResource(R.string.mesh_status_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )

            parsed.coreError?.let { err ->
                Spacer(Modifier.height(8.dp))
                MeshErrorBanner(text = stringResource(R.string.mesh_status_core_error_fmt, err))
            }

            SectionCard(modifier = Modifier.padding(top = 8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${stringResource(R.string.cluster_current_server_fmt, parsed.clusterNodeId)} · ${parsed.serversSummary} · ${parsed.clientsSummary}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text =
                            buildString {
                                if (parsed.iceSrflxRttMs > 0.1) {
                                    append("%.0f %s · ".format(parsed.iceSrflxRttMs, iceSuffix))
                                }
                                append("srflx ${parsed.srflx}")
                            },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard(modifier = Modifier.padding(top = VolterSpacing.sectionGap)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        parsed.meshNodes.take(36).forEach { n ->
                            MeshPeerNodeMiniCard(node = n)
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.mesh_expand_raw_json),
                    style = MaterialTheme.typography.labelLarge,
                )
                Switch(checked = rawExpanded, onCheckedChange = { rawExpanded = it })
            }
            if (rawExpanded) {
                Text(
                    text = prettyMeshJson(lastRawSnap),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (selfTest.isNotBlank()) {
                Text(
                    text = selfTest,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
    }

    if (shareTicketTarget != null) {
        val (name, cfg) = shareTicketTarget!!
        SharePeerTicketDialog(name = name, cfg = cfg, context = ctx, onDismiss = { shareTicketTarget = null })
    }
}

@Composable
private fun MeshErrorBanner(text: String) {
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
private fun MeshConfigQuickEditor(
    mesh: MeshConfig,
    onMeshChange: (MeshConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        MeshSwitchRow(
            title = stringResource(R.string.mesh_title),
            subtitle = stringResource(R.string.mesh_switch_main_sub),
            checked = mesh.enabled,
            onCheckedChange = { onMeshChange(mesh.copy(enabled = it)) },
        )
        MeshSwitchRow(
            title = stringResource(R.string.mesh_switch_volunteer_title),
            subtitle = stringResource(R.string.mesh_switch_volunteer_sub),
            checked = mesh.volunteer.enabled,
            onCheckedChange = { onMeshChange(mesh.copy(volunteer = mesh.volunteer.copy(enabled = it))) },
        )
        MeshSwitchRow(
            title = stringResource(R.string.mesh_switch_p2p_title),
            subtitle = stringResource(R.string.mesh_switch_p2p_sub),
            checked = mesh.p2p.enabled,
            onCheckedChange = { onMeshChange(mesh.copy(p2p = mesh.p2p.copy(enabled = it))) },
        )
        MeshSwitchRow(
            title = stringResource(R.string.mesh_switch_server_relay_title),
            subtitle = stringResource(R.string.mesh_switch_server_relay_sub),
            checked = mesh.serverRelay.enabled,
            onCheckedChange = { onMeshChange(mesh.copy(serverRelay = mesh.serverRelay.copy(enabled = it))) },
        )
        MeshSwitchRow(
            title = stringResource(R.string.mesh_switch_stun_title),
            subtitle = stringResource(R.string.mesh_switch_stun_sub),
            checked = mesh.stun.enabled,
            onCheckedChange = { onMeshChange(mesh.copy(stun = mesh.stun.copy(enabled = it))) },
        )
        MeshSwitchRow(
            title = stringResource(R.string.mesh_flag_peer_udp),
            subtitle = stringResource(R.string.mesh_switch_p2p_udp_sub),
            checked = mesh.p2p.useUdp,
            onCheckedChange = { onMeshChange(mesh.copy(p2p = mesh.p2p.copy(useUdp = it))) },
        )
        MeshSwitchRow(
            title = stringResource(R.string.mesh_flag_peer_quic),
            subtitle = stringResource(R.string.mesh_switch_p2p_quic_sub),
            checked = mesh.p2p.useQuic,
            onCheckedChange = { onMeshChange(mesh.copy(p2p = mesh.p2p.copy(useQuic = it))) },
        )
        MeshSwitchRow(
            title = stringResource(R.string.mesh_flag_peer_tcp),
            subtitle = stringResource(R.string.mesh_switch_p2p_tcp_sub),
            checked = mesh.p2p.useTcp,
            onCheckedChange = { onMeshChange(mesh.copy(p2p = mesh.p2p.copy(useTcp = it))) },
        )
        MeshSwitchRow(
            title = stringResource(R.string.mesh_flag_dht_srflx),
            subtitle = stringResource(R.string.mesh_switch_publish_srflx_sub),
            checked = mesh.stun.publishSrflx,
            onCheckedChange = { onMeshChange(mesh.copy(stun = mesh.stun.copy(publishSrflx = it))) },
        )
        MeshSwitchRow(
            title = stringResource(R.string.mesh_flag_sym_nat),
            subtitle = stringResource(R.string.mesh_switch_nat_punch_sub),
            checked = mesh.stun.symmetricNatHolePunch,
            onCheckedChange = { onMeshChange(mesh.copy(stun = mesh.stun.copy(symmetricNatHolePunch = it))) },
        )
        MeshSwitchRow(
            title = stringResource(R.string.mesh_switch_aggressive_title),
            subtitle = stringResource(R.string.mesh_switch_aggressive_sub),
            checked = mesh.policy.pathAggressive,
            onCheckedChange = { onMeshChange(mesh.copy(policy = mesh.policy.copy(pathAggressive = it))) },
        )
        MeshSwitchRow(
            title = stringResource(R.string.mesh_switch_gossip_title),
            subtitle = stringResource(R.string.mesh_switch_gossip_sub),
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
                        stringResource(R.string.mesh_ticket_share_err),
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
