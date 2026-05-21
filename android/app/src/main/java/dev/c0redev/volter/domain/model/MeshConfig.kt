package dev.c0redev.volter.domain.model

import dev.c0redev.volter.json.optJSONArrayStringList
import dev.c0redev.volter.json.optNullableInt
import dev.c0redev.volter.json.optNullableString
import dev.c0redev.volter.json.putStringListIfNonempty
import org.json.JSONObject

data class MeshConfig(
    val enabled: Boolean = false,
    val volunteer: MeshVolunteerOptions = MeshVolunteerOptions(),
    val p2p: MeshP2POptions = MeshP2POptions(),
    val serverRelay: MeshServerRelayOptions = MeshServerRelayOptions(),
    val stun: MeshStunOptions = MeshStunOptions(),
    val discovery: MeshDiscoveryOptions = MeshDiscoveryOptions(),
    val policy: MeshPolicyOptions = MeshPolicyOptions(),
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("enabled", enabled)
        j.put("volunteer", volunteer.toJson())
        j.put("p2p", p2p.toJson())
        j.put("serverRelay", serverRelay.toJson())
        j.put("stun", stun.toJson())
        j.put("discovery", discovery.toJson())
        j.put("policy", policy.toJson())
        return j
    }

    fun publicCopy(): MeshConfig {
        return copy(
            volunteer = volunteer.copy(privateKey = null),
            discovery = discovery.copy(dhtRpcSecret = null),
        )
    }

    fun applyMeshDefaults(): MeshConfig {
        var m = copy(
            enabled = true,
            volunteer = volunteer.copy(
                enabled = true,
                udpListen = volunteer.udpListen?.takeIf { it.isNotBlank() } ?: "0.0.0.0:0",
                maxConcurrent = volunteer.maxConcurrent.takeIf { it > 0 } ?: 32,
                budgetKbps = volunteer.budgetKbps.takeIf { it > 0 } ?: 768,
                peerId = volunteer.peerId?.takeIf { it.isNotBlank() } ?: "peer-${System.currentTimeMillis()}",
            ),
            p2p = p2p.copy(
                enabled = true,
                useUdp = true,
                useQuic = true,
                useTcp = true,
            ),
            serverRelay = serverRelay.copy(
                enabled = true,
                allowedClasses = serverRelay.allowedClasses?.takeIf { it.isNotEmpty() }
                    ?: listOf("server", "peer"),
            ),
            stun = stun.copy(
                enabled = true,
                servers = stun.servers?.takeIf { it.isNotEmpty() } ?: defaultStunServers(),
                publishSrflx = true,
                symmetricNatHolePunch = true,
            ),
            discovery = discovery.copy(
                gossipEnabled = true,
                gossipIntervalSec = discovery.gossipIntervalSec.takeIf { it > 0 } ?: 180,
                gossipMaxAgeSec = discovery.gossipMaxAgeSec.takeIf { it > 0 } ?: 900,
                dhtRpcIntervalSec = discovery.dhtRpcIntervalSec.takeIf { it > 0 } ?: 120,
                dhtRpcFindK = discovery.dhtRpcFindK.takeIf { it > 0 } ?: 20,
                dhtIterativeAlpha = discovery.dhtIterativeAlpha.takeIf { it > 0 } ?: 3,
            ),
            policy = policy.copy(
                routeMode = policy.routeMode.ifBlank { "auto" },
                maxPeerHops = policy.maxPeerHops.coerceAtLeast(1).let { if (it < 2) 2 else it },
                budgetKbps = policy.budgetKbps.takeIf { it > 0 } ?: 2048,
                healthMaxAgeSec = policy.healthMaxAgeSec.takeIf { it > 0 } ?: 300,
                pathAggressive = true,
            ),
        )
        return m
    }

    companion object {
        fun defaultStunServers(): List<String> = listOf(
            "stun.rtc.yandex.net:3478",
            "stun.l.google.com:19302",
            "stun.cloudflare.com:3478",
            "stun1.l.google.com:19302",
        )

        fun fromJson(j: JSONObject): MeshConfig {
            return MeshConfig(
                enabled = j.optBoolean("enabled", false),
                volunteer = j.optJSONObject("volunteer")?.let { MeshVolunteerOptions.fromJson(it) } ?: MeshVolunteerOptions(),
                p2p = j.optJSONObject("p2p")?.let { MeshP2POptions.fromJson(it) } ?: MeshP2POptions(),
                serverRelay = j.optJSONObject("serverRelay")?.let { MeshServerRelayOptions.fromJson(it) } ?: MeshServerRelayOptions(),
                stun = j.optJSONObject("stun")?.let { MeshStunOptions.fromJson(it) } ?: MeshStunOptions(),
                discovery = j.optJSONObject("discovery")?.let { MeshDiscoveryOptions.fromJson(it) } ?: MeshDiscoveryOptions(),
                policy = j.optJSONObject("policy")?.let { MeshPolicyOptions.fromJson(it) } ?: MeshPolicyOptions(),
            )
        }
    }
}

