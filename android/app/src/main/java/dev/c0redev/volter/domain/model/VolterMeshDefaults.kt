package dev.c0redev.volter.domain.model

import dev.c0redev.volter.BuildConfig


object VolterMeshDefaults {
    fun applyIfEnabled(cfg: Config, settings: ClientSettings): Config {
        if (!BuildConfig.VOLTER_MESH_DEFAULTS || !settings.volterMesh) return cfg
        val base = baseRelay(cfg.server) ?: return cfg
        return cfg.copy(relay = base.withUserOverlay(cfg.relay))
    }

    private fun baseRelay(server: String): RelayOptions? {
        val authority = Config.tcpAuthorityForHttp(server) ?: return null
        val discovery = "http://$authority/volter/relay-index.json"
        val dhtFindUrls =
            if (BuildConfig.VOLTER_DHT_FIND_ENABLED) {
                listOf("http://$authority/volter/dht/find")
            } else {
                emptyList()
            }
        val hostOnly = Config.hostFromServer(server)
        val seedHost =
            if (hostOnly.contains(':')) "[$hostOnly]" else hostOnly
        val seeds =
            if (BuildConfig.VOLTER_DHT_UDP_PORT > 0) {
                listOf("$seedHost:${BuildConfig.VOLTER_DHT_UDP_PORT}")
            } else {
                emptyList()
            }
        return RelayOptions(
            peerId = BuildConfig.VOLTER_RELAY_PEER_ID.takeIf { it.isNotBlank() },
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
            stunServers = listOf("stun.l.google.com:19302", "stun.cloudflare.com:3478"),
        )
    }
}
