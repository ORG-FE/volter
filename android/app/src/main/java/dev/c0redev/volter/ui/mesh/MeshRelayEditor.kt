package dev.c0redev.volter.ui.mesh

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.c0redev.volter.R
import dev.c0redev.volter.domain.model.RelayOptions
import dev.c0redev.volter.ui.components.SectionCard
import dev.c0redev.volter.ui.components.StyledTextField

@Composable
fun MeshRelayEditor(
    relay: RelayOptions,
    onRelayChange: (RelayOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    fun strList(s: String) = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
    fun linesList(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.mesh_section_ice),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StyledTextField(
                    value = relay.stunServers?.joinToString(", ") ?: "",
                    onValueChange = { onRelayChange(relay.copy(stunServers = strList(it))) },
                    label = stringResource(R.string.mesh_stun_servers),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.turnUrls?.joinToString("\n") ?: "",
                    onValueChange = { onRelayChange(relay.copy(turnUrls = linesList(it))) },
                    label = stringResource(R.string.mesh_turn_urls),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                )
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.mesh_section_discovery),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StyledTextField(
                    value = relay.discoveryURL ?: "",
                    onValueChange = { onRelayChange(relay.copy(discoveryURL = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_discovery_url),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.bootstrapPubKey ?: "",
                    onValueChange = { onRelayChange(relay.copy(bootstrapPubKey = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_bootstrap_pub),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.discoverySigned ?: "",
                    onValueChange = { onRelayChange(relay.copy(discoverySigned = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_discovery_signed),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.mesh_section_dht),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StyledTextField(
                    value = relay.dhtRpcSeedPeers?.joinToString(", ") ?: "",
                    onValueChange = { onRelayChange(relay.copy(dhtRpcSeedPeers = strList(it))) },
                    label = stringResource(R.string.mesh_dht_seeds),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.dhtFindUrls?.joinToString(", ") ?: "",
                    onValueChange = { onRelayChange(relay.copy(dhtFindUrls = strList(it))) },
                    label = stringResource(R.string.mesh_dht_find_urls),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.dhtRpcSecret ?: "",
                    onValueChange = { onRelayChange(relay.copy(dhtRpcSecret = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_dht_rpc_secret),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.dhtRpcListenUdp ?: "",
                    onValueChange = { onRelayChange(relay.copy(dhtRpcListenUdp = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_dht_listen_udp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    intField(
                        label = stringResource(R.string.mesh_dht_interval_sec),
                        value = relay.dhtRpcIntervalSec,
                        onInt = { onRelayChange(relay.copy(dhtRpcIntervalSec = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    intField(
                        label = stringResource(R.string.mesh_dht_find_k),
                        value = relay.dhtRpcFindK,
                        onInt = { onRelayChange(relay.copy(dhtRpcFindK = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    intField(
                        label = stringResource(R.string.mesh_dht_iter_rounds),
                        value = relay.dhtIterativeRounds,
                        onInt = { onRelayChange(relay.copy(dhtIterativeRounds = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    intField(
                        label = stringResource(R.string.mesh_dht_iter_alpha),
                        value = relay.dhtIterativeAlpha,
                        onInt = { onRelayChange(relay.copy(dhtIterativeAlpha = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.mesh_section_peer_udp),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StyledTextField(
                    value = relay.peerId ?: "",
                    onValueChange = { onRelayChange(relay.copy(peerId = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_peer_id),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.peerRelayUdpListen ?: "",
                    onValueChange = { onRelayChange(relay.copy(peerRelayUdpListen = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_peer_udp_listen),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.peerRelayUdpAdvertise ?: "",
                    onValueChange = { onRelayChange(relay.copy(peerRelayUdpAdvertise = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_peer_udp_advertise),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.peerQuicServerName ?: "",
                    onValueChange = { onRelayChange(relay.copy(peerQuicServerName = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_peer_quic_sni),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.mesh_section_emergency_stake),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StyledTextField(
                    value = relay.emergencyPolicyURL ?: "",
                    onValueChange = { onRelayChange(relay.copy(emergencyPolicyURL = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_emergency_url),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.emergencyPolicyPubKey ?: "",
                    onValueChange = { onRelayChange(relay.copy(emergencyPolicyPubKey = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_emergency_pub),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.stakeRegistryURL ?: "",
                    onValueChange = { onRelayChange(relay.copy(stakeRegistryURL = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_stake_registry_url),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.stakeRegistryPubKey ?: "",
                    onValueChange = { onRelayChange(relay.copy(stakeRegistryPubKey = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_stake_registry_pub),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.stakeMerkleRootUrl ?: "",
                    onValueChange = { onRelayChange(relay.copy(stakeMerkleRootUrl = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_stake_merkle_root_url),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.stakeBonusHttpUrl ?: "",
                    onValueChange = { onRelayChange(relay.copy(stakeBonusHttpUrl = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_stake_bonus_url),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.stakeReputationFile ?: "",
                    onValueChange = { onRelayChange(relay.copy(stakeReputationFile = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_stake_rep_file),
                    modifier = Modifier.fillMaxWidth(),
                )
                StyledTextField(
                    value = relay.stakeMerkleFile ?: "",
                    onValueChange = { onRelayChange(relay.copy(stakeMerkleFile = it.ifBlank { null })) },
                    label = stringResource(R.string.mesh_stake_merkle_file),
                    modifier = Modifier.fillMaxWidth(),
                )
                intField(
                    label = stringResource(R.string.mesh_stake_min),
                    value = relay.stakeMin,
                    onInt = { onRelayChange(relay.copy(stakeMin = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.mesh_section_gossip),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                StyledTextField(
                    value = relay.gossipPeers?.joinToString(", ") ?: "",
                    onValueChange = { onRelayChange(relay.copy(gossipPeers = strList(it))) },
                    label = stringResource(R.string.mesh_gossip_peers),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    intField(
                        label = stringResource(R.string.mesh_gossip_interval),
                        value = relay.gossipIntervalSec,
                        onInt = { onRelayChange(relay.copy(gossipIntervalSec = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    intField(
                        label = stringResource(R.string.mesh_gossip_max_age),
                        value = relay.gossipMaxAgeSec,
                        onInt = { onRelayChange(relay.copy(gossipMaxAgeSec = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.mesh_section_flags),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                toggle(stringResource(R.string.mesh_flag_gossip), relay.gossipEnabled == true) {
                    onRelayChange(relay.copy(gossipEnabled = it))
                }
                toggle(stringResource(R.string.mesh_flag_path_agg), relay.pathAggressive != false) {
                    onRelayChange(relay.copy(pathAggressive = it))
                }
                toggle(stringResource(R.string.mesh_flag_path_disc), relay.peerPathFromDiscovery != false) {
                    onRelayChange(relay.copy(peerPathFromDiscovery = it))
                }
                toggle(stringResource(R.string.mesh_flag_peer_quic), relay.peerRelayUseQuic == true) {
                    onRelayChange(relay.copy(peerRelayUseQuic = it))
                }
                toggle(stringResource(R.string.mesh_flag_peer_udp), relay.peerRelayUseUdp != false) {
                    onRelayChange(relay.copy(peerRelayUseUdp = it))
                }
                toggle(stringResource(R.string.mesh_flag_dht_srflx), relay.dhtPublishSrflx != false) {
                    onRelayChange(relay.copy(dhtPublishSrflx = it))
                }
                toggle(stringResource(R.string.mesh_flag_sym_nat), relay.symmetricNatHolePunch != false) {
                    onRelayChange(relay.copy(symmetricNatHolePunch = it))
                }
                intField(
                    label = stringResource(R.string.mesh_path_cooldown_ms),
                    value = relay.pathCooldownMs,
                    onInt = { onRelayChange(relay.copy(pathCooldownMs = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun intField(
    label: String,
    value: Int?,
    onInt: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val v = value?.takeIf { it != 0 }?.toString() ?: ""
    StyledTextField(
        value = v,
        onValueChange = { s -> onInt(s.trim().toIntOrNull() ?: 0) },
        label = label,
        modifier = modifier,
        singleLine = true,
    )
}

@Composable
private fun toggle(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