data class MeshVolunteerOptions(
    val enabled: Boolean = false,
    val peerId: String? = null,
    val privateKey: String? = null,
    val udpListen: String? = null,
    val udpAdvertise: String? = null,
    val maxConcurrent: Int = 0,
    val budgetKbps: Int = 0,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("enabled", enabled)
        peerId?.takeIf { it.isNotBlank() }?.let { j.put("peerId", it) }
        privateKey?.takeIf { it.isNotBlank() }?.let { j.put("privateKey", it) }
        udpListen?.takeIf { it.isNotBlank() }?.let { j.put("udpListen", it) }
        udpAdvertise?.takeIf { it.isNotBlank() }?.let { j.put("udpAdvertise", it) }
        if (maxConcurrent > 0) j.put("maxConcurrent", maxConcurrent)
        if (budgetKbps > 0) j.put("budgetKbps", budgetKbps)
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): MeshVolunteerOptions {
            return MeshVolunteerOptions(
                enabled = j.optBoolean("enabled", false),
                peerId = j.optNullableString("peerId"),
                privateKey = j.optNullableString("privateKey"),
                udpListen = j.optNullableString("udpListen"),
                udpAdvertise = j.optNullableString("udpAdvertise"),
                maxConcurrent = j.optInt("maxConcurrent", 0),
                budgetKbps = j.optInt("budgetKbps", 0),
            )
        }
    }
}

data class MeshP2POptions(
    val enabled: Boolean = false,
    val useUdp: Boolean = true,
    val useQuic: Boolean = true,
    val useTcp: Boolean = true,
    val quicServerName: String? = null,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("enabled", enabled)
        j.put("useUdp", useUdp)
        j.put("useQuic", useQuic)
        j.put("useTcp", useTcp)
        quicServerName?.takeIf { it.isNotBlank() }?.let { j.put("quicServerName", it) }
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): MeshP2POptions {
            return MeshP2POptions(
                enabled = j.optBoolean("enabled", false),
                useUdp = j.optBoolean("useUdp", true),
                useQuic = j.optBoolean("useQuic", true),
                useTcp = j.optBoolean("useTcp", true),
                quicServerName = j.optNullableString("quicServerName"),
            )
        }
    }
}

data class MeshServerRelayOptions(
    val enabled: Boolean = true,
    val allowedClasses: List<String>? = null,
    val discoveryUrl: String? = null,
    val bootstrapPubKey: String? = null,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("enabled", enabled)
        j.putStringListIfNonempty("allowedClasses", allowedClasses)
        discoveryUrl?.takeIf { it.isNotBlank() }?.let { j.put("discoveryUrl", it) }
        bootstrapPubKey?.takeIf { it.isNotBlank() }?.let { j.put("bootstrapPubKey", it) }
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): MeshServerRelayOptions {
            return MeshServerRelayOptions(
                enabled = j.optBoolean("enabled", true),
                allowedClasses = j.optJSONArrayStringList("allowedClasses"),
                discoveryUrl = j.optNullableString("discoveryUrl"),
                bootstrapPubKey = j.optNullableString("bootstrapPubKey"),
            )
        }
    }
}

data class MeshStunOptions(
    val enabled: Boolean = true,
    val servers: List<String>? = null,
    val publishSrflx: Boolean = false,
    val symmetricNatHolePunch: Boolean = false,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("enabled", enabled)
        j.putStringListIfNonempty("servers", servers)
        j.put("publishSrflx", publishSrflx)
        j.put("symmetricNatHolePunch", symmetricNatHolePunch)
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): MeshStunOptions {
            return MeshStunOptions(
                enabled = j.optBoolean("enabled", true),
                servers = j.optJSONArrayStringList("servers"),
                publishSrflx = j.optBoolean("publishSrflx", false),
                symmetricNatHolePunch = j.optBoolean("symmetricNatHolePunch", false),
            )
        }
    }
}

