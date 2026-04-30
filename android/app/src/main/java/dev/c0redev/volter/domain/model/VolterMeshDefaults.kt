package dev.c0redev.volter.domain.model

import dev.c0redev.volter.BuildConfig
import java.util.UUID


object VolterMeshDefaults {
    private const val DEFAULT_MESH_HOST = "89.144.63.81"
    private const val DEFAULT_MESH_HTTP_AUTHORITY = "89.144.63.81:25565"
    private val sessionRandomPeerId: String by lazy { "vps-${UUID.randomUUID().toString().replace("-", "").take(12)}" }

    fun applyIfEnabled(cfg: Config, settings: ClientSettings): Config {
        if (!BuildConfig.VOLTER_MESH_DEFAULTS || !settings.volterMesh) return cfg
        val base = buildBaseRelay() ?: return cfg
        return cfg.copy(relay = base.withUserOverlay(cfg.relay))
    }

    fun relayForEditor(@Suppress("UNUSED_PARAMETER") server: String, stored: RelayOptions?): RelayOptions {
        if (!BuildConfig.VOLTER_MESH_DEFAULTS) return stored ?: RelayOptions()
        val base = buildBaseRelay() ?: return stored ?: RelayOptions()
        val merged = base.withUserOverlay(stored)
        return if (merged.peerId.isNullOrBlank()) merged.copy(peerId = sessionRandomPeerId) else merged
    }

    private fun buildBaseRelay(): RelayOptions? {
        val discovery = "http://$DEFAULT_MESH_HTTP_AUTHORITY/volter/relay-index.json"
        val dhtFindUrls =
            if (BuildConfig.VOLTER_DHT_FIND_ENABLED) {
                listOf("http://$DEFAULT_MESH_HTTP_AUTHORITY/volter/dht/find")
            } else {
                emptyList()
            }
        val seeds =
            if (BuildConfig.VOLTER_DHT_UDP_PORT > 0) {
                listOf("$DEFAULT_MESH_HOST:${BuildConfig.VOLTER_DHT_UDP_PORT}")
            } else {
                emptyList()
            }
        return RelayOptions(
            peerId = BuildConfig.VOLTER_RELAY_PEER_ID.takeIf { it.isNotBlank() } ?: sessionRandomPeerId,
            discoveryURL = discovery,
            bootstrapPubKey = BuildConfig.VOLTER_BOOTSTRAP_PUB_KEY.takeIf { it.isNotBlank() },
            dhtRpcSecret = BuildConfig.VOLTER_DHT_RPC_SECRET.takeIf { it.isNotBlank() },
            allowedClasses = listOf("server", "peer"),
            stakeMin = 0,
            pathAggressive = true,
            peerPathFromDiscovery = true,
            peerRelayUseQuic = true,
            peerRelayUseUdp = true,
            dhtRpcSeedPeers = seeds,
            dhtFindUrls = dhtFindUrls,
            dhtPublishSrflx = false,
            symmetricNatHolePunch = false,
            stunServers = listOf("stun4.l.google.com:19302", "stun.sipnet.net:3478"),
        )
    }
}
