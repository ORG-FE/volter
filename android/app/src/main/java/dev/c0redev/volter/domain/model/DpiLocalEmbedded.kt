package dev.c0redev.volter.domain.model

import org.json.JSONObject

data class DpiLocalEmbedded(
    val splitAfter: Int = 1,
    val splitAfter2: Int = 0,
    val ttlMillis: Int = 8,
    val ttl2Millis: Int = 0,
    val disorder: Boolean = false,
    val jitterMaxMs: Int = 0,
    val leadInMs: Int = 0,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        if (splitAfter != 1) j.put("splitAfter", splitAfter)
        if (splitAfter2 != 0) j.put("splitAfter2", splitAfter2)
        if (ttlMillis != 8) j.put("ttlMillis", ttlMillis)
        if (ttl2Millis != 0) j.put("ttl2Millis", ttl2Millis)
        if (disorder) j.put("disorder", true)
        if (jitterMaxMs != 0) j.put("jitterMaxMs", jitterMaxMs)
        if (leadInMs != 0) j.put("leadInMs", leadInMs)
        return j
    }

    companion object {
        fun fromJson(j: JSONObject?): DpiLocalEmbedded? {
            if (j == null) return null
            return DpiLocalEmbedded(
                splitAfter = j.optInt("splitAfter", 1),
                splitAfter2 = j.optInt("splitAfter2", 0),
                ttlMillis = j.optInt("ttlMillis", 8),
                ttl2Millis = j.optInt("ttl2Millis", 0),
                disorder = j.optBoolean("disorder", false),
                jitterMaxMs = j.optInt("jitterMaxMs", 0),
                leadInMs = j.optInt("leadInMs", 0),
            )
        }
    }
}
