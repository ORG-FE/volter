package dev.c0redev.volter.domain.model

import dev.c0redev.volter.json.optJSONArrayStringList
import dev.c0redev.volter.json.optNullableBoolean
import dev.c0redev.volter.json.optNullableInt
import dev.c0redev.volter.json.optNullableString
import dev.c0redev.volter.json.putStringListIfNonempty
import org.json.JSONObject


data class RelayOptions(
    val peerId: String? = null,
    val privateKey: String? = null,
    val allowedClasses: List<String>? = null,
    val maxConcurrent: Int? = null,
    val budgetKbps: Int? = null,
    val peerRelayBudgetKbps: Int? = null,
    val maxPeerHops: Int? = null,
    val healthMaxAgeSec: Int? = null,
    val discoverySigned: String? = null,
    val discoveryURL: String? = null,
    val gossipEnabled: Boolean? = null,
    val bootstrapPubKey: String? = null,
    val emergencyPolicyURL: String? = null,
    val emergencyPolicyPubKey: String? = null,
    val pathAggressive: Boolean? = null,
    val pathCooldownMs: Int? = null,
    val stunServers: List<String>? = null,
    val turnUrls: List<String>? = null,
    val gossipPeers: List<String>? = null,
    val gossipIntervalSec: Int? = null,
    val gossipMaxAgeSec: Int? = null,
    val geoAllowCountries: List<String>? = null,
    val geoDenyCountries: List<String>? = null,
    val stakeMin: Int? = null,
    val peerPathFromDiscovery: Boolean? = null,
    val peerRelayUseQuic: Boolean? = null,
    val peerRelayUseUdp: Boolean? = null,
    val peerRelayUseTcp: Boolean? = null,
    val peerRelayUdpListen: String? = null,
    val peerRelayUdpAdvertise: String? = null,
    val peerQuicServerName: String? = null,
    val dhtFindUrls: List<String>? = null,
    val stakeRegistryURL: String? = null,
    val stakeRegistryPubKey: String? = null,
    val stakeReputationFile: String? = null,
    val stakeBonusHttpUrl: String? = null,
    val stakeMerkleFile: String? = null,
    val stakeMerkleRootUrl: String? = null,
    val dhtRpcListenUdp: String? = null,
    val dhtRpcSecret: String? = null,
    val dhtRpcSeedPeers: List<String>? = null,
    val dhtRpcIntervalSec: Int? = null,
    val dhtRpcFindK: Int? = null,
    val dhtIterativeRounds: Int? = null,
    val dhtIterativeAlpha: Int? = null,
    val dhtPublishSrflx: Boolean? = null,
    val symmetricNatHolePunch: Boolean? = null,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        peerId?.takeIf { it.isNotBlank() }?.let { j.put("peerId", it) }
        privateKey?.takeIf { it.isNotBlank() }?.let { j.put("privateKey", it) }
        j.putStringListIfNonempty("allowedClasses", allowedClasses)
        maxConcurrent?.takeIf { it != 0 }?.let { j.put("maxConcurrent", it) }
        budgetKbps?.takeIf { it != 0 }?.let { j.put("budgetKbps", it) }
        peerRelayBudgetKbps?.takeIf { it != 0 }?.let { j.put("peerRelayBudgetKbps", it) }
        maxPeerHops?.takeIf { it != 0 }?.let { j.put("maxPeerHops", it) }
        healthMaxAgeSec?.takeIf { it != 0 }?.let { j.put("healthMaxAgeSec", it) }
        discoverySigned?.takeIf { it.isNotBlank() }?.let { j.put("discoverySigned", it) }
        discoveryURL?.takeIf { it.isNotBlank() }?.let { j.put("discoveryURL", it) }
        gossipEnabled?.let { j.put("gossipEnabled", it) }
        bootstrapPubKey?.takeIf { it.isNotBlank() }?.let { j.put("bootstrapPubKey", it) }
        emergencyPolicyURL?.takeIf { it.isNotBlank() }?.let { j.put("emergencyPolicyURL", it) }
        emergencyPolicyPubKey?.takeIf { it.isNotBlank() }?.let { j.put("emergencyPolicyPubKey", it) }
        pathAggressive?.let { j.put("pathAggressive", it) }
        pathCooldownMs?.takeIf { it != 0 }?.let { j.put("pathCooldownMs", it) }
        j.putStringListIfNonempty("stunServers", stunServers)
        j.putStringListIfNonempty("turnUrls", turnUrls)
        j.putStringListIfNonempty("gossipPeers", gossipPeers)
        gossipIntervalSec?.takeIf { it != 0 }?.let { j.put("gossipIntervalSec", it) }
        gossipMaxAgeSec?.takeIf { it != 0 }?.let { j.put("gossipMaxAgeSec", it) }
        j.putStringListIfNonempty("geoAllowCountries", geoAllowCountries)
        j.putStringListIfNonempty("geoDenyCountries", geoDenyCountries)
        stakeMin?.takeIf { it != 0 }?.let { j.put("stakeMin", it) }
        peerPathFromDiscovery?.let { j.put("peerPathFromDiscovery", it) }
        peerRelayUseQuic?.let { j.put("peerRelayUseQuic", it) }
        peerRelayUseUdp?.let { j.put("peerRelayUseUdp", it) }
        peerRelayUseTcp?.let { j.put("peerRelayUseTcp", it) }
        peerRelayUdpListen?.takeIf { it.isNotBlank() }?.let { j.put("peerRelayUdpListen", it) }
        peerRelayUdpAdvertise?.takeIf { it.isNotBlank() }?.let { j.put("peerRelayUdpAdvertise", it) }
        peerQuicServerName?.takeIf { it.isNotBlank() }?.let { j.put("peerQuicServerName", it) }
        j.putStringListIfNonempty("dhtFindUrls", dhtFindUrls)
        stakeRegistryURL?.takeIf { it.isNotBlank() }?.let { j.put("stakeRegistryURL", it) }
        stakeRegistryPubKey?.takeIf { it.isNotBlank() }?.let { j.put("stakeRegistryPubKey", it) }
        stakeReputationFile?.takeIf { it.isNotBlank() }?.let { j.put("stakeReputationFile", it) }
        stakeBonusHttpUrl?.takeIf { it.isNotBlank() }?.let { j.put("stakeBonusHttpUrl", it) }
        stakeMerkleFile?.takeIf { it.isNotBlank() }?.let { j.put("stakeMerkleFile", it) }
        stakeMerkleRootUrl?.takeIf { it.isNotBlank() }?.let { j.put("stakeMerkleRootUrl", it) }
        dhtRpcListenUdp?.takeIf { it.isNotBlank() }?.let { j.put("dhtRpcListenUdp", it) }
        dhtRpcSecret?.takeIf { it.isNotBlank() }?.let { j.put("dhtRpcSecret", it) }
        j.putStringListIfNonempty("dhtRpcSeedPeers", dhtRpcSeedPeers)
        dhtRpcIntervalSec?.takeIf { it != 0 }?.let { j.put("dhtRpcIntervalSec", it) }
        dhtRpcFindK?.takeIf { it != 0 }?.let { j.put("dhtRpcFindK", it) }
        dhtIterativeRounds?.takeIf { it != 0 }?.let { j.put("dhtIterativeRounds", it) }
        dhtIterativeAlpha?.takeIf { it != 0 }?.let { j.put("dhtIterativeAlpha", it) }
        dhtPublishSrflx?.let { j.put("dhtPublishSrflx", it) }
        symmetricNatHolePunch?.let { j.put("symmetricNatHolePunch", it) }
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): RelayOptions {
            fun list(key: String) = j.optJSONArrayStringList(key)
            return RelayOptions(
                peerId = j.optNullableString("peerId"),
                privateKey = j.optNullableString("privateKey"),
                allowedClasses = list("allowedClasses"),
                maxConcurrent = j.optNullableInt("maxConcurrent"),
                budgetKbps = j.optNullableInt("budgetKbps"),
                peerRelayBudgetKbps = j.optNullableInt("peerRelayBudgetKbps"),
                maxPeerHops = j.optNullableInt("maxPeerHops"),
                healthMaxAgeSec = j.optNullableInt("healthMaxAgeSec"),
                discoverySigned = j.optNullableString("discoverySigned"),
                discoveryURL = j.optNullableString("discoveryURL"),
                gossipEnabled = j.optNullableBoolean("gossipEnabled"),
                bootstrapPubKey = j.optNullableString("bootstrapPubKey"),
                emergencyPolicyURL = j.optNullableString("emergencyPolicyURL"),
                emergencyPolicyPubKey = j.optNullableString("emergencyPolicyPubKey"),
                pathAggressive = j.optNullableBoolean("pathAggressive"),
                pathCooldownMs = j.optNullableInt("pathCooldownMs"),
                stunServers = list("stunServers"),
                turnUrls = list("turnUrls"),
                gossipPeers = list("gossipPeers"),
                gossipIntervalSec = j.optNullableInt("gossipIntervalSec"),
                gossipMaxAgeSec = j.optNullableInt("gossipMaxAgeSec"),
                geoAllowCountries = list("geoAllowCountries"),
                geoDenyCountries = list("geoDenyCountries"),
                stakeMin = j.optNullableInt("stakeMin"),
                peerPathFromDiscovery = j.optNullableBoolean("peerPathFromDiscovery"),
                peerRelayUseQuic = j.optNullableBoolean("peerRelayUseQuic"),
                peerRelayUseUdp = j.optNullableBoolean("peerRelayUseUdp"),
                peerRelayUseTcp = j.optNullableBoolean("peerRelayUseTcp"),
                peerRelayUdpListen = j.optNullableString("peerRelayUdpListen"),
                peerRelayUdpAdvertise = j.optNullableString("peerRelayUdpAdvertise"),
                peerQuicServerName = j.optNullableString("peerQuicServerName"),
                dhtFindUrls = list("dhtFindUrls"),
                stakeRegistryURL = j.optNullableString("stakeRegistryURL"),
                stakeRegistryPubKey = j.optNullableString("stakeRegistryPubKey"),
                stakeReputationFile = j.optNullableString("stakeReputationFile"),
                stakeBonusHttpUrl = j.optNullableString("stakeBonusHttpUrl"),
                stakeMerkleFile = j.optNullableString("stakeMerkleFile"),
                stakeMerkleRootUrl = j.optNullableString("stakeMerkleRootUrl"),
                dhtRpcListenUdp = j.optNullableString("dhtRpcListenUdp"),
                dhtRpcSecret = j.optNullableString("dhtRpcSecret"),
                dhtRpcSeedPeers = list("dhtRpcSeedPeers"),
                dhtRpcIntervalSec = j.optNullableInt("dhtRpcIntervalSec"),
                dhtRpcFindK = j.optNullableInt("dhtRpcFindK"),
                dhtIterativeRounds = j.optNullableInt("dhtIterativeRounds"),
                dhtIterativeAlpha = j.optNullableInt("dhtIterativeAlpha"),
                dhtPublishSrflx = j.optNullableBoolean("dhtPublishSrflx"),
                symmetricNatHolePunch = j.optNullableBoolean("symmetricNatHolePunch"),
            )
        }
    }
}


