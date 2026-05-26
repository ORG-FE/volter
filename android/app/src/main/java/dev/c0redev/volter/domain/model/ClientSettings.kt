package dev.c0redev.volter.domain.model

import org.json.JSONObject
import org.json.JSONArray

data class ClientSettings(
    val mode: String = "tun",
    val systemProxy: Boolean = false,
    val proxyListen: String = "127.0.0.1:1080",
    val ipv6Tunnel: Boolean = false,
    val dualTun: Boolean = true,
    val transportPreference: String = TRANSPORT_AUTO,
    val splitTunnelMode: String = SPLIT_BYPASS,
    val splitTunnelApps: List<String> = emptyList(),
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        if (mode.isNotBlank()) j.put("mode", mode)
        j.put("systemProxy", systemProxy)
        if (proxyListen.isNotBlank()) j.put("proxyListen", proxyListen)
        j.put("ipv6Tunnel", ipv6Tunnel)
        j.put("dualTun", dualTun)
        j.put("transportPreference", Companion.normalizedTransportPreference(transportPreference))
        j.put("splitTunnelMode", normalizedSplitTunnelMode(splitTunnelMode))
        val apps = JSONArray()
        splitTunnelApps.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { apps.put(it) }
        j.put("splitTunnelApps", apps)
        return j
    }

    companion object {
        const val TRANSPORT_AUTO = "auto"
        const val TRANSPORT_TCP = "tcp"
        const val TRANSPORT_QUIC = "quic"
        const val SPLIT_OFF = "off"
        const val SPLIT_BYPASS = "bypass"
        const val SPLIT_ONLY = "only"

        fun normalizedTransportPreference(raw: String): String {
            return when (raw.trim().lowercase()) {
                TRANSPORT_TCP -> TRANSPORT_TCP
                TRANSPORT_QUIC -> TRANSPORT_QUIC
                else -> TRANSPORT_AUTO
            }
        }

        fun normalizedSplitTunnelMode(raw: String): String {
            return when (raw.trim().lowercase()) {
                SPLIT_ONLY -> SPLIT_ONLY
                SPLIT_BYPASS -> SPLIT_BYPASS
                else -> SPLIT_OFF
            }
        }

        fun fromJson(j: JSONObject): ClientSettings {
            val rawMode = j.optString("mode", "tun")
            val mode = if (rawMode == "proxy") "proxy" else "tun"
            val appsArr = j.optJSONArray("splitTunnelApps")
            val apps = buildList {
                if (appsArr != null) {
                    for (i in 0 until appsArr.length()) {
                        appsArr.optString(i).trim().takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.distinct()
            return ClientSettings(
                mode = mode,
                systemProxy = j.optBoolean("systemProxy", false),
                proxyListen = j.optString("proxyListen", "127.0.0.1:1080"),
                ipv6Tunnel = j.optBoolean("ipv6Tunnel", false),
                dualTun = j.optBoolean("dualTun", true),
                transportPreference = normalizedTransportPreference(j.optString("transportPreference", TRANSPORT_AUTO)),
                splitTunnelMode = normalizedSplitTunnelMode(j.optString("splitTunnelMode", SPLIT_OFF)),
                splitTunnelApps = apps,
            )
        }
    }
}
