package dev.c0redev.volter.domain.model

import dev.c0redev.volter.json.optNullableString
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class AppTrafficEntry(
    val uid: Int,
    val rxBytes: Long,
    val txBytes: Long,
    val label: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("rx", rxBytes)
        put("tx", txBytes)
        put("label", label)
    }

    companion object {
        fun fromJson(o: JSONObject): AppTrafficEntry = AppTrafficEntry(
            uid = o.optInt("uid"),
            rxBytes = o.optLong("rx", 0L),
            txBytes = o.optLong("tx", 0L),
            label = o.optString("label", "?"),
        )
    }
}

data class SessionRecord(
    val start: Instant,
    val end: Instant? = null,
    val durationNs: Long? = null,
    val server: String,
    val configName: String,
    val errorType: String? = null,
    val handshakeOk: Boolean,
    val reconnectCount: Int,
    val rttBeforeNs: Long? = null,
    val rttDuringNs: Long? = null,
    val dnsOkBefore: Boolean,
    val dnsOkAfter: Boolean? = null,
    val probeOk: Boolean,
    val rxBytes: Long? = null,
    val txBytes: Long? = null,
    val byApp: List<AppTrafficEntry> = emptyList(),
    val trafficCollectError: String? = null,
    val routePrefixes: List<String> = emptyList(),
    val excludePrefixes: List<String> = emptyList(),
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        j.put("start", start.toString())
        end?.let { j.put("end", it.toString()) }
        durationNs?.let { j.put("duration", it) }
        j.put("server", server)
        j.put("configName", configName)
        errorType?.let { j.put("errorType", it) }
        j.put("handshakeOK", handshakeOk)
        j.put("reconnectCount", reconnectCount)
        rttBeforeNs?.let { j.put("rttBefore", it) }
        rttDuringNs?.let { j.put("rttDuring", it) }
        j.put("dnsOKBefore", dnsOkBefore)
        dnsOkAfter?.let { j.put("dnsOKAfter", it) }
        j.put("probeOK", probeOk)
        rxBytes?.let { j.put("rxBytes", it) }
        txBytes?.let { j.put("txBytes", it) }
        trafficCollectError?.let { j.put("trafficCollectError", it) }
        if (byApp.isNotEmpty()) {
            j.put("byApp", JSONArray().apply { byApp.forEach { put(it.toJson()) } })
        }
        if (routePrefixes.isNotEmpty()) {
            j.put("routePrefixes", JSONArray().apply { routePrefixes.forEach { put(it) } })
        }
        if (excludePrefixes.isNotEmpty()) {
            j.put("excludePrefixes", JSONArray().apply { excludePrefixes.forEach { put(it) } })
        }
        return j
    }

    companion object {
        fun fromJson(j: JSONObject): SessionRecord = SessionRecord(
            start = Instant.parse(j.getString("start")),
            end = j.optNullableString("end")?.let(Instant::parse),
            durationNs = if (j.has("duration") && !j.isNull("duration")) j.getLong("duration") else null,
            server = j.getString("server"),
            configName = j.getString("configName"),
            errorType = j.optNullableString("errorType"),
            handshakeOk = j.getBoolean("handshakeOK"),
            reconnectCount = j.getInt("reconnectCount"),
            rttBeforeNs = if (j.has("rttBefore") && !j.isNull("rttBefore")) j.getLong("rttBefore") else null,
            rttDuringNs = if (j.has("rttDuring") && !j.isNull("rttDuring")) j.getLong("rttDuring") else null,
            dnsOkBefore = j.getBoolean("dnsOKBefore"),
            dnsOkAfter = if (j.has("dnsOKAfter") && !j.isNull("dnsOKAfter")) j.getBoolean("dnsOKAfter") else null,
            probeOk = j.getBoolean("probeOK"),
            rxBytes = if (j.has("rxBytes") && !j.isNull("rxBytes")) j.getLong("rxBytes") else null,
            txBytes = if (j.has("txBytes") && !j.isNull("txBytes")) j.getLong("txBytes") else null,
            byApp = parseAppList(j.optJSONArray("byApp")),
            trafficCollectError = j.optNullableString("trafficCollectError"),
            routePrefixes = parseStringList(j.optJSONArray("routePrefixes")),
            excludePrefixes = parseStringList(j.optJSONArray("excludePrefixes")),
        )

        fun listFromJson(arr: JSONArray): List<SessionRecord> = List(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }

        private fun parseAppList(arr: JSONArray?): List<AppTrafficEntry> {
            if (arr == null) return emptyList()
            return List(arr.length()) { i -> AppTrafficEntry.fromJson(arr.getJSONObject(i)) }
        }

        private fun parseStringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return List(arr.length()) { i -> arr.getString(i) }
        }
    }
}

data class MetricsStore(val records: List<SessionRecord> = emptyList()) {
    fun toJson(): JSONObject = JSONObject().put("records", JSONArray().apply { records.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(j: JSONObject): MetricsStore {
            val arr = j.optJSONArray("records") ?: JSONArray()
            return MetricsStore(records = SessionRecord.listFromJson(arr))
        }
    }
}