fun RelayOptions.withUserOverlay(user: RelayOptions?): RelayOptions {
    if (user == null) return this
    return copy(
        peerId = user.peerId ?: peerId,
        privateKey = user.privateKey ?: privateKey,
        allowedClasses = user.allowedClasses ?: allowedClasses,
        maxConcurrent = user.maxConcurrent ?: maxConcurrent,
        budgetKbps = user.budgetKbps ?: budgetKbps,
        peerRelayBudgetKbps = user.peerRelayBudgetKbps ?: peerRelayBudgetKbps,
        maxPeerHops = user.maxPeerHops ?: maxPeerHops,
        healthMaxAgeSec = user.healthMaxAgeSec ?: healthMaxAgeSec,
        discoverySigned = user.discoverySigned ?: discoverySigned,
        discoveryURL = user.discoveryURL ?: discoveryURL,
        gossipEnabled = user.gossipEnabled ?: gossipEnabled,
        bootstrapPubKey = user.bootstrapPubKey ?: bootstrapPubKey,
        emergencyPolicyURL = user.emergencyPolicyURL ?: emergencyPolicyURL,
        emergencyPolicyPubKey = user.emergencyPolicyPubKey ?: emergencyPolicyPubKey,
        pathAggressive = user.pathAggressive ?: pathAggressive,
        pathCooldownMs = user.pathCooldownMs ?: pathCooldownMs,
        stunServers = user.stunServers ?: stunServers,
        turnUrls = user.turnUrls ?: turnUrls,
        gossipPeers = user.gossipPeers ?: gossipPeers,
        gossipIntervalSec = user.gossipIntervalSec ?: gossipIntervalSec,
        gossipMaxAgeSec = user.gossipMaxAgeSec ?: gossipMaxAgeSec,
        geoAllowCountries = user.geoAllowCountries ?: geoAllowCountries,
        geoDenyCountries = user.geoDenyCountries ?: geoDenyCountries,
        stakeMin = user.stakeMin ?: stakeMin,
        peerPathFromDiscovery = user.peerPathFromDiscovery ?: peerPathFromDiscovery,
        peerRelayUseQuic = user.peerRelayUseQuic ?: peerRelayUseQuic,
        peerRelayUseUdp = user.peerRelayUseUdp ?: peerRelayUseUdp,
        peerRelayUseTcp = user.peerRelayUseTcp ?: peerRelayUseTcp,
        peerRelayUdpListen = user.peerRelayUdpListen ?: peerRelayUdpListen,
        peerRelayUdpAdvertise = user.peerRelayUdpAdvertise ?: peerRelayUdpAdvertise,
        peerQuicServerName = user.peerQuicServerName ?: peerQuicServerName,
        dhtFindUrls = user.dhtFindUrls ?: dhtFindUrls,
        stakeRegistryURL = user.stakeRegistryURL ?: stakeRegistryURL,
        stakeRegistryPubKey = user.stakeRegistryPubKey ?: stakeRegistryPubKey,
        stakeReputationFile = user.stakeReputationFile ?: stakeReputationFile,
        stakeBonusHttpUrl = user.stakeBonusHttpUrl ?: stakeBonusHttpUrl,
        stakeMerkleFile = user.stakeMerkleFile ?: stakeMerkleFile,
        stakeMerkleRootUrl = user.stakeMerkleRootUrl ?: stakeMerkleRootUrl,
        dhtRpcListenUdp = user.dhtRpcListenUdp ?: dhtRpcListenUdp,
        dhtRpcSecret = user.dhtRpcSecret ?: dhtRpcSecret,
        dhtRpcSeedPeers = user.dhtRpcSeedPeers ?: dhtRpcSeedPeers,
        dhtRpcIntervalSec = user.dhtRpcIntervalSec ?: dhtRpcIntervalSec,
        dhtRpcFindK = user.dhtRpcFindK ?: dhtRpcFindK,
        dhtIterativeRounds = user.dhtIterativeRounds ?: dhtIterativeRounds,
        dhtIterativeAlpha = user.dhtIterativeAlpha ?: dhtIterativeAlpha,
        dhtPublishSrflx = user.dhtPublishSrflx ?: dhtPublishSrflx,
        symmetricNatHolePunch = user.symmetricNatHolePunch ?: symmetricNatHolePunch,
    )
}

