package dev.c0redev.volter.domain.model

import android.util.Base64
import org.json.JSONObject

data class Config(
    val server: String,
    val token: String,
    val quicServer: String? = null,
    val transport: String? = null,
    val quicServerName: String? = null,
    val quicSkipVerify: Boolean? = null,
    val quicCertPinSHA256: String? = null,
    val quicCaCert: String? = null,
    val quicTraceLog: Boolean? = null,
    val routes: String? = null,
    val exclude: String? = null,
    val tunCIDR6: String? = null,
    val dualTransport: Boolean? = null,
    val protection: ProtectionOptions? = null,
    val relay: RelayOptions? = null,
) {
    fun withCloudDefaults(serverMode: String, probeIPv6: Boolean): Config {
        val noPin = quicCertPinSHA256.isNullOrBlank()
        var skip = quicSkipVerify
        if (noPin) {
            skip = null
        }
        val forcedTcp = transport?.equals("tcp", ignoreCase = true) == true
        var t = transport
        var qs = quicServer
        if (forcedTcp) {
            qs = null
        } else {
            when (serverMode.trim().lowercase()) {
                "tcp only" -> {
                    t = "tcp"
                    qs = null
                }
                "quic only", "quic/tcp" -> {
                    if (cloudQuicNeedsDefaultPort(server, qs)) qs = quicHostPortForCloudTcp(server)
                }
                else -> {
                    if (cloudQuicNeedsDefaultPort(server, qs)) qs = quicHostPortForCloudTcp(server)
                }
            }
        }
        var tun6 = tunCIDR6
        if (tun6.isNullOrBlank() && probeIPv6) {
            tun6 = DEFAULT_CLOUD_TUN_CIDR6
        }
        return copy(
            transport = t,
            quicServer = qs,
            quicSkipVerify = skip,
            tunCIDR6 = tun6,
        )
    }

    fun transportSummary(): String {
        val tr = transport?.takeIf { it.isNotBlank() } ?: "auto"
        val qs = quicServer?.let { " · $it" }.orEmpty()
        val dual = when (dualTransport) {
            false -> " · dual off"
            else -> ""
        }
        return "$tr$qs$dual"
    }

    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("server", server)
        j.put("token", token)
        quicServer?.takeIf { it.isNotBlank() }?.let { j.put("quicServer", it) }
        transport?.takeIf { it.isNotBlank() }?.let { j.put("transport", it) }
        quicServerName?.takeIf { it.isNotBlank() }?.let { j.put("quicServerName", it) }
        quicSkipVerify?.let { j.put("quicSkipVerify", it) }
        quicCertPinSHA256?.takeIf { it.isNotBlank() }?.let { j.put("quicCertPinSHA256", it) }
        quicCaCert?.takeIf { it.isNotBlank() }?.let { j.put("quicCaCert", it) }
        quicTraceLog?.let { j.put("quicTraceLog", it) }
        routes?.let { j.put("routes", it) }
        exclude?.let { j.put("exclude", it) }
        tunCIDR6?.let { j.put("tunCIDR6", it) }
        dualTransport?.let { j.put("dualTransport", it) }
        protection?.let { j.put("protection", it.toJson()) }
        relay?.let { j.put("relay", it.toJson()) }
        return j
    }

    companion object {
        const val DEFAULT_CLOUD_TUN_CIDR6 = "fd00:13:37::2/64"
        const val CLOUD_DEFAULT_QUIC_PORT = 4433

        fun quicHostPortForCloudTcp(serverTcp: String): String {
            val s = serverTcp.trim()
            if (s.isEmpty()) return s
            val host = when {
                s.startsWith("[") -> {
                    val end = s.indexOf(']')
                    if (end > 0) s.substring(0, end + 1) else s
                }
                else -> {
                    val idx = s.lastIndexOf(':')
                    if (idx > 0) s.substring(0, idx) else s
                }
            }
            return "$host:$CLOUD_DEFAULT_QUIC_PORT"
        }

        private fun cloudQuicNeedsDefaultPort(tcpServer: String, quicServer: String?): Boolean {
            val tcp = tcpServer.trim()
            val qs = quicServer?.trim().orEmpty()
            if (tcp.isEmpty()) return false
            if (qs.isEmpty()) return true
            return qs == tcp
        }

        fun fromJson(j: JSONObject): Config {
            return Config(
                server = j.getString("server"),
                token = j.getString("token"),
                quicServer = j.optString("quicServer", "").takeIf { it.isNotBlank() },
                transport = j.optString("transport", "").takeIf { it.isNotBlank() },
                quicServerName = j.optString("quicServerName", "").takeIf { it.isNotBlank() },
                quicSkipVerify = if (j.has("quicSkipVerify")) j.optBoolean("quicSkipVerify") else null,
                quicCertPinSHA256 = j.optString("quicCertPinSHA256", "").takeIf { it.isNotBlank() },
                quicCaCert = j.optString("quicCaCert", "").takeIf { it.isNotBlank() },
                quicTraceLog = if (j.has("quicTraceLog")) j.optBoolean("quicTraceLog") else null,
                routes = j.optString("routes", "").takeIf { it.isNotBlank() },
                exclude = j.optString("exclude", "").takeIf { it.isNotBlank() },
                tunCIDR6 = j.optString("tunCIDR6", "").takeIf { it.isNotBlank() },
                dualTransport = when {
                    !j.has("dualTransport") || j.isNull("dualTransport") -> null
                    else -> j.optBoolean("dualTransport", true)
                },
                protection = j.optJSONObject("protection")?.let { ProtectionOptions.fromJson(it) },
                relay = j.optJSONObject("relay")?.let { RelayOptions.fromJson(it) },
            )
        }

        fun sanitizeName(raw: String): String {
            val s = raw.trim()
            val b = StringBuilder()
            for (c in s) {
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_') {
                    b.append(c)
                }
            }
            val out = b.toString()
            return if (out.isBlank()) "default" else out
        }

        fun parseConnection(s: String): Pair<String, String>? {
            val raw = s.trim()
            if (raw.isEmpty()) return null
            parseVolterUriConfig(raw)?.let { cfg ->
                return cfg.server to cfg.token
            }
            for (i in raw.lastIndex downTo 0) {
                if (raw[i] != ':') continue
                val server = raw.substring(0, i).trim()
                val token = raw.substring(i + 1).trim()
                if (server.isBlank() || token.isBlank()) continue
                if (!isValidServerHostPort(server)) continue
                return server to token
            }
            return null
        }

        fun parseShareUri(raw: String): Pair<String, Config>? {
            val cfg = parseVolterUriConfig(raw) ?: return null
            val name = parseVolterUriName(raw)?.ifBlank { "imported" } ?: "imported"
            return sanitizeName(name) to cfg.copy(protection = null, relay = null)
        }

        fun buildShareUri(name: String, cfg: Config): String {
            val payload = JSONObject()
            payload.put("v", 1)
            payload.put("n", sanitizeName(name))
            payload.put("c", cfg.copy(protection = null, relay = null).toJson())
            val b = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return "volter://$b"
        }

        fun buildMinimalConnectionUri(cfg: Config): String {
            val server = cfg.server.trim()
            val token = cfg.token.trim()
            if (server.isEmpty() || token.isEmpty()) return ""
            val json =
                "{\"s\":${JSONObject.quote(server)},\"k\":${JSONObject.quote(token)}}"
            val b =
                Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return "volter://$b"
        }

        fun buildProtectionUri(name: String, protection: ProtectionOptions): String {
            val payload = JSONObject()
            payload.put("v", 1)
            payload.put("t", "protection")
            payload.put("n", sanitizeName(name))
            payload.put("p", protection.toJson())
            val b = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            return "volter://$b"
        }

        fun parseProtectionUri(raw: String): ProtectionOptions? {
            val j = decodeVolterUriPayload(raw) ?: return null
            if (j.optString("t", "") != "protection") return null
            val p = j.optJSONObject("p") ?: return null
            return runCatching { ProtectionOptions.fromJson(p) }.getOrNull()
        }

        fun parseConnectionConfig(s: String): Config? = parseVolterUriConfig(s.trim())?.copy(protection = null)

        private fun parseVolterUriName(raw: String): String? {
            val j = decodeVolterUriPayload(raw) ?: return null
            return j.optString("n", "").trim().takeIf { it.isNotEmpty() }
        }

        private fun parseVolterUriConfig(raw: String): Config? {
            val j = decodeVolterUriPayload(raw) ?: return null
            val cfgObj = when {
                j.has("c") && !j.isNull("c") -> j.optJSONObject("c")
                else -> null
            }
            if (cfgObj != null) {
                return runCatching { fromJson(cfgObj) }.getOrNull()
            }
            val server = j.optString("s", "").trim()
            val token = j.optString("k", "").trim().ifEmpty { j.optString("token", "").trim() }
            if (server.isBlank() || token.isBlank() || !isValidServerHostPort(server)) return null
            val qh = j.optString("qh", "").trim().takeIf { it.isNotEmpty() }
            val qp = j.optString("qp", "").trim().toIntOrNull()
            val quic = if (qh != null && qp != null && qp in 1..65535) quicHostPort(qh, qp) else null
            return Config(server = server, token = token, quicServer = quic)
        }

        fun hostFromServer(server: String): String {
            val s = server.trim()
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                return if (end > 0) s.substring(1, end) else s
            }
            val idx = s.lastIndexOf(':')
            return if (idx > 0) s.substring(0, idx) else s
        }

        
        fun tcpPortFromServer(server: String): Int? {
            val s = server.trim()
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end <= 0 || end >= s.lastIndex || s[end + 1] != ':') return null
                return s.substring(end + 2).toIntOrNull()?.takeIf { it in 1..65535 }
            }
            val idx = s.lastIndexOf(':')
            if (idx <= 0 || idx == s.lastIndex) return null
            return s.substring(idx + 1).toIntOrNull()?.takeIf { it in 1..65535 }
        }

        
        fun tcpAuthorityForHttp(server: String): String? {
            val port = tcpPortFromServer(server) ?: return null
            val host = hostFromServer(server)
            val hostPart = if (host.contains(':')) "[$host]" else host
            return "$hostPart:$port"
        }

        fun quicHostPort(host: String, port: Int): String {
            val h = host.trim().removePrefix("[").removeSuffix("]")
            return if (h.contains(":")) "[$h]:$port" else "$h:$port"
        }

        private fun decodeVolterUriPayload(raw: String): JSONObject? {
            val lower = raw.trim()
            if (!lower.startsWith("volter://", ignoreCase = true)) return null
            var body = raw.trim().substringAfter("://").trim()
            if (body.isEmpty()) return null
            val q = body.indexOfAny(charArrayOf('?', '#'))
            if (q >= 0) body = body.substring(0, q)
            if (body.isBlank()) return null
            val bytes = runCatching {
                Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            }.recoverCatching {
                Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP)
            }.recoverCatching {
                Base64.decode(body, Base64.DEFAULT)
            }.getOrNull() ?: return null
            return runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        }

        private fun isValidServerHostPort(value: String): Boolean {
            val trimmed = value.trim()
            if (trimmed.isBlank()) return false
            return try {
                if (trimmed.startsWith("[")) {
                    val end = trimmed.indexOf(']')
                    if (end <= 0 || end >= trimmed.lastIndex) return false
                    if (trimmed[end + 1] != ':') return false
                    val port = trimmed.substring(end + 2).toIntOrNull()
                    port != null && port in 1..65535
                } else {
                    val idx = trimmed.lastIndexOf(':')
                    if (idx <= 0 || idx == trimmed.lastIndex) return false
                    val host = trimmed.substring(0, idx)
                    if (host.contains(':')) return false
                    val port = trimmed.substring(idx + 1).toIntOrNull()
                    port != null && port in 1..65535 && host.isNotBlank()
                }
            } catch (_: Throwable) {
                false
            }
        }
    }
}

