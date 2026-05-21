package dev.c0redev.volter.domain.model

import dev.c0redev.volter.json.optNullableString
import org.json.JSONArray
import org.json.JSONObject

data class ProtectionOptions(
    val obfuscation: String? = null,
    val junkCount: Int = 0,
    val junkMin: Int = 0,
    val junkMax: Int = 0,
    val padS1: Int = 0,
    val padS2: Int = 0,
    val padS3: Int = 0,
    val padS4: Int = 0,
    val preCheck: Boolean = false,
    val magicSplit: String? = null,
    val junkStyle: String? = null,
    val flushPolicy: String? = null,
    val preambleProfile: String? = null,
    val preambleRotate: Boolean = false,
    val clusterHttpKey: String? = null,
    val clusterMapPath: String? = null,
    val clusterSessionsPath: String? = null,
    val clusterClientsPath: String? = null,
    val clusterPreferredServer: String? = null,
    val clusterInvitePath: String? = null,
    val clusterPeerHandshakePath: String? = null,
    val clusterRouteAssist: Boolean = false,
    val clusterAssistTargetNodeId: String? = null,
    val tlsProfileId: String? = null,
    val ja3TargetHash: String? = null,
    val standaloneDpiOnly: Boolean = false,
    val dpiLocalEngine: String? = null,
    val dpiLocalEmbedded: DpiLocalEmbedded? = null,
    val dpiLocalPreset: String? = null,
    val dpiVolunteer: Boolean = false,
    val dpiVolterTransportObfuscate: Boolean = false,
    val dpiProbeUrls: List<String> = emptyList(),
    val antiDpiWithVpn: Boolean = false,
    val routeMode: String? = null,
    val routePlannerV2: Boolean = false,
    val relayRouteHops: List<String> = emptyList(),
) {
    fun applyClusterDefaults(): ProtectionOptions = copy(
        clusterMapPath = clusterMapPath?.takeIf { it.isNotBlank() } ?: "/volter/cluster-map.json",
        clusterSessionsPath = clusterSessionsPath?.takeIf { it.isNotBlank() } ?: "/volter/cluster-sessions.json",
        clusterClientsPath = clusterClientsPath?.takeIf { it.isNotBlank() } ?: "/volter/cluster-clients.json",
        clusterInvitePath = clusterInvitePath?.takeIf { it.isNotBlank() } ?: "/volter/cluster-invite",
        clusterPeerHandshakePath = clusterPeerHandshakePath?.takeIf { it.isNotBlank() } ?: "/volter/cluster-peer-handshake",
        routePlannerV2 = true,
    )

    fun toJson(): JSONObject {
        val j = JSONObject()
        obfuscation?.let { j.put("obfuscation", it) }
        if (junkCount != 0) j.put("junkCount", junkCount)
        if (junkMin != 0) j.put("junkMin", junkMin)
        if (junkMax != 0) j.put("junkMax", junkMax)
        if (padS1 != 0) j.put("padS1", padS1)
        if (padS2 != 0) j.put("padS2", padS2)
        if (padS3 != 0) j.put("padS3", padS3)
        if (padS4 != 0) j.put("padS4", padS4)
        j.put("preCheck", preCheck)
        magicSplit?.let { j.put("magicSplit", it) }
        junkStyle?.let { j.put("junkStyle", it) }
        flushPolicy?.let { j.put("flushPolicy", it) }
        preambleProfile?.let { j.put("preambleProfile", it) }
        if (preambleRotate) j.put("preambleRotate", true)
        clusterHttpKey?.let { j.put("clusterHttpKey", it) }
        clusterMapPath?.let { j.put("clusterMapPath", it) }
        clusterSessionsPath?.let { j.put("clusterSessionsPath", it) }
        clusterClientsPath?.let { j.put("clusterClientsPath", it) }
        clusterPreferredServer?.let { j.put("clusterPreferredServer", it) }
        clusterInvitePath?.let { j.put("clusterInvitePath", it) }
        clusterPeerHandshakePath?.let { j.put("clusterPeerHandshakePath", it) }
        if (clusterRouteAssist) j.put("clusterRouteAssist", true)
        clusterAssistTargetNodeId?.let { j.put("clusterAssistTargetNodeId", it) }
        tlsProfileId?.let { j.put("tlsProfileId", it) }
        ja3TargetHash?.let { j.put("ja3TargetHash", it) }
        if (standaloneDpiOnly) j.put("standaloneDpiOnly", true)
        dpiLocalEngine?.trim()?.takeIf { it.isNotEmpty() }?.let { j.put("dpiLocalEngine", it) }
        dpiLocalEmbedded?.let { o ->
            val sub = o.toJson()
            if (sub.length() > 0) j.put("dpiLocalEmbedded", sub)
        }
        dpiLocalPreset?.let { j.put("dpiLocalPreset", it) }
        if (dpiVolunteer) j.put("dpiVolunteer", true)
        if (dpiVolterTransportObfuscate) j.put("dpiVolterTransportObfuscate", true)
        if (dpiProbeUrls.isNotEmpty()) {
            val a = JSONArray()
            for (u in dpiProbeUrls) {
                a.put(u)
            }
            j.put("dpiProbeUrls", a)
        }
        if (antiDpiWithVpn) j.put("antiDpiWithVpn", true)
        routeMode?.let { j.put("routeMode", it) }
        if (routePlannerV2) j.put("routePlannerV2", true)
        if (relayRouteHops.isNotEmpty()) {
            val a = JSONArray()
            for (h in relayRouteHops) {
                a.put(h)
            }
            j.put("relayRouteHops", a)
        }
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): ProtectionOptions = ProtectionOptions(
            obfuscation = j.optNullableString("obfuscation"),
            junkCount = j.optInt("junkCount", 0),
            junkMin = j.optInt("junkMin", 0),
            junkMax = j.optInt("junkMax", 0),
            padS1 = j.optInt("padS1", 0),
            padS2 = j.optInt("padS2", 0),
            padS3 = j.optInt("padS3", 0),
            padS4 = j.optInt("padS4", 0),
            preCheck = j.optBoolean("preCheck", false),
            magicSplit = j.optNullableString("magicSplit"),
            junkStyle = j.optNullableString("junkStyle"),
            flushPolicy = j.optNullableString("flushPolicy"),
            preambleProfile = j.optNullableString("preambleProfile"),
            preambleRotate = j.optBoolean("preambleRotate", false),
            clusterHttpKey = j.optNullableString("clusterHttpKey"),
            clusterMapPath = j.optNullableString("clusterMapPath"),
            clusterSessionsPath = j.optNullableString("clusterSessionsPath"),
            clusterClientsPath = j.optNullableString("clusterClientsPath"),
            clusterPreferredServer = j.optNullableString("clusterPreferredServer"),
            clusterInvitePath = j.optNullableString("clusterInvitePath"),
            clusterPeerHandshakePath = j.optNullableString("clusterPeerHandshakePath"),
            clusterRouteAssist = j.optBoolean("clusterRouteAssist", false),
            clusterAssistTargetNodeId = j.optNullableString("clusterAssistTargetNodeId"),
            tlsProfileId = j.optNullableString("tlsProfileId"),
            ja3TargetHash = j.optNullableString("ja3TargetHash"),
            standaloneDpiOnly = j.optBoolean("standaloneDpiOnly", false),
            dpiLocalEngine = j.optNullableString("dpiLocalEngine"),
            dpiLocalEmbedded = DpiLocalEmbedded.fromJson(j.optJSONObject("dpiLocalEmbedded")),
            dpiLocalPreset = j.optNullableString("dpiLocalPreset"),
            dpiVolunteer = j.optBoolean("dpiVolunteer", false),
            dpiVolterTransportObfuscate = j.optBoolean("dpiVolterTransportObfuscate", false),
            dpiProbeUrls = run {
                val arr = j.optJSONArray("dpiProbeUrls") ?: return@run emptyList()
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        add(arr.getString(i))
                    }
                }
            },
            antiDpiWithVpn = j.optBoolean("antiDpiWithVpn", false),
            routeMode = j.optNullableString("routeMode"),
            routePlannerV2 = j.optBoolean("routePlannerV2", false),
            relayRouteHops = run {
                val arr = j.optJSONArray("relayRouteHops") ?: return@run emptyList()
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        add(arr.getString(i))
                    }
                }
            },
        )
    }
}