fun RelayOptions.hasProfileData(): Boolean {
    return hasMeshProfileData() || hasCarryOverData()
}

fun RelayOptions.hasMeshProfileData(): Boolean {
    return !peerId.isNullOrBlank() ||
        !discoveryURL.isNullOrBlank() ||
        !bootstrapPubKey.isNullOrBlank() ||
        !stunServers.isNullOrEmpty() ||
        !gossipPeers.isNullOrEmpty() ||
        !dhtFindUrls.isNullOrEmpty() ||
        !dhtRpcListenUdp.isNullOrBlank() ||
        !dhtRpcSecret.isNullOrBlank() ||
        !dhtRpcSeedPeers.isNullOrEmpty() ||
        !peerRelayUdpListen.isNullOrBlank() ||
        !peerRelayUdpAdvertise.isNullOrBlank()
}

fun RelayOptions.hasCarryOverData(): Boolean {
    return !turnUrls.isNullOrEmpty() ||
        !discoverySigned.isNullOrBlank() ||
        !emergencyPolicyURL.isNullOrBlank() ||
        !emergencyPolicyPubKey.isNullOrBlank() ||
        !geoAllowCountries.isNullOrEmpty() ||
        !geoDenyCountries.isNullOrEmpty() ||
        !stakeRegistryURL.isNullOrBlank() ||
        !stakeRegistryPubKey.isNullOrBlank() ||
        !stakeReputationFile.isNullOrBlank() ||
        !stakeBonusHttpUrl.isNullOrBlank() ||
        !stakeMerkleFile.isNullOrBlank() ||
        !stakeMerkleRootUrl.isNullOrBlank()
}

