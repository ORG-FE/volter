package dev.c0redev.volter.domain.model

import android.util.Base64
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class PeerTicket(
    val peerId: String,
    val pubKey: String,
    val addrs: List<String>,
    val expiresAt: Long,
    val nonce: String,
    val sig: String,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("peerId", peerId)
        j.put("pubKey", pubKey)
        j.put("addrs", JSONArray(addrs))
        j.put("expiresAt", expiresAt)
        j.put("nonce", nonce)
        j.put("sig", sig)
        return j
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = expiresAt <= nowMs

    fun verifySig(): Boolean {
        val expected = sign(peerId, pubKey, addrs, expiresAt, nonce)
        return sig.equals(expected, ignoreCase = true)
    }

    companion object {
        fun fromJson(j: JSONObject): PeerTicket {
            val addrsRaw = j.optJSONArray("addrs")
            val addrs = ArrayList<String>()
            if (addrsRaw != null) {
                for (i in 0 until addrsRaw.length()) {
                    val v = addrsRaw.optString(i, "").trim()
                    if (v.isNotBlank()) addrs.add(v)
                }
            }
            return PeerTicket(
                peerId = j.optString("peerId", "").trim(),
                pubKey = j.optString("pubKey", "").trim(),
                addrs = addrs,
                expiresAt = j.optLong("expiresAt", 0L),
                nonce = j.optString("nonce", "").trim(),
                sig = j.optString("sig", "").trim(),
            )
        }

        fun parseUri(raw: String): PeerTicket? {
            val s = raw.trim()
            if (!s.startsWith("volter://", ignoreCase = true)) return null
            val body = s.substringAfter("://").substringBefore('?').substringBefore('#').trim()
            if (body.isBlank()) return null
            val data = runCatching {
                Base64.decode(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            }.recoverCatching {
                Base64.decode(body, Base64.URL_SAFE)
            }.getOrNull() ?: return null
            val j = runCatching { JSONObject(String(data, Charsets.UTF_8)) }.getOrNull() ?: return null
            if (j.optString("t", "") != "peerTicket") return null
            val ticket = runCatching { fromJson(j.optJSONObject("ticket") ?: return null) }.getOrNull() ?: return null
            if (ticket.peerId.isBlank() || ticket.pubKey.isBlank() || ticket.addrs.isEmpty()) return null
            if (ticket.isExpired()) return null
            if (!ticket.verifySig()) return null
            return ticket
        }

        fun buildUri(ticket: PeerTicket): String {
            val j = JSONObject()
            j.put("v", 1)
            j.put("t", "peerTicket")
            j.put("ticket", ticket.toJson())
            val b = Base64.encodeToString(
                j.toString().toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )
            return "volter://$b"
        }

        fun create(peerId: String, pubKey: String, addrs: List<String>, ttlMs: Long = 24L * 3600_000): PeerTicket {
            val cleanAddrs = addrs.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val exp = System.currentTimeMillis() + ttlMs.coerceAtLeast(1_000)
            val nonce = MessageDigest.getInstance("SHA-256")
                .digest("$peerId:$exp:${cleanAddrs.joinToString(",")}".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)
            val sig = sign(peerId.trim(), pubKey.trim(), cleanAddrs, exp, nonce)
            return PeerTicket(peerId.trim(), pubKey.trim(), cleanAddrs, exp, nonce, sig)
        }

        private fun sign(peerId: String, pubKey: String, addrs: List<String>, expiresAt: Long, nonce: String): String {
            val base = "$peerId|$pubKey|${addrs.joinToString(",")}|$expiresAt|$nonce"
            return MessageDigest.getInstance("SHA-256")
                .digest(base.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
