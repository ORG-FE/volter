package dev.c0redev.volter.domain.model

import org.json.JSONObject

/** Параметры встроенного anti-DPI (split / ttl / disorder), синхронно с Go `DpiLocalEmbedded`. */
data class DpiLocalEmbedded(
    val splitAfter: Int = 1,
    val ttlMillis: Int = 8,
    val disorder: Boolean = false,
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
        if (splitAfter != 1) j.put("splitAfter", splitAfter)
        if (ttlMillis != 8) j.put("ttlMillis", ttlMillis)
        if (disorder) j.put("disorder", true)
        return j
    }

    companion object {
        fun fromJson(j: JSONObject?): DpiLocalEmbedded? {
            if (j == null) return null
            return DpiLocalEmbedded(
                splitAfter = j.optInt("splitAfter", 1),
                ttlMillis = j.optInt("ttlMillis", 8),
                disorder = j.optBoolean("disorder", false),
            )
        }
    }
}