data class MeshDiscoveryOptions(
    val gossipEnabled: Boolean = false,
    val gossipPeers: List<String>? = null,
    val gossipIntervalSec: Int = 0,
    val gossipMaxAgeSec: Int = 0,
    val dhtFindUrls: List<String>? = null,
    val dhtRpcListenUdp: String? = null,
    val dhtRpcSecret: String? = null,
    val dhtRpcSeedPeers: List<String>? = null,
    val dhtRpcIntervalSec: Int = 0,
    val dhtRpcFindK: Int = 0,
    val dhtIterativeRounds: Int = 0,
    val dhtIterativeAlpha: Int = 0,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("gossipEnabled", gossipEnabled)
        j.putStringListIfNonempty("gossipPeers", gossipPeers)
        if (gossipIntervalSec > 0) j.put("gossipIntervalSec", gossipIntervalSec)
        if (gossipMaxAgeSec > 0) j.put("gossipMaxAgeSec", gossipMaxAgeSec)
        j.putStringListIfNonempty("dhtFindUrls", dhtFindUrls)
        dhtRpcListenUdp?.takeIf { it.isNotBlank() }?.let { j.put("dhtRpcListenUdp", it) }
        dhtRpcSecret?.takeIf { it.isNotBlank() }?.let { j.put("dhtRpcSecret", it) }
        j.putStringListIfNonempty("dhtRpcSeedPeers", dhtRpcSeedPeers)
        if (dhtRpcIntervalSec > 0) j.put("dhtRpcIntervalSec", dhtRpcIntervalSec)
        if (dhtRpcFindK > 0) j.put("dhtRpcFindK", dhtRpcFindK)
        if (dhtIterativeRounds > 0) j.put("dhtIterativeRounds", dhtIterativeRounds)
        if (dhtIterativeAlpha > 0) j.put("dhtIterativeAlpha", dhtIterativeAlpha)
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): MeshDiscoveryOptions {
            return MeshDiscoveryOptions(
                gossipEnabled = j.optBoolean("gossipEnabled", false),
                gossipPeers = j.optJSONArrayStringList("gossipPeers"),
                gossipIntervalSec = j.optInt("gossipIntervalSec", 0),
                gossipMaxAgeSec = j.optInt("gossipMaxAgeSec", 0),
                dhtFindUrls = j.optJSONArrayStringList("dhtFindUrls"),
                dhtRpcListenUdp = j.optNullableString("dhtRpcListenUdp"),
                dhtRpcSecret = j.optNullableString("dhtRpcSecret"),
                dhtRpcSeedPeers = j.optJSONArrayStringList("dhtRpcSeedPeers"),
                dhtRpcIntervalSec = j.optInt("dhtRpcIntervalSec", 0),
                dhtRpcFindK = j.optInt("dhtRpcFindK", 0),
                dhtIterativeRounds = j.optInt("dhtIterativeRounds", 0),
                dhtIterativeAlpha = j.optInt("dhtIterativeAlpha", 0),
            )
        }
    }
}

data class MeshPolicyOptions(
    val routeMode: String = "auto",
    val maxPeerHops: Int = 1,
    val budgetKbps: Int = 0,
    val pathAggressive: Boolean = false,
    val pathCooldownMs: Int = 0,
    val healthMaxAgeSec: Int = 300,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("routeMode", routeMode)
        j.put("maxPeerHops", maxPeerHops)
        if (budgetKbps > 0) j.put("budgetKbps", budgetKbps)
        j.put("pathAggressive", pathAggressive)
        if (pathCooldownMs > 0) j.put("pathCooldownMs", pathCooldownMs)
        j.put("healthMaxAgeSec", healthMaxAgeSec)
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): MeshPolicyOptions {
            return MeshPolicyOptions(
                routeMode = j.optString("routeMode", "auto").ifBlank { "auto" },
                maxPeerHops = j.optInt("maxPeerHops", 1).coerceAtLeast(1),
                budgetKbps = j.optInt("budgetKbps", 0),
                pathAggressive = j.optBoolean("pathAggressive", false),
                pathCooldownMs = j.optInt("pathCooldownMs", 0),
                healthMaxAgeSec = j.optInt("healthMaxAgeSec", 300).coerceAtLeast(1),
            )
        }
    }
}