fun RelayOptions.toMesh(base: MeshConfig = MeshConfig()): MeshConfig {
    return base.copy(
        enabled = true,
        volunteer = base.volunteer.copy(
            enabled = !peerRelayUdpListen.isNullOrBlank() || !peerRelayUdpAdvertise.isNullOrBlank(),
            peerId = peerId,
            privateKey = privateKey,
            udpListen = peerRelayUdpListen,
            udpAdvertise = peerRelayUdpAdvertise,
            maxConcurrent = maxConcurrent ?: 0,
            budgetKbps = peerRelayBudgetKbps ?: budgetKbps ?: 0,
        ),
        p2p = base.p2p.copy(
            enabled = peerPathFromDiscovery == true,
            useUdp = peerRelayUseUdp == true,
            useQuic = peerRelayUseQuic == true,
            useTcp = peerRelayUseTcp != false,
            quicServerName = peerQuicServerName,
        ),
        serverRelay = base.serverRelay.copy(
            enabled = true,
            allowedClasses = allowedClasses,
            discoveryUrl = discoveryURL,
            bootstrapPubKey = bootstrapPubKey,
        ),
        stun = base.stun.copy(
            enabled = !stunServers.isNullOrEmpty() || dhtPublishSrflx == true || symmetricNatHolePunch == true,
            servers = stunServers,
            publishSrflx = dhtPublishSrflx == true,
            symmetricNatHolePunch = symmetricNatHolePunch == true,
        ),
        discovery = base.discovery.copy(
            gossipEnabled = gossipEnabled == true,
            gossipPeers = gossipPeers,
            gossipIntervalSec = gossipIntervalSec ?: 0,
            gossipMaxAgeSec = gossipMaxAgeSec ?: 0,
            dhtFindUrls = dhtFindUrls,
            dhtRpcListenUdp = dhtRpcListenUdp,
            dhtRpcSecret = dhtRpcSecret,
            dhtRpcSeedPeers = dhtRpcSeedPeers,
            dhtRpcIntervalSec = dhtRpcIntervalSec ?: 0,
            dhtRpcFindK = dhtRpcFindK ?: 0,
            dhtIterativeRounds = dhtIterativeRounds ?: 0,
            dhtIterativeAlpha = dhtIterativeAlpha ?: 0,
        ),
        policy = base.policy.copy(
            budgetKbps = budgetKbps ?: base.policy.budgetKbps,
            maxPeerHops = maxPeerHops ?: base.policy.maxPeerHops,
            pathAggressive = pathAggressive == true,
            pathCooldownMs = pathCooldownMs ?: base.policy.pathCooldownMs,
            healthMaxAgeSec = healthMaxAgeSec ?: base.policy.healthMaxAgeSec,
        ),
    )
}

fun RelayOptions.legacyCarryOver(): RelayOptions? {
    val out = RelayOptions(
        turnUrls = turnUrls,
        discoverySigned = discoverySigned,
        emergencyPolicyURL = emergencyPolicyURL,
        emergencyPolicyPubKey = emergencyPolicyPubKey,
        geoAllowCountries = geoAllowCountries,
        geoDenyCountries = geoDenyCountries,
        stakeRegistryURL = stakeRegistryURL,
        stakeRegistryPubKey = stakeRegistryPubKey,
        stakeReputationFile = stakeReputationFile,
        stakeBonusHttpUrl = stakeBonusHttpUrl,
        stakeMerkleFile = stakeMerkleFile,
        stakeMerkleRootUrl = stakeMerkleRootUrl,
    )
    return out.takeIf { it.hasCarryOverData() }
}
