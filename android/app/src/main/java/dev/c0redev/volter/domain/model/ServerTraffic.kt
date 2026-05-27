package dev.c0redev.volter.domain.model

import org.json.JSONObject

data class ServerTraffic(
    val rxBytes: Long,
    val txBytes: Long,
    val updatedAt: Long = 0L, // epoch ms, set client-side
) {
    companion object {
        fun fromJson(j: JSONObject): ServerTraffic = ServerTraffic(
            rxBytes = j.optLong("rxBytes", 0L),
            txBytes = j.optLong("txBytes", 0L),
        )
    }
}
